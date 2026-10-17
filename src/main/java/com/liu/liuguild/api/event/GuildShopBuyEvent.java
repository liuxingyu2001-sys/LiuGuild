package com.liu.liuguild.api.event;

import com.liu.liuguild.model.Guild;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;

/** 工会商店购买成功（工会 → 成员）。 */
public class GuildShopBuyEvent extends GuildEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player buyer;
    private final String listingId;
    private final ItemStack item;
    private final int amount;
    private final long paid;
    private final long net;

    public GuildShopBuyEvent(Guild guild, Player buyer, String listingId, ItemStack item,
                             int amount, long paid, long net) {
        super(guild);
        this.buyer = buyer;
        this.listingId = listingId;
        this.item = item;
        this.amount = amount;
        this.paid = paid;
        this.net = net;
    }

    public Player buyer() {
        return buyer;
    }

    public String listingId() {
        return listingId;
    }

    /** 商品样本（数量为 1），修改它不会影响已发货物品。 */
    public ItemStack item() {
        return item;
    }

    public int amount() {
        return amount;
    }

    /** 买家支付总额。 */
    public long paid() {
        return paid;
    }

    /** 税后进入工会公共资金的金额。 */
    public long net() {
        return net;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
