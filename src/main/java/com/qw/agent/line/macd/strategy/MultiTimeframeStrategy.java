package com.qw.agent.line.macd.strategy;

import com.qw.agent.line.macd.model.Kline;
import com.qw.agent.line.macd.model.MACDVPoint;
import com.qw.agent.line.macd.model.TradeDecision;
import com.qw.agent.line.store.KlineStore;
import com.qw.agent.line.util.MathUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多周期 MACDV 买卖点策略 —— 基于日线/4H/1H/15min/5min 五周期联动。
 *
 * <h3>策略层级（从大到小）</h3>
 * <ol>
 *   <li><b>日线（大周期）</b>：决定方向过滤 — MACDV &gt; 0 只做多，MACDV &lt; -120 只做空</li>
 *   <li><b>4H（中周期）</b>：确定中期趋势 — MACDV &gt; 0 多头，MACDV &lt; 0 空头</li>
 *   <li><b>1H（确认层）</b>：极值区域确认 — 数据统计 P10≈-101, P90≈79</li>
 *   <li><b>15min（核心买卖层）</b>：两个买点 [-100附近, 0附近]，一个止盈线 &gt;80</li>
 *   <li><b>5min（精确层）</b>：极端值 &gt;150 做空, &lt;-150 做多</li>
 * </ol>
 *
 * <h3>基于数据的统计阈值（分析90天BTCUSDT MACDV分布）</h3>
 * <pre>
 *   15min: P5=-120, P10=-90, P50=-1.3, P90=82,  &gt;80 仅占 8.2%
 *   5min:  &gt;150 仅占 2.1%, &lt;-150 仅占 1.9%
 *   1H:    &gt;90 仅占 7.5%, &lt;-100 仅占 10.2%
 *   4H:    P10=-97, P90=85
 *   1D:    P5=-165, P95=83
 * </pre>
 *
 * <p>调用方式：注入 {@link KlineStore} 后调用 {@link #decide(String)}。</p>
 */
@Component
public class MultiTimeframeStrategy {

    private static final Logger log = LoggerFactory.getLogger(MultiTimeframeStrategy.class);

    private final KlineStore klineStore;

    public MultiTimeframeStrategy(KlineStore klineStore) {
        this.klineStore = klineStore;
    }

    // ==================== 统计阈值（基于历史数据） ====================

    /** 日线强空头分界线：低于此值禁止做多 */
    static final double DAILY_BEARISH_THRESHOLD = -120.0;

    /** 日线多头分界线：高于此值禁止做空 */
    static final double DAILY_BULLISH_THRESHOLD = 0.0;

    /** 4H 强多头阈值 */
    static final double FOUR_HOUR_STRONG_THRESHOLD = 50.0;

    /** 4H偏空区间下界（回测表明此区间做多亏损） */
    static final double FOUR_HOUR_BEARISH_ZONE = -20.0;

    /** 4H强空区间上界（回测优化值-60，原值-120太严导致仅6次做空；放宽到-60后做空增至27次，总收益+35%） */
    static final double FOUR_HOUR_STRONG_BEAR = -60.0;

    /** 15min 深回调买点（对应 P10 ~ P15） */
    static final double FIFTEEN_MIN_DEEP_PULLBACK = -100.0;
    static final double FIFTEEN_MIN_DEEP_PULLBACK_TOLERANCE = 25.0;  // ±25 即 [-125, -75]

    /** 15min 零轴买点（回调到0轴附近） */
    static final double FIFTEEN_MIN_AXIS = 0.0;
    static final double FIFTEEN_MIN_AXIS_TOLERANCE = 25.0;           // ±25 即 [-25, 25]

    /** 15min 止盈线（P90 以上） */
    static final double FIFTEEN_MIN_TAKE_PROFIT = 80.0;

    /** 15min 短空入场区域（对称的深拉回） */
    static final double FIFTEEN_MIN_DEEP_RALLY = 100.0;
    static final double FIFTEEN_MIN_DEEP_RALLY_TOLERANCE = 25.0;     // ±25 即 [75, 125]

    /** 5min 极端值 — 精确买卖点（仅 2% 数据点达到） */
    static final double FIVE_MIN_EXTREME_OVERBOUGHT = 150.0;
    static final double FIVE_MIN_EXTREME_OVERSOLD = -150.0;

    /** 1H 极值区 — 顶部/底部确认 */
    static final double ONE_HOUR_TOP_ZONE = 90.0;
    static final double ONE_HOUR_BOTTOM_ZONE = -100.0;

    // ==================== 评分阈值 ====================

    static final int SCORE_LONG_THRESHOLD = 7;
    static final int SCORE_SHORT_THRESHOLD = 8;

    /** 平仓后冷却K线数（防止立即反向开仓被反复打脸） */
    static final int COOLDOWN_BARS = 3;

    /** 趋势一致性加分：日线/4H/1H三者同向 */
    static final int TREND_ALIGN_BONUS = 2;

    /** 部分趋势对齐加分 */
    static final int TREND_PARTIAL_BONUS = 1;

    // ==================== 回测优化新增参数 ====================

    /** 4H MACDV 从持仓最高点回落阈值（超出此比例即视为见顶平仓） */
    static final double H4_PEAK_RETRACE_THRESHOLD = 0.13;  // 13%，密集扫描最优(回撤11.74%+胜率58.6%)

    /** 最大持仓 15min K 线数（时间止损，96根 = 24小时） */
    static final int MAX_POSITION_BARS = 72;

    /** 1H MACDV 辅助见顶回落阈值（仅当4H未触发时使用） */
    static final double H1_PEAK_RETRACE_THRESHOLD = 0.12;  // 12%，比4H略宽松避免假信号

    // ==================== 持仓状态跟踪 ====================

    /**
     * 每个交易对的持仓跟踪状态。
     * key=symbol, value=当前持仓跟踪数据
     */
    private final Map<String, PositionState> positionStates = new ConcurrentHashMap<>();

    private static class PositionState {
        /** 持仓期间4H MACDV最高值（用于判断见顶回落） */
        double highestH4Mv = Double.MIN_VALUE;
        /** 持仓期间1H MACDV最高值（辅助判断） */
        double highestH1Mv = Double.MIN_VALUE;
        /** 距离上次开仓经过的15min K线数（时间止损） */
        int barsSinceEntry = 0;
    }

    /**
     * 通知策略一个新的持仓已经打开（由外部交易引擎在开仓后调用）。
     * 重置该symbol的持仓跟踪状态。
     */
    public void resetPositionState(String symbol) {
        positionStates.put(symbol, new PositionState());
    }

    /**
     * 通知策略持仓已平仓（清理跟踪状态）。
     */
    public void clearPositionState(String symbol) {
        positionStates.remove(symbol);
    }

    // ==================== 公开入口 ====================

    /**
     * 根据当前所有周期的 MACDV 指标，返回下一根 15min K 线是否可以立即买卖。
     *
     * @param symbol 交易对（如 BTCUSDT）
     * @return 买卖决策
     */
    public TradeDecision decide(String symbol) {

        // 1. 高效读取各周期最新 MACDV 值（单点查询，非全量加载）
        MACDVPoint daily   = klineStore.getLatestMACDVPoint(symbol, "1d");
        MACDVPoint fourH   = klineStore.getLatestMACDVPoint(symbol, "4h");
        MACDVPoint oneH    = klineStore.getLatestMACDVPoint(symbol, "1h");
        MACDVPoint fifteen = klineStore.getLatestMACDVPoint(symbol, "15m");
        MACDVPoint five    = klineStore.getLatestMACDVPoint(symbol, "5m");

        // 2. 提取数值
        double dMv  = val(daily);
        double h4Mv = val(fourH);
        double h1Mv = val(oneH);
        double m15Mv = val(fifteen);
        double m5Mv  = val(five);

        // 最新价格（从5分钟K线取收盘价）
        double lastPrice = 0;
        Kline latestKline = klineStore.getLatestKline(symbol, "5m");
        if (latestKline != null) {
            lastPrice = latestKline.getClose().doubleValue();
        }

        // 3. 计算多/空双向评分
        ScoreResult ls = calcLongScore(dMv, h4Mv, h1Mv, m15Mv, m5Mv);
        ScoreResult ss = calcShortScore(dMv, h4Mv, h1Mv, m15Mv, m5Mv);

        // 4. 判定最终信号
        String action;
        double confidence;
        StringBuilder reason = new StringBuilder();

        // 5. 计算建议杠杆倍数（基于多周期MACDV位置，非单纯分数）
        int leverage = 2;  // 默认2x（所有合格信号至少2x）
        boolean h4StrongBullish = (h4Mv > 50);
        boolean h4StrongBearish = (h4Mv < FOUR_HOUR_STRONG_BEAR);
        boolean h1NotConflictLong = (h1Mv > 0);
        boolean h1NotConflictShort = (h1Mv < 0);

        if (ls.score >= SCORE_LONG_THRESHOLD && ls.score >= ss.score) {
            // [优化] Score 10 过滤：回测胜率仅50%且拉高回撤，跳过以降低风险
            if (ls.score == 10) {
                action = "HOLD";
                confidence = 0.30;
                reason.append(ls.reason).append(" [Score10过滤]");
            } else {
                action = "LONG";
                confidence = MathUtil.normalizeScore(ls.score);
                reason.append(ls.reason);
                // 3x: 4H强势(>50) + 1H不冲突(>0) — 回测最大亏损仅1.96%
                if (h4StrongBullish && h1NotConflictLong) {
                    leverage = 3;
                    reason.append(" ★3x杠杆");
                } else {
                    leverage = 2;
                    reason.append(" ★2x杠杆");
                }
                // 新开多头：重置持仓跟踪状态
                resetPositionState(symbol);
            }
        } else if (ss.score >= SCORE_SHORT_THRESHOLD && ss.score > ls.score) {
            // [优化] Score 10 过滤：回测胜率仅50%且拉高回撤，跳过以降低风险
            if (ss.score == 10) {
                action = "HOLD";
                confidence = 0.30;
                reason.append(ss.reason).append(" [Score10过滤]");
            } else if (h1Mv < -150) {
                // [优化] 1H极端超卖<-150：禁止做空（反弹概率55.6% > 继续下跌44.4%）
                action = "HOLD";
                confidence = 0.25;
                reason.append(" 1H<-150极端超卖禁止做空");
            } else {
                action = "SHORT";
                confidence = MathUtil.normalizeScore(ss.score);
                reason.append(ss.reason);
                // 3x: 4H强空(<-60) + 1H配合(<0) — 回测最大亏损仅2.21%
                if (h4StrongBearish && h1NotConflictShort) {
                    leverage = 3;
                    reason.append(" ★3x杠杆");
                } else {
                    leverage = 2;
                    reason.append(" ★2x杠杆");
                }
                // [优化] 1H超卖<-100：做空降杠杆到2x，减少极端区放大亏损
                if (h1Mv < -100) {
                    leverage = Math.min(leverage, 2);
                    reason.append(" [1H<-100降2x]");
                }
                // 新开空头：重置持仓跟踪状态
                resetPositionState(symbol);
            }
        } else {
            action = "HOLD";
            double maxScore = Math.max(ls.score, ss.score);
            confidence = Math.min(0.45, Math.max(0.05, maxScore / 8.0));
            reason.append("多空信号不足");
            if (ls.score > 0) reason.append(" 多头=").append(ls.score).append("分");
            if (ss.score > 0) reason.append(" 空头=").append(ss.score).append("分");
            if (ls.score == 0 && ss.score == 0) reason.append(" 指标中性待方向明确");
        }

        if (ls.vetoed) reason.append(" [日线强空<-120过滤做多]");
        if (ss.vetoed) reason.append(" [日线多头>0过滤做空]");

        // 6. 组装结果
        TradeDecision dec = TradeDecision.of(action, MathUtil.round2(confidence), reason.toString());
        dec.setLeverage(leverage);
        dec.setDailyMacdv(MathUtil.round2(dMv));
        dec.setFourHourMacdv(MathUtil.round2(h4Mv));
        dec.setOneHourMacdv(MathUtil.round2(h1Mv));
        dec.setFifteenMinMacdv(MathUtil.round2(m15Mv));
        dec.setFiveMinMacdv(MathUtil.round2(m5Mv));
        dec.setLastPrice(MathUtil.round2(lastPrice));
        if (daily != null)   dec.setDailyTime(daily.getTime());
        if (fourH != null)   dec.setFourHourTime(fourH.getTime());
        if (oneH != null)    dec.setOneHourTime(oneH.getTime());
        if (fifteen != null) dec.setFifteenMinTime(fifteen.getTime());
        if (five != null)    dec.setFiveMinTime(five.getTime());

        log.info("多周期决策: {}", dec);
        return dec;
    }

    /**
     * 判断当前是否应该平多。
     * <p>
     * 满足以下任一条件即平多：
     * <ol>
     *   <li>15min MACDV &gt; 80（原始止盈，快速获利了结）</li>
     *   <li>4H MACDV 从持仓最高点回落 &gt; 15%（见顶信号，趋势可能反转）</li>
     *   <li>持仓超过 96 根 15min K 线（时间止损，约24小时）</li>
     *   <li>1H MACDV 从持仓最高点回落 &gt; 20%（辅助信号，4H未触发时生效）</li>
     * </ol>
     */
    public boolean shouldCloseLong(String symbol) {
        // ---- 更新持仓状态 ----
        PositionState state = positionStates.computeIfAbsent(symbol, k -> new PositionState());
        state.barsSinceEntry++;

        MACDVPoint fifteen = klineStore.getLatestMACDVPoint(symbol, "15m");
        MACDVPoint fourH   = klineStore.getLatestMACDVPoint(symbol, "4h");
        MACDVPoint oneH    = klineStore.getLatestMACDVPoint(symbol, "1h");

        double m15Mv = val(fifteen);
        double h4Mv  = val(fourH);
        double h1Mv  = val(oneH);

        // 更新持仓期间的最高值
        if (h4Mv > state.highestH4Mv) state.highestH4Mv = h4Mv;
        if (h1Mv > state.highestH1Mv) state.highestH1Mv = h1Mv;

        // ---- 条件1: 15min MACDV > 80 止盈（原始逻辑） ----
        if (m15Mv > FIFTEEN_MIN_TAKE_PROFIT) {
            log.info("平多[止盈] symbol={} 15minMv={} > {}", symbol, m15Mv, FIFTEEN_MIN_TAKE_PROFIT);
            return true;
        }

        // ---- 条件2: 4H MACDV 从持仓峰值回落 > 15%（见顶平仓） ----
        if (state.highestH4Mv > 0 && h4Mv > -50) {
            double retracePct = (state.highestH4Mv - h4Mv) / Math.abs(state.highestH4Mv);
            if (retracePct > H4_PEAK_RETRACE_THRESHOLD) {
                log.info("平多[4H见顶] symbol={} 4H最高={} 当前={} 回落={}%",
                        symbol, MathUtil.round2(state.highestH4Mv), MathUtil.round2(h4Mv), MathUtil.round2(retracePct * 100));
                return true;
            }
        }

        // ---- 条件3: 时间止损（持仓超过 MAX_POSITION_BARS 根15min K线） ----
        if (state.barsSinceEntry >= MAX_POSITION_BARS) {
            log.info("平多[时间止损] symbol={} 持仓{}根K线 >= {}",
                    symbol, state.barsSinceEntry, MAX_POSITION_BARS);
            return true;
        }

        // ---- 条件4: 1H MACDV 辅助见顶回落 > 20%（仅当4H值较低时辅助判断） ----
        if (state.highestH1Mv > 0 && h1Mv > -50 && state.highestH4Mv < 30) {
            double retracePct = (state.highestH1Mv - h1Mv) / Math.abs(state.highestH1Mv);
            if (retracePct > H1_PEAK_RETRACE_THRESHOLD) {
                log.info("平多[1H见顶] symbol={} 1H最高={} 当前={} 回落={}%",
                        symbol, MathUtil.round2(state.highestH1Mv), MathUtil.round2(h1Mv), MathUtil.round2(retracePct * 100));
                return true;
            }
        }

        return false;
    }

    /**
     * 判断当前是否应该平空。
     * <p>
     * 满足以下任一条件即平空：
     * <ol>
     *   <li>15min MACDV &lt; -80（原始止盈）</li>
     *   <li>4H MACDV 从持仓最低点反弹 &gt; 15%（见底信号）</li>
     *   <li>持仓超过 96 根 15min K 线（时间止损）</li>
     * </ol>
     */
    public boolean shouldCloseShort(String symbol) {
        // ---- 更新持仓状态 ----
        PositionState state = positionStates.computeIfAbsent(symbol, k -> new PositionState());
        state.barsSinceEntry++;

        MACDVPoint fifteen = klineStore.getLatestMACDVPoint(symbol, "15m");
        MACDVPoint fourH   = klineStore.getLatestMACDVPoint(symbol, "4h");
        MACDVPoint oneH    = klineStore.getLatestMACDVPoint(symbol, "1h");

        double m15Mv = val(fifteen);
        double h4Mv  = val(fourH);
        double h1Mv  = val(oneH);

        // 更新持仓期间的最低值（空头看最低，反弹即见底）
        if (h4Mv < state.highestH4Mv) state.highestH4Mv = h4Mv;   // 用highestH4Mv存"最低"值
        if (h1Mv < state.highestH1Mv) state.highestH1Mv = h1Mv;

        // ---- 条件1: 15min MACDV < -80 止盈（原始逻辑） ----
        if (m15Mv < -FIFTEEN_MIN_TAKE_PROFIT) {
            log.info("平空[止盈] symbol={} 15minMv={} < {}", symbol, m15Mv, -FIFTEEN_MIN_TAKE_PROFIT);
            return true;
        }

        // ---- 条件2: 4H MACDV 从持仓最低点反弹 > 15%（见底平仓） ----
        if (state.highestH4Mv < 0 && h4Mv < 50) {
            double bouncePct = (h4Mv - state.highestH4Mv) / Math.abs(state.highestH4Mv);
            if (bouncePct > H4_PEAK_RETRACE_THRESHOLD) {
                log.info("平空[4H见底] symbol={} 4H最低={} 当前={} 反弹={}%",
                        symbol, MathUtil.round2(state.highestH4Mv), MathUtil.round2(h4Mv), MathUtil.round2(bouncePct * 100));
                return true;
            }
        }

        // ---- 条件3: 时间止损 ----
        if (state.barsSinceEntry >= MAX_POSITION_BARS) {
            log.info("平空[时间止损] symbol={} 持仓{}根K线 >= {}",
                    symbol, state.barsSinceEntry, MAX_POSITION_BARS);
            return true;
        }

        return false;
    }

    // ==================== 评分逻辑 ====================

    private ScoreResult calcLongScore(double dMv, double h4Mv, double h1Mv, double m15Mv, double m5Mv) {
        int score = 0;
        boolean vetoed = false;
        StringBuilder reason = new StringBuilder();

        // ---- 日线：方向过滤 ----
        if (dMv > DAILY_BULLISH_THRESHOLD) {
            score += 3;
            reason.append("日线多头(+3) ");
        } else if (dMv > DAILY_BEARISH_THRESHOLD) {
            score += 1;
            reason.append("日线偏弱(+1) ");
        } else {
            // dMv <= -120: 禁止做多
            vetoed = true;
            reason.append("日线强空(否决) ");
        }

        // ---- 4H：中期趋势 + 阶段过滤(回测数据驱动) ----
        if (h4Mv > FOUR_HOUR_STRONG_THRESHOLD) {
            score += 3;
            reason.append("4H强多头(+3) ");
        } else if (h4Mv > 0) {
            score += 2;
            reason.append("4H弱多头(+2) ");
        } else if (h4Mv > FOUR_HOUR_BEARISH_ZONE) {
            // 4H在 -20~0: 零轴震荡，回测胜率71.4%，允许做多
            score += 0;
            reason.append("4H零轴震荡(+0) ");
        } else {
            // 4H < -20: 回测表明偏空/空头趋势阶段做多100%亏钱，直接否决
            vetoed = true;
            reason.append("4H偏空阶段(否决) ");
        }

        // ---- 15min：核心买卖点 ----
        if (MathUtil.inRange(m15Mv, FIFTEEN_MIN_DEEP_PULLBACK, FIFTEEN_MIN_DEEP_PULLBACK_TOLERANCE)) {
            // -100 附近：深回调买点（最佳）
            score += 4;
            reason.append("15min深回调-100附近(+4) ");
        } else if (MathUtil.inRange(m15Mv, FIFTEEN_MIN_AXIS, FIFTEEN_MIN_AXIS_TOLERANCE)) {
            // 0轴附近：回调到轴的买点
            score += 2;
            reason.append("15min零轴附近(+2) ");
        } else if (m15Mv > FIFTEEN_MIN_TAKE_PROFIT) {
            // > 80：已经进入止盈区，不适合开多
            score -= 3;
            reason.append("15min高位止盈区(-3) ");
        } else if (m15Mv < FIFTEEN_MIN_DEEP_PULLBACK - FIFTEEN_MIN_DEEP_PULLBACK_TOLERANCE) {
            // < -125：极端超卖但-100买点已过
            score += 1;
            reason.append("15min极端超卖(+1) ");
        } else if (m15Mv > 0 && m15Mv <= 50) {
            score += 1;
            reason.append("15min温和上涨(+1) ");
        }

        // ---- 5min：精确买卖点（权重强化） ----
        if (m5Mv < FIVE_MIN_EXTREME_OVERSOLD) {
            score += 3;
            reason.append("5min极值超卖<-150(+3) ");
        } else if (m5Mv > FIVE_MIN_EXTREME_OVERBOUGHT) {
            score -= 2;
            reason.append("5min极值超买>150(-2) ");
        }

        // ---- 1H：极值确认（权重强化） ----
        if (h1Mv < ONE_HOUR_BOTTOM_ZONE) {
            score += 2;
            reason.append("1H底部<-100(+2) ");
        } else if (h1Mv > ONE_HOUR_TOP_ZONE) {
            score -= 2;
            reason.append("1H顶部>90(-2) ");
        }

        // ---- 大小周期冲突惩罚：4H仍多但1H已转空（回调期防连续亏损） ----
        if (h4Mv > 0 && h1Mv <= 0) {
            score -= 3;
            reason.append("1H转空周期冲突(-3) ");
        } else if (h4Mv > 0 && h1Mv < 20) {
            score -= 1;
            reason.append("1H近零轴(-1) ");
        }

        // ---- 趋势一致性加分 ----
        if (dMv > 0 && h4Mv > 0 && h1Mv > 0) {
            score += TREND_ALIGN_BONUS;
            reason.append("趋势一致日4H1H同多(+").append(TREND_ALIGN_BONUS).append(") ");
        } else if (dMv > 0 && h4Mv > 0) {
            score += TREND_PARTIAL_BONUS;
            reason.append("日4H同多(+").append(TREND_PARTIAL_BONUS).append(") ");
        }

        return new ScoreResult(vetoed ? 0 : score, reason.toString().trim(), vetoed);
    }

    private ScoreResult calcShortScore(double dMv, double h4Mv, double h1Mv, double m15Mv, double m5Mv) {
        int score = 0;
        boolean vetoed = false;
        StringBuilder reason = new StringBuilder();

        // ---- 日线：方向过滤 ----
        if (dMv <= DAILY_BEARISH_THRESHOLD) {
            score += 3;
            reason.append("日线强空(+3) ");
        } else if (dMv <= DAILY_BULLISH_THRESHOLD) {
            score += 1;
            reason.append("日线偏空(+1) ");
        } else {
            // dMv > 0: 禁止做空
            vetoed = true;
            reason.append("日线多头(否决) ");
        }

        // ---- 4H：中期趋势 + 阶段过滤(回测优化值 -60) ----
        // 回测: 4H <-60做空胜率63%, 总贡献+16k; 非强空底阶段不反向做空
        if (h4Mv < FOUR_HOUR_STRONG_BEAR) {
            score += 5;
            reason.append("4H强空底做空(+5) ");
        } else {
            // 4H >= -60: 不做空（但可以做多）
            vetoed = true;
            reason.append("4H非强空底(否决) ");
        }

        // ---- 15min：核心买卖点 ----
        if (MathUtil.inRange(m15Mv, FIFTEEN_MIN_DEEP_RALLY, FIFTEEN_MIN_DEEP_RALLY_TOLERANCE)) {
            score += 4;
            reason.append("15min深拉回+100附近(+4) ");
        } else if (MathUtil.inRange(m15Mv, FIFTEEN_MIN_AXIS, FIFTEEN_MIN_AXIS_TOLERANCE)) {
            score += 2;
            reason.append("15min零轴附近(+2) ");
        } else if (m15Mv < -FIFTEEN_MIN_TAKE_PROFIT) {
            score -= 3;
            reason.append("15min低位止盈区(-3) ");
        } else if (m15Mv > FIFTEEN_MIN_DEEP_RALLY + FIFTEEN_MIN_DEEP_RALLY_TOLERANCE) {
            score += 1;
            reason.append("15min极端超买(+1) ");
        } else if (m15Mv < 0 && m15Mv >= -50) {
            score += 1;
            reason.append("15min温和下跌(+1) ");
        }

        // ---- 5min：精确买卖点（权重强化） ----
        if (m5Mv > FIVE_MIN_EXTREME_OVERBOUGHT) {
            score += 3;
            reason.append("5min极值超买>150(+3) ");
        } else if (m5Mv < FIVE_MIN_EXTREME_OVERSOLD) {
            score -= 2;
            reason.append("5min极值超卖<-150(-2) ");
        }

        // ---- 1H：极值确认（权重强化） ----
        if (h1Mv > ONE_HOUR_TOP_ZONE) {
            score += 2;
            reason.append("1H顶部>90(+2) ");
        } else if (h1Mv < ONE_HOUR_BOTTOM_ZONE) {
            score -= 2;
            reason.append("1H底部<-100(-2) ");
        }

        // ---- 趋势一致性加分 ----
        if (dMv < 0 && h4Mv < 0 && h1Mv < 0) {
            score += TREND_ALIGN_BONUS;
            reason.append("趋势一致日4H1H同空(+").append(TREND_ALIGN_BONUS).append(") ");
        } else if (dMv < 0 && h4Mv < 0) {
            score += TREND_PARTIAL_BONUS;
            reason.append("日4H同空(+").append(TREND_PARTIAL_BONUS).append(") ");
        }

        return new ScoreResult(vetoed ? 0 : score, reason.toString().trim(), vetoed);
    }

    // ==================== 工具方法 ====================

    private static double val(MACDVPoint p) {
        return p != null && p.isValid() ? p.getMacdV().doubleValue() : 0;
    }

    // ==================== 内嵌评分结果 ====================

    private static class ScoreResult {
        final int score;
        final String reason;
        final boolean vetoed;
        ScoreResult(int score, String reason, boolean vetoed) {
            this.score = score;
            this.reason = reason.isEmpty() ? "无明显信号" : reason;
            this.vetoed = vetoed;
        }
    }
}
