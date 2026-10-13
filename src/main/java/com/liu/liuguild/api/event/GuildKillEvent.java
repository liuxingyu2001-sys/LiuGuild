package com.liu.liuguild.api.event;

import com.liu.liuguild.model.GuildWar;
import org.bukkit.entity.Player;

/** 工会战中的一次击杀（分数已计入）。 */
public class GuildKillEvent extends GuildWarEvent {

    private final Player killer;
    private final Player victim;
    private final int points;

    public GuildKillEvent(GuildWar war, Player killer, Player victim, int points) {
        super(war);
        this.killer = killer;
        this.victim = victim;
        this.points = points;
    }

    public Player killer() {
        return killer;
    }

    public Player victim() {
        return victim;
    }

    public int points() {
        return points;
    }
}
