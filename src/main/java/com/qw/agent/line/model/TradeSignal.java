package com.qw.agent.line.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 买卖信号模型 —— 记录一次具体的买卖点事件。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TradeSignal {

    /** 信号发生的时间（Unix 秒级时间戳） */
    private long time;

    /** 信号类型：BUY（买入） / SELL（卖出） */
    private String type;

    /** 信号子类型：golden_cross（金叉） / death_cross（死叉） / oversold（超卖） / overbought（超买） */
    private String subType;

    /** 人类可读的原因描述 */
    private String reason;

    /** 信号强度（0 ~ 1，越高越强） */
    private double strength;
}
