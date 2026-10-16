package com.liu.liuguild.command;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 指令表一致性守卫：
 * <ul>
 *   <li>每个 {@code case "xxx"} 子命令都必须出现在 Tab 补全的 {@code SUBS} 里；</li>
 *   <li>每个子命令都必须出现在 {@code /guild help} 里（或者是有意隐藏的别名）。</li>
 * </ul>
 * 这类漂移很难靠人工发现（此前 rename / members / queue 就漏了）。
 */
class CommandSurfaceTest {

    private static final Path CMD = Path.of("src/main/java/com/liu/liuguild/command/GuildCommand.java");

    /**
     * 不单独占一行的子命令：
     * <ul>
     *   <li>别名的另一种写法（help/menu、icon/seticon、hq/home、c/chat、sign|qiandao/checkin）；</li>
     *   <li>{@code /guild top} 的排序参数（wins/losses/level/members，已在 top 的说明里列出）。</li>
     * </ul>
     */
    private static final List<String> ALIASES_OR_SUBOPTIONS = List.of(
            "help", "menu", "icon", "hq", "c", "sign", "qiandao",
            "wh", "cangku", "titles", "chenghao", "wins", "losses", "level", "members",
            "shangdian", "store");

    @Test
    void adminWarStartOpensEventWithoutGuildArguments() throws Exception {
        String src = Files.readString(Path.of("src/main/java/com/liu/liuguild/command/GuildAdminCommand.java"));
        String war = src.substring(src.indexOf("private void war("));
        String start = war.substring(war.indexOf("case \"start\""), war.indexOf("case \"stop\""));
        assertTrue(start.contains("require(sender, args, 3)"));
        assertTrue(start.contains("forceSeason(args[2])"));
        assertTrue(!start.contains("find(args[3])") && !start.contains("forceStart("));
        String manager = Files.readString(Path.of("src/main/java/com/liu/liuguild/war/WarManager.java"));
        assertTrue(manager.contains("startSeason(mode(modeKey), true)"));
        assertTrue(manager.contains("return plugin.seasons().start(modeKey)"));
        assertTrue(manager.contains("!forceEnqueue && !mode.autoEnqueue"));
    }

    @Test
    void adminStopAllHasCrossServerAndSeasonPaths() throws Exception {
        String admin = Files.readString(Path.of("src/main/java/com/liu/liuguild/command/GuildAdminCommand.java"));
        assertTrue(admin.contains("args[2].equalsIgnoreCase(\"all\")"));
        assertTrue(admin.contains("plugin.war().stopAll()"));
        String war = Files.readString(Path.of("src/main/java/com/liu/liuguild/war/WarManager.java"));
        assertTrue(war.contains("publish(\"WAR_STOP_ALL\""));
        assertTrue(war.contains("plugin.sync(this::stopAllLocal)"));
        assertTrue(war.contains("plugin.seasons().stopAll()"));
    }

    private static String source() throws Exception {
        return Files.readString(CMD);
    }

    /** 顶层 switch 里的 case 标签（args[0]）——只扫第一个 switch 块，避开嵌套 switch。 */
    private static List<String> topLevelCases(String src) {
        int start = src.indexOf("switch (sub)");
        if (start < 0) {
            start = src.indexOf("switch (args[0]");
        }
        if (start < 0) {
            throw new IllegalStateException("找不到顶层 switch");
        }
        // 从 switch 开始，到「第一个方法体的收尾」为止：用深度计数截取整个 switch 块
        int braceStart = src.indexOf('{', start);
        int depth = 0;
        int end = braceStart;
        for (int i = braceStart; i < src.length(); i++) {
            char ch = src.charAt(i);
            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    end = i;
                    break;
                }
            }
        }
        String body = src.substring(start, end);
        List<String> out = new ArrayList<>();
        // 只取第一层 case（缩进 12 空格），嵌套 switch 的 case 缩进更深
        Matcher m = Pattern.compile("^ {12}case ((?:\"[a-z_]+\"\\s*,?\\s*)+)\\s*->", Pattern.MULTILINE).matcher(body);
        while (m.find()) {
            Matcher inner = Pattern.compile("\"([a-z_]+)\"").matcher(m.group(1));
            while (inner.find()) {
                out.add(inner.group(1));
            }
        }
        return out;
    }

    private static List<String> subs(String src) {
        Matcher m = Pattern.compile("SUBS = List\\.of\\(([^;]+)\\);", Pattern.DOTALL).matcher(src);
        if (!m.find()) {
            throw new IllegalStateException("找不到 SUBS 定义");
        }
        List<String> out = new ArrayList<>();
        Matcher inner = Pattern.compile("\"([a-z_]+)\"").matcher(m.group(1));
        while (inner.find()) {
            out.add(inner.group(1));
        }
        return out;
    }

    private static List<String> helpMentions(String src) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("helpLine\\(player, \"[^\"]+\", \"/guild ([a-z]+)").matcher(src);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    @Test
    void everySubcommandIsTabCompletable() throws Exception {
        String src = source();
        List<String> subs = subs(src);
        List<String> missing = new ArrayList<>();
        for (String c : topLevelCases(src)) {
            if (!subs.contains(c)) {
                missing.add(c);
            }
        }
        if (!missing.isEmpty()) {
            fail("这些子命令没有出现在 SUBS（Tab 补全不到）: " + missing);
        }
    }

    @Test
    void everySubcommandIsDocumentedInHelp() throws Exception {
        String src = source();
        List<String> help = helpMentions(src);
        List<String> subs = subs(src);
        List<String> missing = new ArrayList<>();
        for (String c : subs) {
            if (ALIASES_OR_SUBOPTIONS.contains(c)) {
                continue;
            }
            if (!help.contains(c)) {
                missing.add(c);
            }
        }
        if (!missing.isEmpty()) {
            fail("这些子命令没有出现在 /guild help 里: " + missing);
        }
    }

    @Test
    void helpDoesNotAdvertiseNonExistentSubcommands() throws Exception {
        String src = source();
        List<String> subs = subs(src);
        for (String c : helpMentions(src)) {
            assertTrue(subs.contains(c), "/guild help 宣传了不存在的子命令: " + c);
        }
    }
}
