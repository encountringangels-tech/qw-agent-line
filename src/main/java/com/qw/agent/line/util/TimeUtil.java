package com.qw.agent.line.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 时间工具类 —— 集中管理 K 线周期转换、时间戳格式化等通用逻辑。
 * <p>
 * 合并自:
 * <ul>
 *   <li>{@code MACDVService.parseIntervalMs()}</li>
 *   <li>{@code KlineSyncService.calculateInitialLimit()} 中的 interval→ms 映射</li>
 *   <li>{@code OrderService.saveTradeSignal()} 中的时间格式化</li>
 *   <li>散落在各处的 {@code 24 * 3600 * 1000} 等魔法数值</li>
 * </ul>
 */
public final class TimeUtil {

    private TimeUtil() {
        // 工具类，禁止实例化
    }

    // ==================== K 线周期常量 ====================

    /** 1 秒的毫秒数 */
    public static final long SECOND_MS = 1000L;
    /** 1 分钟的毫秒数 */
    public static final long MINUTE_MS = 60 * SECOND_MS;
    /** 1 小时的毫秒数 */
    public static final long HOUR_MS = 60 * MINUTE_MS;
    /** 1 天的毫秒数 */
    public static final long DAY_MS = 24 * HOUR_MS;
    /** 7 天的毫秒数 */
    public static final long WEEK_MS = 7 * DAY_MS;
    /** 30 天的毫秒数（近似） */
    public static final long MONTH_MS_30 = 30 * DAY_MS;

    // ==================== 周期映射 ====================

    /**
     * 将 K 线周期字符串转为毫秒数（与币安 API 对齐）。
     *
     * @param interval 周期标识，如 "5m", "15m", "30m", "1h", "4h", "1d"
     * @return 对应的毫秒数；无法识别时返回 0
     */
    public static long parseIntervalMs(String interval) {
        return switch (interval) {
            case "5m"  -> 5 * MINUTE_MS;
            case "15m" -> 15 * MINUTE_MS;
            case "30m" -> 30 * MINUTE_MS;
            case "1h"  -> HOUR_MS;
            case "4h"  -> 4 * HOUR_MS;
            case "1d"  -> DAY_MS;
            default -> 0;
        };
    }

    /**
     * 根据 K 线周期计算覆盖约 30 天所需的初始同步条数（上限 1000，币安单次 API 限制）。
     */
    public static int calculateInitialLimit(String interval) {
        long intervalMs = parseIntervalMs(interval);
        if (intervalMs == 0) return 1000;
        int count = (int) (MONTH_MS_30 / intervalMs);
        return Math.min(count, 1000);
    }

    // ==================== 时间戳工具 ====================

    /** 当前秒级 Unix 时间戳 */
    public static long currentTimeSec() {
        return System.currentTimeMillis() / 1000;
    }

    /** 当前毫秒级 Unix 时间戳 */
    public static long currentTimeMs() {
        return System.currentTimeMillis();
    }

    /** N 天前的毫秒时间戳 */
    public static long daysAgoMs(int days) {
        return System.currentTimeMillis() - (long) days * DAY_MS;
    }

    // ==================== 格式化 ====================

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 格式化为 {@code yyyy-MM-dd HH:mm:ss}。
     */
    public static String formatDateTime(LocalDateTime dt) {
        return dt.format(DT_FMT);
    }

    /**
     * 格式化为 {@code yyyy-MM-dd HH:mm:ss}（使用当前时间）。
     */
    public static String nowFormatted() {
        return LocalDateTime.now().format(DT_FMT);
    }
}
