package com.liu.liuguild.api.event;

import com.liu.liuguild.model.Guild;
import org.bukkit.event.HandlerList;

/** 工会升级。 */
public class GuildLevelUpEvent extends GuildEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    private final int oldLevel;
    private final int newLevel;

    public GuildLevelUpEvent(Guild guild, int oldLevel, int newLevel) {
        super(guild);
        this.oldLevel = oldLevel;
        this.newLevel = newLevel;
    }

    public int oldLevel() {
        return oldLevel;
    }

    public int newLevel() {
        return newLevel;
    }
}
