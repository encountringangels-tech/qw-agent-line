package com.qw.agent.line.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qw.agent.line.indicator.MACDVCalculator;
import com.qw.agent.line.model.*;
import com.qw.agent.line.strategy.MACDVSignalGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * MACD-V 业务服务 —— 编排整个流程：
 * <ol>
 *   <li>从币安拉取 K 线数据</li>
 *   <li>计算 MACD-V 指标</li>
 *   <li>生成买卖信号</li>
 *   <li>组装前端所需的 JSON 数据</li>
 * </ol>
 */
@Service
public class MACDVService {

    private static final Logger log = LoggerFactory.getLogger(MACDVService.class);

    /** 币安现货 K 线 API 地址 */
    private static final String BINANCE_KLINE_URL = "https://api.binance.com/api/v3/klines";

    /** 复用 ObjectMapper（避免每次请求创建新实例） */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient httpClient;
    private final MACDVCalculator calculator;
    private final MACDVSignalGenerator signalGenerator;

    public MACDVService(MACDVCalculator calculator,
                        MACDVSignalGenerator signalGenerator) {
        this.calculator = calculator;
        this.signalGenerator = signalGenerator;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    // ==================== 对外唯一入口 ====================

    /**
     * 获取 MACD-V 图表的全部数据（K 线 + 指标 + 信号）。
     */
    public Map<String, Object> getChartData(String symbol, String interval, int limit,
                                             int fastLen, int slowLen, int signalLen, int atrLen,
                                             int overbought, int oversold) {

        // 1. 从币安拉取 K 线
        List<Kline> klines = fetchKlines(symbol, interval, Math.min(limit, 1000));
        if (klines.isEmpty()) {
            return buildEmptyResult(symbol, interval);
        }

        // 2. K 线转前端格式（for 循环替代 stream，减少对象分配）
        int n = klines.size();
        List<Map<String, Object>> klineData = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            klineData.add(toKlineMap(klines.get(i)));
        }

        // 3. 计算 MACD-V 指标
        List<MACDVPoint> macdvPoints = calculator.calculate(klines, fastLen, slowLen, signalLen, atrLen);

        // 4. 指标点转前端格式
        List<Map<String, Object>> macdvData = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            macdvData.add(toMACDVMap(macdvPoints.get(i)));
        }

        // 5. 生成批量买卖信号
        List<TradeSignal> tradeSignals = signalGenerator.generateBatch(macdvPoints, overbought, oversold);
        int m = tradeSignals.size();
        List<Map<String, Object>> signalData = new ArrayList<>(m);
        for (int i = 0; i < m; i++) {
            signalData.add(toSignalMap(tradeSignals.get(i)));
        }

        // 6. 生成最新综合信号
        LatestSignal latest = signalGenerator.evaluateLatest(macdvPoints, overbought, oversold);

        // 7. 组装结果
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("symbol", symbol);
        result.put("interval", interval);
        result.put("klines", klineData);
        result.put("macdv", macdvData);
        result.put("signals", signalData);
        result.put("latestSignal", toLatestSignalMap(latest));

        return result;
    }

    // ==================== 币安 API 调用 ====================

    /**
     * 从币安拉取 K 线数据。
     */
    @SuppressWarnings("unchecked")
    List<Kline> fetchKlines(String symbol, String interval, int limit) {
        try {
            String url = BINANCE_KLINE_URL + "?symbol=" + symbol + "&interval=" + interval + "&limit=" + limit;
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new RuntimeException("币安 API 错误 " + resp.statusCode() + ": " + resp.body());
            }

            List<Object> raw = MAPPER.readValue(resp.body(), List.class);

            List<Kline> result = new ArrayList<>(raw.size());
            for (Object obj : raw) {
                List<Object> k = (List<Object>) obj;
                result.add(new Kline(
                        ((Number) k.get(0)).longValue(),
                        new BigDecimal((String) k.get(1)),
                        new BigDecimal((String) k.get(2)),
                        new BigDecimal((String) k.get(3)),
                        new BigDecimal((String) k.get(4)),
                        new BigDecimal((String) k.get(5))
                ));
            }
            return result;

        } catch (Exception e) {
            log.error("拉取 K 线失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    // ==================== 类型转换（模型 → 前端 Map） ====================

    private Map<String, Object> toKlineMap(Kline k) {
        Map<String, Object> m = new HashMap<>();
        m.put("time", k.getOpenTime() / 1000);
        m.put("open", k.getOpen().doubleValue());
        m.put("high", k.getHigh().doubleValue());
        m.put("low", k.getLow().doubleValue());
        m.put("close", k.getClose().doubleValue());
        m.put("volume", k.getVolume().doubleValue());
        return m;
    }

    private Map<String, Object> toMACDVMap(MACDVPoint p) {
        Map<String, Object> m = new HashMap<>();
        m.put("time", p.getTime());
        m.put("macdV", p.getMacdV() != null ? p.getMacdV().doubleValue() : null);
        m.put("signal", p.getSignal() != null ? p.getSignal().doubleValue() : null);
        m.put("hist", p.getHist() != null ? p.getHist().doubleValue() : null);
        return m;
    }

    private Map<String, Object> toSignalMap(TradeSignal s) {
        Map<String, Object> m = new HashMap<>();
        m.put("time", s.getTime());
        m.put("type", s.getType());
        m.put("subType", s.getSubType());
        m.put("reason", s.getReason());
        m.put("strength", s.getStrength());
        return m;
    }

    private Map<String, Object> toLatestSignalMap(LatestSignal s) {
        Map<String, Object> m = new HashMap<>();
        m.put("type", s.getType());
        m.put("strength", s.getStrength());
        m.put("reason", s.getReason());
        m.put("macdvLine", s.getMacdvLine());
        m.put("macdvSignal", s.getMacdvSignal());
        m.put("macdvHist", s.getMacdvHist());
        return m;
    }

    private Map<String, Object> buildEmptyResult(String symbol, String interval) {
        Map<String, Object> result = new HashMap<>();
        result.put("symbol", symbol);
        result.put("interval", interval);
        result.put("klines", Collections.emptyList());
        result.put("macdv", Collections.emptyList());
        result.put("signals", Collections.emptyList());
        result.put("latestSignal", Map.of("type", "HOLD", "strength", 0, "reason", "无数据"));
        return result;
    }
}
