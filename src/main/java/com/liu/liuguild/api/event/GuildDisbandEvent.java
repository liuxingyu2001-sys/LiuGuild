package com.liu.liuguild.api.event;

import com.liu.liuguild.model.Guild;

/** 工会解散。 */
public class GuildDisbandEvent extends GuildEvent {

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
