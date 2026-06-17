package com.qw.agent.line.order.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 当前持仓数据模型 — 表示币安合约账户中的持仓信息。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Position {

    private String symbol;
    private String positionSide;  // LONG or SHORT

    private BigDecimal positionAmt;       // Current position amount (+ = long, - = short)
    private BigDecimal entryPrice;        // Average entry price
    private BigDecimal markPrice;         // Current mark price
    private BigDecimal liquidationPrice;  // Liquidation price
    private BigDecimal unrealizedProfit;  // Unrealized PnL
    private BigDecimal realizedProfit;    // Realized PnL

    private int leverage;                 // Current leverage
    private BigDecimal isolatedMargin;    // Isolated margin (if isolated mode)

    private BigDecimal notionalValue;     // Position notional value
    private BigDecimal positionPnl;       // Position PnL
    private BigDecimal pnlRatio;          // PnL ratio (in %)

    private long updateTime;

    /** Whether the position is a long */
    public boolean isLong() {
        return "LONG".equals(positionSide) || (positionAmt != null && positionAmt.compareTo(BigDecimal.ZERO) > 0);
    }

    /** Whether the position is open (non-zero amount) */
    public boolean isOpen() {
        return positionAmt != null && positionAmt.compareTo(BigDecimal.ZERO) != 0;
    }

    /** Get absolute position size */
    public BigDecimal getAbsoluteSize() {
        return positionAmt != null ? positionAmt.abs() : BigDecimal.ZERO;
    }

    /** Calculate return percentage */
    public BigDecimal getReturnPercent() {
        if (entryPrice != null && entryPrice.compareTo(BigDecimal.ZERO) > 0 && markPrice != null) {
            BigDecimal rawReturn = markPrice.subtract(entryPrice)
                    .divide(entryPrice, 6, java.math.RoundingMode.HALF_UP);
            return isLong() ? rawReturn : rawReturn.negate();
        }
        return BigDecimal.ZERO;
    }
}
