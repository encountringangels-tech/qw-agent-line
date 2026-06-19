package com.qw.agent.line.util;

/**
 * 数学工具类 —— 收集项目中通用的数值计算方法。
 * <p>
 * 合并自:
 * <ul>
 *   <li>{@code MACDVCalculator.round2()}</li>
 *   <li>{@code MultiTimeframeStrategy.round2() / normalizeScore() / inRange()}</li>
 *   <li>{@code MACDVSignalGenerator.round2() / calcStrength()}</li>
 * </ul>
 */
public final class MathUtil {

    private MathUtil() {
        // 工具类，禁止实例化
    }

    /**
     * 保留两位小数（四舍五入）。
     */
    public static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /**
     * 判断数值 {@code value} 是否在 {@code [target - tolerance, target + tolerance]} 范围内。
     *
     * @param value     待检测值
     * @param target    目标中心值
     * @param tolerance 允许偏差范围
     */
    public static boolean inRange(double value, double target, double tolerance) {
        return value >= target - tolerance && value <= target + tolerance;
    }

    /**
     * 将策略评分（0~10）归一化为置信度（0.30~0.95）。
     */
    public static double normalizeScore(int score) {
        return Math.min(0.95, Math.max(0.30, score / 10.0));
    }

    /**
     * 计算信号强度（0~1），值越大信号越可靠。
     *
     * @param macdv    当前 MACD-V 值
     * @param threshold 阈值（开多阈值为负数，开空阈值为正数）
     * @param isLong   true=计算多头强度，false=计算空头强度
     */
    public static double calcStrength(double macdv, int threshold, boolean isLong) {
        double dist = isLong ? (threshold - macdv) : (macdv - threshold);
        return round2(0.45 + 0.20 * Math.min(1.0, Math.max(0, dist) / 100.0));
    }
}
