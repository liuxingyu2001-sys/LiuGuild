package com.liu.liuguild.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 工会战配置解析的边界（默认模式大小写、时间表清洗、赛季倍率下限）。 */
class WarConfigTest {

    private static WarSettings war(String yaml) throws Exception {
        YamlConfiguration c = new YamlConfiguration();
        c.loadFromString(yaml);
        return WarSettings.parse(c);
    }

    private static WarModeSettings modes(String yaml, WarSettings war) throws Exception {
        YamlConfiguration c = new YamlConfiguration();
        c.loadFromString(yaml);
        return WarModeSettings.parse(c, war);
    }

    @Test
    void defaultModeIsCaseInsensitive() throws Exception {
        WarSettings w = war("war:\n  default-mode: Season\n");
        WarModeSettings m = modes("modes:\n  season:\n    display: 赛季\n  match: {}\n", w);
        assertEquals("season", m.defaultMode);
        assertEquals("赛季", m.mode("season").display);
    }

    @Test
    void unknownDefaultModeFallsBackToARegisteredMode() throws Exception {
        WarSettings w = war("war:\n  default-mode: nope\n");
        WarModeSettings m = modes("modes:\n  season: {}\n  match: {}\n", w);
        assertTrue(m.modes.containsKey(m.defaultMode));
    }

    @Test
    void blankScheduleEntriesAreDropped() throws Exception {
        WarSettings w = war("");
        WarModeSettings m = modes("modes:\n  season:\n    schedule: [' ', 'sat 20:00', '']\n", w);
        assertEquals(List.of("sat 20:00"), m.mode("season").schedule);
    }

    @Test
    void modesCanOverrideEquipmentAndHealthIndependently() throws Exception {
        WarSettings w = war("war:\n  defaults:\n    fair-equipment: true\n    fair-health: true\n");
        WarModeSettings m = modes("""
                modes:
                  match:
                    fair-equipment: true
                    fair-health: true
                  owngear:
                    fair-equipment: false
                    fair-health: false
                """, w);
        assertTrue(m.mode("match").fairEquipment);
        assertFalse(m.mode("owngear").fairEquipment);
        assertFalse(m.mode("owngear").fairHealth);
    }

    @Test
    void modeRulesCanLiveDirectlyBesideDefaultsInWarYml() throws Exception {
        WarSettings w = war("""
                war:
                  defaults:
                    fair-equipment: true
                    team-size: 8
                  match:
                    arena: main
                  owngear:
                    fair-equipment: false
                    fair-health: false
                """);
        WarModeSettings m = modes("modes:\n  match:\n    duration-seconds: 120\n", w);
        assertEquals(8, m.mode("owngear").teamSize);
        assertFalse(m.mode("owngear").fairEquipment);
        assertFalse(m.mode("owngear").fairHealth);
        assertEquals("main", m.mode("match").arena);
        assertEquals(120, m.mode("match").durationSeconds);
        assertFalse(w.inlineModes.containsKey("defaults"));
        assertFalse(w.inlineModes.containsKey("score"));
    }

    @Test
    void warModesSectionAndDirectModeShareTheSameLayer() throws Exception {
        WarSettings w = war("""
                war:
                  owngear:
                    fair-equipment: false
                  modes:
                    owngear:
                      fair-health: false
                """);
        WarModeSettings m = modes("", w);
        assertFalse(m.mode("owngear").fairEquipment);
        assertFalse(m.mode("owngear").fairHealth);
    }

    @Test
    void warModesFileWinsOverWarYmlInlineRules() throws Exception {
        WarSettings w = war("war:\n  owngear:\n    fair-equipment: false\n");
        WarModeSettings m = modes("modes:\n  owngear:\n    fair-equipment: true\n", w);
        assertTrue(m.mode("owngear").fairEquipment);
    }

    @Test
    void globalBroadcastQueueAppliesUnlessModeOverridesIt() throws Exception {
        WarSettings w = war("war:\n  broadcast-queue: false\n");
        WarModeSettings m = modes("modes:\n  match: {}\n  season:\n    broadcast-queue: true\n", w);
        assertFalse(m.mode("match").broadcastQueue);
        assertTrue(m.mode("season").broadcastQueue);
    }

    @Test
    void seasonMultiplierNeverDropsBelowOne() throws Exception {
        WarSettings w = war("");
        WarModeSettings m = modes("modes:\n  season:\n    season:\n      points-multiplier: 0.25\n", w);
        assertEquals(1.0, m.mode("season").seasonPointsMultiplier);
    }
}
