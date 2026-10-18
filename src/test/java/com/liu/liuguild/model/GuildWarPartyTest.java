package com.liu.liuguild.model;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuildWarPartyTest {

    @Test
    void supportsThirdAndLaterPartiesWithStableSideKeys() {
        GuildWar war = new GuildWar("w", "g1", "g2");
        GuildWar.Party c = war.addParty("g3", "三会", "G3");
        assertEquals("C", c.side());
        assertEquals(3, war.parties().size());
        assertEquals("g3", war.guildOfSide("C"));
        assertEquals("三会", war.nameOfSide("C"));
    }

    @Test
    void ranksByKillsThenDeathsThenGuildId() {
        GuildWar war = new GuildWar("w", "g1", "g2");
        GuildWar.Party c = war.addParty("g3", "三会", "G3");
        war.rosterA().add(UUID.randomUUID());
        war.rosterB().add(UUID.randomUUID());
        c.roster().add(UUID.randomUUID());
        war.addScore("A", 5);
        war.addScore("B", 5);
        war.addScore("C", 5);
        war.addDeath("A"); // A 同分但死亡更多，应排最后
        assertEquals("g2", war.ranking().get(0).guildId());
        assertEquals(war.ranking().get(0).guildId(), war.firstPlace());
    }

    @Test
    void removesPlayerFromTheCorrectParty() {
        GuildWar war = new GuildWar("w", "g1", "g2");
        UUID player = UUID.randomUUID();
        war.rosterA().add(player);
        assertTrue(war.containsPlayer(player));
        assertTrue(war.removePlayer(player));
        assertFalse(war.containsPlayer(player));
        assertEquals(0, war.rosterA().size());
    }

    @Test
    void legacyScoreSettersKeepPartyRankingInSync() {
        GuildWar war = new GuildWar("w", "g1", "g2");
        war.rosterA().add(UUID.randomUUID());
        war.rosterB().add(UUID.randomUUID());
        war.scoreA(7);
        war.scoreB(3);
        assertEquals(7, war.scoreOfSide("A"));
        assertEquals("g1", war.firstPlace());
    }

    @Test
    void fourPartiesKeepAllPlayersAndRanking() {
        GuildWar war = new GuildWar("w", "g1", "g2");
        war.addParty("g3", "三会", "G3");
        war.addParty("g4", "四会", "G4");
        for (GuildWar.Party p : war.parties()) {
            p.roster().add(UUID.randomUUID());
        }
        war.scoreA(10);
        war.addScore("B", 8);
        war.addScore("C", 12);
        war.addScore("D", 3);
        assertEquals(4, war.parties().size());
        assertEquals(4, war.allPlayers().size());
        assertEquals("g3", war.firstPlace());
        assertEquals(12, war.ranking().get(0).score());
    }
}
