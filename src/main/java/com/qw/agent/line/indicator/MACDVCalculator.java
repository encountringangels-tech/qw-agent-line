package com.qw.agent.line.indicator;

import com.qw.agent.line.model.Kline;
import com.qw.agent.line.model.MACDVPoint;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * MACD-V 指标计算器 —— 纯算法，增量迭代，O(N) 复杂度。
 * <p>
 * 计算公式（与 Pine Script "MACD-V" by jamiedubauskas 一致）：
 * <pre>
 *   MACD-V = ((EMA(close, fastLen) − EMA(close, slowLen)) / ATR) × 100
 *   Signal = EMA(MACD-V, signalLen)
 *   Hist   = MACD-V − Signal
 * </pre>
 */
@Component
public class MACDVCalculator {

    /**
     * 对 K 线列表逐根计算 MACD-V 指标。
     * <p>
     * 内部全部使用 double 数组 + 增量迭代，一次遍历完成。
     *
     * @param klines    K 线列表（按时间正序）
     * @param fastLen   快线 EMA 周期
     * @param slowLen   慢线 EMA 周期
     * @param signalLen 信号线 EMA 周期
     * @param atrLen    ATR 周期
     * @return 每根 K 线对应的 MACD-V 点
     */
    public List<MACDVPoint> calculate(List<Kline> klines,
                                       int fastLen,
                                       int slowLen,
                                       int signalLen,
                                       int atrLen) {
        int n = klines.size();
        int needed = slowLen + atrLen + signalLen + 5;
        List<MACDVPoint> results = new ArrayList<>(n);

        // 数据不足
        if (n < needed) {
            for (Kline k : klines) {
                results.add(new MACDVPoint(k.getOpenTime() / 1000, null, null, null));
            }
            return results;
        }

        // ---------------------------------------------------------------
        //  第一步：提取收盘价，为后续计算做索引对齐
        // ---------------------------------------------------------------
        long[] times  = new long[n];
        double[] high = new double[n];
        double[] low  = new double[n];
        double[] close = new double[n];

        for (int i = 0; i < n; i++) {
            Kline k = klines.get(i);
            times[i] = k.getOpenTime() / 1000;
            high[i]  = k.getHigh().doubleValue();
            low[i]   = k.getLow().doubleValue();
            close[i] = k.getClose().doubleValue();
        }

        // ---------------------------------------------------------------
        //  第二步：增量计算 EMA12 和 EMA26
        //  EMA = (value − prevEMA) × k + prevEMA
        // ---------------------------------------------------------------
        double[] emaFast = incrementalEMA(close, fastLen);
        double[] emaSlow = incrementalEMA(close, slowLen);

        // ---------------------------------------------------------------
        //  第三步：增量计算 ATR
        //  TR = max(high−low, |high−prevClose|, |low−prevClose|)
        //  ATR = (prevATR × (period−1) + TR) / period   (Wilder 平滑)
        // ---------------------------------------------------------------
        double[] atr = incrementalATR(high, low, close, atrLen);

        // ---------------------------------------------------------------
        //  第四步：计算 MACD-V 原始值  (只算 emaFast && emaSlow && atr 都有效的点)
        // ---------------------------------------------------------------
        int macdvStart = Math.max(fastLen - 1, Math.max(slowLen - 1, atrLen - 1));
        double[] macdVals = new double[n];
        boolean[] valid = new boolean[n];

        for (int i = macdvStart; i < n; i++) {
            if (atr[i] == 0) continue; // 防止除零
            macdVals[i] = (emaFast[i] - emaSlow[i]) / atr[i] * 100.0;
            valid[i] = true;
        }

        // ---------------------------------------------------------------
        //  第五步：增量计算 Signal 线 (EMA of MACD-V)
        // ---------------------------------------------------------------
        double[] signal = incrementalEMAOnValues(macdVals, valid, signalLen);

        // ---------------------------------------------------------------
        //  第六步：组装结果
        // ---------------------------------------------------------------
        for (int i = 0; i < n; i++) {
            if (!valid[i] || Double.isNaN(signal[i])) {
                results.add(new MACDVPoint(times[i], null, null, null));
            } else {
                double mv = macdVals[i];
                double sg = signal[i];
                double hist = mv - sg;
                results.add(new MACDVPoint(times[i],
                        BigDecimal.valueOf(round2(mv)),
                        BigDecimal.valueOf(round2(sg)),
                        BigDecimal.valueOf(round2(hist))));
            }
        }

        return results;
    }

    // ==================== 增量算法 ====================

    /**
     * 增量 EMA: 一次遍历计算所有 K 线的 EMA。
     * 前 period−1 个位置标记为 NaN（无效）。
     */
    private double[] incrementalEMA(double[] values, int period) {
        int n = values.length;
        double[] result = new double[n];
        double k = 2.0 / (period + 1);

        // 初始 SMA
        double sum = 0;
        for (int i = 0; i < period; i++) sum += values[i];
        result[period - 1] = sum / period;

        // 增量迭代
        for (int i = period; i < n; i++) {
            result[i] = (values[i] - result[i - 1]) * k + result[i - 1];
        }
        return result;
    }

    /**
     * 增量 EMA: 在已有 MACD-V 值上计算 Signal 线。
     * 只对 valid=true 的位置做 EMA，跳过无效点。
     */
    private double[] incrementalEMAOnValues(double[] values, boolean[] valid, int period) {
        int n = values.length;
        double[] result = new double[n];
        Arrays.fill(result, Double.NaN);  // 未初始化位置标记为 NaN，避免被误认为有效值

        // 找到第一个有效位置的索引
        int start = -1;
        for (int i = 0; i < n; i++) {
            if (valid[i]) { start = i; break; }
        }
        if (start < 0) return result; // 全部 NaN

        double k = 2.0 / (period + 1);

        // 收集前 period 个有效值算 SMA 作为初始 EMA
        double sum = 0;
        int count = 0;
        int idx = start;
        while (count < period && idx < n) {
            if (valid[idx]) { sum += values[idx]; count++; }
            idx++;
        }
        if (count < period) return result; // 有效值不够

        // 初始 EMA 设在前 period 个有效值的最后一个位置
        int emaPos = idx - 1;
        result[emaPos] = sum / period;

        // 从 emaPos 往后继续增量
        for (int i = emaPos + 1; i < n; i++) {
            if (valid[i]) {
                result[i] = (values[i] - result[i - 1]) * k + result[i - 1];
            }
        }
        return result;
    }

    /**
     * 增量 ATR: Wilder 平滑法，一次遍历。
     * TR = max(high−low, |high−prevClose|, |low−prevClose|)
     * ATR = (prevATR × (period−1) + TR) / period
     */
    private double[] incrementalATR(double[] high, double[] low, double[] close, int period) {
        int n = high.length;
        double[] atr = new double[n];

        // 先算所有 TR
        double[] tr = new double[n];
        tr[0] = high[0] - low[0]; // 第一根没有前收盘
        for (int i = 1; i < n; i++) {
            double hl = high[i] - low[i];
            double hc = Math.abs(high[i] - close[i - 1]);
            double lc = Math.abs(low[i]  - close[i - 1]);
            tr[i] = Math.max(hl, Math.max(hc, lc));
        }

        // 初始 SMA(TR) 作为第一个 ATR
        double sum = 0;
        for (int i = 0; i < period; i++) sum += tr[i];
        atr[period - 1] = sum / period;

        // 增量 Wilder 平滑
        for (int i = period; i < n; i++) {
            atr[i] = (atr[i - 1] * (period - 1) + tr[i]) / period;
        }
        return atr;
    }

    /** 保留两位小数 */
    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
