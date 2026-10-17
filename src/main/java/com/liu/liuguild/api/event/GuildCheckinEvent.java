package com.liu.liuguild.api.event;

import com.liu.liuguild.model.Guild;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;

/** 成员完成每日签到（个人工会资金已入账，连续天数已更新）。 */
public class GuildCheckinEvent extends GuildEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    private final Player player;
    private final int streak;
    private final long reward;

    public GuildCheckinEvent(Guild guild, Player player, int streak, long reward) {
        super(guild);
        this.player = player;
        this.streak = streak;
        this.reward = reward;
    }

    public Player player() {
        return player;
    }

    /** 签到后的连续天数。 */
    public int streak() {
        return streak;
    }

    /** 本次签到发放的个人工会资金（不含金币）。 */
    public long reward() {
        return reward;
    }
}
