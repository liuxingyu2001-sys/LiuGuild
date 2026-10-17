package com.liu.liuguild.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** config.yml 的 liupass 钩子开关解析。 */
class MainSettingsTest {

    @Test
    void liupassHookDefaults() {
        MainSettings s = MainSettings.parse(new YamlConfiguration());
        assertTrue(s.passEnabled);
        assertTrue(s.passJoin);
        assertTrue(s.passCreate);
        assertTrue(s.passWar);
        assertTrue(s.passWarWin);
        assertTrue(s.passWarKill);
        assertTrue(s.passCheckin);
        assertTrue(s.passDonate);
        // 银行存取可循环刷进度，默认关闭；旧版任务 ID 绑定默认保留
        assertFalse(s.passBankDeposit);
        assertTrue(s.passLegacyTaskIds);
    }

    @Test
    void liupassHookOverrides() {
        YamlConfiguration c = new YamlConfiguration();
        c.set("hooks.liupass.enabled", false);
        c.set("hooks.liupass.war", false);
        c.set("hooks.liupass.bank-deposit", true);
        c.set("hooks.liupass.legacy-task-ids", false);

        MainSettings s = MainSettings.parse(c);
        assertFalse(s.passEnabled);
        assertFalse(s.passWar);
        assertTrue(s.passBankDeposit);
        assertFalse(s.passLegacyTaskIds);
        // 未覆盖的键保持默认
        assertTrue(s.passJoin);
        assertTrue(s.passCheckin);
    }

    @Test
    void qqBotDefaultsAndOverrides() {
        MainSettings defaults = MainSettings.parse(new YamlConfiguration());
        assertFalse(defaults.qqBotEnabled);
        assertEquals(33554432, defaults.qqBotIntents);
        assertEquals("!", defaults.qqBotCommandPrefix);
        assertEquals(5, defaults.qqBotReconnectSeconds);

        YamlConfiguration c = new YamlConfiguration();
        c.set("qqbot.enabled", true);
        c.set("qqbot.app-id", "123");
        c.set("qqbot.client-secret", "secret");
        c.set("qqbot.sandbox", true);
        c.set("qqbot.intents", 33554944);
        c.set("qqbot.push.type", "group");
        c.set("qqbot.push.id", "g1");
        c.set("qqbot.command-prefix", "!");
        c.set("qqbot.reconnect-seconds", 99);
        MainSettings s = MainSettings.parse(c);
        MainSettings.applyServerHooks(s, c);
        assertTrue(s.qqBotEnabled);
        assertEquals("123", s.qqBotAppId);
        assertEquals("group", s.qqBotPushType);
        assertEquals("g1", s.qqBotPushId);
        assertEquals(60, s.qqBotReconnectSeconds);

        YamlConfiguration webhook = new YamlConfiguration();
        webhook.set("webhook.enabled", true);
        webhook.set("webhook.url", "https://example.test/hook");
        webhook.set("webhook.events.guild-create", true);
        MainSettings w = MainSettings.parse(new YamlConfiguration());
        MainSettings.applyServerHooks(w, webhook);
        assertTrue(w.webhookEnabled);
        assertEquals("https://example.test/hook", w.webhookUrl);
        assertTrue(w.webhookGuildCreate);
    }

    @Test
    void externalNotificationsAreNotReadFromSharedConfig() {
        YamlConfiguration shared = new YamlConfiguration();
        shared.set("hooks.qqbot.enabled", true);
        shared.set("hooks.webhook.enabled", true);
        MainSettings s = MainSettings.parse(shared);
        assertFalse(s.qqBotEnabled, "QQ 机器人不能从共享 config.yml 启用");
        assertFalse(s.webhookEnabled, "webhook 不能从共享 config.yml 启用");
    }
}
