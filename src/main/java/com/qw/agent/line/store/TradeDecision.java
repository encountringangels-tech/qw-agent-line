package com.qw.agent.line.store;

/**
 * 多周期MACDV买卖决策结果 —— 直接回答"下一根K线是否可以立即买卖"。
 *
 * <pre>
 * action 取值:
 *   LONG        — 下一根K线做多
 *   SHORT       — 下一根K线做空
 *   CLOSE_LONG  — 下一根K线平多
 *   CLOSE_SHORT — 下一根K线平空
 *   HOLD        — 观望，不操作
 * </pre>
 */
public class TradeDecision {

    /** 操作动作 */
    private String action;

    /** 置信度 0~1，越高越可靠 */
    private double confidence;

    /** 人类可读的决策原因 */
    private String reason;

    /** 当前各周期 MACDV 值（用于前端展示/验证） */
    private double dailyMacdv;
    private double fourHourMacdv;
    private double oneHourMacdv;
    private double fifteenMinMacdv;
    private double fiveMinMacdv;

    /** 各周期 MACDV 更新时间（Unix秒），用于判断数据新鲜度 */
    private long dailyTime;
    private long fourHourTime;
    private long oneHourTime;
    private long fifteenMinTime;
    private long fiveMinTime;

    /** 最新价格 */
    private double lastPrice;

    // ===== Builder风格 =====

    public static TradeDecision of(String action, double confidence, String reason) {
        TradeDecision d = new TradeDecision();
        d.action = action;
        d.confidence = confidence;
        d.reason = reason;
        return d;
    }

    // ===== getter / setter =====

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public double getConfidence() { return confidence; }
    public void setConfidence(double confidence) { this.confidence = confidence; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public double getDailyMacdv() { return dailyMacdv; }
    public void setDailyMacdv(double dailyMacdv) { this.dailyMacdv = dailyMacdv; }

    public double getFourHourMacdv() { return fourHourMacdv; }
    public void setFourHourMacdv(double fourHourMacdv) { this.fourHourMacdv = fourHourMacdv; }

    public double getOneHourMacdv() { return oneHourMacdv; }
    public void setOneHourMacdv(double oneHourMacdv) { this.oneHourMacdv = oneHourMacdv; }

    public double getFifteenMinMacdv() { return fifteenMinMacdv; }
    public void setFifteenMinMacdv(double fifteenMinMacdv) { this.fifteenMinMacdv = fifteenMinMacdv; }

    public double getFiveMinMacdv() { return fiveMinMacdv; }
    public void setFiveMinMacdv(double fiveMinMacdv) { this.fiveMinMacdv = fiveMinMacdv; }

    public long getDailyTime() { return dailyTime; }
    public void setDailyTime(long dailyTime) { this.dailyTime = dailyTime; }

    public long getFourHourTime() { return fourHourTime; }
    public void setFourHourTime(long fourHourTime) { this.fourHourTime = fourHourTime; }

    public long getOneHourTime() { return oneHourTime; }
    public void setOneHourTime(long oneHourTime) { this.oneHourTime = oneHourTime; }

    public long getFifteenMinTime() { return fifteenMinTime; }
    public void setFifteenMinTime(long fifteenMinTime) { this.fifteenMinTime = fifteenMinTime; }

    public long getFiveMinTime() { return fiveMinTime; }
    public void setFiveMinTime(long fiveMinTime) { this.fiveMinTime = fiveMinTime; }

    public double getLastPrice() { return lastPrice; }
    public void setLastPrice(double lastPrice) { this.lastPrice = lastPrice; }

    /** 简短摘要 */
    @Override
    public String toString() {
        return String.format("TradeDecision[%s confidence=%.0f%%] %s | D:%.1f 4H:%.1f 1H:%.1f 15m:%.1f 5m:%.1f",
                action, confidence * 100, reason,
                dailyMacdv, fourHourMacdv, oneHourMacdv, fifteenMinMacdv, fiveMinMacdv);
    }
}
