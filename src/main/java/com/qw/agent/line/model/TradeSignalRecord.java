package com.qw.agent.line.model;

import java.math.BigDecimal;

/**
 * 买卖点记录 —— 持久化到 trade_signal 表。
 *
 * <pre>
 * direction 取值:
 *   LONG   — 做多开仓
 *   SHORT  — 做空开仓
 *   CLOSE  — 平仓（止盈/止损/反向信号）
 * </pre>
 */
public class TradeSignalRecord {

    /** 主键（13位时间戳字符串） */
    private String id;

    /** 交易对 */
    private String symbol;

    /** 信号时间（Unix秒） */
    private long time;

    /** 方向：LONG / SHORT / CLOSE */
    private String direction;

    /** 信号时的价格 */
    private BigDecimal price;

    /** 开仓/平仓金额（USDT） */
    private BigDecimal amount;

    /** 策略评分 */
    private int score;

    /** 杠杆倍数（开仓时记录） */
    private int leverage = 1;

    /** 执行时的账号余额（USDT），开仓/平仓时记录 */
    private double balance;

    /** 信号原因 */
    private String reason;

    /** 创建时间 */
    private String createdAt;

    /** 生成 13 位时间戳 ID */
    public static String generateId() {
        return String.valueOf(System.currentTimeMillis());
    }

    // ===== getter / setter =====

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

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

    public int getLeverage() { return leverage; }
    public void setLeverage(int leverage) { this.leverage = leverage; }

    public double getBalance() { return balance; }
    public void setBalance(double balance) { this.balance = balance; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
}
