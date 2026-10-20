package com.liu.liuguild.config;

import com.liu.liuguild.util.Text;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 所有消息模板都必须能被 MiniMessage 解析。
 *
 * <p>尤其防止手写按钮标签（click/hover）出现引号、嵌套标签损坏：
 * 这种问题只会在游戏内某条消息触发时抛异常，静态 YAML 校验发现不了。</p>
 */
class MessageRenderTest {

    @Test
    void everyDefaultMessageRenders() throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString(Files.readString(Path.of("src/main/resources/messages.yml")));
        for (String section : cfg.getKeys(false)) {
            var sec = cfg.getConfigurationSection(section);
            if (sec == null) {
                continue;
            }
            for (String key : sec.getKeys(true)) {
                if (sec.isConfigurationSection(key)) {
                    continue;
                }
                String raw = sec.getString(key);
                if (raw == null) {
                    continue;
                }
                try {
                    assertNotNull(Text.fmt(raw), section + "." + key);
                } catch (RuntimeException e) {
                    fail(section + "." + key + " 渲染失败: " + e.getMessage());
                }
            }
        }
    }

    @Test
    void notificationButtonsDoNotShowCommands() throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString(Files.readString(Path.of("src/main/resources/messages.yml")));
        var sec = cfg.getConfigurationSection("zh_CN");
        assertNotNull(sec);

        assertButton(sec.getString("mail-login-summary"), "count", "3");
        assertButton(sec.getString("invite-received"), "guild", "测试工会");
        assertButton(sec.getString("unknown-command"));
        assertButton(sec.getString("title-list-hint"));
        assertButton(sec.getString("guild-chat-on"));
        assertButton(sec.getString("app-notify"),
                "player", "Steve", "guild", "测试工会", "message", "");
        assertButton(sec.getString("join-help-line1"));
        assertButton(sec.getString("join-help-line2"));
        assertButton(sec.getString("join-help-line3"));
    }

    private static void assertButton(String raw, Object... kv) {
        assertNotNull(raw);
        var component = Text.fmt(raw, kv);
        assertFalse(Text.plain(component).contains("/guild"),
                "通知中不应出现可见指令: " + Text.plain(component));
        assertTrue(hasClick(component), "通知应包含可点击按钮: " + Text.plain(component));
    }

    private static boolean hasClick(net.kyori.adventure.text.Component c) {
        if (c.clickEvent() != null) {
            return true;
        }
        for (var child : c.children()) {
            if (hasClick(child)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void titleMessagesDoNotExposeInternalIds() throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString(Files.readString(Path.of("src/main/resources/messages.yml")));
        var sec = cfg.getConfigurationSection("zh_CN");
        assertNotNull(sec);
        for (String key : new String[]{"title-list-line", "title-list-line-req", "title-list-line-equipped"}) {
            String raw = sec.getString(key);
            assertNotNull(raw, key);
            assertFalse(raw.contains("{id}"), key + " 不应显示内部头衔标识");
        }
    }
}
