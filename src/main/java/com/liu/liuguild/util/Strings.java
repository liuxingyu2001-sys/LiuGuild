package com.liu.liuguild.util;

import java.util.Locale;
import java.util.Set;

/** 字符串工具：空值处理、名称/标签生成。 */
public final class Strings {

    private Strings() {
    }

    public static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    /** 截断到最大长度（去除首尾空白）；空白返回 null。 */
    public static String trimTo(String s, int max) {
        if (s == null) {
            return null;
        }
        String out = s.trim();
        if (out.length() > max) {
            out = out.substring(0, max);
        }
        return out.isEmpty() ? null : out;
    }

    /** 由名称生成工会标签：去空白、截断，必要时补足最短长度，并保证唯一。 */
    public static String uniqueTag(String name, int min, int max, Set<String> used, java.util.function.Predicate<String> taken) {
        String base = name == null ? "" : name.replaceAll("\\s+", "");
        if (base.isEmpty()) {
            base = "GUILD";
        }
        String tag = base.length() <= max ? base : base.substring(0, max);
        while (tag.length() < min) {
            tag = tag + "0";
        }
        String candidate = tag;
        int n = 1;
        while (used.contains(candidate.toLowerCase(Locale.ROOT))
                || (taken != null && taken.test(candidate))) {
            String suffix = String.valueOf(n);
            candidate = tag.length() >= max
                    ? tag.substring(0, Math.max(1, max - suffix.length())) + suffix
                    : tag + suffix;
            n++;
            if (n > 9999) {
                break;
            }
        }
        return candidate;
    }
}
