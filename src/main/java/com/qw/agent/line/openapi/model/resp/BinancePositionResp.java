package com.qw.agent.line.openapi.model.resp;

import java.math.BigDecimal;

/**
 * 币安持仓响应 — 映射币安合约持仓信息。
 */
public class BinancePositionResp {

    private String symbol;
    private String positionSide;       // LONG / SHORT
    private BigDecimal positionAmt;     // 持仓数量（正=多, 负=空）
    private BigDecimal entryPrice;      // 开仓均价
    private BigDecimal markPrice;       // 标记价格
    private BigDecimal liquidationPrice; // 强平价格
    private BigDecimal unrealizedProfit; // 未实现盈亏
    private BigDecimal realizedProfit;   // 已实现盈亏
    private int leverage;
    private BigDecimal notionalValue;    // 名义价值
    private BigDecimal isolatedMargin;   // 仓位保证金
    private long updateTime;

    public BinancePositionResp() {}

    public static Builder builder() { return new Builder(); }

    public boolean isOpen() {
        return positionAmt != null && positionAmt.compareTo(BigDecimal.ZERO) != 0;
    }

    // ===== getter / setter =====

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public String getPositionSide() { return positionSide; }
    public void setPositionSide(String positionSide) { this.positionSide = positionSide; }

    public BigDecimal getPositionAmt() { return positionAmt; }
    public void setPositionAmt(BigDecimal positionAmt) { this.positionAmt = positionAmt; }

    public BigDecimal getEntryPrice() { return entryPrice; }
    public void setEntryPrice(BigDecimal entryPrice) { this.entryPrice = entryPrice; }

    public BigDecimal getMarkPrice() { return markPrice; }
    public void setMarkPrice(BigDecimal markPrice) { this.markPrice = markPrice; }

    public BigDecimal getLiquidationPrice() { return liquidationPrice; }
    public void setLiquidationPrice(BigDecimal liquidationPrice) { this.liquidationPrice = liquidationPrice; }

    public BigDecimal getUnrealizedProfit() { return unrealizedProfit; }
    public void setUnrealizedProfit(BigDecimal unrealizedProfit) { this.unrealizedProfit = unrealizedProfit; }

    public BigDecimal getRealizedProfit() { return realizedProfit; }
    public void setRealizedProfit(BigDecimal realizedProfit) { this.realizedProfit = realizedProfit; }

    public int getLeverage() { return leverage; }
    public void setLeverage(int leverage) { this.leverage = leverage; }

    public BigDecimal getNotionalValue() { return notionalValue; }
    public void setNotionalValue(BigDecimal notionalValue) { this.notionalValue = notionalValue; }

    public BigDecimal getIsolatedMargin() { return isolatedMargin; }
    public void setIsolatedMargin(BigDecimal isolatedMargin) { this.isolatedMargin = isolatedMargin; }

    public long getUpdateTime() { return updateTime; }
    public void setUpdateTime(long updateTime) { this.updateTime = updateTime; }

    // ===== Builder =====

    public static class Builder {
        private final BinancePositionResp instance = new BinancePositionResp();
        public Builder symbol(String v) { instance.symbol = v; return this; }
        public Builder positionSide(String v) { instance.positionSide = v; return this; }
        public Builder positionAmt(BigDecimal v) { instance.positionAmt = v; return this; }
        public Builder entryPrice(BigDecimal v) { instance.entryPrice = v; return this; }
        public Builder markPrice(BigDecimal v) { instance.markPrice = v; return this; }
        public Builder liquidationPrice(BigDecimal v) { instance.liquidationPrice = v; return this; }
        public Builder unrealizedProfit(BigDecimal v) { instance.unrealizedProfit = v; return this; }
        public Builder realizedProfit(BigDecimal v) { instance.realizedProfit = v; return this; }
        public Builder leverage(int v) { instance.leverage = v; return this; }
        public Builder notionalValue(BigDecimal v) { instance.notionalValue = v; return this; }
        public Builder isolatedMargin(BigDecimal v) { instance.isolatedMargin = v; return this; }
        public Builder updateTime(long v) { instance.updateTime = v; return this; }
        public BinancePositionResp build() { return instance; }
    }

    @Override
    public String toString() {
        return String.format("BinancePositionResp[%s %s amt=%s entry=%s leverage=%dx]",
                symbol, positionSide, positionAmt, entryPrice, leverage);
    }
}
