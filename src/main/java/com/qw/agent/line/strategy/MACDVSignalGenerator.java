package com.qw.agent.line.strategy;

import com.qw.agent.line.model.LatestSignal;
import com.qw.agent.line.model.MACDVPoint;
import com.qw.agent.line.model.TradeSignal;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MACD-V 买卖信号生成器 —— 基于指标序列产生交易信号。
 * <p>
 * 信号来源（与 Pine Script "MACD-V" 一致）：
 * <ul>
 *   <li><b>金叉 / 死叉</b> — MACD-V 线上穿/下穿 Signal 线，权重 0.45</li>
 *   <li><b>超买 / 超卖</b> — MACD-V 线超过 ±150 阈值，权重 0.25</li>
 *   <li><b>柱状图趋势</b> — 柱状图方向及零轴穿越确认动量，权重 0.30</li>
 * </ul>
 */
@Component
public class MACDVSignalGenerator {

    /** 超买阈值（默认 150） */
    public static final int DEFAULT_OVERBOUGHT = 150;

    /** 超卖阈值（默认 -150） */
    public static final int DEFAULT_OVERSOLD = -150;

    /** 最小信号强度，低于此值不触发买卖 */
    private static final double MIN_STRENGTH = 0.25;

    // ==================== 批量信号 ====================

    /**
     * 遍历 MACD-V 序列，检测所有历史买卖点。
     *
     * @param points     MACD-V 指标序列
     * @param overbought 超买阈值
     * @param oversold   超卖阈值
     * @return 买卖信号列表
     */
    public List<TradeSignal> generateBatch(List<MACDVPoint> points, int overbought, int oversold, List<Map<String, Object>> klineData) {
        List<TradeSignal> signals = new ArrayList<>();

        for (int i = 1; i < points.size(); i++) {
            MACDVPoint cur = points.get(i);
            MACDVPoint prev = points.get(i - 1);

            if (!cur.isValid() || !prev.isValid()) continue;

            double cm = cur.getMacdV().doubleValue();
            double cs = cur.getSignal().doubleValue();
            double pm = prev.getMacdV().doubleValue();
            double ps = prev.getSignal().doubleValue();

            // 金叉：MACD-V 线下方向上穿越 Signal 线
            if (pm < ps && cm >= cs) {
                signals.add(new TradeSignal(cur.getTime(), "BUY", "golden_cross", "金叉", 0.72));
            }
            // 死叉：MACD-V 线上方向下穿越 Signal 线
            if (pm >= ps && cm < cs) {
                signals.add(new TradeSignal(cur.getTime(), "SELL", "death_cross", "死叉", 0.72));
            }
            // 进入超卖区（低于阈值后看多）
            if (pm >= oversold && cm < oversold) {
                signals.add(new TradeSignal(cur.getTime(), "BUY", "oversold", "超卖", 0.55));
            }
            // 进入超买区（高于阈值后看空）
            if (pm <= overbought && cm > overbought) {
                signals.add(new TradeSignal(cur.getTime(), "SELL", "overbought", "超买", 0.55));
            }
        }

        return signals;
    }

    // ==================== 最新综合信号 ====================

    /**
     * 根据最新的两个有效指标点，综合评估当前的买卖方向。
     *
     * @param points     全部 MACD-V 点
     * @param overbought 超买阈值
     * @param oversold   超卖阈值
     * @return 综合信号结果
     */
    public LatestSignal evaluateLatest(List<MACDVPoint> points, int overbought, int oversold) {
        // 找到最后一个有效点和它的前一个有效点
        MACDVPoint latest = null;
        MACDVPoint prev = null;

        for (int i = points.size() - 1; i >= 0; i--) {
            MACDVPoint p = points.get(i);
            if (p.isValid()) {
                if (latest == null) {
                    latest = p;
                } else if (prev == null) {
                    prev = p;
                    break;
                }
            }
        }

        if (latest == null) {
            return new LatestSignal("HOLD", 0, "无数据", 0, 0, 0);
        }

        // 提取数值
        double macdV  = latest.getMacdV().doubleValue();
        double signal = latest.getSignal().doubleValue();
        double hist   = latest.getHist().doubleValue();

        double pMacdV = prev != null ? prev.getMacdV().doubleValue()  : macdV;
        double pSig   = prev != null ? prev.getSignal().doubleValue() : signal;
        double pHist  = prev != null ? prev.getHist().doubleValue()   : hist;

        // 三个维度加权评分
        double bullScore = 0;
        double bearScore = 0;
        List<String> reasons = new ArrayList<>();

        // 1. 金叉 / 死叉（权重 0.45）
        if (pMacdV < pSig && macdV >= signal) {
            bullScore += 0.45;
            reasons.add("金叉");
        } else if (pMacdV >= pSig && macdV < signal) {
            bearScore += 0.45;
            reasons.add("死叉");
        }

        // 2. 超买 / 超卖（权重 0.25）
        if (macdV < oversold) {
            double intensity = Math.min(1.0, (oversold - macdV) / 150.0);
            bullScore += 0.25 * intensity;
            reasons.add("超卖");
        } else if (macdV > overbought) {
            double intensity = Math.min(1.0, (macdV - overbought) / 150.0);
            bearScore += 0.25 * intensity;
            reasons.add("超买");
        }

        // 3. 柱状图趋势（权重 0.30）
        if (hist > 0) {
            double intensity = Math.min(1.0, hist / 100.0);
            bullScore += 0.30 * intensity;
            if (pHist <= 0) {
                bullScore += 0.09;
                reasons.add("柱状图翻正");
            }
        } else if (hist < 0) {
            double intensity = Math.min(1.0, Math.abs(hist) / 100.0);
            bearScore += 0.30 * intensity;
            if (pHist >= 0) {
                bearScore += 0.09;
                reasons.add("柱状图翻负");
            }
        }

        // 综合判定
        String type;
        double strength;
        if (bullScore > bearScore && bullScore >= MIN_STRENGTH) {
            type = "BUY";
            strength = Math.min(1.0, bullScore);
        } else if (bearScore > bullScore && bearScore >= MIN_STRENGTH) {
            type = "SELL";
            strength = Math.min(1.0, bearScore);
        } else {
            type = "HOLD";
            strength = 0;
        }

        return new LatestSignal(
                type,
                strength,
                reasons.isEmpty() ? "中性" : String.join("+", reasons),
                round2(macdV),
                round2(signal),
                round2(hist)
        );
    }

    /** 保留两位小数 */
    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
