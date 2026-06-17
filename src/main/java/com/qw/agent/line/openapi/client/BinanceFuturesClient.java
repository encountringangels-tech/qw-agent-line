package com.qw.agent.line.openapi.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qw.agent.line.openapi.config.BinanceConfig;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 币安合约 API 客户端 — 封装 HMAC 签名与 HTTP 请求。
 * <p>
 * 文档: https://binance-docs.github.io/apidocs/futures/cn/
 */
@Component
public class BinanceFuturesClient {

    private static final Logger log = LoggerFactory.getLogger(BinanceFuturesClient.class);
    private static final String HMAC_SHA256 = "HmacSHA256";

    private final BinanceConfig config;
    private final ObjectMapper mapper;
    private final HttpClient httpClient;
    private Mac mac;

    public BinanceFuturesClient(BinanceConfig config) {
        this.config = config;
        this.mapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @PostConstruct
    public void init() {
        try {
            SecretKeySpec keySpec = new SecretKeySpec(
                    config.getApi().getSecretKey().getBytes(StandardCharsets.UTF_8), HMAC_SHA256);
            mac = Mac.getInstance(HMAC_SHA256);
            mac.init(keySpec);
            log.info("BinanceFuturesClient 初始化完成, baseUrl={}", config.getApi().getBaseUrl());
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            log.error("Binance 签名初始化失败", e);
            throw new RuntimeException("Binance 签名初始化失败", e);
        }
    }

    // ==================== 下单接口 ====================

    /**
     * 市价开多（单向持仓模式，不传 positionSide）。
     * <p>
     * 币安合约账户默认为单向持仓模式，传 positionSide=LONG/SHORT 会报错 -4061。
     * 如果后续切换为双向持仓模式（Hedge Mode），需要加回 positionSide。
     */
    public String marketBuy(String symbol, BigDecimal usdtAmount) {
        double quantity = usdtAmount.divide(getMarkPrice(symbol), 8, java.math.RoundingMode.HALF_UP).doubleValue();
        quantity = roundStepSize(symbol, quantity);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        params.put("side", "BUY");
        params.put("type", "MARKET");
        params.put("quantity", String.valueOf(quantity));
        params.put("newOrderRespType", "RESULT");
        return postSigned("/fapi/v1/order", params);
    }

    /**
     * 市价开空（单向持仓模式，不传 positionSide）。
     * <p>
     * 币安合约账户默认为单向持仓模式，传 positionSide=LONG/SHORT 会报错 -4061。
     * 如果后续切换为双向持仓模式（Hedge Mode），需要加回 positionSide。
     */
    public String marketSell(String symbol, BigDecimal usdtAmount) {
        double quantity = usdtAmount.divide(getMarkPrice(symbol), 8, java.math.RoundingMode.HALF_UP).doubleValue();
        quantity = roundStepSize(symbol, quantity);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        params.put("side", "SELL");
        params.put("type", "MARKET");
        params.put("quantity", String.valueOf(quantity));
        params.put("newOrderRespType", "RESULT");
        return postSigned("/fapi/v1/order", params);
    }

    /**
     * 平多仓（单向持仓模式，side=SELL + reduceOnly）。
     * <p>
     * 单向持仓下开多后，持仓方向是 BOTH 且持仓量为正。
     * 平多 = side=SELL + reduceOnly=true，不传 positionSide。
     */
    public String closeLong(String symbol, BigDecimal quantity) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        params.put("side", "SELL");
        params.put("type", "MARKET");
        params.put("quantity", String.valueOf(quantity));
        params.put("reduceOnly", "true");
        params.put("newOrderRespType", "RESULT");
        return postSigned("/fapi/v1/order", params);
    }

    /**
     * 平空仓（单向持仓模式，side=BUY + reduceOnly）。
     * <p>
     * 单向持仓下开空后，持仓方向是 BOTH 且持仓量为负。
     * 平空 = side=BUY + reduceOnly=true，不传 positionSide。
     */
    public String closeShort(String symbol, BigDecimal quantity) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        params.put("side", "BUY");
        params.put("type", "MARKET");
        params.put("quantity", String.valueOf(quantity));
        params.put("reduceOnly", "true");
        params.put("newOrderRespType", "RESULT");
        return postSigned("/fapi/v1/order", params);
    }

    /**
     * 限价单（单向持仓模式，不传 positionSide）。
     *
     * @param side BUY=开多/平空, SELL=开空/平多
     */
    public String limitOrder(String symbol, String side,
                             BigDecimal quantity, BigDecimal price) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        params.put("side", side);
        params.put("type", "LIMIT");
        params.put("timeInForce", "GTC");
        params.put("quantity", String.valueOf(quantity));
        params.put("price", String.valueOf(price));
        params.put("newOrderRespType", "RESULT");
        return postSigned("/fapi/v1/order", params);
    }

    // ==================== 撤单 ====================

    /** 撤销订单 */
    public String cancelOrder(String symbol, String orderId) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        params.put("orderId", orderId);
        return deleteSigned("/fapi/v1/order", params);
    }

    // ==================== 查询 ====================

    /** 查询订单状态 */
    public String getOrder(String symbol, String orderId) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        params.put("orderId", orderId);
        return getSigned("/fapi/v1/order", params);
    }

    /** 查询当前挂单 */
    public String getOpenOrders(String symbol) {
        Map<String, String> params = new LinkedHashMap<>();
        if (symbol != null && !symbol.isEmpty()) {
            params.put("symbol", symbol);
        }
        return getSigned("/fapi/v1/openOrders", params);
    }

    /** 查询所有持仓 */
    public String getPositions() {
        return getSigned("/fapi/v2/positionRisk", new LinkedHashMap<>());
    }

    /** 查询指定持仓 */
    public String getPosition(String symbol) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        return getSigned("/fapi/v2/positionRisk", params);
    }

    /** 查询账户资产 */
    public String getAccountBalance() {
        return getSigned("/fapi/v2/account", new LinkedHashMap<>());
    }

    /** 设置杠杆倍数 */
    public String setLeverage(String symbol, int leverage) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        params.put("leverage", String.valueOf(leverage));
        return postSigned("/fapi/v1/leverage", params);
    }

    /** 获取当前标记价格 */
    public String getPriceTicker(String symbol) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        return getPublic("/fapi/v1/ticker/price", params);
    }

    // ==================== 内部方法 ====================

    private BigDecimal getMarkPrice(String symbol) {
        try {
            String json = getPriceTicker(symbol);
            Map<?, ?> map = mapper.readValue(json, Map.class);
            return new BigDecimal(map.get("price").toString());
        } catch (Exception e) {
            log.error("获取标记价格失败 [{}]", symbol, e);
            throw new RuntimeException("获取标记价格失败", e);
        }
    }

    /** 根据步长截断数量（硬编码常见交易对，可按需扩展） */
    private double roundStepSize(String symbol, double quantity) {
        double step = symbol.startsWith("BTC") ? 0.001 : 0.01;
        return Math.floor(quantity / step) * step;
    }

    // ==================== 签名 ====================

    private String sign(Map<String, String> params) {
        String qs = params.entrySet().stream()
                .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(Collectors.joining("&"));
        return bytesToHex(mac.doFinal(qs.getBytes(StandardCharsets.UTF_8)));
    }

    private static String encode(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private Map<String, String> withSignature(Map<String, String> params) {
        params.put("timestamp", String.valueOf(System.currentTimeMillis()));
        params.put("recvWindow", String.valueOf(config.getApi().getRecvWindow()));
        params.put("signature", sign(params));
        return params;
    }

    // ==================== HTTP 请求 ====================

    private String getPublic(String path, Map<String, String> params) {
        return execute(buildGet(config.getApi().getBaseUrl() + path + "?" + toQueryString(params)));
    }

    private String getSigned(String path, Map<String, String> params) {
        return execute(buildGet(config.getApi().getBaseUrl() + path + "?" + toQueryString(withSignature(params))));
    }

    private String postSigned(String path, Map<String, String> params) {
        return execute(buildPost(config.getApi().getBaseUrl() + path, toQueryString(withSignature(params))));
    }

    private String deleteSigned(String path, Map<String, String> params) {
        return execute(buildDelete(config.getApi().getBaseUrl() + path + "?" + toQueryString(withSignature(params))));
    }

    private HttpRequest buildGet(String url) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("X-MBX-APIKEY", config.getApi().getApiKey())
                .header("Accept", "application/json")
                .GET()
                .build();
    }

    private HttpRequest buildPost(String url, String body) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("X-MBX-APIKEY", config.getApi().getApiKey())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private HttpRequest buildDelete(String url) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("X-MBX-APIKEY", config.getApi().getApiKey())
                .header("Accept", "application/json")
                .DELETE()
                .build();
    }

    private String execute(HttpRequest request) {
        try {
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.error("币安 API 错误 [{}]: {}", resp.statusCode(), resp.body());
                throw new RuntimeException("币安 API 错误 " + resp.statusCode() + ": " + resp.body());
            }
            return resp.body();
        } catch (Exception e) {
            log.error("币安 API 请求失败", e);
            throw new RuntimeException("币安 API 请求失败", e);
        }
    }

    private static String toQueryString(Map<String, String> params) {
        return params.entrySet().stream()
                .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(Collectors.joining("&"));
    }
}
