package com.liu.liuguild.api.event;

import com.liu.liuguild.model.Guild;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;

/** 玩家离开工会（主动退出 / 被踢 / 解散）。 */
public class GuildLeaveEvent extends GuildEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    public enum Cause {QUIT, KICK, DISBAND}

    private final Player player;
    private final Cause cause;

    public GuildLeaveEvent(Guild guild, Player player, Cause cause) {
        super(guild);
        this.player = player;
        this.cause = cause;
    }

    public Player player() {
        return player;
    }

    public Cause cause() {
        return cause;
    }
}
