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
    void meleeAndRankRewardsParseFromWarYmlOrModeFile() throws Exception {
        WarSettings w = war("""
                war:
                  custom-melee:
                    season:
                      melee: true
                      rank-rewards:
                        '1': 7000
                        '2': 4000
                """);
        WarModeSettings m = modes("", w);
        WarMode melee = m.mode("custom-melee");
        assertTrue(melee.melee);
        assertEquals(7000L, melee.rankRewards.get(1));
        assertEquals(4000L, melee.rankRewards.get(2));
        assertFalse(m.mode("custom-melee").rankRewards.containsKey(3));
    }

    @Test
    void seasonIsMeleeAndUsesDefaultRankRewardsWhenNotConfigured() throws Exception {
        WarSettings w = war("");
        WarMode season = modes("modes:\n  season: {}\n", w).mode("season");
        assertTrue(season.melee);
        assertEquals(5000L, season.rankRewards.get(1));
        assertEquals(1000L, season.rankRewards.get(3));
        assertFalse(modes("modes:\n  match: {}\n", w).mode("match").melee);
    }

    @Test
    void meleeRegistrationWindowIsConfigurableAndClamped() throws Exception {
        WarSettings w = war("");
        WarModeSettings m = modes("modes:\n  season:\n    season:\n      registration-seconds: 2\n", w);
        assertEquals(5, m.mode("season").seasonRegistrationSeconds);
        WarModeSettings m2 = modes("modes:\n  season:\n    season:\n      registration-seconds: 45\n", w);
        assertEquals(45, m2.mode("season").seasonRegistrationSeconds);
    }

    @Test
    void extraRewardsInheritUnlessModeExplicitlyOverrides() throws Exception {
        WarSettings w = war("""
                war:
                  defaults:
                    rewards:
                      extra:
                        - name: 默认奖励
                          commands: ["say {player}"]
                """);
        WarModeSettings inherited = modes("modes:\n  match: {}\n", w);
        assertEquals(1, inherited.mode("match").extraRewards.size());
        WarModeSettings cleared = modes("modes:\n  match:\n    rewards:\n      extra: []\n", w);
        assertTrue(cleared.mode("match").extraRewards.isEmpty());
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

    @Test
    void parsesKothObjective() throws Exception {
        WarSettings w = war("");
        WarModeSettings m = modes("""
                modes:
                  koth:
                    objective:
                      type: KOTH
                      points-per-second: 2
                      target: 50
                      name: 中心
                """, w);
        WarMode mode = m.mode("koth");
        assertEquals("koth", mode.objectiveType);
        assertEquals(2, mode.objectivePointsPerSecond);
        assertEquals(50, mode.objectiveTarget);
        assertEquals("中心", mode.objectiveName);
    }

    @Test
    void parsesArenaPool() throws Exception {
        WarSettings w = war("");
        WarModeSettings m = modes("""
                modes:
                  match:
                    arena: main
                    arenas: [main, arena2, arena2]
                """, w);
        assertEquals(java.util.List.of("main", "arena2"), m.mode("match").arenaPool);
        assertTrue(modes("modes:\n  match: {}\n", w).mode("match").arenaPool.isEmpty());
    }

    @Test
    void parsesNameplateSettings() throws Exception {
        WarSettings w = war("""
                war:
                  nameplate:
                    enabled: false
                    format-a: "A:{guild}:{player}"
                    format-b: "B:{guild}"
                """);
        assertFalse(w.nameplateEnabled);
        assertEquals("A:{guild}:{player}", w.nameplateFormatA);
        assertEquals("B:{guild}", w.nameplateFormatB);
    }

    @Test
    void nameplateSectionIsNotParsedAsAWarMode() throws Exception {
        WarSettings w = war("""
                war:
                  nameplate:
                    enabled: true
                    format-a: "A:{guild}"
                    format-b: "B:{guild}"
                  defaults:
                    team-size: 8
                """);
        assertFalse(w.inlineModes.containsKey("nameplate"),
                "nameplate 是全局配置段，不能出现在工会战模式列表里");
        assertEquals(8, w.defaults.teamSize);
    }

    @Test
    void parsesMultiGuildMatchWindows() throws Exception {
        WarSettings w = war("""
                war:
                  match-gather-seconds: 12
                  match-accept-seconds: 8
                """);
        assertEquals(12, w.matchGatherSeconds);
        assertEquals(8, w.matchAcceptSeconds);
    }
}
