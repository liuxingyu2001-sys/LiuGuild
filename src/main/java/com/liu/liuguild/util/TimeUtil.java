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

    /** 当前本地时区的 epoch day（用于每日签到等按日判定）。 */
    public static long todayEpochDay() {
        return LocalDateTime.now(ZONE).toLocalDate().toEpochDay();
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
        // 英文缩写
        DAYS.put("mon", 1);
        DAYS.put("tue", 2);
        DAYS.put("wed", 3);
        DAYS.put("thu", 4);
        DAYS.put("fri", 5);
        DAYS.put("sat", 6);
        DAYS.put("sun", 7);
        // 英文全称
        DAYS.put("monday", 1);
        DAYS.put("tuesday", 2);
        DAYS.put("wednesday", 3);
        DAYS.put("thursday", 4);
        DAYS.put("friday", 5);
        DAYS.put("saturday", 6);
        DAYS.put("sunday", 7);
        // 数字：1=周一 ... 7=周日
        for (int i = 1; i <= 7; i++) {
            DAYS.put(String.valueOf(i), i);
        }
        // 中文
        String[] cn = {"", "周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        String[] cnShort = {"", "一", "二", "三", "四", "五", "六", "日"};
        String[] cnLong = {"", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"};
        for (int i = 1; i <= 7; i++) {
            DAYS.put(cn[i], i);
            DAYS.put(cnLong[i], i);
            DAYS.put(cnShort[i], i);
            DAYS.put("周" + cnShort[i], i);
        }
    }

    /** 解析单个星期 token（支持 mon / monday / 1-7 / 周一 / 星期一 / 一）。 */
    private static Integer dayValue(String token) {
        if (token == null) {
            return null;
        }
        String t = token.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) {
            return null;
        }
        Integer d = DAYS.get(t);
        if (d != null) {
            return d;
        }
        // 数字开头的容错（如 "1日" 不处理，仅纯数字已在 map 中）
        return DAYS.get(t.replace("星期", "周"));
    }

    /** 把时间表条目渲染为中文显示，如 {@code sat 20:00 -> 周六 20:00}、{@code 1-5 20:00 -> 周一至周五 20:00}。 */
    public static String prettySchedule(String entry) {
        if (entry == null || entry.isBlank()) {
            return "";
        }
        String[] parts = entry.trim().split("\\s+");
        String dayExpr = parts.length >= 2 ? parts[0] : "*";
        String timeExpr = parts.length >= 2 ? parts[1] : parts[0];
        return prettyDay(dayExpr) + " " + timeExpr;
    }

    private static final String[] CN_DAY = {"", "周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    private static String prettyDay(String expr) {
        String e = expr == null ? "" : expr.trim().toLowerCase(Locale.ROOT);
        if (e.isEmpty() || e.equals("*") || e.equals("daily") || e.equals("每天")) {
            return "每天";
        }
        StringBuilder sb = new StringBuilder();
        String[] tokens = e.split(",");
        for (int i = 0; i < tokens.length; i++) {
            if (i > 0) {
                sb.append("、");
            }
            String t = tokens[i].trim();
            if (t.contains("-")) {
                String[] r = t.split("-");
                Integer a = dayValue(r[0].trim());
                Integer b = dayValue(r.length > 1 ? r[1].trim() : r[0].trim());
                if (a != null && b != null) {
                    int lo = Math.min(a, b);
                    int hi = Math.max(a, b);
                    sb.append(CN_DAY[lo]).append("至").append(CN_DAY[hi]);
                } else {
                    sb.append(t);
                }
            } else {
                Integer d = dayValue(t);
                sb.append(d != null ? CN_DAY[d] : t);
            }
        }
        return sb.toString();
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
                Integer a = dayValue(range[0].trim());
                Integer b = dayValue(range.length > 1 ? range[1].trim() : range[0].trim());
                if (a == null || b == null) {
                    continue;
                }
                int lo = Math.min(a, b);
                int hi = Math.max(a, b);
                if (dayOfWeek >= lo && dayOfWeek <= hi) {
                    return true;
                }
            } else {
                Integer d = dayValue(t);
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
