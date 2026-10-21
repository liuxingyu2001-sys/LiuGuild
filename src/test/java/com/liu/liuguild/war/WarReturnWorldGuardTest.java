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
}
