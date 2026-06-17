package com.qw.agent.line.openapi.model.resp;

import java.math.BigDecimal;

/**
 * 币安订单响应 — 映射币安合约下单返回结果。
 */
public class BinanceOrderResp {

    private String orderId;
    private String clientOrderId;
    private String symbol;
    private String side;           // BUY / SELL
    private String type;           // MARKET / LIMIT / ...
    private String positionSide;   // LONG / SHORT
    private String status;         // NEW / FILLED / CANCELED / REJECTED / ...
    private BigDecimal origQuantity;
    private BigDecimal executedQuantity;
    private BigDecimal cumQuote;
    private BigDecimal price;
    private BigDecimal avgPrice;
    private boolean filled;
    private String failureReason;
    private String rawJson;

    public BinanceOrderResp() {}

    public static Builder builder() { return new Builder(); }

    // ===== getter / setter =====

    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }

    public String getClientOrderId() { return clientOrderId; }
    public void setClientOrderId(String clientOrderId) { this.clientOrderId = clientOrderId; }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public String getSide() { return side; }
    public void setSide(String side) { this.side = side; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getPositionSide() { return positionSide; }
    public void setPositionSide(String positionSide) { this.positionSide = positionSide; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public BigDecimal getOrigQuantity() { return origQuantity; }
    public void setOrigQuantity(BigDecimal origQuantity) { this.origQuantity = origQuantity; }

    public BigDecimal getExecutedQuantity() { return executedQuantity; }
    public void setExecutedQuantity(BigDecimal executedQuantity) { this.executedQuantity = executedQuantity; }

    public BigDecimal getCumQuote() { return cumQuote; }
    public void setCumQuote(BigDecimal cumQuote) { this.cumQuote = cumQuote; }

    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }

    public BigDecimal getAvgPrice() { return avgPrice; }
    public void setAvgPrice(BigDecimal avgPrice) { this.avgPrice = avgPrice; }

    public boolean isFilled() { return filled; }
    public void setFilled(boolean filled) { this.filled = filled; }

    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }

    public String getRawJson() { return rawJson; }
    public void setRawJson(String rawJson) { this.rawJson = rawJson; }

    // ===== Builder =====

    public static class Builder {
        private final BinanceOrderResp instance = new BinanceOrderResp();
        public Builder orderId(String v) { instance.orderId = v; return this; }
        public Builder clientOrderId(String v) { instance.clientOrderId = v; return this; }
        public Builder symbol(String v) { instance.symbol = v; return this; }
        public Builder side(String v) { instance.side = v; return this; }
        public Builder type(String v) { instance.type = v; return this; }
        public Builder positionSide(String v) { instance.positionSide = v; return this; }
        public Builder status(String v) { instance.status = v; return this; }
        public Builder origQuantity(BigDecimal v) { instance.origQuantity = v; return this; }
        public Builder executedQuantity(BigDecimal v) { instance.executedQuantity = v; return this; }
        public Builder cumQuote(BigDecimal v) { instance.cumQuote = v; return this; }
        public Builder price(BigDecimal v) { instance.price = v; return this; }
        public Builder avgPrice(BigDecimal v) { instance.avgPrice = v; return this; }
        public Builder filled(boolean v) { instance.filled = v; return this; }
        public Builder failureReason(String v) { instance.failureReason = v; return this; }
        public Builder rawJson(String v) { instance.rawJson = v; return this; }
        public BinanceOrderResp build() { return instance; }
    }

    @Override
    public String toString() {
        return String.format("BinanceOrderResp[%s %s %s %s status=%s filled=%s]",
                symbol, side, positionSide, type, status, filled);
    }
}
