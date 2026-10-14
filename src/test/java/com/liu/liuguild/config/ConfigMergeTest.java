package com.liu.liuguild.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 共享配置「覆盖层」合并语义测试：
 * 游戏内编辑只写入 overrides/，并通过本合并覆盖到基础文件之上，基础文件（含注释）不被破坏。
 */
class ConfigMergeTest {

    @Test
    void overridesTakePrecedenceAndDeletionsAreApplied() {
        YamlConfiguration base = new YamlConfiguration();
        base.set("arenas.main.world", "world");
        base.set("arenas.main.radius", 32);
        base.set("arenas.main.spawn-a.x", 1.0);
        base.set("arenas.old.world", "nether");

        YamlConfiguration overrides = new YamlConfiguration();
        overrides.set("arenas.main.radius", 64);
        overrides.set("arenas.main.spawn-a.x", 99.0);
        overrides.set("arenas.old", ConfigManager.DELETED);

        ConfigManager.mergeOverrides(base, overrides);

        assertEquals(64, base.getInt("arenas.main.radius"));
        assertEquals(99.0, base.getDouble("arenas.main.spawn-a.x"));
        assertEquals("world", base.getString("arenas.main.world"));
        assertFalse(base.isSet("arenas.old"));
    }

    @Test
    void deletedSectionCannotBeResurrectedByChildOverrides() {
        YamlConfiguration base = new YamlConfiguration();
        base.set("arenas.main.world", "world");
        YamlConfiguration overrides = new YamlConfiguration();
        // 同一个覆盖层里既要改子键、又要删除父节点：删除必须胜出。
        overrides.set("arenas.main.world", "nether");
        overrides.set("arenas.main", ConfigManager.DELETED);

        ConfigManager.mergeOverrides(base, overrides);

        assertFalse(base.isSet("arenas.main"));
    }

    @Test
    void fillMissingAddsNewDefaultsWithoutOverwritingUserValues() {
        YamlConfiguration user = new YamlConfiguration();
        user.set("gui.main.buttons.war.slot", 22);
        user.set("gui.main.title", "自定义标题");

        YamlConfiguration defaults = new YamlConfiguration();
        defaults.set("gui.main.title", "默认标题");
        defaults.set("gui.main.buttons.war.slot", 99);
        defaults.set("gui.main.buttons.checkin.slot", 28);
        defaults.set("gui.noguild.title", "无工会");

        ConfigManager.fillMissing(user, defaults);

        assertEquals("自定义标题", user.getString("gui.main.title"));   // 已有值保留
        assertEquals(22, user.getInt("gui.main.buttons.war.slot"));       // 已有值保留
        assertEquals(28, user.getInt("gui.main.buttons.checkin.slot"));   // 新键补齐
        assertEquals("无工会", user.getString("gui.noguild.title"));      // 新菜单补齐
    }

    @Test
    void overridesCanAddNewKeys() {
        YamlConfiguration base = new YamlConfiguration();
        base.set("war.enabled", true);
        YamlConfiguration overrides = new YamlConfiguration();
        overrides.set("war.bossbar", false);
        overrides.set("war.newmode.team-size", 5);

        ConfigManager.mergeOverrides(base, overrides);

        assertTrue(base.getBoolean("war.enabled"));
        assertFalse(base.getBoolean("war.bossbar"));
        assertEquals(5, base.getInt("war.newmode.team-size"));
    }
}
