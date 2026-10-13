package com.liu.liuguild.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.regex.Pattern;

/** MiniMessage 文本工具。 */
public final class Text {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    /** 去掉 MiniMessage 标签与旧版颜色代码，用于校验名称纯文本长度。 */
    private static final Pattern MM_TAG = Pattern.compile("<[^>]*>");
    private static final Pattern LEGACY = Pattern.compile("[&\u00a7][0-9a-fk-orA-FK-OR]");

    private Text() {
    }

    public static Component mm(String s) {
        return MM.deserialize(s == null ? "" : s);
    }

    /** 支持 {占位符} 的 MiniMessage 解析（先把占位符替换掉再解析）。 */
    public static Component fmt(String template, Object... kv) {
        return MM.deserialize(replace(template, kv));
    }

    /** 把 {key} 替换为 value；kv 为 key1, value1, key2, value2 ... */
    public static String replace(String template, Object... kv) {
        if (template == null) {
            return "";
        }
        String out = template;
        if (kv != null) {
            for (int i = 0; i + 1 < kv.length; i += 2) {
                String key = "{" + kv[i] + "}";
                String value = kv[i + 1] == null ? "" : String.valueOf(kv[i + 1]);
                out = out.replace(key, value);
            }
        }
        return out;
    }

    public static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    /** 清除所有格式标记后的纯文本。 */
    public static String clean(String s) {
        if (s == null) {
            return "";
        }
        String out = MM_TAG.matcher(s).replaceAll("");
        out = LEGACY.matcher(out).replaceAll("");
        return out.trim();
    }

    /** 是否包含非法格式标记（颜色/标签），用于名称校验。 */
    public static boolean hasFormatting(String s) {
        if (s == null) {
            return false;
        }
        return MM_TAG.matcher(s).find() || LEGACY.matcher(s).find();
    }

    /**
     * 转义玩家可控文本，防止 MiniMessage 注入（如 <red>、<click:...>）。
     * 转义后的文本可用于 {@link #fmt} 的占位符值。
     */
    public static String esc(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("<", "\\<");
    }
}
