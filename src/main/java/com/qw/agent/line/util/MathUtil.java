package com.qw.agent.line.util;

/**
 * 数学工具类 —— 收集项目中通用的数值计算方法。
 * <p>
 * 合并自 {@code MACDVCalculator.round2()} 和 {@code MultiTimeframeStrategy} 的工具方法。
 */
public final class MathUtil {

    private MathUtil() {
    }

    /**
     * 保留两位小数（四舍五入）。
     */
    public static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /**
     * 判断数值 {@code value} 是否在 {@code [target - tolerance, target + tolerance]} 范围内。
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
}
