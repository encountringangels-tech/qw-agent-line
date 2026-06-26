package com.qw.agent.line.backtesting;

import com.qw.agent.line.macd.indicator.MACDVCalculator;
import com.qw.agent.line.macd.model.Kline;
import com.qw.agent.line.macd.model.MACDVPoint;
import com.qw.agent.line.macd.model.TradeDecision;
import com.qw.agent.line.macd.strategy.MultiTimeframeStrategy;
import com.qw.agent.line.store.KlineStore;
import com.qw.agent.line.util.DbPathUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 多周期 MACDV 策略回测引擎 —— 在 Backtesting.main() 中独立运行。
 *
 * <h3>执行流程</h3>
 * <ol>
 *   <li>从主 SQLite 库（agent-line.db）的 {@code kline} 表查出 5 个周期的全部历史 K 线</li>
 *   <li>为每个周期预计算 MACDV 指标（复用 {@link MACDVCalculator}）</li>
 *   <li>新建一个独立的回测 SQLite 库文件（如 {@code backtest-BTCUSDT-15m.db}），
 *       含 {@code kline} / {@code macdv_point} 表</li>
 *   <li>按时间正序遍历 15min K 线。每一步：</li>
 *   <ol type="a">
 *     <li>将当前 15min K 线 + MACDV 写入回测库</li>
 *     <li>将其他周期「在当前 15min 收盘时已完成」的数据写入回测库</li>
 *     <li>有持仓 → 调 {@link MultiTimeframeStrategy#shouldCloseLong} 判断平仓</li>
 *     <li>空仓 → 调 {@link MultiTimeframeStrategy#decide(String)} 判断开仓</li>
 *   </ol>
 *   <li>开仓信号**延迟一根 K 线**执行（以 {@code K[i+1].open} 入场），避免未来函数</li>
 *   <li>结束后生成 Markdown 回测报告</li>
 * </ol>
 *
 * <p><b>注意</b>：本类不启动 Spring Boot，手动创建所需组件，
 * 避免 {code BTCBotTask} 等定时任务干扰回测。</p>
 */
public class Backtesting {

    private static final Logger log = LoggerFactory.getLogger(Backtesting.class);

    /** 策略依赖的 5 个周期（从短到长） */
    private static final String[] TIMEFRAMES = {"5m", "15m", "1h", "4h", "1d"};

    /** 各周期毫秒值，用于判断 candle 是否已完成 */
    private static final Map<String, Long> INTERVAL_MS = Map.of(
            "5m",  300_000L,
            "15m", 900_000L,
            "1h",  3_600_000L,
            "4h",  14_400_000L,
            "1d",  86_400_000L
    );

    /** 核心决策周期 */
    private static final String CORE_TF = "15m";

    /** MACDV 计算参数 */
    private static final int FAST_LEN = 12;
    private static final int SLOW_LEN = 26;
    private static final int SIGNAL_LEN = 9;
    private static final int ATR_LEN = 14;

    /** 平仓后冷却 K 线数（与策略 COOLDOWN_BARS 一致） */
    private static final int COOLDOWN_BARS = 3;

    private Backtesting() {
    }

    // ========================================================================
    //  入口
    // ========================================================================

    /**
     * 用法：Backtesting [symbol] [initialCapital]
     * <pre>
     *   java Backtesting BTCUSDT 100000
     *   java Backtesting ETHUSDT  50000
     * </pre>
     */
    public static void main(String[] args) {
        String symbol = args.length > 0 ? args[0] : "BTCUSDT";
        double initialCapital = args.length > 1 ? Double.parseDouble(args[1]) : 100_000;

        System.out.println("╔══════════════════════════════════════════════╗");
        System.out.println("║     MACD-V 多周期回测引擎");
        System.out.println("║     交易对: " + symbol);
        System.out.println("║     初始资金: $" + String.format("%,.0f", initialCapital));
        System.out.println("║     核心周期: " + CORE_TF);
        System.out.println("╚══════════════════════════════════════════════╝");
        System.out.println();

        try {
            // 1. 创建主库数据源（读取原始 K 线）
            DataSource mainDs = createMainDataSource();
            KlineStore mainStore = new KlineStore(mainDs);
            MACDVCalculator calculator = new MACDVCalculator();

            // 2. 查询所有周期 K 线 + 预计算 MACDV
            System.out.println("▶ 加载数据...");
            Map<String, List<Kline>> allKlines = new LinkedHashMap<>();
            Map<String, List<MACDVPoint>> allMacdv = new LinkedHashMap<>();

            for (String tf : TIMEFRAMES) {
                List<Kline> klines = mainStore.getKlines(symbol, tf, Integer.MAX_VALUE);
                if (klines.isEmpty()) {
                    System.err.println("❌ [" + tf + "] 无数据，请先同步 K 线");
                    return;
                }
                allKlines.put(tf, klines);
                allMacdv.put(tf, calculator.calculate(klines, FAST_LEN, SLOW_LEN, SIGNAL_LEN, ATR_LEN));
                System.out.printf("  [%s] %d 根 K 线, %d 个 MACDV 点\n",
                        tf, klines.size(), allMacdv.get(tf).size());
            }

            // 3. 清理旧回测数据 + 创建回测库
            System.out.println("\n▶ 清理旧回测数据...");
            clearOldBacktestData(symbol);
            System.out.println("▶ 创建回测数据库...");
            DataSource btDs = createBacktestDataSource(symbol);
            KlineStore btStore = new KlineStore(btDs);
            JdbcTemplate btJdbc = new JdbcTemplate(btDs);
            createBacktestTradeTable(btJdbc);

            // 4. 创建策略（指向回测库）
            MultiTimeframeStrategy btStrategy = new MultiTimeframeStrategy(btStore);

            // 5. 运行回测
            System.out.println("\n▶ 运行回测...");
            BacktestResult result = run(symbol, allKlines, allMacdv, btJdbc, btStrategy, initialCapital);

            // 6. 生成报告
            System.out.println("\n▶ 生成回测报告...");
            String report = generateMarkdownReport(result, symbol, initialCapital);
            System.out.println(report);

            // 7. 保存
            saveReport(report, symbol);

            System.out.printf("\n✅ 回测完成！共 %d 笔交易，收益率 %+.2f%%\n",
                    result.trades.size(), result.returnPct);

        } catch (Exception e) {
            log.error("回测失败", e);
            System.err.println("❌ 回测失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ========================================================================
    //  回测核心
    // ========================================================================

    /**
     * 回测主循环。
     * <p>
     * 每一步：写数据 → 平仓检查 → 执行待入场 → 开仓检查。
     */
    private static BacktestResult run(String symbol,
                                       Map<String, List<Kline>> allKlines,
                                       Map<String, List<MACDVPoint>> allMacdv,
                                       JdbcTemplate btJdbc,
                                       MultiTimeframeStrategy strategy,
                                       double capital) {

        List<Kline> m15List = allKlines.get("15m");
        List<MACDVPoint> m15Macdv = allMacdv.get("15m");

        // 找第一个有效 MACDV 点作为起点
        int startIdx = 0;
        for (int i = 0; i < m15Macdv.size(); i++) {
            if (m15Macdv.get(i).isValid()) {
                startIdx = i;
                break;
            }
        }

        // 各周期指针（已写入回测库的最大索引）
        int[] ptrs = new int[TIMEFRAMES.length];
        Arrays.fill(ptrs, -1);

        // 状态
        String position = "FLAT";
        PendingEntry pending = null;
        TradeRecord currentTrade = null;
        List<TradeRecord> trades = new ArrayList<>();

        double equity = capital;
        double peak = capital;
        double maxDrawdown = 0;
        int cooldown = 0;

        int totalSteps = m15List.size() - startIdx;
        int lastPct = -10;

        for (int i = startIdx; i < m15List.size(); i++) {
            Kline m15Kline = m15List.get(i);
            MACDVPoint m15Point = m15Macdv.get(i);
            long currentTimeMs = m15Kline.getOpenTime();
            long currentTimeSec = currentTimeMs / 1000;

            // ----- 进度 -----
            int pct = (i - startIdx) * 100 / Math.max(1, totalSteps);
            if (pct >= lastPct + 10) {
                System.out.printf("  进度 %d%% (第 %d/%d 根 15min K 线)\n",
                        pct, i - startIdx + 1, totalSteps);
                lastPct = pct;
            }

            // ======================================================
            //  Step A: 写入当前 15min 数据到回测库
            // ======================================================
            insertKline(btJdbc, symbol, "15m", m15Kline);
            insertMacdvPoint(btJdbc, symbol, "15m", m15Point);

            // ======================================================
            //  Step B: 推进其他周期指针，写入已完成的 K 线 + MACDV
            // ======================================================
            long currentWindowEndMs = currentTimeMs + INTERVAL_MS.get("15m");
            for (int tfIdx = 0; tfIdx < TIMEFRAMES.length; tfIdx++) {
                String tf = TIMEFRAMES[tfIdx];
                if ("15m".equals(tf)) continue;

                List<Kline> tfKlines = allKlines.get(tf);
                List<MACDVPoint> tfMacdv = allMacdv.get(tf);

                while (ptrs[tfIdx] + 1 < tfKlines.size()) {
                    int nextIdx = ptrs[tfIdx] + 1;
                    Kline nextK = tfKlines.get(nextIdx);
                    // 该 K 线完成时间 = open_time + interval
                    long candleEndMs = nextK.getOpenTime() + INTERVAL_MS.get(tf);
                    if (candleEndMs <= currentWindowEndMs) {
                        ptrs[tfIdx] = nextIdx;
                        insertKline(btJdbc, symbol, tf, nextK);

                        MACDVPoint mp = tfMacdv.get(nextIdx);
                        if (mp != null && mp.isValid()) {
                            insertMacdvPoint(btJdbc, symbol, tf, mp);
                        }
                    } else {
                        break;
                    }
                }
            }

            // ======================================================
            //  Step C: 平仓检查（先于入场，同一根 K 线不同时出入）
            // ======================================================
            if ("LONG".equals(position) && currentTrade != null) {
                if (strategy.shouldCloseLong(symbol)) {
                    double exitPrice = m15Kline.getClose().doubleValue();
                    double pnl = calcPnl("LONG", currentTrade.entryPrice, exitPrice,
                            currentTrade.leverage, equity);
                    equity += pnl;
                    if (equity > peak) peak = equity;
                    double dd = (peak - equity) / peak * 100;
                    if (dd > maxDrawdown) maxDrawdown = dd;

                    currentTrade.exitTime = currentTimeSec;
                    currentTrade.exitPrice = exitPrice;
                    currentTrade.pnl = pnl;
                    currentTrade.exitReason = "策略平仓";
                    trades.add(currentTrade);
                    currentTrade = null;
                    position = "FLAT";
                    cooldown = COOLDOWN_BARS;
                    strategy.clearPositionState(symbol);
                }
            } else if ("SHORT".equals(position) && currentTrade != null) {
                if (strategy.shouldCloseShort(symbol)) {
                    double exitPrice = m15Kline.getClose().doubleValue();
                    double pnl = calcPnl("SHORT", currentTrade.entryPrice, exitPrice,
                            currentTrade.leverage, equity);
                    equity += pnl;
                    if (equity > peak) peak = equity;
                    double dd = (peak - equity) / peak * 100;
                    if (dd > maxDrawdown) maxDrawdown = dd;

                    currentTrade.exitTime = currentTimeSec;
                    currentTrade.exitPrice = exitPrice;
                    currentTrade.pnl = pnl;
                    currentTrade.exitReason = "策略平仓";
                    trades.add(currentTrade);
                    currentTrade = null;
                    position = "FLAT";
                    cooldown = COOLDOWN_BARS;
                    strategy.clearPositionState(symbol);
                }
            }

            // ======================================================
            //  Step D: 执行待入场（上一根 K 线生成的信号，在本根开盘价执行）
            // ======================================================
            if (pending != null) {
                double entryPrice = m15Kline.getOpen().doubleValue();
                currentTrade = new TradeRecord();
                currentTrade.direction = pending.direction;
                currentTrade.entryTime = currentTimeSec;
                currentTrade.entryPrice = entryPrice;
                currentTrade.leverage = pending.leverage;
                currentTrade.score = pending.score;
                currentTrade.entryReason = pending.reason;
                position = pending.direction;
                pending = null;

                strategy.resetPositionState(symbol);
            }

            // ======================================================
            //  Step E: 空仓时检查新信号
            // ======================================================
            if ("FLAT".equals(position) && cooldown <= 0) {
                TradeDecision dec = strategy.decide(symbol);
                String action = dec.getAction();
                if ("LONG".equals(action) || "SHORT".equals(action)) {
                    int score = (int) Math.round(dec.getConfidence() * 100);
                    pending = new PendingEntry(action, dec.getLeverage(), score, dec.getReason());
                }
            }

            if (cooldown > 0) cooldown--;
        }

        // ---- 强制平仓 ----
        if (currentTrade != null) {
            Kline lastKline = m15List.get(m15List.size() - 1);
            double exitPrice = lastKline.getClose().doubleValue();
            double pnl = calcPnl(currentTrade.direction, currentTrade.entryPrice,
                    exitPrice, currentTrade.leverage, equity);
            equity += pnl;
            if (equity > peak) peak = equity;
            currentTrade.exitTime = lastKline.getOpenTime() / 1000;
            currentTrade.exitPrice = exitPrice;
            currentTrade.pnl = pnl;
            currentTrade.exitReason = "回测结束";
            trades.add(currentTrade);
        }

        return computeStats(trades, capital, equity, peak, maxDrawdown);
    }

    // ========================================================================
    //  内部数据类
    // ========================================================================

    private static class PendingEntry {
        final String direction;
        final int leverage;
        final int score;
        final String reason;

        PendingEntry(String direction, int leverage, int score, String reason) {
            this.direction = direction;
            this.leverage = leverage;
            this.score = score;
            this.reason = reason;
        }
    }

    private static class TradeRecord {
        String direction;
        long entryTime;
        long exitTime;
        double entryPrice;
        double exitPrice;
        int leverage;
        int score;
        double pnl;
        String entryReason;
        String exitReason;
    }

    /** 回测统计结果 */
    private static class BacktestResult {
        double initialCapital;
        double finalCapital;
        double totalReturn;
        double returnPct;
        int totalTrades;
        int wins;
        int losses;
        double winRate;
        double maxWin;
        double maxLoss;
        double avgWin;
        double avgLoss;
        double profitFactor;
        double maxDrawdown;
        double peakCapital;
        List<TradeRecord> trades;

        int longCount;
        double longPnl;
        double longWr;
        int shortCount;
        double shortPnl;
        double shortWr;
        int lever2Count;
        double lever2Pnl;
        int lever3Count;
        double lever3Pnl;

        Map<String, Integer> exitReasons = new LinkedHashMap<>();
        Map<Integer, ScoreGroup> byScore = new TreeMap<>();
    }

    private static class ScoreGroup {
        int count;
        int wins;
        double pnl;
    }

    // ========================================================================
    //  盈亏计算
    // ========================================================================

    /** 复利计算：PnL = 当前权益 × (价格变动%) × 杠杆 */
    private static double calcPnl(String direction, double entry, double exit,
                                   int leverage, double currentEquity) {
        double priceReturn = "LONG".equals(direction) ? (exit / entry - 1) : (1 - exit / entry);
        return currentEquity * priceReturn * leverage;
    }

    // ========================================================================
    //  统计
    // ========================================================================

    private static BacktestResult computeStats(List<TradeRecord> trades,
                                                double initial, double finalCapital,
                                                double peak, double maxDD) {
        BacktestResult r = new BacktestResult();
        r.initialCapital = initial;
        r.finalCapital = finalCapital;
        r.totalReturn = finalCapital - initial;
        r.returnPct = (finalCapital - initial) / initial * 100;
        r.totalTrades = trades.size();
        r.trades = trades;
        r.peakCapital = peak;
        r.maxDrawdown = maxDD;

        List<TradeRecord> winList = new ArrayList<>();
        List<TradeRecord> loseList = new ArrayList<>();

        for (TradeRecord t : trades) {
            if (t.pnl >= 0) winList.add(t);
            else loseList.add(t);

            if ("LONG".equals(t.direction)) {
                r.longCount++;
                r.longPnl += t.pnl;
            } else {
                r.shortCount++;
                r.shortPnl += t.pnl;
            }

            if (t.leverage == 2) {
                r.lever2Count++;
                r.lever2Pnl += t.pnl;
            } else if (t.leverage >= 3) {
                r.lever3Count++;
                r.lever3Pnl += t.pnl;
            }

            String reason = t.exitReason != null ? t.exitReason.split("[ (]")[0] : "未知";
            r.exitReasons.merge(reason, 1, Integer::sum);

            r.byScore.computeIfAbsent(t.score, k -> new ScoreGroup());
            ScoreGroup sg = r.byScore.get(t.score);
            sg.count++;
            sg.pnl += t.pnl;
            if (t.pnl >= 0) sg.wins++;
        }

        r.wins = winList.size();
        r.losses = loseList.size();
        r.winRate = trades.isEmpty() ? 0 : (double) winList.size() / trades.size() * 100;
        r.maxWin = winList.isEmpty() ? 0 : winList.stream().mapToDouble(t -> t.pnl).max().orElse(0);
        r.maxLoss = loseList.isEmpty() ? 0 : loseList.stream().mapToDouble(t -> t.pnl).min().orElse(0);
        r.avgWin = winList.isEmpty() ? 0 : winList.stream().mapToDouble(t -> t.pnl).sum() / winList.size();
        r.avgLoss = loseList.isEmpty() ? 0 : loseList.stream().mapToDouble(t -> t.pnl).sum() / loseList.size();

        double totalWin = winList.stream().mapToDouble(t -> t.pnl).sum();
        double totalLoss = Math.abs(loseList.stream().mapToDouble(t -> t.pnl).sum());
        r.profitFactor = totalLoss == 0 ? (totalWin > 0 ? Double.POSITIVE_INFINITY : 0) : totalWin / totalLoss;

        r.longWr = r.longCount == 0 ? 0 :
                (double) trades.stream().filter(t -> "LONG".equals(t.direction) && t.pnl >= 0).count()
                        / r.longCount * 100;
        r.shortWr = r.shortCount == 0 ? 0 :
                (double) trades.stream().filter(t -> "SHORT".equals(t.direction) && t.pnl >= 0).count()
                        / r.shortCount * 100;

        return r;
    }

    // ========================================================================
    //  数据库操作
    // ========================================================================

    /** 获取回测库文件路径 */
    private static String getBacktestDbPath(String symbol) {
        File dataDir = DbPathUtil.ensureDataDir();
        String dbName = String.format("backtest-%s-%s.db", symbol, CORE_TF);
        return new File(dataDir, dbName).getAbsolutePath();
    }

    /** 删除旧的回测数据库文件（确保每次回测从零开始） */
    private static void clearOldBacktestData(String symbol) {
        String dbPath = getBacktestDbPath(symbol);
        File dbFile = new File(dbPath);
        if (dbFile.exists()) {
            if (dbFile.delete()) {
                log.info("已删除旧回测库: {}", dbPath);
            } else {
                log.warn("无法删除旧回测库: {}", dbPath);
            }
        } else {
            log.info("无旧回测库需要清理");
        }
    }

    /** 创建主库数据源（读取原始 K 线） */
    private static DataSource createMainDataSource() {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl(DbPathUtil.getJdbcUrl());
        log.info("主库: {}", DbPathUtil.getDbPath());
        return ds;
    }

    /** 创建回测库数据源（独立的 SQLite 文件） */
    private static DataSource createBacktestDataSource(String symbol) {
        String dbPath = getBacktestDbPath(symbol);
        log.info("回测库: {}", dbPath);

        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbPath);
        return ds;
    }

    /** 创建回测交易记录表 */
    private static void createBacktestTradeTable(JdbcTemplate jdbc) {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS bt_trade (
                id           INTEGER PRIMARY KEY AUTOINCREMENT,
                symbol       TEXT    NOT NULL,
                direction    TEXT    NOT NULL,
                entry_time   INTEGER NOT NULL,
                entry_price  REAL    NOT NULL,
                exit_time    INTEGER,
                exit_price   REAL,
                leverage     INTEGER NOT NULL DEFAULT 2,
                score        INTEGER NOT NULL DEFAULT 0,
                pnl          REAL,
                entry_reason TEXT,
                exit_reason  TEXT,
                created_at   TEXT NOT NULL DEFAULT (datetime('now','localtime'))
            )
        """);
    }

    /** 插入一条 K 线到回测库 */
    private static void insertKline(JdbcTemplate j, String symbol, String interval, Kline k) {
        j.update("""
            INSERT OR REPLACE INTO kline (symbol, interval, open_time, open, high, low, close, volume)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """, symbol, interval, k.getOpenTime(),
                k.getOpen().doubleValue(), k.getHigh().doubleValue(),
                k.getLow().doubleValue(), k.getClose().doubleValue(),
                k.getVolume().doubleValue());
    }

    /** 插入一条 MACDV 点到回测库 */
    private static void insertMacdvPoint(JdbcTemplate j, String symbol, String interval, MACDVPoint p) {
        j.update("""
            INSERT OR REPLACE INTO macdv_point (symbol, interval, time, macdV, signal, hist)
            VALUES (?, ?, ?, ?, ?, ?)
        """, symbol, interval, p.getTime(),
                p.getMacdV() != null ? p.getMacdV().doubleValue() : null,
                p.getSignal() != null ? p.getSignal().doubleValue() : null,
                p.getHist() != null ? p.getHist().doubleValue() : null);
    }

    // ========================================================================
    //  Markdown 报告
    // ========================================================================

    private static final DateTimeFormatter DTF =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private static String generateMarkdownReport(BacktestResult r, String symbol, double initialCapital) {
        StringBuilder md = new StringBuilder();

        md.append("# ").append(symbol).append(" 多周期 MACDV 回测报告\n\n");
        md.append("> **生成时间**: ").append(DTF.format(Instant.now())).append("\n");
        md.append("> **策略**: MultiTimeframeStrategy（日线/4H/1H/15min/5min 五周期联动）\n");
        md.append("> **核心周期**: ").append(CORE_TF).append("\n");
        md.append("> **初始资金**: $").append(String.format("%,.0f", initialCapital)).append("\n");
        md.append("> **MACDV 参数**: fast=").append(FAST_LEN).append(" slow=").append(SLOW_LEN)
                .append(" signal=").append(SIGNAL_LEN).append(" atr=").append(ATR_LEN).append("\n");
        md.append("> **开仓规则**: 信号延迟 1 根 K 线，以下一根开盘价入场\n");
        md.append("> **平仓规则**: 以当前 K 线收盘价出场\n\n");
        md.append("---\n\n");

        // ---- 核心指标 ----
        md.append("## 回测结果\n\n");
        md.append("| 指标 | 数值 |\n");
        md.append("|:---|---:|\n");
        md.append("| 最终资金 | $").append(fmt(r.finalCapital)).append(" |\n");
        md.append("| 总收益 | $").append(fmt(r.totalReturn))
                .append("（**").append(fmtPct(r.returnPct)).append("**） |\n");
        md.append("| 总交易数 | ").append(r.totalTrades).append(" |\n");
        md.append("| 盈利/亏损 | ").append(r.wins).append("胜 / ").append(r.losses)
                .append("负（胜率**").append(fmtPct(r.winRate)).append("**） |\n");
        md.append("| 最大单笔盈利 | $").append(fmt(r.maxWin)).append(" |\n");
        md.append("| 最大单笔亏损 | $").append(fmt(r.maxLoss)).append(" |\n");
        md.append("| 平均盈利/亏损 | $").append(fmt(r.avgWin)).append(" / $").append(fmt(r.avgLoss)).append(" |\n");
        md.append("| 盈亏比 | ").append(Double.isInfinite(r.profitFactor) ? "∞" :
                String.format("%.2f", r.profitFactor)).append(" |\n");
        md.append("| 最大回撤 | **").append(String.format("%.2f", r.maxDrawdown)).append("%** |\n");
        md.append("| 峰值资金 | $").append(fmt(r.peakCapital)).append(" |\n\n");
        md.append("---\n\n");

        // ---- 方向与杠杆 ----
        md.append("## 方向与杠杆\n\n");
        md.append("| 方向 | 次数 | 总盈亏 | 胜率 |\n");
        md.append("|:---|:---:|:---:|:---:|\n");
        md.append("| LONG | ").append(r.longCount).append(" | $").append(fmt(r.longPnl))
                .append(" | ").append(fmtPct(r.longWr)).append(" |\n");
        md.append("| SHORT | ").append(r.shortCount).append(" | $").append(fmt(r.shortPnl))
                .append(" | ").append(fmtPct(r.shortWr)).append(" |\n\n");

        md.append("| 杠杆 | 次数 | 总盈亏 |\n");
        md.append("|:---|:---:|:---:|\n");
        md.append("| 2x | ").append(r.lever2Count).append(" | $").append(fmt(r.lever2Pnl)).append(" |\n");
        md.append("| 3x | ").append(r.lever3Count).append(" | $").append(fmt(r.lever3Pnl)).append(" |\n\n");
        md.append("---\n\n");

        // ---- 评分分布 ----
        md.append("## 评分与胜率\n\n");
        md.append("| 评分 | 次数 | 总盈亏 | 胜率 |\n");
        md.append("|:---:|:---:|:---:|:---:|\n");
        for (Map.Entry<Integer, ScoreGroup> e : r.byScore.entrySet()) {
            ScoreGroup sg = e.getValue();
            double wr = sg.count > 0 ? (double) sg.wins / sg.count * 100 : 0;
            md.append("| ").append(e.getKey()).append(" | ").append(sg.count)
                    .append(" | $").append(fmt(sg.pnl))
                    .append(" | ").append(fmtPct(wr)).append(" |\n");
        }
        md.append("\n---\n\n");

        // ---- 出场原因 ----
        md.append("## 出场原因分布\n\n");
        md.append("| 原因 | 次数 |\n");
        md.append("|:---|:---:|\n");
        r.exitReasons.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> md.append("| ").append(e.getKey()).append(" | ").append(e.getValue()).append(" |\n"));
        md.append("\n---\n\n");

        // ---- 交易明细 ----
        md.append("## 完整交易记录\n\n");
        md.append("| # | 入场时间 | 方向 | 入场价 | 出场价 | 杠杆 | 盈亏 | 评分 | 入场理由 | 出场理由 |\n");
        md.append("|:---|:---|:---|:---|:---|:---:|:---:|:---:|:---|:---|\n");
        for (int i = 0; i < r.trades.size(); i++) {
            TradeRecord t = r.trades.get(i);
            String dir = "LONG".equals(t.direction) ? "L" : "S";
            String sign = t.pnl >= 0 ? "+" : "";
            md.append("| ").append(i + 1)
                    .append(" | ").append(ts(t.entryTime))
                    .append(" | ").append(dir)
                    .append(" | ").append(String.format("%.0f", t.entryPrice))
                    .append(" | ").append(String.format("%.0f", t.exitPrice))
                    .append(" | ").append(t.leverage).append("x")
                    .append(" | ").append(sign).append("$").append(String.format("%.0f", t.pnl))
                    .append(" | ").append(t.score)
                    .append(" | ").append(trunc(t.entryReason, 50))
                    .append(" | ").append(t.exitReason)
                    .append(" |\n");
        }

        md.append("\n---\n\n");
        md.append("*由 Backtesting.java 生成 | 核心周期 ").append(CORE_TF)
                .append(" | 延迟一根K线执行（无未来函数） | 复利+杠杆 2x/3x*\n");

        return md.toString();
    }

    // ========================================================================
    //  格式化工具
    // ========================================================================

    private static String fmt(double v) {
        if (Math.abs(v) >= 1_000_000) return String.format("%.2fM", v / 1_000_000);
        if (Math.abs(v) >= 1_000) return String.format("%.2fK", v / 1_000);
        return String.format("%.2f", v);
    }

    private static String fmtPct(double v) {
        return String.format("%+.2f", v) + "%";
    }

    private static String ts(long unixSec) {
        return DTF.format(Instant.ofEpochSecond(unixSec));
    }

    private static String trunc(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "…" : s;
    }

    /** 保存 Markdown 报告到 data 目录 */
    private static void saveReport(String report, String symbol) {
        File dataDir = DbPathUtil.ensureDataDir();
        String fileName = String.format("backtest-report-%s-%s.md", symbol, CORE_TF);
        File reportFile = new File(dataDir, fileName);
        try (FileWriter fw = new FileWriter(reportFile)) {
            fw.write(report);
            System.out.println("\n📄 报告已保存: " + reportFile.getAbsolutePath());
        } catch (IOException e) {
            log.warn("保存报告失败: {}", e.getMessage());
        }
    }
}
