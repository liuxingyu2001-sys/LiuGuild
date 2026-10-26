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

    public enum Result {A_WIN, B_WIN, OTHER_WIN, DRAW, CANCELLED}

    private final Result result;
    private final String winnerGuildId;

    public GuildWarEndEvent(GuildWar war, Result result) {
        this(war, result, "");
    }

    /**
     * @param winnerGuildId 实际获胜工会；N 公会混战时可能是 C/D…，不能只靠 A/B 推断
     */
    public GuildWarEndEvent(GuildWar war, Result result, String winnerGuildId) {
        super(war);
        this.result = result;
        String resolved = winnerGuildId == null ? "" : winnerGuildId;
        if (resolved.isBlank() && result == Result.A_WIN) {
            resolved = war.guildA();
        } else if (resolved.isBlank() && result == Result.B_WIN) {
            resolved = war.guildB();
        }
        this.winnerGuildId = resolved;
    }

    public Result result() {
        return result;
    }

    /** 获胜工会 id；平局/取消为空。N 公会混战时直接返回真实冠军。 */
    public String winnerGuildId() {
        return winnerGuildId;
    }
}
