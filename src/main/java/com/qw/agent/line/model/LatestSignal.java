package com.qw.agent.line.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 最新综合信号 —— 描述当前最新的一个综合评判结果。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
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
}
