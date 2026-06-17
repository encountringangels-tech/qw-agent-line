package com.qw.agent.line.service;

import com.qw.agent.line.model.Kline;
import com.qw.agent.line.store.KlineStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * K 线数据定时同步任务 —— 每 5 分钟从币安增量拉取各周期 K 线。
 * <p>
 * 执行时机：每 5 分钟的 05 秒（如 00:00:05, 00:05:05, 00:10:05 ...）
 * 首次运行时根据周期自动计算需要拉取的历史 K 线条数（覆盖约 30 天），后续只增量拉取新产生的 K 线。
 */
@Component
public class KlineSyncService {

    private static final Logger log = LoggerFactory.getLogger(KlineSyncService.class);

    /** 需要同步的交易对列表（与前端一致） */
    private static final String[] SYMBOLS = {"BTCUSDT", "ETHUSDT"};

    /** 需要同步的 K 线周期 */
    private static final String[] INTERVALS = {"5m", "15m", "30m", "1h", "4h", "1d"};

    private final MACDVService macdvService;
    private final KlineStore klineStore;

    public KlineSyncService(MACDVService macdvService, KlineStore klineStore) {
        this.macdvService = macdvService;
        this.klineStore = klineStore;
    }

    /**
     * 每 5 分钟的 05 秒执行一次，同步所有交易对的所有周期。
     * <p>
     * cron: second=5, minute=every 5 (0,5,10,15...)
     */
    @Scheduled(cron = "5 */5 * * * *", zone = "Asia/Shanghai")
    public void syncAll() {
        log.info("===== 定时同步开始 =====");
        long start = System.currentTimeMillis();

        for (String symbol : SYMBOLS) {
            for (String interval : INTERVALS) {
                try {
                    syncInterval(symbol, interval);
                } catch (Exception e) {
                    log.error("同步失败 [{}/{}]: {}", symbol, interval, e.getMessage());
                }
            }
        }

        long elapsed = System.currentTimeMillis() - start;
        log.info("===== 定时同步结束，耗时 {}ms =====", elapsed);
    }

    /** 同步单个 (symbol, interval) 的 K 线 */
    private void syncInterval(String symbol, String interval) {
        long latestOpenTime = klineStore.getLatestOpenTime(symbol, interval);

        List<Kline> klines;
        if (latestOpenTime == 0) {
            // 首次同步：根据间隔周期自动计算历史 K 线条数（覆盖约 30 天）
            int limit = calculateInitialLimit(interval);
            log.info("首次同步 [{}/{}]，拉取 {} 条历史数据", symbol, interval, limit);
            klines = macdvService.fetchKlines(symbol, interval, limit);
        } else {
            // 增量同步：只拉取最新时间之后的数据
            klines = macdvService.fetchKlinesAfter(symbol, interval, latestOpenTime);
        }

        if (klines == null || klines.isEmpty()) {
            return;
        }

        klineStore.saveKlines(symbol, interval, klines);
        log.info("已同步 [{}/{}] {} 条", symbol, interval, klines.size());
        // 增量计算并持久化 MACD-V 指标
        try {
            macdvService.syncMACDV(symbol, interval);
        } catch (Exception e) {
            log.error("MACD-V 同步失败 [{}/{}]: {}", symbol, interval, e.getMessage());
        }
    }

    /**
     * 根据 K 线间隔周期自动计算首次同步需要拉取的条数。
     * <p>
     * 目标覆盖天数约 30 天，上限 1000 条（币安单次 API 限制）。
     * <ul>
     *   <li>5m  → 8640 条/30天，上限 1000</li>
     *   <li>15m → 2880 条/30天，上限 1000</li>
     *   <li>30m → 1440 条/30天，上限 1000</li>
     *   <li>1h  → 720 条/30天</li>
     *   <li>4h  → 180 条/30天</li>
     *   <li>1d  → 30 条/30天</li>
     * </ul>
     */
    private static int calculateInitialLimit(String interval) {
        long intervalMs = switch (interval) {
            case "5m"  -> 5L * 60 * 1000;
            case "15m" -> 15L * 60 * 1000;
            case "30m" -> 30L * 60 * 1000;
            case "1h"  -> 3600L * 1000;
            case "4h"  -> 4L * 3600 * 1000;
            case "1d"  -> 24L * 3600 * 1000;
            default -> 0;
        };

        // 目标覆盖 30 天
        long targetMs = 30L * 24 * 3600 * 1000;
        int count = (int) (targetMs / intervalMs);
        // 币安单次最多 1000 条
        return Math.min(count, 1000);
    }
}
