package com.qw.agent.line.strategy;

import com.qw.agent.line.model.LatestSignal;
import com.qw.agent.line.model.MACDVPoint;
import com.qw.agent.line.model.TradeSignal;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MACD-V 买卖信号生成器 —— 基于 MACDV 区间 + Hist 形态。
 * <p>
 * 信号类型：
 * <ul>
 *   <li><b>d (开多)</b> — MACDV 低于阈值 + Hist V底</li>
 *   <li><b>pd (平多)</b> — Hist 倒V顶（需满足最小持仓K线数）</li>
 *   <li><b>k (开空)</b> — MACDV 高于阈值 + Hist 倒V顶</li>
 *   <li><b>pk (平空)</b> — Hist V底（需满足最小持仓K线数）</li>
 * </ul>
 * <p>
 * 趋势过滤：当启用时，MACDV>0 只允许做多，MACDV&lt;0 只允许做空。
 */
@Component
public class MACDVSignalGenerator {

    /** 开多阈值：MACDV 低于此值考虑开多 */
    public static final int DEFAULT_D_TH = -100;

    /** 开空阈值：MACDV 高于此值考虑开空 */
    public static final int DEFAULT_K_TH = 100;

    /** 默认使用趋势过滤 */
    public static final boolean DEFAULT_TREND_FILTER = true;

    /** 默认最小持仓K线数 */
    public static final int DEFAULT_MIN_HOLD_BARS = 3;

    // ==================== 批量信号 ====================

    /**
     * 遍历 MACD-V 序列，检测所有 d/pd/k/pk 信号。
     *
     * @param points       MACD-V 指标序列
     * @param dTh          开多阈值 (MACDV < dTh + HistV底 → d)
     * @param kTh          开空阈值 (MACDV > kTh + Hist倒V顶 → k)
     * @param trendFilter  趋势过滤: true=MACDV>0只做多, MACDV<0只做空
     * @param minHoldBars  最小持仓K线数(平仓信号需持仓≥此值才触发)
     * @param klineData    K 线数据（当前未使用，保留接口兼容）
     * @return 买卖信号列表
     */
    public List<TradeSignal> generateBatch(List<MACDVPoint> points, int dTh, int kTh,
                                            boolean trendFilter, int minHoldBars,
                                            List<Map<String, Object>> klineData) {
        List<TradeSignal> signals = new ArrayList<>();

        // 状态跟踪：记录每笔开仓的索引
        String state = "NONE";
        int entryIdx = -1;

        for (int i = 2; i < points.size(); i++) {
            MACDVPoint cur = points.get(i);
            MACDVPoint prev = points.get(i - 1);
            MACDVPoint prev2 = points.get(i - 2);

            if (!cur.isValid() || !prev.isValid() || !prev2.isValid()) continue;

            double cm = cur.getMacdV().doubleValue();
            double h  = cur.getHist().doubleValue();
            double h1 = prev.getHist().doubleValue();
            double h2 = prev2.getHist().doubleValue();

            boolean histVBottom = h2 > h1 && h1 < h;  // Hist V底
            boolean histVTop    = h2 < h1 && h1 > h;  // Hist 倒V顶

            // 趋势过滤：MACDV > 0 只允许做多，MACDV < 0 只允许做空
            boolean isD = !trendFilter ? (cm < dTh) : (cm > 0);
            boolean isK = !trendFilter ? (cm > kTh) : (cm < 0);

            // === 状态机 ===
            if ("NONE".equals(state)) {
                // d (开多): 超卖 + HistV底
                if (isD && histVBottom) {
                    signals.add(new TradeSignal(cur.getTime(), "d", "d", "开多",
                            round2(0.45 + 0.20 * Math.min(1.0, (dTh - cm) / 100.0))));
                    state = "LONG";
                    entryIdx = i;
                }
                // k (开空): 超买 + Hist倒V顶
                else if (isK && histVTop) {
                    signals.add(new TradeSignal(cur.getTime(), "k", "k", "开空",
                            round2(0.45 + 0.20 * Math.min(1.0, (cm - kTh) / 100.0))));
                    state = "SHORT";
                    entryIdx = i;
                }
            } else if ("LONG".equals(state)) {
                boolean heldLongEnough = (i - entryIdx) >= minHoldBars;
                // pd (平多): Hist倒V顶 + 持仓足够久
                if (histVTop && heldLongEnough) {
                    signals.add(new TradeSignal(cur.getTime(), "pd", "pd", "平多", 0.45));
                    state = "NONE";
                }
            } else if ("SHORT".equals(state)) {
                boolean heldLongEnough = (i - entryIdx) >= minHoldBars;
                // pk (平空): HistV底 + 持仓足够久
                if (histVBottom && heldLongEnough) {
                    signals.add(new TradeSignal(cur.getTime(), "pk", "pk", "平空", 0.45));
                    state = "NONE";
                }
            }
        }

        return signals;
    }

    // ==================== 最新综合信号（带状态跟踪） ====================

    /**
     * 根据全部指标点，模拟状态机得到最新的持仓状态和信号。
     *
     * @param points      全部 MACD-V 点
     * @param dTh         开多阈值
     * @param kTh         开空阈值
     * @param trendFilter 趋势过滤
     * @param minHoldBars 最小持仓K线数
     * @return 最新综合信号结果
     */
    public LatestSignal evaluateLatest(List<MACDVPoint> points, int dTh, int kTh,
                                        boolean trendFilter, int minHoldBars) {
        String state = "NONE";
        String lastSignalType = "HOLD";
        String lastReason = "中性";
        double lastStrength = 0;
        int entryIdx = -1;

        // 找到最后有效点用于数值展示
        MACDVPoint latest = null;
        for (int i = points.size() - 1; i >= 0; i--) {
            if (points.get(i).isValid()) {
                latest = points.get(i);
                break;
            }
        }
        if (latest == null) {
            return new LatestSignal("HOLD", 0, "无数据", 0, 0, 0);
        }

        double curMacdV = latest.getMacdV().doubleValue();
        double curSignal = latest.getSignal().doubleValue();
        double curHist = latest.getHist().doubleValue();

        // 状态机遍历
        for (int i = 2; i < points.size(); i++) {
            MACDVPoint cur = points.get(i);
            MACDVPoint prev = points.get(i - 1);
            MACDVPoint prev2 = points.get(i - 2);

            if (!cur.isValid() || !prev.isValid() || !prev2.isValid()) continue;

            double cm = cur.getMacdV().doubleValue();
            double h  = cur.getHist().doubleValue();
            double h1 = prev.getHist().doubleValue();
            double h2 = prev2.getHist().doubleValue();

            boolean histVBottom = h2 > h1 && h1 < h;
            boolean histVTop    = h2 < h1 && h1 > h;

            // 趋势过滤（与 generateBatch 一致）
            boolean isD = !trendFilter ? (cm < dTh) : (cm > 0);
            boolean isK = !trendFilter ? (cm > kTh) : (cm < 0);

            if ("NONE".equals(state)) {
                if (isD && histVBottom) {
                    state = "LONG";
                    entryIdx = i;
                    lastSignalType = "d";
                    lastReason = "开多";
                    lastStrength = round2(0.45 + 0.20 * Math.min(1.0, (dTh - cm) / 100.0));
                } else if (isK && histVTop) {
                    state = "SHORT";
                    entryIdx = i;
                    lastSignalType = "k";
                    lastReason = "开空";
                    lastStrength = round2(0.45 + 0.20 * Math.min(1.0, (cm - kTh) / 100.0));
                }
            } else if ("LONG".equals(state) && histVTop && (i - entryIdx) >= minHoldBars) {
                state = "NONE";
                lastSignalType = "pd";
                lastReason = "平多";
                lastStrength = 0.45;
            } else if ("SHORT".equals(state) && histVBottom && (i - entryIdx) >= minHoldBars) {
                state = "NONE";
                lastSignalType = "pk";
                lastReason = "平空";
                lastStrength = 0.45;
            }
        }

        if ("LONG".equals(state)) {
            lastSignalType = "d(持仓)";
            lastReason = "持多中";
        } else if ("SHORT".equals(state)) {
            lastSignalType = "k(持仓)";
            lastReason = "持空中";
        }

        return new LatestSignal(
                lastSignalType,
                lastStrength,
                lastReason,
                round2(curMacdV),
                round2(curSignal),
                round2(curHist)
        );
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
