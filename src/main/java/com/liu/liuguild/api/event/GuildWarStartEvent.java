package com.liu.liuguild.api.event;

import com.liu.liuguild.model.GuildWar;
import org.bukkit.event.HandlerList;

/** 工会战正式开打（进入 ACTIVE）。 */
public class GuildWarStartEvent extends GuildWarEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    public GuildWarStartEvent(GuildWar war) {
        super(war);
    }
}
