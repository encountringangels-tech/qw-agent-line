package test.test;

import com.qw.agent.line.macd.model.TradeSignalRecord;
import com.qw.agent.line.store.KlineStore;
import org.sqlite.SQLiteDataSource;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

/**
 * 交易盈亏计算器 —— 基于 trade_signal 表中的买卖信号模拟实际盈亏。
 *
 * <pre>
 *   java test.TradePnlCalculator 100000 BTCUSDT
 *   java test.TradePnlCalculator 50000 ETHUSDT
 *   java test.TradePnlCalculator 100000          (默认 BTCUSDT)
 * </pre>
 *
 * <h3>模拟规则</h3>
 * <ul>
 *   <li>开多(LONG)：全部资金按信号价买入</li>
 *   <li>开空(SHORT)：全部资金按信号价做空</li>
 *   <li>平仓(CLOSE)：按信号价平仓，结算盈亏</li>
 *   <li>仓位为 FLAT 时忽略 CLOSE 信号</li>
 * </ul>
 */
public class TradePnlCalculator {

    public static void main(String[] args) {
        double initialCapital = 100_000;
        String symbol = "BTCUSDT";

        if (args.length >= 1) initialCapital = Double.parseDouble(args[0]);
        if (args.length >= 2) symbol = args[1].toUpperCase();

        KlineStore store = createStore();
        List<TradeSignalRecord> signals = store.getTradeSignals(symbol);

        if (signals.isEmpty()) {
            System.out.println("[" + symbol + "] trade_signal 表无数据");
            return;
        }

        System.out.println("===== 盈亏模拟 [" + symbol + "] =====");
        System.out.println("初始资金: " + formatMoney(initialCapital));
        System.out.println("信号总数: " + signals.size());
        System.out.println();

        // ---- 模拟 ----
        double capital = initialCapital;
        String position = "FLAT";
        double entryPrice = 0;
        double quantity = 0;
        int entryScore = 0;
        long entryTime = 0;

        List<TradeRecord> trades = new ArrayList<>();

        double maxCapital = capital;
        double minCapital = capital;

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        sdf.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));

        for (TradeSignalRecord sig : signals) {
            double price = sig.getPrice().doubleValue();
            String dir = sig.getDirection();
            long t = sig.getTime();

            if ("FLAT".equals(position)) {
                if ("LONG".equals(dir)) {
                    entryPrice = price;
                    quantity = capital / price;
                    entryScore = sig.getScore();
                    entryTime = t;
                    position = "LONG";
                    System.out.printf("[%s] 🔵 开多  价格=%.2f  数量=%.4f  评分=%d  %s%n",
                            sdf.format(new Date(t * 1000)), price, quantity,
                            sig.getScore(), sig.getReason());
                } else if ("SHORT".equals(dir)) {
                    entryPrice = price;
                    quantity = capital / price;
                    entryScore = sig.getScore();
                    entryTime = t;
                    position = "SHORT";
                    System.out.printf("[%s] 🔴 开空  价格=%.2f  数量=%.4f  评分=%d  %s%n",
                            sdf.format(new Date(t * 1000)), price, quantity,
                            sig.getScore(), sig.getReason());
                }
                // CLOSE while FLAT → 忽略

            } else if ("LONG".equals(position) && "CLOSE".equals(dir)) {
                double newCapital = quantity * price;
                double pnl = newCapital - capital;
                capital = newCapital;

                TradeRecord tr = new TradeRecord();
                tr.type = "做多";
                tr.entryTime = entryTime;
                tr.exitTime = t;
                tr.entryPrice = entryPrice;
                tr.exitPrice = price;
                tr.pnl = pnl;
                tr.score = entryScore;
                trades.add(tr);

                String emoji = pnl >= 0 ? "🟢" : "🔴";
                System.out.printf("[%s] %s 平多  入场=%.2f  出场=%.2f  PnL=%+.2f  累计=%.2f%n",
                        sdf.format(new Date(t * 1000)), emoji,
                        entryPrice, price, pnl, capital);

                position = "FLAT";
                maxCapital = Math.max(maxCapital, capital);
                minCapital = Math.min(minCapital, capital);

            } else if ("SHORT".equals(position) && "CLOSE".equals(dir)) {
                double pnl = quantity * (entryPrice - price);
                double newCapital = capital + pnl;
                capital = newCapital;

                TradeRecord tr = new TradeRecord();
                tr.type = "做空";
                tr.entryTime = entryTime;
                tr.exitTime = t;
                tr.entryPrice = entryPrice;
                tr.exitPrice = price;
                tr.pnl = pnl;
                tr.score = entryScore;
                trades.add(tr);

                String emoji = pnl >= 0 ? "🟢" : "🔴";
                System.out.printf("[%s] %s 平空  入场=%.2f  出场=%.2f  PnL=%+.2f  累计=%.2f%n",
                        sdf.format(new Date(t * 1000)), emoji,
                        entryPrice, price, pnl, capital);

                position = "FLAT";
                maxCapital = Math.max(maxCapital, capital);
                minCapital = Math.min(minCapital, capital);
            }
            // 持仓中遇到同向开仓信号 → 忽略（保持原仓位）
            // 持仓中遇到反向开仓信号 → 先平仓再开反向（state machine 已保证不会出现）
        }

        // ---- 未平仓处理 ----
        if (!"FLAT".equals(position)) {
            System.out.println();
            System.out.println("⚠ 最后仓位未平仓: " + position + " @" + entryPrice);
        }

        // ---- 汇总 ----
        double totalReturn = capital - initialCapital;
        double returnPct = totalReturn / initialCapital * 100;

        System.out.println();
        System.out.println("========== 交易总结 ==========");
        System.out.printf("  初始资金: %s%n", formatMoney(initialCapital));
        System.out.printf("  最终资金: %s%n", formatMoney(capital));
        System.out.printf("  总收益:   %+.2f (%+.2f%%)%n", totalReturn, returnPct);
        System.out.println();

        int totalTrades = trades.size();
        int winTrades = 0, loseTrades = 0;
        double totalWin = 0, totalLose = 0;
        double maxWin = Double.MIN_VALUE, maxLose = Double.MAX_VALUE;

        for (TradeRecord tr : trades) {
            if (tr.pnl > 0) { winTrades++; totalWin += tr.pnl; maxWin = Math.max(maxWin, tr.pnl); }
            if (tr.pnl < 0) { loseTrades++; totalLose += tr.pnl; maxLose = Math.min(maxLose, tr.pnl); }
        }

        double winRate = totalTrades > 0 ? winTrades * 100.0 / totalTrades : 0;
        double avgWin = winTrades > 0 ? totalWin / winTrades : 0;
        double avgLose = loseTrades > 0 ? totalLose / loseTrades : 0;
        double profitFactor = (totalLose != 0) ? Math.abs(totalWin / totalLose) : (totalWin > 0 ? Double.POSITIVE_INFINITY : 0);

        System.out.println("  交易统计:");
        System.out.printf("    总交易次数: %d%n", totalTrades);
        System.out.printf("    盈利: %d 次  (%.1f%%)%n", winTrades, winRate);
        System.out.printf("    亏损: %d 次  (%.1f%%)%n", loseTrades, totalTrades > 0 ? loseTrades * 100.0 / totalTrades : 0);
        System.out.printf("    最大单笔盈利: %+.2f%n", maxWin == Double.MIN_VALUE ? 0 : maxWin);
        System.out.printf("    最大单笔亏损: %+.2f%n", maxLose == Double.MAX_VALUE ? 0 : maxLose);
        System.out.printf("    平均盈利: %+.2f  平均亏损: %+.2f%n", avgWin, avgLose);
        System.out.printf("    盈亏比(Profit Factor): %.2f%n", profitFactor);
        System.out.printf("    最大回撤: %.2f -> %.2f (%.2f%%)%n",
                maxCapital, minCapital,
                maxCapital > 0 ? (maxCapital - minCapital) / maxCapital * 100 : 0);
    }

    // ==================== 内嵌类 ====================

    static class TradeRecord {
        String type;
        long entryTime, exitTime;
        double entryPrice, exitPrice;
        double pnl;
        int score;
    }

    // ==================== 基础设施 ====================

    private static KlineStore createStore() {
        String userDir = System.getProperty("user.dir");
        File dbDir = new File(userDir).getName().equals("qw-agent-line")
                ? new File(userDir, "data")
                : new File(userDir, "qw-agent-line/data");
        dbDir.mkdirs();
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + new File(dbDir, "agent-line.db").getAbsolutePath());
        return new KlineStore(ds);
    }

    private static String formatMoney(double v) {
        if (Math.abs(v) >= 1_000_000) return String.format("%.2fM", v / 1_000_000);
        if (Math.abs(v) >= 1_000) return String.format("%.2fK", v / 1_000);
        return String.format("%.2f", v);
    }
}
