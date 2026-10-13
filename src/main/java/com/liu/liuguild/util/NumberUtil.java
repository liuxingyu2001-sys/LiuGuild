package com.liu.liuguild.util;

import java.util.Locale;

/** 数字解析与取值工具：统一处理非法输入与范围限制。 */
public final class NumberUtil {

    private NumberUtil() {
    }

    public static int toInt(String s, int def) {
        if (s == null) {
            return def;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static long toLong(String s, long def) {
        if (s == null) {
            return def;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static double toDouble(String s, double def) {
        if (s == null) {
            return def;
        }
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** 把输入解析为正整数（用于金额），非法或非正数返回 -1。 */
    public static double toPositiveDouble(String s) {
        double v = toDouble(s, -1);
        return v > 0 ? v : -1;
    }

    public static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    public static long clamp(long v, long min, long max) {
        return Math.max(min, Math.min(max, v));
    }

    public static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    /** 是否全部为数字字符。 */
    public static boolean isDigits(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 解析金额，支持 {@code k}/{@code m}/{@code b} 后缀（大小写不敏感）。
     * 例：{@code 1k}=1000，{@code 1.5m}=1500000，{@code 2b}=2e9。
     * 非法或非正数返回 -1。
     */
    public static double parseAmount(String s) {
        if (s == null) {
            return -1;
        }
        String v = s.trim().toLowerCase(Locale.ROOT).replace(",", "").replace("_", "");
        if (v.isEmpty()) {
            return -1;
        }
        double mult = 1;
        char last = v.charAt(v.length() - 1);
        if (last == 'k' || last == 'm' || last == 'b' || last == 'w') {
            mult = switch (last) {
                case 'k' -> 1_000d;
                case 'm' -> 1_000_000d;
                case 'b' -> 1_000_000_000d;
                default -> 10_000d; // w = 万
            };
            v = v.substring(0, v.length() - 1);
        }
        double base = toDouble(v, -1);
        if (base <= 0) {
            return -1;
        }
        return base * mult;
    }
}
