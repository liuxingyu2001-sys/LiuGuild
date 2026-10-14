package com.liu.liuguild.model;

import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * 工会商店的一条上架记录。
 *
 * <p>一个 listing = 一种物品 + 一个价格 + 一份库存。物品本体完整序列化保存，
 * 所以附魔 / 自定义名 / CraftEngine 物品都能原样卖出去。</p>
 */
public final class ShopListing {

    private final String id;
    private final String guildId;
    /** Base64(serializeAsBytes)，固定单件。 */
    private final String itemData;
    /** 展示用物品（从 itemData 反序列化，可能为 null）。 */
    private final ItemStack preview;
    private volatile int stock;
    /** 玩家从工会买走的单价。 */
    private volatile long price;
    /** 工会向成员收购的单价；0 = 不收购。 */
    private volatile long buyPrice;
    private final UUID seller;
    private final String sellerName;
    private final long createdAt;

    public ShopListing(String id, String guildId, String itemData, ItemStack preview,
                       int stock, long price, long buyPrice,
                       UUID seller, String sellerName, long createdAt) {
        this.id = id;
        this.guildId = guildId;
        this.itemData = itemData;
        this.preview = preview;
        this.stock = stock;
        this.price = price;
        this.buyPrice = buyPrice;
        this.seller = seller;
        this.sellerName = sellerName;
        this.createdAt = createdAt;
    }

    public String id() {
        return id;
    }

    public String guildId() {
        return guildId;
    }

    public String itemData() {
        return itemData;
    }

    public ItemStack preview() {
        return preview == null ? null : preview.clone();
    }

    public int stock() {
        return stock;
    }

    public void stock(int stock) {
        this.stock = Math.max(0, stock);
    }

    public long price() {
        return price;
    }

    public void price(long price) {
        this.price = Math.max(0, price);
    }

    public long buyPrice() {
        return buyPrice;
    }

    public void buyPrice(long buyPrice) {
        this.buyPrice = Math.max(0, buyPrice);
    }

    /** 工会是否收购这种物品。 */
    public boolean buying() {
        return buyPrice > 0;
    }

    public boolean soldOut() {
        return stock <= 0;
    }

    public UUID seller() {
        return seller;
    }

    public String sellerName() {
        return sellerName;
    }

    public long createdAt() {
        return createdAt;
    }
}
