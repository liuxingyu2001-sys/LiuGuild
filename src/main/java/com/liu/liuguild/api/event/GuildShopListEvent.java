package com.liu.liuguild.api.event;

import com.liu.liuguild.model.Guild;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;

/** 工会商店上架成功（成员物品 → 工会货架）。 */
public class GuildShopListEvent extends GuildEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player seller;
    private final String listingId;
    private final ItemStack item;
    private final int amount;
    private final long price;

    public GuildShopListEvent(Guild guild, Player seller, String listingId, ItemStack item,
                              int amount, long price) {
        super(guild);
        this.seller = seller;
        this.listingId = listingId;
        this.item = item;
        this.amount = amount;
        this.price = price;
    }

    public Player seller() {
        return seller;
    }

    public String listingId() {
        return listingId;
    }

    /** 商品样本（数量为 1），修改它不会影响货架物品。 */
    public ItemStack item() {
        return item;
    }

    public int amount() {
        return amount;
    }

    /** 单件售价（工会资金）。 */
    public long price() {
        return price;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
