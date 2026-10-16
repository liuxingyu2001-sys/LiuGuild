package com.liu.liuguild.war;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class SeasonBattleTest {
    private final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
    private SeasonBattle battle(int lives) {
        SeasonBattle battle = new SeasonBattle();
        battle.register("a", List.of(a), lives);
        battle.register("b", List.of(b), lives);
        battle.register("c", List.of(c), lives);
        battle.start(100, 1000);
        return battle;
    }

    @Test void killsScoreAndDeathsOnlyBreakTies() {
        SeasonBattle battle = battle(3);
        assertTrue(battle.death(c, a, 2, 110));
        assertTrue(battle.respawn(c));
        assertTrue(battle.death(a, b, 2, 120));
        var ranks = battle.standings();
        assertEquals("b", ranks.getFirst().guildId());
        assertEquals(2, ranks.getFirst().points());
        assertEquals("a", ranks.get(1).guildId());
        assertEquals(2, ranks.get(1).points(), "死亡不能扣分");
        assertEquals(1, ranks.get(1).deaths());
    }

    @Test void respawningGuildsAreNotEliminatedAndDuplicateDeathsIgnored() {
        SeasonBattle battle = battle(2);
        assertTrue(battle.death(b, a, 1, 110));
        assertFalse(battle.death(b, a, 1, 111));
        assertTrue(battle.death(c, a, 1, 112));
        assertEquals(SeasonBattle.Phase.RUNNING, battle.phase());
        assertTrue(battle.respawn(b));
        assertTrue(battle.respawn(c));
        assertTrue(battle.death(b, a, 1, 113));
        assertTrue(battle.death(c, a, 1, 114));
        assertEquals(SeasonBattle.Phase.FINISHED, battle.phase());
        assertEquals(4, battle.standings().getFirst().points());
        assertFalse(battle.respawn(c));
    }

    @Test void deadlineStopsScoringAndEqualStatsShareRank() {
        SeasonBattle battle = battle(2);
        assertFalse(battle.death(b, a, 1, 1100));
        assertEquals(SeasonBattle.Phase.FINISHED, battle.phase());
        assertTrue(battle.standings().stream().allMatch(row -> row.rank() == 1 && row.points() == 0));
    }

    @Test void insufficientPointsAreReusedDeterministically() {
        SeasonBattle battle = battle(2);
        assertEquals(0, battle.spawnIndex("a", 2));
        assertEquals(1, battle.spawnIndex("b", 2));
        assertEquals(0, battle.spawnIndex("c", 2));
        assertThrows(IllegalArgumentException.class, () -> battle.spawnIndex("a", 0));
    }

    @Test void disconnectEliminatesWithoutAwardingKillPoints() {
        SeasonBattle battle=battle(3);
        battle.eliminate(b,110);
        assertEquals(SeasonBattle.Phase.RUNNING,battle.phase());
        battle.eliminate(c,120);
        assertEquals(SeasonBattle.Phase.FINISHED,battle.phase());
        assertEquals(0,battle.standings().stream().mapToLong(SeasonBattle.Standing::points).sum());
    }

    @Test void registrationIsAtomicAndCannotChangeAfterStart() {
        SeasonBattle battle = new SeasonBattle();
        battle.register("a", List.of(a), 2);
        assertThrows(IllegalArgumentException.class, () -> battle.register("b", List.of(b, a), 2));
        battle.register("b", List.of(b), 2);
        battle.start(100, 1000);
        assertThrows(IllegalStateException.class, () -> battle.register("c", List.of(c), 2));
        assertFalse(battle.death(c, a, 1, 110));
        assertTrue(battle.death(a, a, 1, 111));
        assertEquals(0, battle.standings().stream().mapToLong(SeasonBattle.Standing::points).sum());
    }
}
