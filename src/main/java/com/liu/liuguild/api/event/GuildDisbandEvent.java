package com.liu.liuguild.api.event;

import com.liu.liuguild.model.Guild;
import org.bukkit.event.HandlerList;

/** 工会解散。 */
public class GuildDisbandEvent extends GuildEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    public enum Cause {PLAYER, ADMIN, INACTIVE}

    private final Cause cause;

    public GuildDisbandEvent(Guild guild, Cause cause) {
        super(guild);
        this.cause = cause;
    }

    public Cause cause() {
        return cause;
    }
}
