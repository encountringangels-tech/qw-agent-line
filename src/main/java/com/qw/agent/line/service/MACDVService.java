package com.qw.agent.line.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qw.agent.line.indicator.MACDVCalculator;
import com.qw.agent.line.model.*;
import com.qw.agent.line.store.KlineStore;
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
 *   <li>从币安拉取 K 线数据（优先从本地 SQLite 缓存读取）</li>
 *   <li>计算 MACD-V 指标（优先从本地 SQLite 缓存读取）</li>
 *   <li>生成 d/pd/k/pk 买卖信号</li>
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
    private final KlineStore klineStore;

    public MACDVService(MACDVCalculator calculator,
                        MACDVSignalGenerator signalGenerator,
                        KlineStore klineStore) {
        this.calculator = calculator;
        this.signalGenerator = signalGenerator;
        this.klineStore = klineStore;
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
                                             int dTh, int kTh, boolean trendFilter, int minHoldBars) {

        // 1. 获取 K 线（优先从本地 SQLite 缓存读取）
        List<Kline> klines = getCachedKlines(symbol, interval, limit);
        if (klines.isEmpty()) {
            return buildEmptyResult(symbol, interval);
        }

        // 2. K 线转前端格式
        int n = klines.size();
        List<Map<String, Object>> klineData = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            klineData.add(toKlineMap(klines.get(i)));
        }

        // 3. 获取 MACD-V 指标
        List<MACDVPoint> macdvPoints = getCachedMACDVPoints(symbol, interval, klines,
                fastLen, slowLen, signalLen, atrLen);

        // 4. 指标点转前端格式
        List<Map<String, Object>> macdvData = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            macdvData.add(toMACDVMap(macdvPoints.get(i)));
        }

        // 5. 生成批量买卖信号 (d/pd/k/pk)
        List<TradeSignal> tradeSignals = signalGenerator.generateBatch(macdvPoints, dTh, kTh,
                trendFilter, minHoldBars, klineData);
        int m = tradeSignals.size();
        List<Map<String, Object>> signalData = new ArrayList<>(m);
        for (int i = 0; i < m; i++) {
            signalData.add(toSignalMap(tradeSignals.get(i)));
        }

        // 6. 生成最新综合信号
        LatestSignal latest = signalGenerator.evaluateLatest(macdvPoints, dTh, kTh,
                trendFilter, minHoldBars);

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

    // ==================== 定时任务同步 MACDV ====================

    /** 默认 MACD-V 参数 */
    private static final int DEFAULT_FAST_LEN   = 12;
    private static final int DEFAULT_SLOW_LEN   = 26;
    private static final int DEFAULT_SIGNAL_LEN = 9;
    private static final int DEFAULT_ATR_LEN    = 26;

    /**
     * 定时任务调用 —— 检查近 7 天 K 线是否都已计算 MACD-V，缺失则自动补齐。
     * <p>
     * 全量重算后通过 {@code INSERT OR IGNORE} 写入，已有时间点自动跳过，
     * 只有新增 K 线对应的 MACD-V 点会被持久化。
     */
    public void syncMACDV(String symbol, String interval) {
        int totalKlines = klineStore.countKlines(symbol, interval);
        if (totalKlines == 0) {
            return;
        }

        int needed = DEFAULT_SLOW_LEN + DEFAULT_ATR_LEN + DEFAULT_SIGNAL_LEN + 5;
        if (totalKlines < needed) {
            log.debug("K 线数量不足 [{}/{}]: {}/{}", symbol, interval, totalKlines, needed);
            return;
        }

        // 检查近 7 天是否有 K 线缺少对应 MACD-V
        long sevenDaysAgo = System.currentTimeMillis() - 7L * 24 * 3600 * 1000;
        int missing = klineStore.countKlinesWithoutMACDV(symbol, interval, sevenDaysAgo);
        if (missing == 0) {
            log.debug("MACD-V 已是最新 [{}/{}]", symbol, interval);
            return;
        }

        log.info("检测到 [{}/{}] 近 7 天有 {} 根 K 线缺少 MACD-V，开始重算", symbol, interval, missing);

        List<Kline> klines = klineStore.getKlines(symbol, interval, totalKlines);
        List<MACDVPoint> points = calculator.calculate(
                klines, DEFAULT_FAST_LEN, DEFAULT_SLOW_LEN, DEFAULT_SIGNAL_LEN, DEFAULT_ATR_LEN);
        klineStore.saveMACDVPoints(symbol, interval, points);

        int macdvTotal = klineStore.countMACDVPoints(symbol, interval);
        log.info("MACD-V 已同步 [{}/{}]: 总计 {} 点", symbol, interval, macdvTotal);
    }

    // ==================== 本地缓存读取（KlineStore） ====================

    private List<Kline> getCachedKlines(String symbol, String interval, int limit) {
        int localCount = klineStore.countKlines(symbol, interval);
        if (localCount >= limit) {
            log.info("K 线缓存命中: {}_{} x{}", symbol, interval, limit);
            return klineStore.getKlines(symbol, interval, limit);
        }

        log.info("K 线缓存不足: {}_{} (本地={}, 需要={})，从币安拉取",
                symbol, interval, localCount, limit);
        List<Kline> klines = fetchKlines(symbol, interval, Math.min(limit, 1000));
        if (!klines.isEmpty()) {
            klineStore.saveKlines(symbol, interval, klines);
        }
        return klineStore.getKlines(symbol, interval, limit);
    }

    private List<MACDVPoint> getCachedMACDVPoints(String symbol, String interval,
                                                   List<Kline> klines,
                                                   int fastLen, int slowLen,
                                                   int signalLen, int atrLen) {
        int localCount = klineStore.countMACDVPoints(symbol, interval);
        if (localCount >= klines.size()) {
            log.info("MACD-V 缓存命中: {}_{} x{}", symbol, interval, klines.size());
            return klineStore.getMACDVPoints(symbol, interval);
        }

        log.info("MACD-V 缓存不足: {}_{} (本地={}, 需要={})，重新计算",
                symbol, interval, localCount, klines.size());
        List<MACDVPoint> points = calculator.calculate(klines, fastLen, slowLen, signalLen, atrLen);
        klineStore.saveMACDVPoints(symbol, interval, points);
        return points;
    }

    // ==================== 币安 API 调用 ====================

    /**
     * 增量拉取：拉取指定时间戳之后的所有 K 线（最多 1000 条）。
     * @param afterTime 毫秒时间戳，返回的数据 > afterTime
     */
    @SuppressWarnings("unchecked")
    List<Kline> fetchKlinesAfter(String symbol, String interval, long afterTime) {
        String url = BINANCE_KLINE_URL + "?symbol=" + symbol + "&interval=" + interval
                + "&startTime=" + (afterTime + 1) + "&limit=1000";
        return doFetchKlines(url);
    }

    @SuppressWarnings("unchecked")
    List<Kline> fetchKlines(String symbol, String interval, int limit) {
        String url = BINANCE_KLINE_URL + "?symbol=" + symbol + "&interval=" + interval + "&limit=" + limit;
        return doFetchKlines(url);
    }

    /** 执行 HTTP GET 请求并解析 K 线数据 */
    @SuppressWarnings("unchecked")
    private List<Kline> doFetchKlines(String url) {
        try {
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
