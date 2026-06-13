package com.qw.agent.line.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qw.agent.line.indicator.MACDVCalculator;
import com.qw.agent.line.service.MACDVService;
import com.qw.agent.line.store.KlineStore;
import com.qw.agent.line.store.MultiTimeframeStrategy;
import com.qw.agent.line.store.TradeDecision;
import com.qw.agent.line.strategy.MACDVSignalGenerator;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.sqlite.SQLiteDataSource;

import java.util.Map;

/**
 * MACD-V 图表 REST 控制器 —— 最薄的一层，仅处理 HTTP 请求与响应。
 * <p>
 * 所有业务逻辑委托给 {@link MACDVService}。
 * <p>
 * 也可通过 main 方法直接调用（不启动 Spring）：
 * <pre>
 *   MACDVController.main(new String[]{"ETHUSDT", "15m", "200"});
 * </pre>
 */
@RestController
@RequestMapping("/macdv")
public class MACDVController {

    private final MACDVService macdvService;
    private final MultiTimeframeStrategy mtfStrategy;

    public MACDVController(MACDVService macdvService,
                           MultiTimeframeStrategy mtfStrategy) {
        this.macdvService = macdvService;
        this.mtfStrategy = mtfStrategy;
    }

    /**
     * 直接运行 main 方法获取数据（不依赖 Spring 容器）。
     * @param args [symbol, interval, limit, fastLen, slowLen, signalLen, atrLen, dTh, kTh]
     */
    public static void main(String[] args) throws Exception {
        // 默认参数
        String symbol     = "BTCUSDT";
        String interval   = "1h";
        int limit         = 1000;
        int fastLen       = 12;
        int slowLen       = 26;
        int signalLen     = 9;
        int atrLen        = 26;
        int dTh           = MACDVSignalGenerator.DEFAULT_D_TH;
        int kTh           = MACDVSignalGenerator.DEFAULT_K_TH;
        boolean trendFilter = MACDVSignalGenerator.DEFAULT_TREND_FILTER;
        int minHoldBars    = MACDVSignalGenerator.DEFAULT_MIN_HOLD_BARS;
        int cooldownBars   = MACDVSignalGenerator.DEFAULT_COOLDOWN_BARS;
        double minHistAmp  = MACDVSignalGenerator.DEFAULT_MIN_HIST_AMP;

        if (args.length >= 1) symbol       = args[0];
        if (args.length >= 2) interval     = args[1];
        if (args.length >= 3) limit        = Integer.parseInt(args[2]);
        if (args.length >= 4) fastLen      = Integer.parseInt(args[3]);
        if (args.length >= 5) slowLen      = Integer.parseInt(args[4]);
        if (args.length >= 6) signalLen    = Integer.parseInt(args[5]);
        if (args.length >= 7) atrLen       = Integer.parseInt(args[6]);
        if (args.length >= 8) dTh          = Integer.parseInt(args[7]);
        if (args.length >= 9) kTh          = Integer.parseInt(args[8]);
        if (args.length >= 10) trendFilter = Boolean.parseBoolean(args[9]);
        if (args.length >= 11) minHoldBars = Integer.parseInt(args[10]);
        if (args.length >= 12) cooldownBars = Integer.parseInt(args[11]);
        if (args.length >= 13) minHistAmp  = Double.parseDouble(args[12]);

        // 手动组装依赖（不启动 Spring）
        MACDVCalculator calc = new MACDVCalculator();
        MACDVSignalGenerator sig = new MACDVSignalGenerator();

        // SQLite 数据源
        String userDir = System.getProperty("user.dir");
        String dbDir = new java.io.File(userDir).getName().equals("qw-agent-line")
                ? userDir + "/data"
                : userDir + "/qw-agent-line/data";
        new java.io.File(dbDir).mkdirs();
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbDir + "/agent-line.db");
        KlineStore klineStore = new KlineStore(ds);

        MACDVService service = new MACDVService(calc, sig, klineStore);

        long start = System.currentTimeMillis();
        Map<String, Object> data = service.getChartData(
                symbol, interval, limit, 0,
                fastLen, slowLen, signalLen, atrLen,
                dTh, kTh, trendFilter, minHoldBars,
                cooldownBars, minHistAmp);
        long elapsed = System.currentTimeMillis() - start;

        // 打印统计
        int k = ((java.util.List<?>) data.get("klines")).size();
        int m = ((java.util.List<?>) data.get("macdv")).size();
        long v = ((java.util.List<?>) data.get("macdv")).stream()
                .filter(r -> ((Map<String, Object>) r).get("macdV") != null).count();
        int s = ((java.util.List<?>) data.get("signals")).size();
        System.out.println("===== MACD-V 信号 =====");
        System.out.println("K 线: " + k + " 条");
        System.out.println("MACD-V: " + m + " 条（有效 " + v + " 点）");
        System.out.println("d/pd/k/pk 信号: " + s + " 个");
        System.out.println("dTh=" + dTh + ", kTh=" + kTh);
        System.out.println("耗时: " + elapsed + "ms");
        System.out.println();

        // 输出统计摘要，不输出全部JSON
        long dCnt = data.get("signals") != null ? ((java.util.List<?>) data.get("signals")).stream()
                .filter(r -> "d".equals(((Map<String, Object>) r).get("type"))).count() : 0;
        long pdCnt = data.get("signals") != null ? ((java.util.List<?>) data.get("signals")).stream()
                .filter(r -> "pd".equals(((Map<String, Object>) r).get("type"))).count() : 0;
        long kCnt = data.get("signals") != null ? ((java.util.List<?>) data.get("signals")).stream()
                .filter(r -> "k".equals(((Map<String, Object>) r).get("type"))).count() : 0;
        long pkCnt = data.get("signals") != null ? ((java.util.List<?>) data.get("signals")).stream()
                .filter(r -> "pk".equals(((Map<String, Object>) r).get("type"))).count() : 0;
        System.out.println("d(开多)=" + dCnt + ", pd(平多)=" + pdCnt
                + ", k(开空)=" + kCnt + ", pk(平空)=" + pkCnt);
        System.out.println("最新信号: " + ((Map<String, Object>) data.get("latestSignal")).get("type")
                + " - " + ((Map<String, Object>) data.get("latestSignal")).get("reason"));

        // 输出完整 JSON
        ObjectMapper mapper = new ObjectMapper();
        System.out.println();
        System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(data));
    }

    /**
     * 获取 MACD-V 图表所需的全部数据。
     *
     * @param symbol     交易对（默认 BTCUSDT）
     * @param interval   K 线周期（默认 1h）
     * @param limit      返回 K 线条数（默认 1000）
     * @param fastLen    快线 EMA 周期（默认 12）
     * @param slowLen    慢线 EMA 周期（默认 26）
     * @param signalLen  信号线 EMA 周期（默认 9）
     * @param atrLen     ATR 周期（默认 26）
     * @param dTh        开多阈值（默认 -100）
     * @param kTh        开空阈值（默认 100）
     * @param trendFilter 趋势过滤（默认 true，MACDV>0只做多,<0只做空）
     * @param minHoldBars 最小持仓K线数（默认 3）
     * @return 包含 klines / macdv / signals / latestSignal 的 JSON
     */
    /**
     * 重定向到图表页面 (根路径或 /chart)。
     */
    @GetMapping("/")
    public org.springframework.http.ResponseEntity<Void> redirectToChart() {
        java.net.URI uri = java.net.URI.create("/index.html");
        return org.springframework.http.ResponseEntity.status(302).location(uri).build();
    }

    @GetMapping("/data")
    public ResponseEntity<Map<String, Object>> getData(
            @RequestParam(defaultValue = "BTCUSDT") String symbol,
            @RequestParam(defaultValue = "1h") String interval,
            @RequestParam(defaultValue = "500") int limit,
            @RequestParam(defaultValue = "0") long before,
            @RequestParam(defaultValue = "12") int fastLen,
            @RequestParam(defaultValue = "26") int slowLen,
            @RequestParam(defaultValue = "9") int signalLen,
            @RequestParam(defaultValue = "26") int atrLen,
            @RequestParam(defaultValue = "-100") int dTh,
            @RequestParam(defaultValue = "100") int kTh,
            @RequestParam(defaultValue = "true") boolean trendFilter,
            @RequestParam(defaultValue = "3") int minHoldBars,
            @RequestParam(defaultValue = "3") int cooldownBars,
            @RequestParam(defaultValue = "0.03") double minHistAmp) {

        Map<String, Object> data = macdvService.getChartData(
                symbol, interval, limit, before,
                fastLen, slowLen, signalLen, atrLen,
                dTh, kTh, trendFilter, minHoldBars,
                cooldownBars, minHistAmp);

        return ResponseEntity.ok(data);
    }

    /**
     * 多周期策略决策 —— 返回下一根15min K线是否可以立即买卖。
     *
     * @param symbol 交易对（默认 BTCUSDT）
     * @return TradeDecision JSON: { action, confidence, reason,
     *          dailyMacdv, fourHourMacdv, oneHourMacdv, fifteenMinMacdv,
     *          fiveMinMacdv, lastPrice }
     */
    @GetMapping("/decide")
    public ResponseEntity<TradeDecision> decide(
            @RequestParam(defaultValue = "BTCUSDT") String symbol) {
        TradeDecision decision = mtfStrategy.decide(symbol);
        return ResponseEntity.ok(decision);
    }

    /**
     * 判断是否应平多（15min MACDV &gt; 80）。
     */
    @GetMapping("/should-close-long")
    public ResponseEntity<Map<String, Object>> shouldCloseLong(
            @RequestParam(defaultValue = "BTCUSDT") String symbol) {
        boolean should = mtfStrategy.shouldCloseLong(symbol);
        return ResponseEntity.ok(Map.of("symbol", symbol, "shouldCloseLong", should));
    }

    /**
     * 判断是否应平空（15min MACDV &lt; -80）。
     */
    @GetMapping("/should-close-short")
    public ResponseEntity<Map<String, Object>> shouldCloseShort(
            @RequestParam(defaultValue = "BTCUSDT") String symbol) {
        boolean should = mtfStrategy.shouldCloseShort(symbol);
        return ResponseEntity.ok(Map.of("symbol", symbol, "shouldCloseShort", should));
    }
}
