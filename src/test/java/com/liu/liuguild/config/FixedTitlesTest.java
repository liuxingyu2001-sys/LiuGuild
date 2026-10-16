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
}
