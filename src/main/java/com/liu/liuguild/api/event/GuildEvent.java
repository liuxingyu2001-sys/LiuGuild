package com.liu.liuguild.api.event;

import com.liu.liuguild.model.Guild;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** 所有工会事件的基类。 */
public abstract class GuildEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Guild guild;

    protected GuildEvent(Guild guild) {
        this.guild = guild;
    }

    public Guild guild() {
        return guild;
    }

    public String guildId() {
        return guild == null ? null : guild.id();
    }

    public String guildName() {
        return guild == null ? null : guild.name();
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
