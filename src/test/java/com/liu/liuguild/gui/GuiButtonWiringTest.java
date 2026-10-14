package com.liu.liuguild.gui;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * GUI 按钮接线守卫。
 *
 * <p>曾真实发生过的问题：gui.yml 里的按钮写在了菜单层级（没放在 {@code buttons:} 下），
 * 或者 id 拼错，于是被 {@code actionId} 的 {@code default -> buttonId} 原样透传，
 * 点击后**静默无反应**（例如“创建工会”按钮曾长期点不动）。</p>
 *
 * <p>这里把 gui.yml 的每个按钮 id 过一遍 {@link GuiManager#actionId(String)}，
 * 再校验结果确实是 {@code handleAction} 认识的 action。</p>
 */
class GuiButtonWiringTest {

    /** 已知且有意为之的“纯展示”按钮：点了不做事。 */
    private static final Set<String> INTENTIONAL_NOOP = Set.of(
            "info",      // 主菜单名片（点击改为打开详情，这里兜底）
            "card",      // 详情面板名片
            "help",      // 无工会引导（改为发送引导后不再走 noop）
            "close");

    private static String guiSource() throws Exception {
        return Files.readString(Path.of("src/main/resources/gui.yml"));
    }

    private static String guiManagerSource() throws Exception {
        return Files.readString(Path.of("src/main/java/com/liu/liuguild/gui/GuiManager.java"));
    }

    /** 收集 gui.yml 里所有「带 slot 的按钮」id 及其所属菜单。 */
    private static List<String[]> buttons() throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString(guiSource());
        List<String[]> out = new ArrayList<>();
        ConfigurationSection gui = cfg.getConfigurationSection("gui");
        if (gui == null) {
            return out;
        }
        for (String menuName : gui.getKeys(false)) {
            ConfigurationSection menu = gui.getConfigurationSection(menuName);
            if (menu == null) {
                continue;
            }
            ConfigurationSection buttons = menu.getConfigurationSection("buttons");
            if (buttons == null) {
                continue;
            }
            for (String id : buttons.getKeys(false)) {
                ConfigurationSection b = buttons.getConfigurationSection(id);
                if (b != null && b.isSet("slot")) {
                    out.add(new String[]{menuName, id});
                }
            }
        }
        return out;
    }

    /** 从 handleAction 的 switch 里取出所有已处理的字面量 action。 */
    private static Set<String> handledActions() throws Exception {
        String src = guiManagerSource();
        // 只扫 handleAction 方法体
        int start = src.indexOf("private void handle(Player player, Holder holder, String action)");
        assertTrue(start > 0, "找不到 handle(...)");
        int braceStart = src.indexOf('{', start);
        int depth = 0;
        int end = braceStart;
        for (int i = braceStart; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    end = i;
                    break;
                }
            }
        }
        // handle(...) 的 switch + handleDynamic 的 if-else 链都要算
        int dynStart = src.indexOf("private void handleDynamic(");
        String body = src.substring(start, end) + (dynStart > 0 ? src.substring(dynStart) : "");
        Set<String> out = new LinkedHashSet<>();
        Matcher m = Pattern.compile("case \"([a-zA-Z:_]+)\"").matcher(body);
        while (m.find()) {
            out.add(m.group(1));
        }
        // handleDynamic 里用 action.equals("...") 判断的固定 action 也要算
        String q = "\"";
        Matcher e = Pattern.compile("action\\.equals\\(" + q + "([a-zA-Z_]+)" + q + "\\)").matcher(body);
        while (e.find()) {
            out.add(e.group(1));
        }
        return out;
    }

    /** 动态分支前缀（形如 action.startsWith("xxx:")）。 */
    private static Set<String> dynamicPrefixes() throws Exception {
        String src = guiManagerSource();
        Set<String> out = new LinkedHashSet<>();
        Matcher m = Pattern.compile("action\\.startsWith\\(\"([a-zA-Z_]+):\"\\)").matcher(src);
        while (m.find()) {
            out.add(m.group(1) + ":");
        }
        return out;
    }

    @Test
    void everyConfiguredButtonResolvesToAHandledAction() throws Exception {
        Set<String> handled = handledActions();
        Set<String> prefixes = dynamicPrefixes();
        List<String> broken = new ArrayList<>();
        for (String[] pair : buttons()) {
            String menu = pair[0];
            String id = pair[1];
            if (INTENTIONAL_NOOP.contains(id)) {
                continue;
            }
            String action = GuiManager.actionId(id);
            boolean ok = handled.contains(action);
            if (!ok) {
                // 动态 action（如 war_queue:<key>）只要前缀被识别即可
                for (String p : prefixes) {
                    if (action.startsWith(p)) {
                        ok = true;
                        break;
                    }
                }
            }
            if (!ok) {
                broken.add(menu + "." + id + " -> " + action);
            }
        }
        if (!broken.isEmpty()) {
            fail("这些 GUI 按钮点击后没有任何处理逻辑（会静默无反应）: " + broken);
        }
    }

    @Test
    void everyButtonHasASlot() throws Exception {
        // 按钮必须写在 buttons: 下且带 slot，否则永远不会被渲染
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString(guiSource());
        ConfigurationSection gui = cfg.getConfigurationSection("gui");
        assertTrue(gui != null);
        List<String> bad = new ArrayList<>();
        for (String menuName : gui.getKeys(false)) {
            ConfigurationSection menu = gui.getConfigurationSection(menuName);
            if (menu == null) {
                continue;
            }
            ConfigurationSection buttons = menu.getConfigurationSection("buttons");
            if (buttons == null) {
                continue;
            }
            for (String id : buttons.getKeys(false)) {
                ConfigurationSection b = buttons.getConfigurationSection(id);
                if (b == null || !b.isSet("slot")) {
                    bad.add(menuName + "." + id);
                }
            }
        }
        if (!bad.isEmpty()) {
            fail("这些按钮没有 slot（不会被渲染，等于死按钮）: " + bad);
        }
    }

    @Test
    void destructivesAreNotOnTheMainMenu() throws Exception {
        // 「退出工会 / 解散工会」属于危险操作，必须放在子菜单里，避免主界面误点
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString(guiSource());
        ConfigurationSection main = cfg.getConfigurationSection("gui.main.buttons");
        assertTrue(main != null);
        for (String dangerous : List.of("leave", "disband")) {
            assertTrue(!main.contains(dangerous),
                    "危险操作 " + dangerous + " 不应出现在主菜单（玩家容易误操作）");
        }
    }

    /**
     * 代码里直接登记的动作（{@code holder.actions.put(n, "xxx")}）也必须被处理。
     *
     * <p>这一条抓的是真实发生过的 bug：{@code admin:wars_back} 在两个列表里都用来做
     * “返回”，但 handle() 里从未处理，导致返回按钮**点了没反应**。
     * （注意字符类必须包含冒号，否则 {@code "admin:xxx"} 这类动作根本匹配不到。）</p>
     */
    @Test
    void everyCodeRegisteredActionIsHandled() throws Exception {
        Set<String> handled = handledActions();
        Set<String> prefixes = dynamicPrefixes();
        String src = guiManagerSource();
        List<String> broken = new ArrayList<>();
        // 匹配 holder.actions.put(槽位, "字面量动作")；动态拼接（"war_edit:" + id）由前缀另判
        Matcher m = Pattern.compile("actions\\.put\\(\\s*[^,]+,\\s*\"([a-zA-Z_:]+)\"\\)").matcher(src);
        while (m.find()) {
            String action = m.group(1);
            boolean ok = handled.contains(action);
            if (!ok) {
                for (String p : prefixes) {
                    if (action.startsWith(p)) {
                        ok = true;
                        break;
                    }
                }
            }
            if (!ok) {
                broken.add(action);
            }
        }
        if (!broken.isEmpty()) {
            fail("这些在代码里登记的动作没有任何处理逻辑（按钮/条目会点了没反应）: " + broken);
        }
    }
}
