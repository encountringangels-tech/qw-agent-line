package com.qw.agent.line.util;

import java.math.BigDecimal;

/**
 * 类型转换工具类 —— 集中处理 Map/JSON 解析中的类型安全转换。
 * <p>
 * 合并自 {@code BinanceTradeController} 中的 {@code str() / toBigDec() / intVal() / longVal()}。
 * 这些工具方法在解析币安 API 返回的 JSON Map 时被频繁使用。
 */
public final class ConvertUtil {

    private ConvertUtil() {
        // 工具类，禁止实例化
    }

    /**
     * 安全转换为字符串，null 返回 null。
     */
    public static String str(Object v) {
        return v != null ? v.toString() : null;
    }

    /**
     * 安全转换为 BigDecimal，null 或解析失败返回 null。
     */
    public static BigDecimal toBigDec(Object v) {
        if (v == null) return null;
        try {
            return new BigDecimal(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 安全转换为 BigDecimal，失败返回 {@link BigDecimal#ZERO}。
     */
    public static BigDecimal toBigDecZero(Object v) {
        BigDecimal result = toBigDec(v);
        return result != null ? result : BigDecimal.ZERO;
    }

    /**
     * 安全转换为 int，null 或解析失败返回 0。
     */
    public static int intVal(Object v) {
        if (v == null) return 0;
        try {
            return Integer.parseInt(v.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 安全转换为 long，null 或解析失败返回 0。
     */
    public static long longVal(Object v) {
        if (v == null) return 0L;
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * 安全转换为 double，null 或解析失败返回 0.0。
     */
    public static double doubleVal(Object v) {
        if (v == null) return 0.0;
        try {
            return Double.parseDouble(v.toString());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    /**
     * 安全转换为 boolean，null 返回 false。
     */
    public static boolean boolVal(Object v, boolean defaultValue) {
        if (v == null) return defaultValue;
        if (v instanceof Boolean) return (Boolean) v;
        return "true".equalsIgnoreCase(v.toString());
    }

    /**
     * 格式化价格为两位小数。
     */
    public static String formatPrice(double v) {
        return String.format("%.2f", v);
    }

    /**
     * 格式化余额为两位小数。
     */
    public static String formatBalance(double v) {
        return String.format("%.2f", v);
    }
}
