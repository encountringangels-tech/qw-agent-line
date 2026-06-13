package test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qw.agent.line.indicator.MACDVCalculator;
import com.qw.agent.line.model.AIAnalysisPayload;
import com.qw.agent.line.model.Kline;
import com.qw.agent.line.model.MACDVPoint;
import com.qw.agent.line.service.MACDVService;
import com.qw.agent.line.store.KlineStore;
import com.qw.agent.line.strategy.MACDVSignalGenerator;
import org.sqlite.SQLiteDataSource;

import java.io.File;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据库管理工具 —— 三个独立方法，复制到 main 中即可执行。
 *
 * <pre>
 *   deleteAllData();                  → 删除全部数据
 *   syncLatest();                     → 立即同步最新数据（全部交易对）
 *   fetchHistory("ETHUSDT",365);   → 拉取过去 365 天所有周期的 K 线及 MACD-V
 * </pre>
 */
public class Test {

    private static final String[] SYMBOLS   = {"BTCUSDT", "ETHUSDT"};
    private static final String[] INTERVALS = {"5m", "15m", "30m", "1h", "4h", "1d"};

    public static void main(String[] args) throws Exception {
        // ===== 选择要执行的方法，取消注释即可 =====

//         deleteAllData();
        // syncLatest();
        // syncLatest();
        // syncLatest();
//         fetchHistory("BTCUSDT", 700);
        // 第二个参数 daysBack: 365=一年, 30=一个月, 7=一周, 0=全量
        generateAIAnalysisFile("BTCUSDT", 90);
    }

    // =========================================================================
    //   方法一：删除全部数据
    // =========================================================================

    /** 删除 SQLite 中全部 K 线和 MACD-V 数据 */
    public static void deleteAllData() {
        KlineStore store = createStore();
        store.deleteAll();
        System.out.println("已删除全部数据。");
    }

    // =========================================================================
    //   方法二：立即同步最新数据
    // =========================================================================

    /** 对所有交易对全部周期，立即从币安增量拉取最新 K 线并计算 MACD-V */
    public static void syncLatest() {
        KlineStore store = createStore();
        MACDVService service = createService(store);

        for (String symbol : SYMBOLS) {
            for (String interval : INTERVALS) {
                try {
                    syncSingle(service, store, symbol, interval);
                } catch (Exception e) {
                    System.err.println("同步失败 [" + symbol + "/" + interval + "]: " + e.getMessage());
                }
            }
        }
        System.out.println("===== 同步完成 =====");
    }

    private static void syncSingle(MACDVService service, KlineStore store,
                                   String symbol, String interval) {
        System.out.println("---- 同步 [" + symbol + "/" + interval + "] ----");

        long latestOpenTime = store.getLatestOpenTime(symbol, interval);
        List<Kline> klines;
        if (latestOpenTime == 0) {
            System.out.println("  首次同步，拉取 1000 条...");
            klines = service.fetchKlines(symbol, interval, 1000);
        } else {
            System.out.println("  增量同步，起于 " + new java.util.Date(latestOpenTime) + " ...");
            klines = service.fetchKlinesAfter(symbol, interval, latestOpenTime);
        }

        if (klines.isEmpty()) {
            System.out.println("  无新数据");
            return;
        }

        store.saveKlines(symbol, interval, klines);
        System.out.println("  写入 " + klines.size() + " 条 K 线");

        service.syncMACDV(symbol, interval);

        int totalK = store.countKlines(symbol, interval);
        int totalM = store.countMACDVPoints(symbol, interval);
        System.out.println("  [" + symbol + "/" + interval + "] K线=" + totalK + " MACD-V=" + totalM);
    }

    // =========================================================================
    //   方法三：拉取历史 K 线
    // =========================================================================

    /**
     * 从币安拉取指定天数前的历史 K 线（全部周期）并计算 MACD-V。
     * 每 1000 条一批：拉取 → 入库 → 继续下一批，最后统一计算 MACD-V。
     *
     * @param symbol  交易对，如 "ETHUSDT"
     * @param daysAgo 从多少天前开始拉取，365=去年，730=前年
     */
    public static void fetchHistory(String symbol, int daysAgo) {
        KlineStore store = createStore();
        MACDVService service = createService(store);

        long now = System.currentTimeMillis();
        long startMs = now - daysAgo * 24L * 3600 * 1000;

        System.out.println("===== 拉取 [" + symbol + "] 过去 " + daysAgo + " 天数据 =====");
        System.out.println("  时间范围: " + new java.util.Date(startMs)
                + " ~ " + new java.util.Date(now));
        System.out.println();

        for (String interval : INTERVALS) {
            try {
                System.out.println("---- [" + symbol + "/" + interval + "] ----");

                long nextStart = startMs;
                int batchNo = 0;
                int totalSaved = 0;

                while (true) {
                    List<Kline> batch = service.fetchKlinesAfter(symbol, interval, nextStart - 1);
                    if (batch.isEmpty()) break;

                    store.saveKlines(symbol, interval, batch);
                    totalSaved += batch.size();
                    batchNo++;
                    System.out.println("  批次" + batchNo + ": 写入 " + batch.size()
                            + " 条 (累计 " + totalSaved + ")");

                    if (batch.size() < 1000) break; // 最后一批

                    nextStart = batch.get(batch.size() - 1).getOpenTime() + 1;
                    if (nextStart > now) break;
                }

                if (totalSaved == 0) {
                    System.out.println("  无数据");
                    continue;
                }

                // 全部 K 线入库后统一计算 MACD-V
                service.syncMACDV(symbol, interval);
                int totalM = store.countMACDVPoints(symbol, interval);
                System.out.println("  MACD-V=" + totalM + " 点");
            } catch (Exception e) {
                System.err.println("  失败 [" + symbol + "/" + interval + "]: " + e.getMessage());
            }
        }

        System.out.println();
        System.out.println("===== 历史数据拉取完成 =====");
        System.out.println("symbol=" + symbol + " 各周期统计:");
        for (String interval : INTERVALS) {
            int k = store.countKlines(symbol, interval);
            int m = store.countMACDVPoints(symbol, interval);
            System.out.printf("  %-6s  K线=%-6d  MACD-V=%-6d%n", interval, k, m);
        }
    }

    // =========================================================================
    //   方法四：生成 AI 分析文件
    // =========================================================================

    /**
     * 从 SQLite 读取指定交易对所有周期的 K 线 + MACD-V 数据，
     * 按时间范围过滤后合并为 AI 友好的 JSON 文件。
     *
     * <p>输出文件：{@code data/ai-analysis-BTCUSDT-365d-20260613T120000.json}</p>
     *
     * @param symbol   交易对，如 "BTCUSDT"
     * @param daysBack 导出最近多少天的数据；0 表示全量导出
     */
    public static void generateAIAnalysisFile(String symbol, int daysBack) throws Exception {
        long t0 = System.currentTimeMillis();

        KlineStore store = createStore();

        // 计算时间范围
        long endTimeMs = System.currentTimeMillis();
        long startTimeMs = daysBack > 0
                ? endTimeMs - daysBack * 24L * 3600 * 1000
                : 0;

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
                .withZone(ZoneId.of("Asia/Shanghai"));

        // ===== 1. 统计各周期数据量 & 预估 =====
        int totalK = 0, totalM = 0;
        long[] estRows = { // 5m, 15m, 30m, 1h, 4h, 1d 的理论条数
                daysBack * 24L * 12,
                daysBack * 24L * 4,
                daysBack * 24L * 2,
                daysBack * 24L,
                daysBack * 6L,
                daysBack
        };
        long estTotal = 0;
        System.out.println("===== 数据统计 =====");
        System.out.println("  时间范围: " + (daysBack > 0
                ? fmt.format(Instant.ofEpochMilli(startTimeMs)) + " ~ " + fmt.format(Instant.ofEpochMilli(endTimeMs))
                : "全量"));
        System.out.println();
        System.out.println("  周期     库内K线   预计导出");
        for (int i = 0; i < INTERVALS.length; i++) {
            String iv = INTERVALS[i];
            int k = store.countKlines(symbol, iv);
            int m = store.countMACDVPoints(symbol, iv);
            totalK += k;
            totalM += m;
            long est = daysBack > 0 ? Math.min(estRows[i], k) : k;
            estTotal += est;
            System.out.printf("  %-6s  %-9d %-9d%n", iv, k, est);
        }
        System.out.printf("  合计:  K线=%d  MACD-V=%d  预计导出行=%d%n", totalK, totalM, estTotal);

        long estBytes = (long)(estTotal * 200 * 1.1);
        long estTokens = estBytes / 4;
        System.out.printf("  预估文件大小: %s  |  预估 Token: ~%s%n",
                formatSize(estBytes), formatToken(estTokens));
        if (estTokens > 200_000) {
            System.out.printf("  ⚠ 超过 AI 上下文窗口(GPT-4 128K, Claude 200K)，建议用 daysBack=7 或 30%n");
        }

        // ===== 2. 构建顶层 payload =====
        AIAnalysisPayload payload = new AIAnalysisPayload();
        payload.setSymbol(symbol);
        payload.setGeneratedAt(fmt.format(Instant.now()));
        payload.initLegend();

        // ===== 3. 逐周期读取 & 组装 =====
        double globalLatestPrice = 0;
        long globalLatestTime = 0;
        int actualTotal = 0;
        String dataFrom = null, dataTo = null;

        for (String interval : INTERVALS) {
            System.out.println("---- 组装 [" + symbol + "/" + interval + "] ----");
            long t1 = System.currentTimeMillis();

            List<AIAnalysisPayload.AIAnalysisRow> rows = buildRows(
                    store, symbol, interval, startTimeMs, endTimeMs);
            if (rows == null || rows.isEmpty()) {
                System.out.println("  数据不足，跳过");
                continue;
            }
            payload.putRows(interval, rows);

            long t2 = System.currentTimeMillis();
            actualTotal += rows.size();
            System.out.printf("  完成: %d 行, 耗时 %dms%n", rows.size(), t2 - t1);

            // 取其时间范围
            if (dataFrom == null) {
                dataFrom = formatTimestamp(rows.get(0).getTime());
            }
            dataTo = formatTimestamp(rows.get(rows.size() - 1).getTime());

            // 记录最新价
            AIAnalysisPayload.AIAnalysisRow last = rows.get(rows.size() - 1);
            if (last.getTime() > globalLatestTime) {
                globalLatestTime = last.getTime();
                globalLatestPrice = last.getClose();
            }
        }

        payload.setDataFrom(dataFrom);
        payload.setDataTo(dataTo);

        // ===== 4. 输出 JSON 文件（紧凑格式） =====
        ObjectMapper mapper = new ObjectMapper();

        String modeLabel = daysBack == 0 ? "full" : daysBack + "d";
        String fileName = "ai-analysis-" + symbol + "-" + modeLabel + "-"
                + DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
                        .withZone(ZoneId.of("Asia/Shanghai"))
                        .format(Instant.now())
                + ".json";

        String userDir = System.getProperty("user.dir");
        File dataDir = new File(userDir).getName().equals("qw-agent-line")
                ? new File(userDir, "data")
                : new File(userDir, "qw-agent-line/data");
        dataDir.mkdirs();
        File outFile = new File(dataDir, fileName);

        System.out.println();
        System.out.println("---- 写入 JSON 文件 ----");
        long tw1 = System.currentTimeMillis();
        mapper.writeValue(outFile, payload);
        long tw2 = System.currentTimeMillis();
        long fileSize = outFile.length();
        long actualTokens = fileSize / 4;

        long totalMs = System.currentTimeMillis() - t0;

        System.out.println("===== AI 分析文件已生成 =====");
        System.out.println("  文件:     " + outFile.getAbsolutePath());
        System.out.println("  大小:     " + formatSize(fileSize));
        System.out.println("  交易对:   " + symbol);
        System.out.println("  时间范围: " + dataFrom + " ~ " + dataTo);
        System.out.println("  导出行数: " + actualTotal);
        System.out.println("  周期数:   " + payload.getTimeframes().size());
        System.out.println("  最新价:   " + globalLatestPrice);
        System.out.println("  估算Token: ~" + formatToken(actualTokens));
        System.out.println("  写入耗时: " + (tw2 - tw1) + "ms");
        System.out.println("  总耗时:   " + totalMs + "ms");
    }

    // ---- 内部辅助 ----

    private static List<AIAnalysisPayload.AIAnalysisRow> buildRows(
            KlineStore store, String symbol, String interval,
            long startTimeMs, long endTimeMs) {

        int kCount = store.countKlines(symbol, interval);
        if (kCount == 0) return null;
        List<Kline> klines = store.getKlines(symbol, interval, kCount);

        if (startTimeMs > 0) {
            List<Kline> filtered = new ArrayList<>();
            for (Kline k : klines) {
                long ot = k.getOpenTime();
                if (ot >= startTimeMs && ot <= endTimeMs) filtered.add(k);
            }
            klines = filtered;
            if (klines.isEmpty()) return null;
        }

        long fromSec = klines.get(0).getOpenTime() / 1000;
        long toSec = klines.get(klines.size() - 1).getOpenTime() / 1000;
        List<MACDVPoint> macdvPoints = store.getMACDVPointsRange(symbol, interval, fromSec, toSec);
        if (macdvPoints.isEmpty()) return null;

        Map<Long, MACDVPoint> macdvMap = new LinkedHashMap<>();
        for (MACDVPoint p : macdvPoints) macdvMap.put(p.getTime(), p);

        List<AIAnalysisPayload.AIAnalysisRow> rows = new ArrayList<>(klines.size());
        double prevMacdv = Double.NaN, prevSignal = Double.NaN;

        for (Kline k : klines) {
            AIAnalysisPayload.AIAnalysisRow row = new AIAnalysisPayload.AIAnalysisRow();
            long ts = k.getOpenTime() / 1000;
            row.setTime(ts);
            row.setOpen(round2(k.getOpen().doubleValue()));
            row.setHigh(round2(k.getHigh().doubleValue()));
            row.setLow(round2(k.getLow().doubleValue()));
            row.setClose(round2(k.getClose().doubleValue()));
            row.setVolume(round2(k.getVolume().doubleValue()));

            MACDVPoint mp = macdvMap.get(ts);
            if (mp != null && mp.isValid()) {
                double mv = round2(mp.getMacdV().doubleValue());
                double sg = round2(mp.getSignal().doubleValue());
                row.setMacdv(mv);
                row.setSignal(sg);
                row.setHist(round2(mp.getHist().doubleValue()));
                if (!Double.isNaN(prevMacdv) && !Double.isNaN(prevSignal)) {
                    if (prevMacdv <= prevSignal && mv > sg) row.setGoldenCross(true);
                    if (prevMacdv >= prevSignal && mv < sg) row.setDeathCross(true);
                }
                prevMacdv = mv;
                prevSignal = sg;
            }
            rows.add(row);
        }

        AIAnalysisPayload.AIAnalysisRow last = rows.get(rows.size() - 1);
        System.out.println("  [" + symbol + "/" + interval + "] "
                + rows.size() + " 行, 最新价=" + last.getClose()
                + ", MACD-V=" + (last.getMacdv() != null ? last.getMacdv() : "N/A"));
        return rows;
    }

    private static String formatTimestamp(long epochSec) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
                .withZone(ZoneId.of("Asia/Shanghai")).format(Instant.ofEpochSecond(epochSec));
    }

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }

    private static String formatToken(long tokens) {
        if (tokens < 1000) return tokens + "";
        if (tokens < 1_000_000) return String.format("%.1fK", tokens / 1000.0);
        return String.format("%.1fM", tokens / 1_000_000.0);
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    // =========================================================================
    //   基础设施
    // =========================================================================

    private static KlineStore createStore() {
        String userDir = System.getProperty("user.dir");
        String dbDir = new File(userDir).getName().equals("qw-agent-line")
                ? userDir + "/data"
                : userDir + "/qw-agent-line/data";
        new File(dbDir).mkdirs();
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbDir + "/agent-line.db");
        return new KlineStore(ds);
    }

    private static MACDVService createService(KlineStore store) {
        return new MACDVService(new MACDVCalculator(), new MACDVSignalGenerator(), store);
    }
}
