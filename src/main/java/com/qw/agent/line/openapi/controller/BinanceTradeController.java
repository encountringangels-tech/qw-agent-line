package com.qw.agent.line.openapi.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qw.agent.line.openapi.client.BinanceFuturesClient;
import com.qw.agent.line.openapi.model.resp.BinanceOrderResp;
import com.qw.agent.line.openapi.model.resp.BinancePositionResp;
import com.qw.agent.line.util.ConvertUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 币安合约买卖 REST 接口。
 * <p>
 * 迁移自 {@code com.qw.agent.line.order.OrderController}，所有接口迁移到 /api/binance 路径下。
 * <p>
 * 基础路径: /api/binance
 */
@RestController
@RequestMapping("/api/binance")
public class BinanceTradeController {

    private static final Logger log = LoggerFactory.getLogger(BinanceTradeController.class);

    private final BinanceFuturesClient client;
    private final ObjectMapper mapper;

    public BinanceTradeController(BinanceFuturesClient client, ObjectMapper mapper) {
        this.client = client;
        this.mapper = mapper;
    }

    // ==================== 市价下单 ====================

    /**
     * 市价买入（开多）。
     * POST /api/binance/market-buy
     *
     * @param body { "symbol": "BTCUSDT", "usdtAmount": 100.0 }
     */
    @PostMapping("/market-buy")
    public BinanceOrderResp marketBuy(@RequestBody Map<String, Object> body) {
        String symbol = (String) body.get("symbol");
        BigDecimal usdtAmount = new BigDecimal(body.get("usdtAmount").toString());
        log.info("marketBuy: symbol={}, amount={}", symbol, usdtAmount);
        String json = client.marketBuy(symbol, usdtAmount);
        return parseOrder(json);
    }

    /**
     * 市价卖出（开空）。
     * POST /api/binance/market-sell
     *
     * @param body { "symbol": "BTCUSDT", "usdtAmount": 100.0 }
     */
    @PostMapping("/market-sell")
    public BinanceOrderResp marketSell(@RequestBody Map<String, Object> body) {
        String symbol = (String) body.get("symbol");
        BigDecimal usdtAmount = new BigDecimal(body.get("usdtAmount").toString());
        log.info("marketSell: symbol={}, amount={}", symbol, usdtAmount);
        String json = client.marketSell(symbol, usdtAmount);
        return parseOrder(json);
    }

    // ==================== 平仓 ====================

    /**
     * 平多仓（卖出平多）。
     * POST /api/binance/close-long
     *
     * @param body { "symbol": "BTCUSDT", "quantity": 0.001 }
     */
    @PostMapping("/close-long")
    public BinanceOrderResp closeLong(@RequestBody Map<String, Object> body) {
        String symbol = (String) body.get("symbol");
        BigDecimal quantity = new BigDecimal(body.get("quantity").toString());
        log.info("closeLong: symbol={}, qty={}", symbol, quantity);
        String json = client.closeLong(symbol, quantity);
        return parseOrder(json);
    }

    /**
     * 平空仓（买入平空）。
     * POST /api/binance/close-short
     *
     * @param body { "symbol": "BTCUSDT", "quantity": 0.001 }
     */
    @PostMapping("/close-short")
    public BinanceOrderResp closeShort(@RequestBody Map<String, Object> body) {
        String symbol = (String) body.get("symbol");
        BigDecimal quantity = new BigDecimal(body.get("quantity").toString());
        log.info("closeShort: symbol={}, qty={}", symbol, quantity);
        String json = client.closeShort(symbol, quantity);
        return parseOrder(json);
    }

    // ==================== 限价单 ====================

    /**
     * 限价单。
     * POST /api/binance/limit
     *
     * @param body { "symbol": "BTCUSDT", "side": "BUY", "quantity": 0.001, "price": 50000.0 }
     *           单向持仓模式下 side=BUY 开多, side=SELL 开空，不传 positionSide。
     */
    @PostMapping("/limit")
    public BinanceOrderResp limitOrder(@RequestBody Map<String, Object> body) {
        String symbol = (String) body.get("symbol");
        String side = (String) body.get("side");
        BigDecimal quantity = new BigDecimal(body.get("quantity").toString());
        BigDecimal price = new BigDecimal(body.get("price").toString());
        log.info("limitOrder: {} {} qty={} price={}", side, symbol, quantity, price);
        String json = client.limitOrder(symbol, side, quantity, price);
        return parseOrder(json);
    }

    // ==================== 撤单 ====================

    /**
     * 撤销订单。
     * POST /api/binance/cancel
     *
     * @param body { "symbol": "BTCUSDT", "orderId": "123456789" }
     */
    @PostMapping("/cancel")
    public Map<String, Object> cancelOrder(@RequestBody Map<String, Object> body) {
        String symbol = (String) body.get("symbol");
        String orderId = body.get("orderId").toString();
        log.info("cancelOrder: symbol={}, orderId={}", symbol, orderId);
        try {
            client.cancelOrder(symbol, orderId);
            return Map.of("success", true);
        } catch (Exception e) {
            log.error("撤单失败: {}", e.getMessage());
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    // ==================== 查询 ====================

    /**
     * 查询订单状态。
     * GET /api/binance/query?symbol=BTCUSDT&orderId=123456789
     */
    @GetMapping("/query")
    public BinanceOrderResp getOrder(@RequestParam String symbol,
                                     @RequestParam("orderId") String orderId) {
        log.info("getOrder: symbol={}, orderId={}", symbol, orderId);
        String json = client.getOrder(symbol, orderId);
        return parseOrder(json);
    }

    /**
     * 查询当前挂单。
     * GET /api/binance/open-orders?symbol=BTCUSDT
     */
    @GetMapping("/open-orders")
    public List<BinanceOrderResp> getOpenOrders(@RequestParam(required = false) String symbol) {
        log.info("getOpenOrders: symbol={}", symbol);
        String json = client.getOpenOrders(symbol);
        return parseOrderList(json);
    }

    // ==================== 持仓 ====================

    /**
     * 查询所有持仓。
     * GET /api/binance/positions
     */
    @GetMapping("/positions")
    public List<BinancePositionResp> getPositions() {
        String json = client.getPositions();
        return parsePositionList(json);
    }

    /**
     * 查询指定交易对持仓。
     * GET /api/binance/position?symbol=BTCUSDT
     */
    @GetMapping("/position")
    public BinancePositionResp getPosition(@RequestParam String symbol) {
        String json = client.getPosition(symbol);
        List<BinancePositionResp> list = parsePositionList(json);
        return list.isEmpty() ? null : list.get(0);
    }

    // ==================== 账户 ====================

    /**
     * 查询账户资产。
     * GET /api/binance/balance
     */
    @GetMapping("/balance")
    public Map<String, Object> getBalance() {
        String json = client.getAccountBalance();
        try {
            List<Map<String, Object>> assets = mapper.readValue(json,
                    new TypeReference<List<Map<String, Object>>>() {});
            return Map.of("assets", assets);
        } catch (Exception e) {
            log.error("解析账户资产失败", e);
            return Map.of("error", e.getMessage());
        }
    }

    // ==================== 杠杆 ====================

    /**
     * 设置杠杆倍数。
     * POST /api/binance/leverage
     *
     * @param body { "symbol": "BTCUSDT", "leverage": 5 }
     */
    @PostMapping("/leverage")
    public Map<String, Object> setLeverage(@RequestBody Map<String, Object> body) {
        String symbol = (String) body.get("symbol");
        int leverage = Integer.parseInt(body.get("leverage").toString());
        log.info("setLeverage: symbol={}, leverage={}x", symbol, leverage);
        try {
            client.setLeverage(symbol, leverage);
            return Map.of("success", true, "symbol", symbol, "leverage", leverage);
        } catch (Exception e) {
            log.error("设置杠杆失败: {}", e.getMessage());
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    // ==================== 行情 ====================

    /**
     * 获取当前最新价格。
     * GET /api/binance/price?symbol=BTCUSDT
     */
    @GetMapping("/price")
    public Map<String, Object> getPrice(@RequestParam String symbol) {
        String json = client.getPriceTicker(symbol);
        try {
            Map<String, Object> ticker = mapper.readValue(json,
                    new TypeReference<Map<String, Object>>() {});
            return ticker;
        } catch (Exception e) {
            log.error("获取价格失败", e);
            return Map.of("error", e.getMessage());
        }
    }

    // ==================== 解析工具 ====================

    private BinanceOrderResp parseOrder(String json) {
        try {
            Map<String, Object> m = mapper.readValue(json,
                    new TypeReference<Map<String, Object>>() {});
            // 币安合约市价单返回 cumQty（单位=基础币种）而不返回 cumQuote/avgPrice，
            // 所以 avgPrice 从 cumQty 计算：cumQty / executedQty（仅当二者不同时才有意义）
            BigDecimal executedQty = ConvertUtil.toBigDec(m.get("executedQty"));
            BigDecimal cumQty = ConvertUtil.toBigDec(m.get("cumQty"));
            BigDecimal avgPrice;
            if (executedQty != null && executedQty.compareTo(BigDecimal.ZERO) > 0
                    && cumQty != null && cumQty.compareTo(executedQty) != 0
                    && cumQty.compareTo(BigDecimal.ZERO) > 0) {
                // cumQty = quote 累计成交额时，才能算出均价
                avgPrice = cumQty.divide(executedQty, 2, java.math.RoundingMode.HALF_UP);
            } else {
                avgPrice = ConvertUtil.toBigDec(m.get("avgPrice"));  // 可能 null
            }
            return BinanceOrderResp.builder()
                    .orderId(ConvertUtil.str(m.get("orderId")))
                    .clientOrderId(ConvertUtil.str(m.get("clientOrderId")))
                    .symbol(ConvertUtil.str(m.get("symbol")))
                    .side(ConvertUtil.str(m.get("side")))
                    .type(ConvertUtil.str(m.get("type")))
                    .positionSide(ConvertUtil.str(m.get("positionSide")))
                    .status(ConvertUtil.str(m.get("status")))
                    .origQuantity(ConvertUtil.toBigDec(m.get("origQty")))
                    .executedQuantity(executedQty)
                    .cumQuote(cumQty)
                    .price(ConvertUtil.toBigDec(m.get("price")))
                    .avgPrice(avgPrice)
                    .filled("FILLED".equals(ConvertUtil.str(m.get("status"))))
                    .failureReason(ConvertUtil.str(m.get("failureReason")))
                    .rawJson(json)
                    .build();
        } catch (Exception e) {
            log.error("解析订单响应失败", e);
            return BinanceOrderResp.builder()
                    .status("PARSE_ERROR")
                    .failureReason(e.getMessage())
                    .rawJson(json)
                    .build();
        }
    }

    private List<BinanceOrderResp> parseOrderList(String json) {
        try {
            List<Map<String, Object>> list = mapper.readValue(json,
                    new TypeReference<List<Map<String, Object>>>() {});
            return list.stream().map(this::toOrderResp).toList();
        } catch (Exception e) {
            log.error("解析订单列表失败", e);
            return Collections.emptyList();
        }
    }

    private BinanceOrderResp toOrderResp(Map<String, Object> m) {
        BigDecimal executedQty = ConvertUtil.toBigDec(m.get("executedQty"));
        BigDecimal cumQty = ConvertUtil.toBigDec(m.get("cumQty"));
        BigDecimal avgPrice;
        if (executedQty != null && executedQty.compareTo(BigDecimal.ZERO) > 0
                && cumQty != null && cumQty.compareTo(executedQty) != 0
                && cumQty.compareTo(BigDecimal.ZERO) > 0) {
            avgPrice = cumQty.divide(executedQty, 2, java.math.RoundingMode.HALF_UP);
        } else {
            avgPrice = ConvertUtil.toBigDec(m.get("avgPrice"));
        }
        return BinanceOrderResp.builder()
                .orderId(ConvertUtil.str(m.get("orderId")))
                .clientOrderId(ConvertUtil.str(m.get("clientOrderId")))
                .symbol(ConvertUtil.str(m.get("symbol")))
                .side(ConvertUtil.str(m.get("side")))
                .type(ConvertUtil.str(m.get("type")))
                .positionSide(ConvertUtil.str(m.get("positionSide")))
                .status(ConvertUtil.str(m.get("status")))
                .origQuantity(ConvertUtil.toBigDec(m.get("origQty")))
                .executedQuantity(executedQty)
                .cumQuote(cumQty)
                .price(ConvertUtil.toBigDec(m.get("price")))
                .avgPrice(avgPrice)
                .filled("FILLED".equals(ConvertUtil.str(m.get("status"))))
                .build();
    }

    private List<BinancePositionResp> parsePositionList(String json) {
        try {
            List<Map<String, Object>> list = mapper.readValue(json,
                    new TypeReference<List<Map<String, Object>>>() {});
            return list.stream().map(this::toPositionResp).toList();
        } catch (Exception e) {
            log.error("解析持仓列表失败", e);
            return Collections.emptyList();
        }
    }

    private BinancePositionResp toPositionResp(Map<String, Object> m) {
        return BinancePositionResp.builder()
                .symbol(ConvertUtil.str(m.get("symbol")))
                .positionSide(ConvertUtil.str(m.get("positionSide")))
                .positionAmt(ConvertUtil.toBigDec(m.get("positionAmt")))
                .entryPrice(ConvertUtil.toBigDec(m.get("entryPrice")))
                .markPrice(ConvertUtil.toBigDec(m.get("markPrice")))
                .liquidationPrice(ConvertUtil.toBigDec(m.get("liquidationPrice")))
                .unrealizedProfit(ConvertUtil.toBigDec(m.get("unRealizedProfit")))
                .realizedProfit(ConvertUtil.toBigDec(m.get("realizedProfit")))
                .leverage(ConvertUtil.intVal(m.get("leverage")))
                .notionalValue(ConvertUtil.toBigDec(m.get("notional")))
                .isolatedMargin(ConvertUtil.toBigDec(m.get("isolatedMargin")))
                .updateTime(ConvertUtil.longVal(m.get("updateTime")))
                .build();
    }

    // ==================== 工具（委托给 ConvertUtil） ====================
}
