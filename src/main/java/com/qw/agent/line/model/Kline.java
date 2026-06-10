package com.qw.agent.line.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * K 线数据模型 —— 对应币安 API 返回的单根蜡烛图。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Kline {

    /** 开盘时间戳（毫秒） */
    private long openTime;

    /** 开盘价 */
    private BigDecimal open;

    /** 最高价 */
    private BigDecimal high;

    /** 最低价 */
    private BigDecimal low;

    /** 收盘价 */
    private BigDecimal close;

    /** 成交量（基础资产数量） */
    private BigDecimal volume;
}
