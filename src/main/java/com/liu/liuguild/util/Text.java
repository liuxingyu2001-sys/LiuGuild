package com.liu.liuguild.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 统一文本渲染工具。
 *
 * <p>所有模板同时支持三种书写方式，并可在同一段文本中混用：</p>
 * <ul>
 *   <li>MiniMessage：{@code <red>}、{@code <gradient:...>}、{@code <click:...>} 等；</li>
 *   <li>旧版颜色代码：{@code &a}/{@code §a}、{@code &l} 等；</li>
 *   <li>RGB 颜色：{@code &#FF0000} / {@code §#FF0000}。</li>
 * </ul>
 *
 * <p>占位符使用 {@code {key}} 形式。{@link #fmt} 默认把占位符值当作纯文本插入，
 * 因此玩家昵称、工会名等含 {@code <} / {@code &} 的内容不会注入格式或点击事件；
 * 确实需要富文本时使用 {@link #fmtRich}。</p>
 */
public final class Text {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    /** 去掉 MiniMessage 标签与旧版颜色代码，用于名称纯文本长度校验。 */
    private static final Pattern MM_TAG = Pattern.compile("<[^>]*>");
    private static final Pattern LEGACY = Pattern.compile("(?i)(?:&|\u00a7)(?:#[0-9a-f]{6}|[0-9a-fk-or])");

    /** 旧版单字符颜色/格式代码 -> MiniMessage 标签。 */
    private static final String LEGACY_CODES = "0123456789abcdefklmnor";
    private static final String[] LEGACY_TAGS = {
            "<black>", "<dark_blue>", "<dark_green>", "<dark_aqua>", "<dark_red>", "<dark_purple>",
            "<gold>", "<gray>", "<dark_gray>", "<blue>", "<green>", "<aqua>", "<red>", "<light_purple>",
            "<yellow>", "<white>", "<obfuscated>", "<bold>", "<strikethrough>", "<underlined>",
            "<italic>", "<reset>"
    };

    private Text() {
    }

    /**
     * 解析含 MiniMessage 与旧版颜色代码的文本。
     */
    public static Component mm(String s) {
        return MM.deserialize(legacyToMini(s));
    }

    /**
     * 渲染带 {@code {key}} 占位符的模板；占位符值按纯文本插入（防注入）。
     * 参数形式为 {@code key1, value1, key2, value2 ...}。
     */
    public static Component fmt(String template, Object... kv) {
        return render(template, false, kv);
    }

    /** 与 {@link #fmt} 相同，但占位符值允许携带 MiniMessage / 旧版颜色（用于配置内的富文本）。 */
    public static Component fmtRich(String template, Object... kv) {
        return render(template, true, kv);
    }

    /**
     * 选择性富文本：仅 {@code richKeys} 中的占位符按格式解析，其余按纯文本插入。
     * 用于 GUI 模板中既显示玩家名（需转义）又显示公告（允许颜色）的场景。
     */
    public static Component fmtSelective(String template, java.util.Set<String> richKeys, Object... kv) {
        String source = prepare(template);
        List<TagResolver> resolvers = new ArrayList<>();
        if (kv != null) {
            for (int i = 0; i + 1 < kv.length; i += 2) {
                String key = String.valueOf(kv[i]);
                String value = kv[i + 1] == null ? "" : String.valueOf(kv[i + 1]);
                source = source.replace("{" + key + "}", "<" + key + ">");
                resolvers.add(richKeys != null && richKeys.contains(key)
                        ? Placeholder.parsed(key, sanitizeToMini(value))
                        : Placeholder.unparsed(key, value));
            }
        }
        return MM.deserialize(source, TagResolver.resolver(resolvers));
    }

    /**
     * 模板预处理缓存（legacyToMini + linkCommands）。模板是有限的配置/消息集合，
     * 命中率接近 100%，可省掉每条消息两次正则/逐字符扫描；reload 时整体清空。
     */
    private static final int PREPARED_CACHE_MAX = 512;
    private static final java.util.Map<String, String> PREPARED = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, String> eldest) {
                    return size() > PREPARED_CACHE_MAX;
                }
            });

    private static String prepare(String template) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        String hit = PREPARED.get(template);
        if (hit != null) {
            return hit;
        }
        String prepared = linkCommands(legacyToMini(template));
        PREPARED.put(template, prepared);
        return prepared;
    }

    /** 配置/消息重载后清空模板缓存（模板内容可能已变化）。 */
    public static void invalidateCache() {
        PREPARED.clear();
    }

    private static Component render(String template, boolean richValues, Object... kv) {
        String source = prepare(template);
        List<TagResolver> resolvers = new ArrayList<>();
        if (kv != null) {
            for (int i = 0; i + 1 < kv.length; i += 2) {
                String key = String.valueOf(kv[i]);
                String value = kv[i + 1] == null ? "" : String.valueOf(kv[i + 1]);
                // 只替换模板中真实存在的 {key} 标记，避免误伤其它文本。
                source = source.replace("{" + key + "}", "<" + key + ">");
                resolvers.add(richValues
                        ? Placeholder.parsed(key, legacyToMini(value))
                        : Placeholder.unparsed(key, value));
            }
        }
        return MM.deserialize(source, TagResolver.resolver(resolvers));
    }

    /** 消息中出现的 /guild、/guildadmin 指令自动变为可点击（点击补全到聊天栏）。 */
    private static final Pattern COMMAND_TOKEN =
            Pattern.compile("/(guildadmin|guild|gh|g)(?![A-Za-z0-9_\\-])((?:\\s+[A-Za-z0-9_\\-]+)*)");

    /**
     * 把模板里的指令提示变成可点击组件（附悬停说明）。
     * 仅作用于模板自身的纯文本，跳过已经手写的 MiniMessage 标签
     * （尤其是 {@code <click:run_command:'/guild mail'>}，避免嵌套标签损坏）。
     * 玩家输入占位符不会被解析，从而不会引入注入风险。
     */
    static String linkCommands(String source) {
        if (source == null || source.indexOf('/') < 0) {
            return source;
        }
        StringBuilder out = new StringBuilder(source.length() + 32);
        int cursor = 0;
        while (cursor < source.length()) {
            int open = source.indexOf('<', cursor);
            if (open < 0) {
                out.append(linkPlainCommands(source.substring(cursor)));
                break;
            }
            out.append(linkPlainCommands(source.substring(cursor, open)));
            int close = source.indexOf('>', open + 1);
            if (close < 0) {
                out.append(source, open, source.length());
                break;
            }
            // 已经手写的 MiniMessage 标签（如 <click:run_command:'/guild mail'>）
            // 内部不能再插入 click/hover，否则会生成嵌套/损坏的标签。
            out.append(source, open, close + 1);
            cursor = close + 1;
        }
        return out.toString();
    }

    private static String linkPlainCommands(String text) {
        if (text == null || text.indexOf('/') < 0) {
            return text == null ? "" : text;
        }
        Matcher m = COMMAND_TOKEN.matcher(text);
        StringBuilder sb = new StringBuilder(text.length() + 32);
        while (m.find()) {
            String cmd = m.group();
            String tag = "<click:suggest_command:'" + cmd + "'><hover:show_text:'点击补全该指令'>"
                    + cmd + "</hover></click>";
            m.appendReplacement(sb, Matcher.quoteReplacement(tag));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 把 {key} 替换为 value 的旧式字符串替换（仅用于非组件场景，如日志）。 */
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

    /** 清除所有格式标记后的纯文本（用于名称长度校验）。 */
    public static String clean(String s) {
        if (s == null) {
            return "";
        }
        String out = s.replaceAll("(?i)(?:&|\u00a7)#[0-9a-f]{6}", "");
        out = MM_TAG.matcher(out).replaceAll("");
        out = LEGACY.matcher(out).replaceAll("");
        return out.trim();
    }

    /** 是否包含颜色/格式标记（用于禁止名称使用颜色与标签）。 */
    public static boolean hasFormatting(String s) {
        return s != null && (MM_TAG.matcher(s).find() || LEGACY.matcher(s).find());
    }

    /**
     * 转义玩家可控文本，防止 MiniMessage 注入（如 {@code <red>}、{@code <click:...>}）。
     * 仅在需要把玩家文本拼进字符串再交给 MiniMessage 时使用。
     */
    public static String esc(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("<", "\\<");
    }

    /** 文本能否被 MiniMessage 解析；用于写入侧校验，避免畸形格式入库后炸掉渲染。 */
    public static boolean isValidMini(String s) {
        if (s == null || s.isEmpty()) {
            return true;
        }
        try {
            mm(s);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 校验玩家自定义文本：保留颜色与装饰格式，但清除点击/悬浮/插入等交互事件，
     * 返回可安全交给 {@link #fmtRich} 的 MiniMessage 字符串。
     */
    public static String sanitizeToMini(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        try {
            return MM.serialize(stripEvents(mm(s)));
        } catch (Throwable t) {
            // 玩家输入的公告/简介可能包含畸形 MiniMessage（如 "<red" 或未知标签）。
            // 这里必须降级为纯文本，否则一个工会的公告会让该会所有成员的 GUI 打不开。
            return esc(s);
        }
    }

    private static Component stripEvents(Component component) {
        List<Component> children = new ArrayList<>();
        for (Component child : component.children()) {
            children.add(stripEvents(child));
        }
        return component.children(children).clickEvent(null).hoverEvent(null).insertion(null);
    }

    /**
     * 把旧版 {@code &}/{@code §} 颜色代码转换为 MiniMessage 标签，
     * 保留 MiniMessage 原生标签与普通文本。
     */
    static String legacyToMini(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        if (s.indexOf('&') < 0 && s.indexOf('\u00a7') < 0) {
            return s;
        }
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c == '&' || c == '\u00a7') && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                if (next == '#' && i + 8 <= s.length() && isHex(s, i + 2, i + 8)) {
                    out.append("<#").append(s, i + 2, i + 8).append('>');
                    i += 7;
                    continue;
                }
                int idx = LEGACY_CODES.indexOf(Character.toLowerCase(next));
                if (idx >= 0) {
                    out.append(LEGACY_TAGS[idx]);
                    i++;
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    private static boolean isHex(String s, int from, int to) {
        for (int i = from; i < to; i++) {
            if (Character.digit(s.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    /** 是否需要对文本做旧版代码转换（供外部判断）。 */
    public static boolean hasLegacyCode(String s) {
        return s != null && LEGACY.matcher(s).find();
    }

    /** 便捷：把颜色值解析为 Adventure 颜色（供占位符/图标等复用）。 */
    public static TextColor color(String s) {
        return TextColor.fromHexString(s == null ? "" : s.trim().toLowerCase(Locale.ROOT));
    }
}
