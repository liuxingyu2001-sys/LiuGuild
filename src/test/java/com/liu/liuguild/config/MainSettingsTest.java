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
        c.set("hooks.qqbot.enabled", true);
        c.set("hooks.qqbot.app-id", "123");
        c.set("hooks.qqbot.client-secret", "secret");
        c.set("hooks.qqbot.sandbox", true);
        c.set("hooks.qqbot.intents", 33554944);
        c.set("hooks.qqbot.push.type", "group");
        c.set("hooks.qqbot.push.id", "g1");
        c.set("hooks.qqbot.command-prefix", "!");
        c.set("hooks.qqbot.reconnect-seconds", 99);
        MainSettings s = MainSettings.parse(c);
        assertTrue(s.qqBotEnabled);
        assertEquals("123", s.qqBotAppId);
        assertEquals("group", s.qqBotPushType);
        assertEquals("g1", s.qqBotPushId);
        assertEquals(60, s.qqBotReconnectSeconds);
    }
}
