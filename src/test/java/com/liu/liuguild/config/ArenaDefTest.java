package com.liu.liuguild.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Round-11 tests: fully customizable arena / temporary-world / after-war settings. */
class ArenaDefTest {

    private static ArenaDef parse(String yaml) {
        YamlConfiguration c = new YamlConfiguration();
        try {
            c.loadFromString(yaml);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return ArenaDef.parse("main", c);
    }

    @Test
    void parsesServerWorldTemplateAndSpawns() {
        ArenaDef a = parse("""
                server: arena-1
                world: arena_world
                template: guild_arena
                radius: 64
                spawn-a: { x: 1.5, y: 65.0, z: 2.5, yaw: 90.0, pitch: 10.0 }
                spawn-b: { x: 30.0, y: 65.0, z: 2.5 }
                spawn-spectator: { x: 15.0, y: 70.0, z: 15.0 }
                """);
        assertEquals("arena-1", a.server);
        assertEquals("arena_world", a.world);
        assertTrue(a.hasTemplate());
        assertEquals(64, a.radius);
        assertEquals(1.5, a.spawnA.x());
        assertEquals(90f, a.spawnA.yaw());
        assertEquals(30.0, a.spawnB.x());
    }

    @Test
    void parsesTemporaryWorldSettings() {
        ArenaDef a = parse("""
                temporary-world:
                  keep-on-end: true
                  builtin: true
                  world-type: normal
                  environment: THE_END
                  name-format: "war_{id}"
                  time: 18000
                  weather: thunder
                  difficulty: HARD
                  gamerules:
                    doDaylightCycle: false
                    keepInventory: true
                """);
        assertTrue(a.tempWorld.keepOnEnd);
        assertTrue(a.tempWorld.builtin);
        assertEquals("normal", a.tempWorld.worldType);
        assertEquals("THE_END", a.tempWorld.environment);
        assertEquals("war_{id}", a.tempWorld.nameFormat);
        assertEquals(18000, a.tempWorld.time);
        assertEquals("thunder", a.tempWorld.weather);
        assertEquals("HARD", a.tempWorld.difficulty);
        assertEquals(Boolean.FALSE, a.tempWorld.gamerules.get("doDaylightCycle"));
        assertEquals(Boolean.TRUE, a.tempWorld.gamerules.get("keepInventory"));
    }

    @Test
    void parsesAfterWarSettings() {
        ArenaDef a = parse("""
                after-war:
                  auto-return: false
                  return-delay-seconds: 5
                  end-spawn:
                    enabled: true
                    world: lobby
                    x: 100.5
                    y: 64.0
                    z: -20.5
                    yaw: 45.0
                """);
        assertFalse(a.afterWar.autoReturn);
        assertEquals(5, a.afterWar.returnDelaySeconds);
        assertTrue(a.afterWar.endSpawnEnabled);
        assertEquals("lobby", a.afterWar.endSpawnWorld);
        assertEquals(100.5, a.afterWar.endSpawn.x());
        assertEquals(-20.5, a.afterWar.endSpawn.z());
    }

    @Test
    void supportsArbitraryNamedSpawnPoints() {
        ArenaDef a = parse("""
                spawn-a: { x: 1.0, y: 65.0, z: 1.0 }
                spawn-b: { x: 2.0, y: 65.0, z: 2.0 }
                spawn-c: { x: 3.0, y: 65.0, z: 3.0 }
                spawn-d: { x: 4.0, y: 65.0, z: 4.0 }
                spawn-spectator: { x: 9.0, y: 70.0, z: 9.0 }
                team-a-spawn: c
                team-b-spawn: d
                """);
        assertEquals(3.0, a.spawn("c").x());
        assertEquals(4.0, a.spawn("d").x());
        assertEquals(9.0, a.spawn("spec").x(), "spec 应映射到 spectator");
        assertEquals(java.util.List.of("a", "b", "c", "d"), a.spawnPointNames());
        assertEquals(3.0, a.teamSpawn("A").x(), "A 队应使用 team-a-spawn=c");
        assertEquals(4.0, a.teamSpawn("B").x(), "B 队应使用 team-b-spawn=d");
    }

    @Test
    void teamSpawnDefaultsToAandB() {
        ArenaDef a = parse("""
                spawn-a: { x: 1.0, y: 65.0, z: 1.0 }
                spawn-b: { x: 2.0, y: 65.0, z: 2.0 }
                """);
        assertEquals("a", a.teamASpawn);
        assertEquals("b", a.teamBSpawn);
        assertEquals(1.0, a.teamSpawn("A").x());
        assertEquals(2.0, a.teamSpawn("B").x());
        assertEquals(null, a.spawn("zzz"));
    }

    @Test
    void defaultsAreSaneWhenUnset() {
        ArenaDef a = parse("world: world");
        assertTrue(a.afterWar.autoReturn);
        assertEquals(0, a.afterWar.returnDelaySeconds);
        assertFalse(a.afterWar.endSpawnEnabled);
        assertFalse(a.tempWorld.keepOnEnd);
        assertFalse(a.tempWorld.builtin);
        assertEquals("flat", a.tempWorld.worldType);
        assertEquals("NORMAL", a.tempWorld.environment);
        assertEquals(-1, a.tempWorld.time);
    }
}
