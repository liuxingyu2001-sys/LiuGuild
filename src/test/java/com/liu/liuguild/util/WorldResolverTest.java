package com.liu.liuguild.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 世界目录识别：兼容 Paper 1.21.11+ / Leaf 26.x 的 dimensions 布局。 */
class WorldResolverTest {

    @TempDir
    File container;

    private File worldDir(String relative) throws IOException {
        File dir = new File(container, relative);
        Files.createDirectories(dir.toPath());
        Files.writeString(new File(dir, "level.dat").toPath(), "");
        return dir;
    }

    @Test
    void resolvesNewDimensionLayout() throws IOException {
        File jjc = worldDir("zy/dimensions/minecraft/jjc");
        assertEquals(jjc, WorldResolver.findFolder(container, "jjc"));
        assertEquals(jjc, WorldResolver.findFolder(container, "minecraft:jjc"));
    }

    @Test
    void resolvesOtherNamespaceAndLegacyLayout() throws IOException {
        File custom = worldDir("zy/dimensions/custom/arena_x");
        assertEquals(custom, WorldResolver.findFolder(container, "arena_x"));

        File legacy = worldDir("old_world");
        assertEquals(legacy, WorldResolver.findFolder(container, "old_world"));
    }

    @Test
    void avoidsMatchingNonWorldFolders() throws IOException {
        Files.createDirectories(new File(container, "zy/dimensions/minecraft/notaworld").toPath());
        assertNull(WorldResolver.findFolder(container, "notaworld"));
        assertNull(WorldResolver.findFolder(container, "missing"));
    }

    @Test
    void keyNameStripsNamespace() {
        assertEquals("jjc", WorldResolver.keyName("minecraft:jjc"));
        assertEquals("jjc", WorldResolver.keyName("jjc"));
        assertEquals("", WorldResolver.keyName(null));
    }

    @Test
    void detectsTransientDungeonAndArenaWorlds() {
        assertTrue(WorldResolver.isTransientWorld("ld_dungeoncolosseum_25754e940a53485d9bbddd0a2546aa4e"));
        assertTrue(WorldResolver.isTransientWorld("ldext_arena_123"));
        assertTrue(WorldResolver.isTransientWorld("minecraft:ld_world_abc"));
        assertTrue(WorldResolver.isTransientWorld(null));
        assertTrue(WorldResolver.isTransientWorld(" "));
        assertFalse(WorldResolver.isTransientWorld("world"));
        assertFalse(WorldResolver.isTransientWorld("jjc"));
        assertFalse(WorldResolver.isTransientWorld("guild_arena_static"));
    }
}
