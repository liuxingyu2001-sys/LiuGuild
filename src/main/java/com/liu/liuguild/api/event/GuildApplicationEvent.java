package com.liu.liuguild.api.event;

import com.liu.liuguild.model.Guild;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;

/** 工会入会申请状态变化（提交/批准/拒绝）。 */
public class GuildApplicationEvent extends GuildEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    public enum Action {SUBMIT, APPROVE, REJECT}

    private final Player actor;
    private final Action action;
    private final String applicationId;

    public GuildApplicationEvent(Guild guild, Player actor, Action action, String applicationId) {
        super(guild);
        this.actor = actor;
        this.action = action;
        this.applicationId = applicationId;
    }

    /** 提交者为申请人；批准/拒绝者为操作官员。 */
    public Player actor() {
        return actor;
    }

    public Action action() {
        return action;
    }

    public String applicationId() {
        return applicationId;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
