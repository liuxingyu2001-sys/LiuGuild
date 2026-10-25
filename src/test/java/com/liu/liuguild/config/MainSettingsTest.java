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
    void warPetAndMcMmoDefaults() {
        MainSettings defaults = MainSettings.parse(new YamlConfiguration());
        assertTrue(defaults.petsBlockInWar);
        assertTrue(defaults.mcmmoBlockInWar);

        YamlConfiguration c = new YamlConfiguration();
        c.set("hooks.pets.block-in-war", false);
        c.set("hooks.mcmmo.block-in-war", false);
        MainSettings s = MainSettings.parse(c);
        assertFalse(s.petsBlockInWar);
        assertFalse(s.mcmmoBlockInWar);
    }

    @Test
    void qqBotDefaultsAndOverrides() {
        MainSettings defaults = MainSettings.parse(new YamlConfiguration());
        assertFalse(defaults.qqBotEnabled);
        assertEquals(33554432, defaults.qqBotIntents);
        assertEquals("!", defaults.qqBotCommandPrefix);
        assertEquals(5, defaults.qqBotReconnectSeconds);

        YamlConfiguration c = new YamlConfiguration();
        c.set("qqbot.enable", true);
        c.set("qqbot.app-id", "123");
        c.set("qqbot.app-secret", "secret");
        c.set("qqbot.sandbox", true);
        c.set("qqbot.connection.gateway", "wss://example.test/ws");
        c.set("qqbot.connection.reconnect-seconds", 2);
        c.set("qqbot.connection.reconnect-max-seconds", 40);
        c.set("qqbot.connection.timeout-seconds", 20);
        c.set("qqbot.intents", java.util.List.of("group", "guild"));
        c.set("qqbot.intents-bits", 0);
        c.set("qqbot.receive.enable", false);
        c.set("qqbot.receive.channels", java.util.List.of("g1"));
        c.set("qqbot.receive.log-targets", false);
        c.set("qqbot.send.enable", true);
        c.set("qqbot.send.targets", java.util.List.of("group:g1", "channel:c1"));
        c.set("qqbot.send.interval-ms", 1500);
        c.set("qqbot.send.max-length", 1800);
        c.set("qqbot.send.passive-reply", false);
        c.set("qqbot.command-prefix", "!");
        MainSettings s = MainSettings.parse(c);
        MainSettings.applyServerHooks(s, c);
        assertTrue(s.qqBotEnabled);
        assertEquals("123", s.qqBotAppId);
        assertEquals("secret", s.qqBotClientSecret);
        assertEquals((1 << 25) | (1 << 30), s.qqBotIntents);
        assertEquals(2, s.qqBotReconnectSeconds);
        assertEquals(40, s.qqBotReconnectMaxSeconds);
        assertEquals(20, s.qqBotTimeoutSeconds);
        assertFalse(s.qqBotReceiveEnabled);
        assertEquals(java.util.Set.of("g1"), s.qqBotReceiveChannels);
        assertFalse(s.qqBotLogTargets);
        assertTrue(s.qqBotSendEnabled);
        assertEquals(java.util.List.of("group:g1", "channel:c1"), s.qqBotSendTargets);
        assertEquals(1500, s.qqBotSendIntervalMs);
        assertEquals(1800, s.qqBotSendMaxLength);
        assertFalse(s.qqBotPassiveReply);
    }

    @Test
    void qqBotKeepsLegacyCredentialAndPushKeys() {
        YamlConfiguration legacy = new YamlConfiguration();
        legacy.set("qqbot.enabled", true);
        legacy.set("qqbot.app-id", "123");
        legacy.set("qqbot.client-secret", "secret");
        legacy.set("qqbot.intents", 33554432);
        legacy.set("qqbot.push.type", "group");
        legacy.set("qqbot.push.id", "g1");
        MainSettings s = MainSettings.parse(new YamlConfiguration());
        MainSettings.applyServerHooks(s, legacy);
        assertTrue(s.qqBotEnabled);
        assertEquals("secret", s.qqBotClientSecret);
        assertEquals(33554432, s.qqBotIntents);
        assertTrue(s.qqBotSendEnabled);
        assertEquals(java.util.List.of("group:g1"), s.qqBotSendTargets);
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
