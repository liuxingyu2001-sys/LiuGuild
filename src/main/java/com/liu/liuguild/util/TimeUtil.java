package com.liu.liuguild.util;

import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** 时间 / 日期工具：解析、格式化与工会战时间表匹配。 */
public final class TimeUtil {

    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private TimeUtil() {
    }

    public static long now() {
        return System.currentTimeMillis();
    }

    /** 剩余毫秒 → 向上取整的秒数。 */
    public static long secondsLeft(long untilMillis) {
        return Math.max(0, (untilMillis - System.currentTimeMillis() + 999) / 1000);
    }

    public static long secondsUntil(long untilMillis) {
        return Math.max(0, (untilMillis - System.currentTimeMillis()) / 1000);
    }

    public static String formatDate(long millis) {
        return DATE.format(new Date(millis).toInstant().atZone(ZONE));
    }

    public static String formatDateTime(long millis) {
        return DATE_TIME.format(new Date(millis).toInstant().atZone(ZONE));
    }

    /** 用指定 SimpleDateFormat 格式化。 */
    public static String format(long millis, String pattern) {
        return new SimpleDateFormat(pattern).format(new Date(millis));
    }

    public static long parse(String text, String pattern, long def) {
        if (text == null || text.isBlank() || text.startsWith("0000")) {
            return def;
        }
        try {
            return new SimpleDateFormat(pattern).parse(text.trim()).getTime();
        } catch (Exception e) {
            return def;
        }
    }

    /** 解析 {@code yyyy-MM-dd HH:mm:ss}。 */
    public static long parseDateTime(String text) {
        if (text == null || text.isBlank() || text.startsWith("0000")) {
            return now();
        }
        try {
            return LocalDateTime.parse(text.trim(), DATE_TIME).atZone(ZONE).toInstant().toEpochMilli();
        } catch (Exception e) {
            return now();
        }
    }

    /** 把秒数格式化为 {@code 1天2小时3分} 形式。 */
    public static String human(long seconds) {
        if (seconds <= 0) {
            return "0秒";
        }
        long d = seconds / 86400;
        long h = (seconds % 86400) / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        StringBuilder sb = new StringBuilder();
        if (d > 0) {
            sb.append(d).append("天");
        }
        if (h > 0) {
            sb.append(h).append("小时");
        }
        if (m > 0) {
            sb.append(m).append("分");
        }
        if (s > 0 || sb.length() == 0) {
            sb.append(s).append("秒");
        }
        return sb.toString();
    }

    // =====================================================================
    // 工会战时间表
    // =====================================================================

    private static final Map<String, Integer> DAYS = new LinkedHashMap<>();

    static {
        DAYS.put("mon", 1);
        DAYS.put("tue", 2);
        DAYS.put("wed", 3);
        DAYS.put("thu", 4);
        DAYS.put("fri", 5);
        DAYS.put("sat", 6);
        DAYS.put("sun", 7);
    }

    /** 判断当前时间是否命中时间表条目（{@code "20:00"} / {@code "sat 20:00"} / {@code "mon-fri 20:00"}）。 */
    public static boolean matchesSchedule(String entry) {
        LocalDateTime t = LocalDateTime.now(ZONE);
        return matchesSchedule(entry, t.getDayOfWeek().getValue(), t.getHour(), t.getMinute());
    }

    public static boolean matchesSchedule(String entry, int dayOfWeek, int hour, int minute) {
        if (entry == null || entry.isBlank()) {
            return false;
        }
        String[] parts = entry.trim().toLowerCase(Locale.ROOT).split("\\s+");
        String dayExpr = parts.length >= 2 ? parts[0] : "*";
        String timeExpr = parts.length >= 2 ? parts[1] : parts[0];
        if (!dayMatches(dayExpr, dayOfWeek)) {
            return false;
        }
        String[] hm = timeExpr.split(":");
        if (hm.length != 2) {
            return false;
        }
        int h = NumberUtil.toInt(hm[0], -1);
        int m = NumberUtil.toInt(hm[1], -1);
        return h == hour && m == minute;
    }

    private static boolean dayMatches(String expr, int dayOfWeek) {
        if (expr == null || expr.isBlank() || expr.equals("*") || expr.equals("daily") || expr.equals("每天")) {
            return true;
        }
        for (String token : expr.split(",")) {
            String t = token.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (t.contains("-")) {
                String[] range = t.split("-");
                Integer a = DAYS.get(range[0].trim());
                Integer b = DAYS.get(range.length > 1 ? range[1].trim() : range[0].trim());
                if (a == null || b == null) {
                    continue;
                }
                int lo = Math.min(a, b);
                int hi = Math.max(a, b);
                if (dayOfWeek >= lo && dayOfWeek <= hi) {
                    return true;
                }
            } else {
                Integer d = DAYS.get(t);
                if (d != null && d == dayOfWeek) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 当天已过/即将到达的分钟数（用于判断时间表是否已触发）。 */
    public static int currentMinuteOfWeek() {
        LocalDateTime t = LocalDateTime.now(ZONE);
        return (t.getDayOfWeek().getValue() - 1) * 1440 + t.getHour() * 60 + t.getMinute();
    }
}
