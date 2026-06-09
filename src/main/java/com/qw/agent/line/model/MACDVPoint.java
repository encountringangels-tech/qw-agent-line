package com.qw.agent.line.model;

import java.math.BigDecimal;

/**
 * MACD-V 指标数据点 —— 对应一根 K 线计算出的三个值。
 * <p>
 * 字段说明（与 Pine Script "MACD-V" by jamiedubauskas 一致）：
 * <ul>
 *   <li><b>macdV</b>  — MACD-V 线 = ((EMA(fast) − EMA(slow)) / ATR) × 100</li>
 *   <li><b>signal</b> — Signal 线 = EMA(MACD-V, signalLen)</li>
 *   <li><b>hist</b>   — 柱状图 = MACD-V − Signal</li>
 * </ul>
 */
public class MACDVPoint {

    /** Unix 秒级时间戳 */
    private long time;

    /** MACD-V 线值（可能为 null，表示数据不足无法计算） */
    private BigDecimal macdV;

    /** Signal 线值（可能为 null） */
    private BigDecimal signal;

    /** 柱状图值（可能为 null） */
    private BigDecimal hist;

    public MACDVPoint() {}

    public MACDVPoint(long time, BigDecimal macdV, BigDecimal signal, BigDecimal hist) {
        this.time = time;
        this.macdV = macdV;
        this.signal = signal;
        this.hist = hist;
    }

    /** 该点数据是否有效（三个值都不为 null） */
    public boolean isValid() {
        return macdV != null && signal != null && hist != null;
    }

    public long getTime() { return time; }
    public void setTime(long time) { this.time = time; }
    public BigDecimal getMacdV() { return macdV; }
    public void setMacdV(BigDecimal macdV) { this.macdV = macdV; }
    public BigDecimal getSignal() { return signal; }
    public void setSignal(BigDecimal signal) { this.signal = signal; }
    public BigDecimal getHist() { return hist; }
    public void setHist(BigDecimal hist) { this.hist = hist; }
}
