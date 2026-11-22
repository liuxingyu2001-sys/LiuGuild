package com.liu.liuguild.config;

import com.liu.liuguild.model.GuildRank;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FixedTitlesTest {
    @Test
    void oldSettingsUseFixedDefaultsInsteadOfCreationLimits() {
        var config = new YamlConfiguration();
        config.set("guild.titles.max", 100);
        config.set("guild.titles.manage-rank", "MEMBER");
        var settings = GuildSettings.parse(config);
        assertEquals(3, settings.fixedTitles.size());
        assertFalse(settings.fixedTitles.get("leader").usableBy(GuildRank.MEMBER));
        assertTrue(settings.fixedTitles.get("leader").usableBy(GuildRank.LEADER));
    }

    @Test
    void explicitPresetsReplaceDefaultsAndCanBeEmpty() {
        var config = new YamlConfiguration();
        config.set("guild.titles.presets.guard.display", "<gold>工会卫士");
        config.set("guild.titles.presets.guard.min-rank", "OFFICER");
        var settings = GuildSettings.parse(config);
        assertEquals(1, settings.fixedTitles.size());
        assertEquals("<gold>工会卫士", settings.fixedTitles.get("guard").display());
        assertFalse(settings.fixedTitles.get("guard").usableBy(GuildRank.MEMBER));
        config.set("guild.titles.presets", null);
        config.createSection("guild.titles.presets");
        assertTrue(GuildSettings.parse(config).fixedTitles.isEmpty());
    }

    @Test
    void invalidPresetIsRejected() {
        var config = new YamlConfiguration();
        config.set("guild.titles.presets.bad.display", " ");
        assertThrows(IllegalArgumentException.class, () -> GuildSettings.parse(config));
    }

    // =====================================================================
    // 自动职位头衔（未手动装备固定头衔时的回退显示）
    // =====================================================================

    @Test
    void autoRankTitleDefaultsToPositionNames() {
        var settings = GuildSettings.parse(new YamlConfiguration());
        assertTrue(settings.autoRankTitle, "默认开启：未装备固定头衔时自动显示职位");
        assertEquals("<white>成员", settings.autoRankTitle(GuildRank.MEMBER));
        assertEquals("<aqua>副会长", settings.autoRankTitle(GuildRank.OFFICER));
        assertEquals("<gold>会长", settings.autoRankTitle(GuildRank.LEADER));
        assertEquals("", settings.autoRankTitle(null));
    }

    @Test
    void autoRankTitleOverridesConfiguredRanksOnly() {
        var config = new YamlConfiguration();
        config.set("guild.titles.auto-rank.enabled", true);
        config.set("guild.titles.auto-rank.texts.officer", "&b副会长");
        config.set("guild.titles.auto-rank.texts.member", ""); // 显式留空 = 不显示
        var settings = GuildSettings.parse(config);
        assertEquals("&b副会长", settings.autoRankTitle(GuildRank.OFFICER));
        assertEquals("", settings.autoRankTitle(GuildRank.MEMBER));
        assertEquals("<gold>会长", settings.autoRankTitle(GuildRank.LEADER), "未写的职位保留默认");
    }

    @Test
    void autoRankTitleCanBeDisabled() {
        var config = new YamlConfiguration();
        config.set("guild.titles.auto-rank.enabled", false);
        var settings = GuildSettings.parse(config);
        assertFalse(settings.autoRankTitle);
        assertEquals("", settings.autoRankTitle(GuildRank.LEADER));
    }

    @Test
    void invalidAutoRankTextIsRejected() {
        var config = new YamlConfiguration();
        config.set("guild.titles.auto-rank.texts.leader", "<red>"); // 只有颜色标签，没有可见文字
        assertThrows(IllegalArgumentException.class, () -> GuildSettings.parse(config));
    }
}
