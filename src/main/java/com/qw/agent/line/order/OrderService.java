package com.qw.agent.line.order;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qw.agent.line.model.TradeSignalRecord;
import com.qw.agent.line.openapi.client.BinanceFuturesClient;
import com.qw.agent.line.store.KlineStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 订单管理服务 —— 执行买卖操作并将记录持久化到 trade_signal 表。
 * <p>
 * 下单金额实时从币安查询，不使用本地缓存余额。
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final KlineStore klineStore;
    private final BinanceFuturesClient binanceClient;
    private final ObjectMapper mapper;

    public OrderService(KlineStore klineStore, BinanceFuturesClient binanceClient) {
        this.klineStore = klineStore;
        this.binanceClient = binanceClient;
        this.mapper = new ObjectMapper();
    }

    // ==================== 对外交易接口 ====================

    /**
     * 市价开多（从币安查询可用余额，全仓开多）。
     *
     * @param symbol   交易对
     * @param leverage 杠杆倍数
     * @param reason   原因
     * @return 交易记录
     */
    public TradeSignalRecord openLong(String symbol, int leverage, String reason) {
        // 1. 从币安查询可用余额
        BigDecimal usdtBalance = getAvailableBalance();
        BigDecimal usdtAmount = usdtBalance.multiply(BigDecimal.valueOf(leverage));

        log.info("开多: symbol={}, 可用余额={} USDT, 开仓金额={} USDT, 杠杆={}x",
                symbol, usdtBalance, usdtAmount, leverage);

        // 2. 执行市价开多
        String json = binanceClient.marketBuy(symbol, usdtAmount);

        // 3. 解析成交结果
        OrderResult result = parseOrderResult(json, symbol);
        BigDecimal price = result.avgPrice;
        BigDecimal executedQty = result.executedQty;

        log.info("开多成交: symbol={}, 价格={}, 数量={}", symbol, price, executedQty);

        // 4. 记录到 trade_signal
        return saveTradeSignal(symbol, "LONG", price, usdtAmount.doubleValue(),
                0, leverage, usdtBalance.doubleValue(), reason);
    }

    /**
     * 市价开空（从币安查询可用余额，全仓开空）。
     *
     * @param symbol   交易对
     * @param leverage 杠杆倍数
     * @param reason   原因
     * @return 交易记录
     */
    public TradeSignalRecord openShort(String symbol, int leverage, String reason) {
        BigDecimal usdtBalance = getAvailableBalance();
        BigDecimal usdtAmount = usdtBalance.multiply(BigDecimal.valueOf(leverage));

        log.info("开空: symbol={}, 可用余额={} USDT, 开仓金额={} USDT, 杠杆={}x",
                symbol, usdtBalance, usdtAmount, leverage);

        String json = binanceClient.marketSell(symbol, usdtAmount);
        OrderResult result = parseOrderResult(json, symbol);

        log.info("开空成交: symbol={}, 价格={}, 数量={}", symbol, result.avgPrice, result.executedQty);

        return saveTradeSignal(symbol, "SHORT", result.avgPrice, usdtAmount.doubleValue(),
                0, leverage, usdtBalance.doubleValue(), reason);
    }

    /**
     * 平多仓（全仓平多）。
     *
     * @param symbol 交易对
     * @return 交易记录
     */
    public TradeSignalRecord closeLong(String symbol, String reason) {
        // 1. 从币安查询当前持仓数量
        BigDecimal positionAmt = getPositionAmount(symbol, "LONG");
        if (positionAmt == null || positionAmt.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("平多: 无多头持仓 [{}]", symbol);
            return null;
        }

        log.info("平多: symbol={}, 数量={}", symbol, positionAmt);

        // 2. 执行平多
        String json = binanceClient.closeLong(symbol, positionAmt);
        OrderResult result = parseOrderResult(json, symbol);

        log.info("平多成交: symbol={}, 价格={}", symbol, result.avgPrice);

        return saveTradeSignal(symbol, "CLOSE_LONG", result.avgPrice, positionAmt.doubleValue(),
                0, 1, getAvailableBalance().doubleValue(), reason);
    }

    /**
     * 平空仓（全仓平空）。
     *
     * @param symbol 交易对
     * @return 交易记录
     */
    public TradeSignalRecord closeShort(String symbol, String reason) {
        BigDecimal positionAmt = getPositionAmount(symbol, "SHORT");
        if (positionAmt == null || positionAmt.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("平空: 无空头持仓 [{}]", symbol);
            return null;
        }

        log.info("平空: symbol={}, 数量={}", symbol, positionAmt);

        String json = binanceClient.closeShort(symbol, positionAmt);
        OrderResult result = parseOrderResult(json, symbol);

        log.info("平空成交: symbol={}, 价格={}", symbol, result.avgPrice);

        return saveTradeSignal(symbol, "CLOSE_SHORT", result.avgPrice, positionAmt.doubleValue(),
                0, 1, getAvailableBalance().doubleValue(), reason);
    }

    // ==================== 账户查询 ====================

    /**
     * 从币安查询 USDT 可用余额。
     */
    public BigDecimal getAvailableBalance() {
        try {
            String json = binanceClient.getAccountBalance();
            Map<String, Object> account = mapper.readValue(json,
                    new TypeReference<Map<String, Object>>() {});
            Object assetsRaw = account.get("assets");
            if (assetsRaw instanceof List<?> assets) {
                for (Object obj : assets) {
                    if (obj instanceof Map<?, ?> asset) {
                        if ("USDT".equals(asset.get("asset"))) {
                            return new BigDecimal(asset.get("availableBalance").toString());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("查询 USDT 余额失败", e);
        }
        return BigDecimal.ZERO;
    }

    /**
     * 从币安查询当前持仓数量。
     *
     * @param symbol       交易对
     * @param positionSide LONG 或 SHORT
     * @return 持仓数量（正值），无持仓返回 null
     */
    public BigDecimal getPositionAmount(String symbol, String positionSide) {
        try {
            String json = binanceClient.getPosition(symbol);
            List<Map<String, Object>> positions = mapper.readValue(json,
                    new TypeReference<List<Map<String, Object>>>() {});
            for (Map<String, Object> pos : positions) {
                String ps = (String) pos.get("positionSide");
                if ("BOTH".equals(ps) || (positionSide.equals(ps))) {
                    BigDecimal amt = new BigDecimal(pos.get("positionAmt").toString());
                    // LONG: 正数, SHORT: 负数, 统一返回绝对值
                    if ("LONG".equals(positionSide) && amt.compareTo(BigDecimal.ZERO) > 0) {
                        return amt;
                    }
                    if ("SHORT".equals(positionSide) && amt.compareTo(BigDecimal.ZERO) < 0) {
                        return amt.abs();
                    }
                    if ("BOTH".equals(ps) && amt.compareTo(BigDecimal.ZERO) != 0) {
                        return amt.abs();
                    }
                }
            }
        } catch (Exception e) {
            log.error("查询持仓失败 [{} {}]", symbol, positionSide, e);
        }
        return null;
    }

    /**
     * 从币安查询当前是否有持仓（单向模式，BOTH）。
     */
    public boolean hasPosition(String symbol) {
        try {
            String json = binanceClient.getPosition(symbol);
            List<Map<String, Object>> positions = mapper.readValue(json,
                    new TypeReference<List<Map<String, Object>>>() {});
            for (Map<String, Object> pos : positions) {
                BigDecimal amt = new BigDecimal(pos.get("positionAmt").toString());
                if (amt.compareTo(BigDecimal.ZERO) != 0) {
                    return true;
                }
            }
        } catch (Exception e) {
            log.error("查询持仓状态失败 [{}]", symbol, e);
        }
        return false;
    }

    /**
     * 从币安查询当前持仓方向。
     *
     * @return "LONG" / "SHORT" / null（空仓）
     */
    public String getPositionDirection(String symbol) {
        try {
            String json = binanceClient.getPosition(symbol);
            List<Map<String, Object>> positions = mapper.readValue(json,
                    new TypeReference<List<Map<String, Object>>>() {});
            for (Map<String, Object> pos : positions) {
                BigDecimal amt = new BigDecimal(pos.get("positionAmt").toString());
                if (amt.compareTo(BigDecimal.ZERO) > 0) {
                    return "LONG";
                } else if (amt.compareTo(BigDecimal.ZERO) < 0) {
                    return "SHORT";
                }
            }
        } catch (Exception e) {
            log.error("查询持仓方向失败 [{}]", symbol, e);
        }
        return null;
    }

    /**
     * 从币安查询当前持仓的入场价格。
     */
    public BigDecimal getEntryPrice(String symbol) {
        try {
            String json = binanceClient.getPosition(symbol);
            List<Map<String, Object>> positions = mapper.readValue(json,
                    new TypeReference<List<Map<String, Object>>>() {});
            for (Map<String, Object> pos : positions) {
                BigDecimal amt = new BigDecimal(pos.get("positionAmt").toString());
                if (amt.compareTo(BigDecimal.ZERO) != 0) {
                    return new BigDecimal(pos.get("entryPrice").toString());
                }
            }
        } catch (Exception e) {
            log.error("查询入场价格失败 [{}]", symbol, e);
        }
        return BigDecimal.ZERO;
    }

    // ==================== 内部工具 ====================

    /** 解析币安下单返回的 JSON，提取成交价和数量 */
    private OrderResult parseOrderResult(String json, String symbol) {
        try {
            Map<String, Object> m = mapper.readValue(json,
                    new TypeReference<Map<String, Object>>() {});
            OrderResult r = new OrderResult();
            String status = (String) m.get("status");
            if ("FILLED".equals(status)) {
                r.executedQty = new BigDecimal(m.get("executedQty").toString());
                // 币安合约市价单返回 cumQty（基础币种数量）而非 cumQuote/avgPrice
                // 所以直接用当前标记价格作为成交均价
                Object priceRaw = m.get("avgPrice");
                if (priceRaw != null) {
                    r.avgPrice = new BigDecimal(priceRaw.toString());
                }
                if (r.avgPrice == null || r.avgPrice.compareTo(BigDecimal.ZERO) == 0) {
                    priceRaw = m.get("price");
                    if (priceRaw != null) {
                        r.avgPrice = new BigDecimal(priceRaw.toString());
                    }
                }
                if (r.avgPrice == null || r.avgPrice.compareTo(BigDecimal.ZERO) == 0) {
                    // 最后fallback：从币安查询当前标记价格
                    r.avgPrice = getMarkPrice(symbol);
                }
            } else {
                r.executedQty = BigDecimal.ZERO;
                r.avgPrice = BigDecimal.ZERO;
                log.warn("订单未成交: status={}, json={}", status, json);
            }
            return r;
        } catch (Exception e) {
            log.error("解析下单结果失败: {}", json, e);
            OrderResult r = new OrderResult();
            r.executedQty = BigDecimal.ZERO;
            r.avgPrice = BigDecimal.ZERO;
            return r;
        }
    }

    /** 从币安查询当前标记价格，作为成交均价的 fallback */
    private BigDecimal getMarkPrice(String symbol) {
        try {
            String json = binanceClient.getPriceTicker(symbol);
            Map<String, Object> ticker = mapper.readValue(json,
                    new TypeReference<Map<String, Object>>() {});
            return new BigDecimal(ticker.get("price").toString());
        } catch (Exception e) {
            log.warn("获取标记价格失败 [{}]", symbol, e);
            return BigDecimal.ZERO;
        }
    }

    private static class OrderResult {
        BigDecimal avgPrice;
        BigDecimal executedQty;
    }

    /** 记录交易到 trade_signal 表 */
    private TradeSignalRecord saveTradeSignal(String symbol, String direction,
                                               BigDecimal price, double amount,
                                               int score, int leverage,
                                               double balance, String reason) {
        TradeSignalRecord record = new TradeSignalRecord();
        record.setId(TradeSignalRecord.generateId());
        record.setSymbol(symbol);
        record.setTime(System.currentTimeMillis() / 1000);
        record.setDirection(direction);
        record.setPrice(price);
        record.setAmount(BigDecimal.valueOf(amount));
        record.setScore(score);
        record.setLeverage(leverage);
        record.setBalance(balance);
        record.setReason(reason);
        record.setCreatedAt(LocalDateTime.now().format(
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

        klineStore.saveTradeSignal(record);
        log.info("交易记录: {} {} price={} amount={} leverage={}x balance={} reason={}",
                direction, symbol, price, amount, leverage, balance, reason);
        return record;
    }
}
