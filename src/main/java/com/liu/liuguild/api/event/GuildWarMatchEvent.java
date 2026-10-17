package com.liu.liuguild.api.event;

import com.liu.liuguild.model.GuildWar;
import org.bukkit.event.HandlerList;

/** 两个工会匹配成功。 */
public class GuildWarMatchEvent extends GuildWarEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    public GuildWarMatchEvent(GuildWar war) {
        super(war);
    }
}
