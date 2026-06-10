package com.qw.agent.line.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

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
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MACDVPoint {

    /** Unix 秒级时间戳 */
    private long time;

    /** MACD-V 线值（可能为 null，表示数据不足无法计算） */
    private BigDecimal macdV;

    /** Signal 线值（可能为 null） */
    private BigDecimal signal;

    /** 柱状图值（可能为 null） */
    private BigDecimal hist;

    /** 该点数据是否有效（三个值都不为 null） */
    public boolean isValid() {
        return macdV != null && signal != null && hist != null;
    }
}
