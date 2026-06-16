package com.qw.agent.line.model;

import java.math.BigDecimal;

/**
 * 15min 买卖点记录 —— 持久化到 trade_signal 表。
 *
 * <pre>
 * direction 取值:
 *   LONG   — 做多开仓
 *   SHORT  — 做空开仓
 *   CLOSE  — 平仓（止盈/反向信号）
 * </pre>
 */
public class TradeSignalRecord {

    /** 自增主键 */
    private Long id;

    /** 交易对 */
    private String symbol;

    /** 信号时间（Unix秒，对齐15min K线） */
    private long time;

    /** 方向：LONG / SHORT / CLOSE */
    private String direction;

    /** 信号时的收盘价 */
    private BigDecimal price;

    /** 开仓/平仓金额（USDT） */
    private BigDecimal amount;

    /** 策略评分 */
    private int score;

    /** 信号原因 */
    private String reason;

    /** 创建时间 */
    private String createdAt;

    // ===== getter / setter =====

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public long getTime() { return time; }
    public void setTime(long time) { this.time = time; }

    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }

    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public int getScore() { return score; }
    public void setScore(int score) { this.score = score; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
}
