package com.liu.liuguild.api.event;

import com.liu.liuguild.model.GuildWar;
import org.bukkit.event.HandlerList;

/** 工会战结束。 */
public class GuildWarEndEvent extends GuildWarEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    public enum Result {A_WIN, B_WIN, DRAW, CANCELLED}

    private final Result result;

    public GuildWarEndEvent(GuildWar war, Result result) {
        super(war);
        this.result = result;
    }

    public Result result() {
        return result;
    }

    public String winnerGuildId() {
        return switch (result) {
            case A_WIN -> war().guildA();
            case B_WIN -> war().guildB();
            default -> "";
        };
    }
}
