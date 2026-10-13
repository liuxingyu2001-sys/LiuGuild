package com.liu.liuguild.api.event;

import com.liu.liuguild.model.Guild;
import org.bukkit.entity.Player;

/** 工会创建完成（已在内存登记，落库异步进行）。 */
public class GuildCreateEvent extends GuildEvent {

    private final Player creator;

    public GuildCreateEvent(Guild guild, Player creator) {
        super(guild);
        this.creator = creator;
    }

    public Player creator() {
        return creator;
    }
}
