package com.qw.agent.line.task;

import com.qw.agent.line.model.Kline;
import com.qw.agent.line.model.MACDVPoint;
import com.qw.agent.line.order.OrderService;
import com.qw.agent.line.service.MACDVService;
import com.qw.agent.line.store.KlineStore;
import com.qw.agent.line.store.MultiTimeframeStrategy;
import com.qw.agent.line.store.TradeDecision;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * BTC 自动交易定时任务 —— 每 15 分钟（:00 / :15 / :30 / :45）执行一次。
 *
 * <p>执行顺序：</p>
 * <ol>
 *   <li><b>K 线同步</b> — 从币安拉取最新 K 线并写入本地库</li>
 *   <li><b>MACD-V 计算</b> — 补齐缺失的 MACD-V 数据点</li>
 *   <li><b>策略交易</b> — 基于最新数据做多/做空/平仓</li>
 * </ol>
 *
 * <p>三步合一的目的是保证 {@code strategy.decide()} 使用的 MACDV 数据是最新的，
 * 避免因外部定时任务同步滞后导致基于过期数据交易。</p>
 *
 * <p>仅支持单笔持仓，使用静态变量缓存余额和持仓状态。</p>
 */
@Component
public class BTCBotTask {

    private static final Logger log = LoggerFactory.getLogger(BTCBotTask.class);

    // ==================== 缓存变量 ====================

    /** 初始余额（USDT） */
    private static final double INITIAL_BALANCE = 1000.0;

    /** 当前可用余额，启动时加载 */
    private static double balance = 0;

    /** 当前持仓方向，null 空仓，LONG/SHORT 互斥 */
    private static String position = null;

    /** 开仓价格 */
    private static double entryPrice = 0;

    /** 开仓金额 = 余额 × 杠杆 */
    private static double positionAmount = 0;

    /** 开仓杠杆倍数 */
    private static int currentLeverage = 1;

    /** 策略交易需要同步的周期（与 MultiTimeframeStrategy 一致） */
    private static final String[] TRADE_INTERVALS = {"5m", "15m", "1h", "4h", "1d"};

    // ==================== 依赖 ====================

    private final MACDVService macdvService;
    private final KlineStore klineStore;
    private final MultiTimeframeStrategy strategy;
    private final OrderService orderService;

    public BTCBotTask(MACDVService macdvService, KlineStore klineStore,
                      MultiTimeframeStrategy strategy, OrderService orderService) {
        this.macdvService = macdvService;
        this.klineStore = klineStore;
        this.strategy = strategy;
        this.orderService = orderService;
    }

    @PostConstruct
    public void init() {
        balance = INITIAL_BALANCE;
        log.info("┌─────────────────────────────────────────────");
        log.info("│ BTC 交易机器人初始化完成");
        log.info("│   初始余额: {} USDT", formatBalance(balance));
        log.info("│   当前持仓: {}", position != null ? position : "空仓");
        log.info("│   执行周期: 每15分钟 (:00/15/30/45)");
        log.info("│   数据同步: K线 → MACDV → 交易（三步合一）");
        log.info("└─────────────────────────────────────────────");
    }

    /**
     * 每 15 分钟执行一次（:00/:15/:30/:45）。
     *
     * <h3>执行时序说明</h3>
     * <p>以 15:00 触发为例：</p>
     * <pre>
     *   14:45～15:00  15m 蜡烛交易中
     *   15:00:00     蜡烛收盘，BTCBotTask 触发
     *   15:00:01     [1/3] 拉取刚收盘的 K 线并保存
     *   15:00:02     [2/3] 计算该 K 线的 MACDV
     *   15:00:03     [3/3] strategy.decide() 基于最新 MACDV 做出判断
     *                 → 若有信号（LONG/SHORT），handleOpen* 立即执行
     *                 → 不等待下一根 K 线
     * </pre>
     *
     * <p>三步合一的目的是保证 decide() 使用的 MACDV 数据是最新的，
     * 避免因外部定时任务同步滞后导致基于过期数据交易。</p>
     */
    @Scheduled(cron = "0 0,15,30,45 * * * ?")
    public void execute() {
        String symbol = "BTCUSDT";
        long tick = System.currentTimeMillis();

        log.info("");
        log.info("═════════ BTC 自动交易 [{}] ═════════", symbol);
        log.info("  触发时刻 → {} (Unix ms)", tick);
        log.info("  当前状态 → 余额={}  持仓={}  开仓价={}  杠杆={}x",
                formatBalance(balance),
                position != null ? position : "空仓",
                position != null ? formatPrice(entryPrice) : "-",
                position != null ? currentLeverage : "-");

        try {
            // ======== 第一步：同步最新 K 线 ========
            log.info("  ─── [1/3] 同步K线数据 ───");
            for (String interval : TRADE_INTERVALS) {
                long latestOpenTime = klineStore.getLatestOpenTime(symbol, interval);
                List<Kline> klines;
                if (latestOpenTime == 0) {
                    klines = macdvService.fetchKlines(symbol, interval, 500);
                } else {
                    klines = macdvService.fetchKlinesAfter(symbol, interval, latestOpenTime);
                }
                if (klines != null && !klines.isEmpty()) {
                    klineStore.saveKlines(symbol, interval, klines);
                    log.info("    [{}/{}] 已同步 {} 条新K线", symbol, interval, klines.size());
                } else {
                    log.info("    [{}/{}] 无新K线", symbol, interval);
                }
            }

            // ======== 第二步：计算 MACDV ========
            log.info("  ─── [2/3] 计算MACDV指标 ───");
            for (String interval : TRADE_INTERVALS) {
                macdvService.syncMACDV(symbol, interval);
                // 读取刚写入的最新 MACDV 时间，确认已更新
                MACDVPoint latest = klineStore.getLatestMACDVPoint(symbol, interval);
                if (latest != null) {
                    log.info("    [{}/{}] 最新MACDV time={}", symbol, interval, latest.getTime());
                }
            }

            // ======== 第三步：策略交易 ========
            log.info("  ─── [3/3] 策略交易 ───");
            TradeDecision decision = strategy.decide(symbol);
            String action = decision.getAction();
            double price = decision.getLastPrice();
            int leverage = decision.getLeverage();
            String reason = decision.getReason();
            int score = (int) (decision.getConfidence() * 100);

            log.info("  策略决策 → action={}  price={}  confidence={}%  leverage={}x",
                    action, formatPrice(price), score, leverage);
            log.info("  决策理由 → {}", reason);

            // 安全检查：有持仓时只能走 HOLD
            if (position != null && !"HOLD".equals(action)) {
                log.warn("  ⚠ 已有持仓 {} 但策略返回 {}，降级为 HOLD", position, action);
                handleCloseIfNeeded(symbol, price, reason);
                return;
            }

            switch (action) {
                case "LONG" -> handleOpenLong(symbol, price, score, leverage, reason);
                case "SHORT" -> handleOpenShort(symbol, price, score, leverage, reason);
                case "HOLD" -> handleCloseIfNeeded(symbol, price, reason);
                default -> log.warn("  未知操作类型: {}", action);
            }

        } catch (Exception e) {
            log.error("  ❌ BTC 自动交易异常", e);
        }

        log.info("  最终状态 → 余额={}  持仓={}",
                formatBalance(balance), position != null ? position : "空仓");
        log.info("═════════ 自动交易结束 ═════════");
    }

    // ==================== 开仓逻辑 ====================

    private void handleOpenLong(String symbol, double price, int score, int leverage, String reason) {
        log.info("  ─── 开仓决策: 做多 ───");
        if (position != null) {
            log.info("  ⏭ 已有持仓 {}，忽略", position);
            return;
        }
        positionAmount = balance * leverage;
        currentLeverage = leverage;

        log.info("  ✅ 执行做多开仓");
        log.info("     开仓价格   = {}", formatPrice(price));
        log.info("     开仓前余额 = {} USDT", formatBalance(balance));
        log.info("     杠杆       = {}x", leverage);
        log.info("     开仓金额   = {} (余额{}×杠杆{}x)", formatBalance(positionAmount), formatBalance(balance), leverage);

        orderService.executeOrder(symbol, "LONG", price, positionAmount,
                score, leverage, balance, reason);

        position = "LONG";
        entryPrice = price;
        strategy.resetPositionState(symbol);
        log.info("  ✔ 做多开仓完成");
    }

    private void handleOpenShort(String symbol, double price, int score, int leverage, String reason) {
        log.info("  ─── 开仓决策: 做空 ───");
        if (position != null) {
            log.info("  ⏭ 已有持仓 {}，忽略", position);
            return;
        }
        positionAmount = balance * leverage;
        currentLeverage = leverage;

        log.info("  ✅ 执行做空开仓");
        log.info("     开仓价格   = {}", formatPrice(price));
        log.info("     开仓前余额 = {} USDT", formatBalance(balance));
        log.info("     杠杆       = {}x", leverage);
        log.info("     开仓金额   = {} (余额{}×杠杆{}x)", formatBalance(positionAmount), formatBalance(balance), leverage);

        orderService.executeOrder(symbol, "SHORT", price, positionAmount,
                score, leverage, balance, reason);

        position = "SHORT";
        entryPrice = price;
        strategy.resetPositionState(symbol);
        log.info("  ✔ 做空开仓完成");
    }

    // ==================== 平仓逻辑 ====================

    private void handleCloseIfNeeded(String symbol, double price, String reason) {
        log.info("  ─── 平仓检查 ───");
        if (position == null) {
            log.info("  ℹ 空仓，无操作");
            return;
        }

        boolean shouldClose;
        String closeType;
        if ("LONG".equals(position)) {
            log.info("  当前持仓: LONG  entry={}  current={}", formatPrice(entryPrice), formatPrice(price));
            shouldClose = strategy.shouldCloseLong(symbol);
            closeType = "平多";
        } else {
            log.info("  当前持仓: SHORT  entry={}  current={}", formatPrice(entryPrice), formatPrice(price));
            shouldClose = strategy.shouldCloseShort(symbol);
            closeType = "平空";
        }

        if (!shouldClose) {
            log.info("  ℹ 平仓条件不满足，继续持仓");
            return;
        }

        double pnl = calculatePnL(position, entryPrice, price, positionAmount);
        double oldBalance = balance;
        balance += pnl;

        log.info("  ✅ {}", closeType);
        log.info("     平仓价格 = {}", formatPrice(price));
        log.info("     盈亏     = {} USDT", formatPnl(pnl));
        log.info("     余额变化 = {} → {}", formatBalance(oldBalance), formatBalance(balance));

        orderService.executeOrder(symbol, "CLOSE", price, positionAmount,
                0, currentLeverage, balance, closeType + ": " + reason);

        position = null;
        entryPrice = 0;
        positionAmount = 0;
        currentLeverage = 1;
        strategy.clearPositionState(symbol);
        log.info("  ✔ {}完成，余额={}", closeType, formatBalance(balance));
    }

    // ==================== 盈亏计算 ====================

    private static double calculatePnL(String pos, double entry, double exit, double amount) {
        if (amount <= 0 || entry <= 0) return 0;
        double returnRate = "LONG".equals(pos) ? (exit - entry) / entry : (entry - exit) / entry;
        return returnRate * amount;
    }

    // ==================== 格式化工具 ====================

    private static String formatPrice(double v) {
        return String.format("%.2f", v);
    }

    private static String formatBalance(double v) {
        return String.format("%.2f", v);
    }

    private static String formatPnl(double v) {
        return String.format("%s%.2f", v >= 0 ? "+" : "", v);
    }
}
