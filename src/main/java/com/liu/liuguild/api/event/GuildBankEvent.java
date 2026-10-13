package com.liu.liuguild.api.event;

import com.liu.liuguild.model.Guild;
import org.bukkit.entity.Player;

/** 工会银行存取。 */
public class GuildBankEvent extends GuildEvent {

    public enum Action {DEPOSIT, WITHDRAW}

    private final Player player;
    private final Action action;
    private final long amount;
    private final long balance;

    public GuildBankEvent(Guild guild, Player player, Action action, long amount, long balance) {
        super(guild);
        this.player = player;
        this.action = action;
        this.amount = amount;
        this.balance = balance;
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

    public long balance() {
        return balance;
    }
}
