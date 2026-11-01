package com.liu.liuguild.war;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 赛后返程不能把副本临时世界的旧坐标套到主世界。 */
class WarReturnWorldGuardTest {

    private String war() throws Exception {
        return Files.readString(Path.of("src/main/java/com/liu/liuguild/war/WarManager.java"));
    }

    @Test
    void originOfNeverPersistsTransientWorld() throws Exception {
        String source = war();
        int from = source.indexOf("private WarOrigin originOf(");
        int to = source.indexOf("private WarOrigin rememberOrigin(", from);
        assertTrue(from >= 0 && to > from);
        String method = source.substring(from, to);
        assertTrue(method.contains("WorldResolver.isTransientWorld(loc.getWorld().getName())"));
        assertTrue(method.contains("WorldResolver.defaultSpawn()"));
    }

    @Test
    void localReturnFallsBackToSpawnInsteadOfOldCoordinates() throws Exception {
        String source = war();
        int from = source.indexOf("private Location originDestination(");
        int to = source.indexOf("private Location safeReturnLocation(", from);
        assertTrue(from >= 0 && to > from);
        String method = source.substring(from, to);
        assertTrue(method.contains("WorldResolver.isTransientWorld(origin.world())"));
        assertTrue(method.contains("WorldResolver.defaultSpawn()"));
        assertTrue(method.contains("return new Location(world, origin.x()"));
        assertTrue(source.contains("destination = safeReturnLocation(returnLoc.get(uuid));"));
    }

    @Test
    void arenaWorldIsDestroyedOnlyAfterPlayersReturn() throws Exception {
        String source = war();
        // 赛末不能立即销毁临时世界，否则异步还原中的玩家会先落到承办服主城，再被送回出发服。
        int from = source.indexOf("private void cleanup(GuildWar war, boolean publishTerminal)");
        int to = source.indexOf("private void scheduleArenaTeardown(", from);
        assertTrue(from >= 0 && to > from);
        String cleanup = source.substring(from, to);
        assertTrue(cleanup.contains("scheduleArenaTeardown(war, arenaRef, builtin, dynamic, warPlayers, fallback, 0)"),
                "临时竞技场必须延迟到参赛者离场后再销毁");
        assertTrue(!cleanup.contains("destroyBuiltinWorld(builtin);"), "收尾阶段直接销毁会制造两段传送");
        assertTrue(source.contains("private boolean arenaReusedByAnotherSession"), "延迟期间要防止误删新场次的世界");
        String season = Files.readString(Path.of("src/main/java/com/liu/liuguild/war/SeasonService.java"));
        assertTrue(season.contains("scheduleSeasonWorldClose(closingArena, closingWorld, 0)"));
        assertTrue(season.contains("closeSeasonWorldIfCurrent"));
        assertTrue(season.contains("!arenaWorld.getPlayers().isEmpty()"),
                "赛季关世界要等世界内没有玩家（含已提前淘汰、返程中的成员）");
        assertTrue(source.contains("pluginOwnedWorlds"),
                "延迟收尾中的内置世界不能被同名新场次复用");
        String repo = Files.readString(Path.of("src/main/java/com/liu/liuguild/war/SeasonRepository.java"));
        assertTrue(repo.contains("AND state<>'LEFT'"), "统一集合点不能覆盖已离场（提前淘汰）玩家的出发地");
    }
}
