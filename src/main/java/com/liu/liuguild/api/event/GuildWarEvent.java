package com.liu.liuguild.api.event;

import com.liu.liuguild.model.GuildWar;
import org.bukkit.event.Event;

/** 所有工会战事件的基类。 */
public abstract class GuildWarEvent extends Event {

    private final GuildWar war;

    protected GuildWarEvent(GuildWar war) {
        this.war = war;
    }

    public GuildWar war() {
        return war;
    }

    public String warId() {
        return war == null ? null : war.id();
    }
}
