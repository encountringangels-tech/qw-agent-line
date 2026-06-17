package com.qw.agent.line.order.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 订单数据模型 — 表示向币安合约交易所提交或从交易所接收的订单。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Order {

    private String orderId;
    private String clientOrderId;
    private String symbol;

    /** Order side: BUY, SELL */
    private String side;

    /** Order type: MARKET, LIMIT, STOP_MARKET, TAKE_PROFIT_MARKET */
    private String type;

    /** Position side: LONG, SHORT (for futures) */
    private String positionSide;

    /** Order status: NEW, PARTIALLY_FILLED, FILLED, CANCELED, REJECTED, EXPIRED */
    private String status;

    /** Quantity */
    private BigDecimal origQuantity;
    private BigDecimal executedQuantity;
    private BigDecimal cumQuote;

    /** Price */
    private BigDecimal price;
    private BigDecimal stopPrice;
    private BigDecimal avgPrice;

    /** Leverage */
    private int leverage;

    /** Flags */
    private boolean reduceOnly;
    private boolean closePosition;

    /** Time */
    private long orderTime;
    private long updateTime;

    /** Working type: MARK_PRICE, CONTRACT_PRICE */
    private String workingType;

    /** Failure reason if rejected */
    private String failureReason;

    /** Whether the order is fully filled */
    public boolean isFilled() {
        return "FILLED".equals(status);
    }

    /** Whether the order is a buy */
    public boolean isBuy() {
        return "BUY".equals(side);
    }

    /** Whether the order is a sell */
    public boolean isSell() {
        return "SELL".equals(side);
    }

    /** Calculate the average fill price */
    public BigDecimal getAveragePrice() {
        if (executedQuantity != null && executedQuantity.compareTo(BigDecimal.ZERO) > 0
                && cumQuote != null && cumQuote.compareTo(BigDecimal.ZERO) > 0) {
            return cumQuote.divide(executedQuantity, 8, java.math.RoundingMode.HALF_UP);
        }
        return avgPrice;
    }
}
