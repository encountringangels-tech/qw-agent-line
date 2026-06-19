package com.qw.agent.line.macd.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qw.agent.line.macd.indicator.MACDVCalculator;
import com.qw.agent.line.macd.model.*;
import com.qw.agent.line.store.KlineStore;
import com.qw.agent.line.macd.model.TradeDecision;
import com.qw.agent.line.macd.strategy.MACDVSignalGenerator;
import com.qw.agent.line.util.TimeUtil;
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
     *
     * @param before Unix 秒级时间戳，>0 表示加载此时间之前的 K 线（分页模式，不含信号）
     */
    public Map<String, Object> getChartData(String symbol, String interval, int limit,
                                             long before,
                                             int fastLen, int slowLen, int signalLen, int atrLen,
                                             int dTh, int kTh, boolean trendFilter, int minHoldBars,
                                             int cooldownBars, double minHistAmp,
                                             String signalSource) {

        // 1. 获取 K 线
        List<Kline> klines;
        if (before > 0) {
            // 分页模式：加载指定时间之前的历史数据，纯 DB 读取
            klines = klineStore.getKlinesBefore(symbol, interval, before * 1000, limit);
        } else {
            klines = getCachedKlines(symbol, interval, limit);
        }

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
        List<MACDVPoint> macdvPoints;
        if (before > 0) {
            // 分页模式：DB 层按时间范围直接查询，避免全量加载后内存过滤
            long fromSec = klines.get(0).getOpenTime() / 1000;
            long toSec = klines.get(n - 1).getOpenTime() / 1000;
            macdvPoints = klineStore.getMACDVPointsRange(symbol, interval, fromSec, toSec);
        } else {
            macdvPoints = getCachedMACDVPoints(symbol, interval, klines,
                    fastLen, slowLen, signalLen, atrLen);
        }

        // 4. 指标点转前端格式
        List<Map<String, Object>> macdvData = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            macdvData.add(toMACDVMap(macdvPoints.get(i)));
        }

        // 5. 组装结果
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("symbol", symbol);
        result.put("interval", interval);
        result.put("klines", klineData);
        result.put("macdv", macdvData);

        if (before > 0) {
            // 分页模式：有信号源时依然合并信号，无信号源时不生成
            if (!signalSource.isEmpty()) {
                mergeStrategySignals(result, symbol, interval, macdvData, signalSource);
            } else {
                result.put("signals", List.of());
                result.put("latestSignal", Map.of("type", "HOLD", "strength", 0, "reason", "分页数据"));
            }
        } else if (!signalSource.isEmpty()) {
            // 使用指定策略表（如 trade_signal），仅15min标记
            mergeStrategySignals(result, symbol, interval, macdvData, signalSource);
        } else {
            // 默认信号生成器仅用于15min，其他周期不生成信号
            if (!"15m".equals(interval)) {
                result.put("signals", List.of());
                result.put("latestSignal", Map.of("type", "HOLD", "strength", 0,
                        "reason", "信号仅15min显示"));
            } else {
                List<TradeSignal> tradeSignals = signalGenerator.generateBatch(macdvPoints, dTh, kTh,
                        trendFilter, minHoldBars, cooldownBars, minHistAmp, klineData);
                int m = tradeSignals.size();
                List<Map<String, Object>> signalData = new ArrayList<>(m);
                for (int i = 0; i < m; i++) {
                    signalData.add(toSignalMap(tradeSignals.get(i)));
                }
                result.put("signals", signalData);

                LatestSignal latest = signalGenerator.evaluateLatest(macdvPoints, dTh, kTh,
                        trendFilter, minHoldBars, cooldownBars, minHistAmp);
                result.put("latestSignal", toLatestSignalMap(latest));
            }
        }

        return result;
    }

    /**
     * 从策略信号表读取买卖点，仅15min标注到MACDV数据点上。可扩展为其他策略表。
     */
    private void mergeStrategySignals(Map<String, Object> result, String symbol,
                                       String interval,
                                       List<Map<String, Object>> macdvData,
                                       String signalSource) {
        if (!"15m".equals(interval)) {
            result.put("signals", List.of());
            result.put("latestSignal", Map.of("type", "HOLD", "strength", 0,
                    "reason", signalSource + "仅15min显示"));
            return;
        }
        List<TradeSignalRecord> records = klineStore.getTradeSignals(symbol);
        if (records.isEmpty()) {
            result.put("signals", List.of());
            result.put("latestSignal", Map.of("type", "HOLD", "strength", 0,
                    "reason", "无策略信号"));
            return;
        }
        Map<Long, TradeSignalRecord> signalMap = new java.util.LinkedHashMap<>();
        for (TradeSignalRecord r : records) signalMap.put(r.getTime(), r);

        // 跟踪持仓方向，CLOSE 时据此判断是 pd(平多) 还是 pk(平空)
        String pos = "FLAT";
        List<Map<String, Object>> signalList = new ArrayList<>();
        for (Map<String, Object> point : macdvData) {
            long time = ((Number) point.get("time")).longValue();
            TradeSignalRecord sig = signalMap.get(time);
            if (sig != null) {
                String dir = sig.getDirection();
                String type;
                if ("LONG".equals(dir))  { type = "d";  pos = "LONG"; }
                else if ("SHORT".equals(dir)) { type = "k";  pos = "SHORT"; }
                else { type = "LONG".equals(pos) ? "pd" : "pk"; pos = "FLAT"; }

                Map<String, Object> marker = new LinkedHashMap<>();
                marker.put("time", time);
                marker.put("type", type);
                marker.put("direction", dir);
                marker.put("price", sig.getPrice().doubleValue());
                marker.put("amount", sig.getAmount().doubleValue());
                marker.put("score", sig.getScore());
                marker.put("reason", sig.getReason());
                point.put("tradeSignal", marker);
                signalList.add(marker);
            }
        }
        result.put("signals", signalList);
        result.put("signalSource", signalSource);
        result.put("latestSignal", signalList.isEmpty()
                ? Map.of("type", "HOLD", "strength", 0, "reason", "无信号")
                : signalList.get(signalList.size() - 1));
    }

    // ==================== 定时任务同步 MACDV ====================

    /** 默认 MACD-V 参数 */
    private static final int DEFAULT_FAST_LEN   = 12;
    private static final int DEFAULT_SLOW_LEN   = 26;
    private static final int DEFAULT_SIGNAL_LEN = 9;
    private static final int DEFAULT_ATR_LEN    = 26;

    /**
     * 定时任务调用 —— 只计算缺失的 MACD-V 点并增量写入，避免重复全量重算。
     * <p>
     * 仅读取最后 N 条 K 线（N = 缺失数 + 计算所需的历史缓冲区），
     * 计算后只写入时间戳大于已有最新 MACDV 点的新数据。
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
        long sevenDaysAgo = TimeUtil.daysAgoMs(7);
        int missing = klineStore.countKlinesWithoutMACDV(symbol, interval, sevenDaysAgo);
        if (missing == 0) {
            log.debug("MACD-V 已是最新 [{}/{}]", symbol, interval);
            return;
        }

        log.info("检测到 [{}/{}] 近 7 天有 {} 根 K 线缺少 MACD-V，开始补齐", symbol, interval, missing);

        // 只读取最后 N 条 K 线：缺失数 + 计算所需的缓冲区（慢线+ATR+信号线+余量）
        int buffer = DEFAULT_SLOW_LEN + DEFAULT_ATR_LEN + DEFAULT_SIGNAL_LEN + 10;
        int fetchCount = Math.min(missing + buffer, totalKlines);
        List<Kline> klines = klineStore.getKlines(symbol, interval, fetchCount);

        // 计算 MACDV，但只提取真正新增的点写入
        List<MACDVPoint> allPoints = calculator.calculate(
                klines, DEFAULT_FAST_LEN, DEFAULT_SLOW_LEN, DEFAULT_SIGNAL_LEN, DEFAULT_ATR_LEN);

        // 找到已有最新 MACDV 点的时间（秒），只保存此时间之后的点
        MACDVPoint latestPoint = klineStore.getLatestMACDVPoint(symbol, interval);
        long latestTimeSec = latestPoint != null ? latestPoint.getTime() : 0;

        List<MACDVPoint> newPoints = allPoints.stream()
                .filter(p -> p.getTime() > latestTimeSec)
                .collect(java.util.stream.Collectors.toList());

        if (newPoints.isEmpty()) {
            log.info("MACD-V 已是最新 [{}/{}]（无需写入）", symbol, interval);
            return;
        }

        klineStore.saveMACDVPoints(symbol, interval, newPoints);
        int macdvTotal = klineStore.countMACDVPoints(symbol, interval);
        log.info("MACD-V 已同步 [{}/{}]: 新增 {} 点，总计 {} 点",
                symbol, interval, newPoints.size(), macdvTotal);
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
            // DB 层按时间范围直接查询，避免全量加载后内存过滤
            long fromTime = klines.get(0).getOpenTime() / 1000;
            long toTime = klines.get(klines.size() - 1).getOpenTime() / 1000;
            List<MACDVPoint> cached = klineStore.getMACDVPointsRange(symbol, interval, fromTime, toTime);
            if (cached.size() >= klines.size()) {
                log.info("MACD-V 缓存命中: {}_{} x{}", symbol, interval, klines.size());
                return cached;
            }
            log.info("MACD-V 缓存部分命中: {}_{} (DB={}, 需要={})，补齐计算",
                    symbol, interval, cached.size(), klines.size());
        } else {
            log.info("MACD-V 缓存不足: {}_{} (本地={}, 需要={})，重新计算",
                    symbol, interval, localCount, klines.size());
        }

        List<MACDVPoint> points = calculator.calculate(klines, fastLen, slowLen, signalLen, atrLen);
        klineStore.saveMACDVPoints(symbol, interval, points);
        return points;
    }

    // ==================== 币安 API 调用 ====================

    /**
     * 增量拉取：根据时间范围计算需要的 K 线条数，用 {@code limit} 参数拉取最新数据。
     * <p>
     * 覆盖范围：从 {@code afterTime - 一个周期} 到当前时间。
     * 例如 15m 周期、最新 K 线 13:30 → 需覆盖 13:15 至今 → 约 3 根 K 线 → limit=3。
     * 超过 1000 条时自动分页（长时间断连后追赶）。
     *
     * @param afterTime 毫秒时间戳，本地最新一条 K 线的开盘时间
     */
    @SuppressWarnings("unchecked")
    public List<Kline> fetchKlinesAfter(String symbol, String interval, long afterTime) {
        long intervalMs = TimeUtil.parseIntervalMs(interval);
        long now = System.currentTimeMillis();
        long fromTime = Math.max(0, afterTime - intervalMs);
        long timeSpan = now - fromTime;
        int needed = (int) (timeSpan / intervalMs) + 1; // +1 安全余量

        if (needed > 1000) {
            // 长时间断连，分页追赶
            return fetchKlinesRange(symbol, interval, fromTime, now);
        }
        // 直接取最新 needed 根（天然包含当前未完成 + 上一根刚关闭的完整数据）
        return fetchKlines(symbol, interval, needed);
    }

    @SuppressWarnings("unchecked")
    public List<Kline> fetchKlines(String symbol, String interval, int limit) {
        String url = BINANCE_KLINE_URL + "?symbol=" + symbol + "&interval=" + interval + "&limit=" + limit;
        return doFetchKlines(url);
    }

    /**
     * 分页拉取指定时间范围内的全部 K 线（自动处理币安 1000 条/次的分页限制）。
     *
     * @param symbol      交易对
     * @param interval    K 线周期
     * @param startTimeMs 起始毫秒时间戳（含）
     * @param endTimeMs   结束毫秒时间戳（含），0 表示到当前时间
     * @return 按时间正序排列的全部 K 线
     */
    @SuppressWarnings("unchecked")
    public List<Kline> fetchKlinesRange(String symbol, String interval,
                                        long startTimeMs, long endTimeMs) {
        if (endTimeMs <= 0) {
            endTimeMs = System.currentTimeMillis();
        }

        java.util.List<Kline> all = new java.util.ArrayList<>();
        long nextStart = startTimeMs;
        int page = 0;

        while (true) {
            String url = BINANCE_KLINE_URL
                    + "?symbol=" + symbol
                    + "&interval=" + interval
                    + "&startTime=" + nextStart
                    + "&endTime=" + endTimeMs
                    + "&limit=1000";
            List<Kline> batch = doFetchKlines(url);
            if (batch.isEmpty()) break;

            all.addAll(batch);
            page++;
            log.info("  分页拉取 [{}/{}] 第 {} 页: {} 条 (累计 {})",
                    symbol, interval, page, batch.size(), all.size());

            // 币安单次最多 1000；如果不足 1000 说明已拉完
            if (batch.size() < 1000) break;

            // 下一页起始时间 = 本批最后一根 K 线开仓时间 + 1ms
            nextStart = batch.get(batch.size() - 1).getOpenTime() + 1;
            if (nextStart > endTimeMs) break;

            // 防止无限循环
            if (page >= 5000) {
                log.warn("分页拉取超过 5000 页，停止 [{}/{}]", symbol, interval);
                break;
            }
        }

        log.info("分页拉取完成 [{}/{}]: {} 条, {} 页", symbol, interval, all.size(), page);
        return all;
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
