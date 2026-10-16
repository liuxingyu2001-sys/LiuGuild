package com.liu.liuguild.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ConfigFileMigrationTest {
    @TempDir Path root;

    @Test
    void migratesValuesAndDeletionsOnceThenAllowsManualEditing() throws Exception {
        Files.writeString(root.resolve("arena.yml"), "# 场地\narenas:\n  main:\n    world: jjc\n    server: zy\n  old:\n    world: old\n");
        Files.createDirectories(root.resolve("overrides"));
        String overlay = "arenas:\n  main:\n    spawn-a:\n      x: 123.0\n  old: __liuguild_deleted__\n";
        Files.writeString(root.resolve("overrides/arena.yml"), overlay);
        assertTrue(ConfigManager.migrateLegacyFile(root.toFile(), "arena.yml"));
        var config = YamlConfiguration.loadConfiguration(root.resolve("arena.yml").toFile());
        assertEquals("jjc", config.getString("arenas.main.world"));
        assertEquals("zy", config.getString("arenas.main.server"));
        assertEquals(123.0, config.getDouble("arenas.main.spawn-a.x"));
        assertFalse(config.contains("arenas.old"));
        assertFalse(Files.exists(root.resolve("overrides/arena.yml")));
        try (var backups = Files.list(root.resolve("migration-backups"))) {
            assertEquals(overlay, Files.readString(backups.findFirst().orElseThrow()));
        }
        ConfigManager.updateFile(root.toFile(), "arena.yml", "arenas.main.world", "new_world");
        assertFalse(ConfigManager.migrateLegacyFile(root.toFile(), "arena.yml"));
        config = YamlConfiguration.loadConfiguration(root.resolve("arena.yml").toFile());
        assertEquals("new_world", config.getString("arenas.main.world"));
        assertEquals(123.0, config.getDouble("arenas.main.spawn-a.x"));
        assertTrue(Files.readString(root.resolve("arena.yml")).contains("# 场地"));
    }

    @Test
    void invalidOverlayLeavesBothFilesIntact() throws Exception {
        String base = "arenas: {}\n";
        String invalid = "arenas: [broken\n";
        Files.writeString(root.resolve("arena.yml"), base);
        Files.createDirectories(root.resolve("overrides"));
        Files.writeString(root.resolve("overrides/arena.yml"), invalid);
        assertThrows(Exception.class, () -> ConfigManager.migrateLegacyFile(root.toFile(), "arena.yml"));
        assertEquals(base, Files.readString(root.resolve("arena.yml")));
        assertEquals(invalid, Files.readString(root.resolve("overrides/arena.yml")));
    }

    @Test
    void directEditsReloadDiskAndRemoveNodesWithoutCreatingOverlay() throws Exception {
        Files.writeString(root.resolve("arena.yml"), "arenas:\n  main:\n    world: jjc\n    server: zy\n");
        ConfigManager.updateFile(root.toFile(), "arena.yml", "arenas.main.spawn-a.x", 42);
        var config = YamlConfiguration.loadConfiguration(root.resolve("arena.yml").toFile());
        config.set("arenas.main.server", "pvp");
        config.save(root.resolve("arena.yml").toFile());
        ConfigManager.updateFile(root.toFile(), "arena.yml", "arenas.main.world", "jjsj");
        config = YamlConfiguration.loadConfiguration(root.resolve("arena.yml").toFile());
        assertEquals("pvp", config.getString("arenas.main.server"));
        assertEquals(42, config.getInt("arenas.main.spawn-a.x"));
        assertEquals("jjsj", config.getString("arenas.main.world"));
        ConfigManager.updateFile(root.toFile(), "arena.yml", "arenas.main", null);
        assertFalse(YamlConfiguration.loadConfiguration(root.resolve("arena.yml").toFile()).contains("arenas.main"));
        assertFalse(Files.exists(root.resolve("overrides")));
    }
}
