package com.qw.agent.line.task;

import com.qw.agent.line.model.Kline;
import com.qw.agent.line.model.MACDVPoint;
import com.qw.agent.line.model.TradeSignalRecord;
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

import java.math.BigDecimal;
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
 * <p>余额和持仓状态实时从币安查询，不使用本地缓存变量。</p>
 */
@Component
public class BTCBotTask {

    private static final Logger log = LoggerFactory.getLogger(BTCBotTask.class);

    /** 策略交易需要同步的周期（与 MultiTimeframeStrategy 一致） */
    private static final String[] TRADE_INTERVALS = {"5m", "15m", "1h", "4h", "1d"};

    /** 默认开仓金额（USDT），当余额查询失败时使用 */
    private static final double DEFAULT_ORDER_USDT = 100.0;

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
        String symbol = "BTCUSDT";
        // 启动时从币安查询当前持仓状态，恢复上下文
        String direction = orderService.getPositionDirection(symbol);
        BigDecimal entryPrice = orderService.getEntryPrice(symbol);
        BigDecimal balance = orderService.getAvailableBalance();

        log.info("┌─────────────────────────────────────────────");
        log.info("│ BTC 交易机器人初始化完成");
        log.info("│   账户余额: {} USDT", formatBalance(balance.doubleValue()));
        log.info("│   当前持仓: {} (入场价={})",
                direction != null ? direction : "空仓",
                direction != null ? formatPrice(entryPrice.doubleValue()) : "-");
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
     *   15:00:00     蜡烛收盘
     *   15:00:02     BTCBotTask 触发（延后2秒，等待K线数据就绪）
     *   15:00:03     [1/3] 拉取刚收盘的 K 线并保存
     *   15:00:04     [2/3] 计算该 K 线的 MACDV
     *   15:00:05     [3/3] strategy.decide() 基于最新 MACDV 做出判断
     *                 → 若有信号（LONG/SHORT），立即执行开仓
     *                 → 不等待下一根 K 线
     * </pre>
     */
    @Scheduled(cron = "2 0,15,30,45 * * * ?")
    public void execute() {
        String symbol = "BTCUSDT";
        long tick = System.currentTimeMillis();

        // 实时从币安查询当前状态
        String currentPosition = orderService.getPositionDirection(symbol);
        BigDecimal currentBalance = orderService.getAvailableBalance();
        BigDecimal currentEntryPrice = orderService.getEntryPrice(symbol);

        log.info("");
        log.info("═════════ BTC 自动交易 [{}] ═════════", symbol);
        log.info("  触发时刻 → {} (Unix ms)", tick);
        log.info("  当前状态 → 余额={}  持仓={}  开仓价={}",
                formatBalance(currentBalance.doubleValue()),
                currentPosition != null ? currentPosition : "空仓",
                currentPosition != null ? formatPrice(currentEntryPrice.doubleValue()) : "-");

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

            // 如果币安有持仓，则策略动作降级为 HOLD（只走平仓检查）
            if (currentPosition != null && !"HOLD".equals(action)) {
                log.warn("  ⚠ 币安已有持仓 {} 但策略返回 {}，降级为 HOLD", currentPosition, action);
                handleCloseIfNeeded(symbol, currentPosition);
                return;
            }

            switch (action) {
                case "LONG" -> handleOpenLong(symbol, leverage, reason);
                case "SHORT" -> handleOpenShort(symbol, leverage, reason);
                case "HOLD" -> handleCloseIfNeeded(symbol, currentPosition);
                default -> log.warn("  未知操作类型: {}", action);
            }

        } catch (Exception e) {
            log.error("  ❌ BTC 自动交易异常", e);
        }

        BigDecimal finalBalance = orderService.getAvailableBalance();
        String finalPosition = orderService.getPositionDirection(symbol);
        log.info("  最终状态 → 余额={}  持仓={}",
                formatBalance(finalBalance.doubleValue()),
                finalPosition != null ? finalPosition : "空仓");
        log.info("═════════ 自动交易结束 ═════════");
    }

    // ==================== 开仓逻辑 ====================

    private void handleOpenLong(String symbol, int leverage, String reason) {
        log.info("  ─── 开仓决策: 做多 ───");

        // 再次确认无持仓
        if (orderService.hasPosition(symbol)) {
            log.info("  ⏭ 币安已有持仓，忽略做多信号");
            return;
        }

        TradeSignalRecord record = orderService.openLong(symbol, leverage, reason);
        if (record != null) {
            strategy.resetPositionState(symbol);
            log.info("  ✔ 做多开仓完成");
        } else {
            log.error("  ❌ 做多开仓失败");
        }
    }

    private void handleOpenShort(String symbol, int leverage, String reason) {
        log.info("  ─── 开仓决策: 做空 ───");

        if (orderService.hasPosition(symbol)) {
            log.info("  ⏭ 币安已有持仓，忽略做空信号");
            return;
        }

        TradeSignalRecord record = orderService.openShort(symbol, leverage, reason);
        if (record != null) {
            strategy.resetPositionState(symbol);
            log.info("  ✔ 做空开仓完成");
        } else {
            log.error("  ❌ 做空开仓失败");
        }
    }

    // ==================== 平仓逻辑 ====================

    private void handleCloseIfNeeded(String symbol, String currentPosition) {
        log.info("  ─── 平仓检查 ───");
        if (currentPosition == null) {
            log.info("  ℹ 空仓，无操作");
            return;
        }

        boolean shouldClose;
        String closeType;
        if ("LONG".equals(currentPosition)) {
            shouldClose = strategy.shouldCloseLong(symbol);
            closeType = "平多";
        } else {
            shouldClose = strategy.shouldCloseShort(symbol);
            closeType = "平空";
        }

        if (!shouldClose) {
            log.info("  ℹ 平仓条件不满足，继续持仓");
            return;
        }

        log.info("  ✅ 执行{}", closeType);
        TradeSignalRecord record;
        if ("LONG".equals(currentPosition)) {
            record = orderService.closeLong(symbol, closeType + ": " + currentPosition);
        } else {
            record = orderService.closeShort(symbol, closeType + ": " + currentPosition);
        }

        if (record != null) {
            strategy.clearPositionState(symbol);
            BigDecimal balance = orderService.getAvailableBalance();
            log.info("  ✔ {}完成，当前余额={} USDT", closeType, formatBalance(balance.doubleValue()));
        } else {
            log.error("  ❌ {}失败", closeType);
        }
    }

    // ==================== 格式化工具 ====================

    private static String formatPrice(double v) {
        return String.format("%.2f", v);
    }

    private static String formatBalance(double v) {
        return String.format("%.2f", v);
    }
}
