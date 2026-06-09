package com.qw.agent.line.model;

/**
 * 最新综合信号 —— 描述当前最新的一个综合评判结果。
 */
public class LatestSignal {

    /** 综合方向：BUY（买入） / SELL（卖出） / HOLD（观望） */
    private String type;

    /** 综合强度（0 ~ 1） */
    private double strength;

    /** 原因描述 */
    private String reason;

    /** 最新的 MACD-V 线值 */
    private double macdvLine;

    /** 最新的 Signal 线值 */
    private double macdvSignal;

    /** 最新的柱状图值 */
    private double macdvHist;

    public LatestSignal() {}

    public LatestSignal(String type, double strength, String reason, double macdvLine, double macdvSignal, double macdvHist) {
        this.type = type;
        this.strength = strength;
        this.reason = reason;
        this.macdvLine = macdvLine;
        this.macdvSignal = macdvSignal;
        this.macdvHist = macdvHist;
    }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public double getStrength() { return strength; }
    public void setStrength(double strength) { this.strength = strength; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public double getMacdvLine() { return macdvLine; }
    public void setMacdvLine(double macdvLine) { this.macdvLine = macdvLine; }
    public double getMacdvSignal() { return macdvSignal; }
    public void setMacdvSignal(double macdvSignal) { this.macdvSignal = macdvSignal; }
    public double getMacdvHist() { return macdvHist; }
    public void setMacdvHist(double macdvHist) { this.macdvHist = macdvHist; }
}
