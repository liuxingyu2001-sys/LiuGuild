package com.liu.liuguild.war;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Round-3 regression tests for war life-pool math. */
class WarMathTest {

    private static UUID id() {
        return UUID.randomUUID();
    }

    @Test
    void distributesLivesEvenlyWithRemainder() {
        List<UUID> team = List.of(id(), id(), id());
        Map<UUID, Integer> lives = WarMath.distributeLives(team, 8);
        int sum = lives.values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(8, sum);
        assertTrue(lives.values().stream().allMatch(v -> v >= 2));
    }

    @Test
    void everyPlayerGetsAtLeastOneLife() {
        List<UUID> team = List.of(id(), id(), id(), id());
        Map<UUID, Integer> lives = WarMath.distributeLives(team, 0);
        assertEquals(4, lives.size());
        assertTrue(lives.values().stream().allMatch(v -> v >= 1));
    }

    @Test
    void balancedPoolsMatchAcrossUnequalTeams() {
        // 3 vs 1 with 3 lives each: pools are equalized to max(3, 1*3) = 3 per side,
        // so the solo player gets 3 lives against the trio's 3 combined lives.
        int[] pools = WarMath.teamLifePools(true, 3, 3, 1);
        assertEquals(pools[0], pools[1]);
        assertEquals(3, pools[0]);
    }

    @Test
    void unbalancedPoolsScaleWithTeamSize() {
        int[] pools = WarMath.teamLifePools(false, 2, 4, 2);
        assertEquals(8, pools[0]);
        assertEquals(4, pools[1]);
    }

    @Test
    void balancedSinglePlayerPerSideStaysAtConfiguredLives() {
        int[] pools = WarMath.teamLifePools(true, 3, 1, 1);
        assertEquals(3, pools[0]);
        assertEquals(3, pools[1]);
    }

    @Test
    void emptyTeamProducesEmptyDistribution() {
        assertTrue(WarMath.distributeLives(List.of(), 5).isEmpty());
    }
}
