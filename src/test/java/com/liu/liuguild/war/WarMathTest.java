package com.liu.liuguild.war;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
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
    void drainBatchProcessesIncrementallyAndSurvivesFailures() {
        Deque<Integer> queue = new ArrayDeque<>(List.of(1, 2, 3, 4, 5));
        List<Integer> seen = new ArrayList<>();

        int first = WarMath.drainBatch(queue, 2, n -> {
            if (n == 2) {
                throw new IllegalStateException("boom");
            }
            seen.add(n);
        });
        assertEquals(2, first);
        assertEquals(List.of(1), seen); // 2 抛异常但已出队，不影响后续
        assertEquals(3, queue.size());

        int rest = WarMath.drainBatch(queue, 100, seen::add);
        assertEquals(3, rest);
        assertEquals(List.of(1, 3, 4, 5), seen);
        assertTrue(queue.isEmpty());
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

    @Test
    void mirrorMaxAgeSumsPhasesPlusMargin() {
        long age = WarMath.mirrorMaxAgeMillis(15, 600, 10, WarMath.MIRROR_SAFETY_MARGIN_MILLIS);
        assertEquals((15 + 600 + 10) * 1000L + WarMath.MIRROR_SAFETY_MARGIN_MILLIS, age);
    }

    @Test
    void mirrorMaxAgeFallsBackToTwoHoursWhenPhasesUnknown() {
        long age = WarMath.mirrorMaxAgeMillis(0, 0, 0, WarMath.MIRROR_SAFETY_MARGIN_MILLIS);
        assertEquals(2L * 60L * 60L * 1000L + WarMath.MIRROR_SAFETY_MARGIN_MILLIS, age);
    }

    @Test
    void mirrorIsAliveWithinLifetimeAndExpiredBeyondIt() {
        long created = 1_000_000L;
        long limit = WarMath.mirrorMaxAgeMillis(15, 600, 10, WarMath.MIRROR_SAFETY_MARGIN_MILLIS);
        // 仍在有效期内：不应被视为过期
        assertTrue(!WarMath.mirrorExpired(created, created + limit, 15, 600, 10, WarMath.MIRROR_SAFETY_MARGIN_MILLIS));
        // 超过一个毫秒：过期
        assertTrue(WarMath.mirrorExpired(created, created + limit + 1, 15, 600, 10, WarMath.MIRROR_SAFETY_MARGIN_MILLIS));
    }

    @Test
    void mvpScoreWeighsKillsDeathsAndSurvival() {
        assertEquals(15, WarMath.mvpScore(5, 1, 120)); // 15 - 2 + 2
        assertEquals(-2, WarMath.mvpScore(0, 1, 0));
        assertEquals(0, WarMath.mvpScore(-5, -5, -100), "负数输入按 0 处理");
    }

    @Test
    void kothOwnerOnlyWhenOneTeamOnPoint() {
        assertEquals("A", WarMath.kothOwner(2, 0));
        assertEquals("B", WarMath.kothOwner(0, 3));
        assertEquals("", WarMath.kothOwner(1, 1));
        assertEquals("", WarMath.kothOwner(0, 0));
    }
}
