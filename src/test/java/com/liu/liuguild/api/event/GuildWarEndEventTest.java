package com.liu.liuguild.api.event;

import com.liu.liuguild.model.GuildWar;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GuildWarEndEventTest {

    @Test
    void thirdPartyWinnerIsReportedAsOtherWin() {
        GuildWar war = new GuildWar("w", "g1", "g2");
        war.addParty("g3", "三会", "G3");

        GuildWarEndEvent event = new GuildWarEndEvent(war, GuildWarEndEvent.Result.OTHER_WIN, "g3");

        assertEquals(GuildWarEndEvent.Result.OTHER_WIN, event.result());
        assertEquals("g3", event.winnerGuildId());
    }

    @Test
    void legacyConstructorStillResolvesSideWinners() {
        GuildWar war = new GuildWar("w", "g1", "g2");

        assertEquals("g1", new GuildWarEndEvent(war, GuildWarEndEvent.Result.A_WIN).winnerGuildId());
        assertEquals("g2", new GuildWarEndEvent(war, GuildWarEndEvent.Result.B_WIN).winnerGuildId());
        assertEquals("", new GuildWarEndEvent(war, GuildWarEndEvent.Result.DRAW).winnerGuildId());
        assertEquals("", new GuildWarEndEvent(war, GuildWarEndEvent.Result.CANCELLED).winnerGuildId());
    }
}
