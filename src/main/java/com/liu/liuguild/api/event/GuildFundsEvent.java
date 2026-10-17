package com.liu.liuguild.api.event;

import com.liu.liuguild.model.Guild;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;

/** 工会个人资金变动（目前仅「捐献入工会公共资金」）。 */
public class GuildFundsEvent extends GuildEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    public enum Action {DONATE}

    private final Player player;
    private final Action action;
    private final long amount;

    public GuildFundsEvent(Guild guild, Player player, Action action, long amount) {
        super(guild);
        this.player = player;
        this.action = action;
        this.amount = amount;
    }

    public Player player() {
        return player;
    }

    public Action action() {
        return action;
    }

    public long amount() {
        return amount;
    }
}
