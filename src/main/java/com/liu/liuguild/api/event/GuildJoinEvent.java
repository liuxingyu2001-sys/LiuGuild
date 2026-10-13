package com.liu.liuguild.api.event;

import com.liu.liuguild.model.Guild;
import org.bukkit.entity.Player;

/** 玩家加入工会。 */
public class GuildJoinEvent extends GuildEvent {

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
