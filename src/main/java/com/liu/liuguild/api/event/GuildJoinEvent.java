package com.liu.liuguild.api.event;

import com.liu.liuguild.model.Guild;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;

/** 玩家加入工会。 */
public class GuildJoinEvent extends GuildEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    private final Player player;
    private final boolean viaInvite;

    public GuildJoinEvent(Guild guild, Player player, boolean viaInvite) {
        super(guild);
        this.player = player;
        this.viaInvite = viaInvite;
    }

    public Player player() {
        return player;
    }

    public boolean viaInvite() {
        return viaInvite;
    }
}
