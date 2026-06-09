package com.qw.agent.line.indicator;

import com.qw.agent.line.model.Kline;
import com.qw.agent.line.model.MACDVPoint;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * MACD-V 指标计算器 —— 纯算法，不依赖任何外部服务。
 * <p>
 * 计算公式（与 Pine Script "MACD-V" by jamiedubauskas 一致）：
 * <pre>
 *   MACD-V = ((EMA(close, fastLen) − EMA(close, slowLen)) / ATR(atrLen)) × 100
 *   Signal = EMA(MACD-V, signalLen)
 *   Hist   = MACD-V − Signal
 * </pre>
 */
@Component
public class MACDVCalculator {

    /** 内部计算精度 */
    private static final int SCALE = 8;

    // ==================== 公开入口 ====================

    /**
     * 对 K 线列表逐根计算 MACD-V 指标，返回与 K 线一一对应的指标点列表。
     * 前面数据不足的 K 线对应的指标点为 null 值。
     *
     * @param klines    K 线列表（按时间正序）
     * @param fastLen   快线 EMA 周期（默认 12）
     * @param slowLen   慢线 EMA 周期（默认 26）
     * @param signalLen 信号线 EMA 周期（默认 9）
     * @param atrLen    ATR 周期（默认 26，建议与 slowLen 保持一致）
     * @return 每个 K 线对应的 MACD-V 点，长度与 klines 相同
     */
    public List<MACDVPoint> calculate(List<Kline> klines,
                                       int fastLen,
                                       int slowLen,
                                       int signalLen,
                                       int atrLen) {
        int needed = slowLen + atrLen + signalLen + 5;
        List<MACDVPoint> results = new ArrayList<>(klines.size());

        // 数据不足，全部返回无效点
        if (klines.size() < needed) {
            for (Kline k : klines) {
                results.add(new MACDVPoint(k.getOpenTime() / 1000, null, null, null));
            }
            return results;
        }

        // 第一步：逐根计算 MACD-V 原始值
        List<BigDecimal> rawValues = calculateRawMacdV(klines, fastLen, slowLen, atrLen);

        // 第二步：逐根计算 Signal 线（EMA of MACD-V）
        for (int i = 0; i < rawValues.size(); i++) {
            long time = klines.get(i).getOpenTime() / 1000;
            BigDecimal mv = rawValues.get(i);

            if (mv == null) {
                results.add(new MACDVPoint(time, null, null, null));
                continue;
            }

            // 从当前位置往前收集足够多的有效 MACD-V 值
            List<BigDecimal> validVals = collectValidValues(rawValues, i, signalLen + 3);
            BigDecimal signal = calculateEMAFromList(validVals, signalLen);

            if (signal == null) {
                results.add(new MACDVPoint(time, mv, null, null));
            } else {
                BigDecimal hist = mv.subtract(signal);
                results.add(new MACDVPoint(time, mv, signal, hist));
            }
        }

        return results;
    }

    // ==================== 私有计算方法 ====================

    /**
     * 逐根计算原始 MACD-V 值。
     * MACD-V = ((EMA(fast) - EMA(slow)) / ATR) × 100
     */
    private List<BigDecimal> calculateRawMacdV(List<Kline> klines, int fastLen, int slowLen, int atrLen) {
        List<BigDecimal> raw = new ArrayList<>(klines.size());
        for (int i = 0; i < klines.size(); i++) {
            List<Kline> window = klines.subList(0, i + 1);
            BigDecimal emaFast = calculateEMA(window, fastLen);
            BigDecimal emaSlow = calculateEMA(window, slowLen);
            BigDecimal atr = calculateATR(window, atrLen);

            if (emaFast == null || emaSlow == null || atr == null || atr.signum() == 0) {
                raw.add(null);
            } else {
                BigDecimal macdV = emaFast.subtract(emaSlow)
                        .divide(atr, SCALE, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100));
                raw.add(macdV);
            }
        }
        return raw;
    }

    /**
     * 从列表中收集有效值（跳过 null）。
     */
    private List<BigDecimal> collectValidValues(List<BigDecimal> values, int endIdx, int maxCount) {
        List<BigDecimal> result = new ArrayList<>();
        for (int j = endIdx; j >= 0 && result.size() < maxCount; j--) {
            if (values.get(j) != null) {
                result.add(0, values.get(j));
            }
        }
        return result;
    }

    // ==================== 基础技术指标 ====================

    /**
     * 计算简单移动平均线（SMA）。
     */
    BigDecimal calculateSMA(List<Kline> data, int period) {
        if (data.size() < period) return null;
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = data.size() - period; i < data.size(); i++) {
            sum = sum.add(data.get(i).getClose());
        }
        return sum.divide(BigDecimal.valueOf(period), SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 计算指数移动平均线（EMA）。
     * EMA = (close − EMAprev) × k + EMAprev,  k = 2 / (period + 1)
     */
    public BigDecimal calculateEMA(List<Kline> data, int period) {
        if (data.size() < period) return null;
        BigDecimal k = BigDecimal.valueOf(2)
                .divide(BigDecimal.valueOf(period + 1), SCALE, RoundingMode.HALF_UP);
        BigDecimal ema = calculateSMA(data.subList(0, period), period);
        if (ema == null) return null;
        for (int i = period; i < data.size(); i++) {
            ema = data.get(i).getClose().subtract(ema).multiply(k).add(ema);
        }
        return ema;
    }

    /**
     * 从一组标量值计算 EMA（用于 MACD-V 的 Signal 线）。
     */
    public BigDecimal calculateEMAFromList(List<BigDecimal> values, int period) {
        if (values.size() < period) return null;
        BigDecimal k = BigDecimal.valueOf(2)
                .divide(BigDecimal.valueOf(period + 1), SCALE, RoundingMode.HALF_UP);
        BigDecimal ema = values.subList(0, period).stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(period), SCALE, RoundingMode.HALF_UP);
        for (int i = period; i < values.size(); i++) {
            ema = values.get(i).subtract(ema).multiply(k).add(ema);
        }
        return ema;
    }

    /**
     * 计算平均真实波幅（ATR）。
     * TR = max(high − low, |high − prevClose|, |low − prevClose|)
     * ATR = sum(TR) / period
     */
    public BigDecimal calculateATR(List<Kline> data, int period) {
        if (data.size() < period + 1) return null;
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = data.size() - period; i < data.size(); i++) {
            Kline c = data.get(i);
            Kline p = data.get(i - 1);
            BigDecimal hl = c.getHigh().subtract(c.getLow()).abs();
            BigDecimal hc = c.getHigh().subtract(p.getClose()).abs();
            BigDecimal lc = c.getLow().subtract(p.getClose()).abs();
            sum = sum.add(Collections.max(List.of(hl, hc, lc)));
        }
        return sum.divide(BigDecimal.valueOf(period), SCALE, RoundingMode.HALF_UP);
    }
}
