package com.liu.liuguild.shop;

import com.liu.liuguild.LiuGuild;
import com.liu.liuguild.model.Guild;
import com.liu.liuguild.model.GuildMember;
import com.liu.liuguild.model.GuildRank;
import com.liu.liuguild.model.ShopListing;
import com.liu.liuguild.module.GuildModule;
import com.liu.liuguild.util.ItemCodec;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工会商店：工会把自己的物品明码标价挂出来，<b>只供本会成员购买</b>。
 * 购买用成员自己的<b>个人工会资金</b>支付，税后货款进入工会<b>点数</b>（公共工会资金）。
 *
 * <p><b>工会隔离</b>：上架记录以 {@code guild_id} 为准，每个工会的商店物品完全独立，
 * 工会之间不能互相看到或购买对方的商品（因此也不存在“全服共享商店”）。</p>
 *
 * <p>不再提供收购；Vault 金币与工会银行不参与商店交易。</p>
 *
 * <h3>钱与物的顺序</h3>
 * <p>买卖最怕「扣了钱没给货」或「给了货没扣钱」。个人资金扣款、库存扣减和工会点数入账
 * 在数据库同一事务里完成（见 {@link ShopRepository#purchase}），任一步失败都整体回滚。</p>
 *
 * <h3>并发</h3>
 * <p>库存扣减走数据库原子 UPDATE（见 {@link ShopRepository#tryDecrement}），
 * 内存缓存只是展示用 —— 抢购的最后一件由数据库裁决，抢不到的人不会拿到东西。</p>
 */
public final class ShopService implements GuildModule, Listener {

    private final LiuGuild plugin;
    private final ShopRepository repo;

    /** listingId → listing（展示用缓存；库存以数据库为准）。 */
    private final Map<String, ShopListing> cache = new ConcurrentHashMap<>();
    private boolean listenerRegistered;

    public ShopService(LiuGuild plugin) {
        this.plugin = plugin;
        this.repo = new ShopRepository(plugin.db());
    }

    @Override
    public String id() {
        return "shop";
    }

    @Override
    public int order() {
        return 57;
    }

    @Override
    public void enable(LiuGuild plugin) {
        if (!listenerRegistered) {
            Bukkit.getPluginManager().registerEvents(this, plugin);
            listenerRegistered = true;
        }
        if (!plugin.settings().guild.shopEnabled) {
            return;
        }
        plugin.async(() -> {
            try {
                List<ShopListing> all = repo.loadAll();
                cache.clear();
                for (ShopListing l : all) {
                    cache.put(l.id(), l);
                }
            } catch (Exception e) {
                plugin.getLogger().warning("加载工会商店失败: " + e.getMessage());
            }
        });
    }

    @Override
    public void disable() {
        // 每笔交易即时入库，无需关闭时落库
    }

    @Override
    public void onCrossServer(String type, Map<String, String> data) {
        if (!"GUILD_SHOP".equals(type)) {
            return;
        }
        // 回音：本服自己发的广播也会回来。本地内存已是最新，
        // 重载可能与异步写入竞争，把刚上架的商品冲掉。
        String from = data.get("server");
        if (from != null && from.equals(plugin.serverId())) {
            return;
        }
        String guildId = data.get("guild");
        if (guildId != null) {
            reloadGuild(guildId);
        }
    }

    @Override
    public void reload() {
        enable(plugin);
    }

    // =====================================================================
    // 读取
    // =====================================================================

    public ShopListing listing(String id) {
        return id == null ? null : cache.get(id);
    }

    /** 某工会的全部上架（按创建时间）。这是商店唯一的可见范围，工会之间互不可见。 */
    public List<ShopListing> listingsOf(String guildId) {
        List<ShopListing> out = new ArrayList<>();
        for (ShopListing l : cache.values()) {
            if (l.guildId().equals(guildId)) {
                out.add(l);
            }
        }
        out.sort(java.util.Comparator.comparingLong(ShopListing::createdAt));
        return out;
    }

    public void reloadGuild(String guildId) {
        plugin.async(() -> {
            try {
                List<ShopListing> list = repo.loadGuild(guildId);
                cache.values().removeIf(l -> l.guildId().equals(guildId));
                for (ShopListing l : list) {
                    cache.put(l.id(), l);
                }
            } catch (Exception e) {
                plugin.getLogger().warning("重载工会商店失败(" + guildId + "): " + e.getMessage());
            }
        });
    }

    // =====================================================================
    // 上架 / 下架 / 改价
    // =====================================================================

    /** 为什么不能上架（返回消息 key；null = 可以）。 */
    public String validateNew(Guild g, Player player, ItemStack item, long price) {
        if (!plugin.settings().guild.shopEnabled) {
            return "shop-disabled";
        }
        if (g == null) {
            return "not-in-guild";
        }
        if (item == null || item.getType().isAir()) {
            return "shop-hand-empty";
        }
        if (price < 0) {
            return "shop-price-invalid";
        }
        int max = plugin.settings().guild.shopMaxListings;
        if (listingsOf(g.id()).size() >= max) {
            return "shop-listing-limit";
        }
        // LiuShop 禁售的物品不允许上架 —— 否则黑名单形同虚设
        var ls = plugin.liuShop();
        if (ls != null && ls.available()) {
            String id = ls.idOf(item);
            if (id != null && ls.isBlacklisted(id)) {
                return "shop-blacklisted";
            }
        }
        return null;
    }

    /**
     * 上架：从玩家手里扣掉物品，建一条 listing。
     *
     * @param amount 上架数量（会被限制到手里实际拥有与单次上限）
     * @return 消息 key；null = 成功（成功时会给出提示，用 {@link #lastError} 风格另发）
     */
    public String addListing(Guild g, Player player, ItemStack sample, int amount, long price, long buyPrice) {
        if (!canManage(g, player)) return "shop-no-permission";
        String err = validateNew(g, player, sample, price);
        if (err != null) {
            return err;
        }
        int want = Math.max(1, Math.min(amount, plugin.settings().guild.shopMaxStackPerListing));
        ItemStack base = sample.clone();
        base.setAmount(1);
        int available = countInInventory(player.getInventory(), base);
        if (available <= 0) {
            return "shop-hand-empty";
        }
        int take = Math.min(want, available);
        String encoded = ItemCodec.encode(base);
        if (encoded == null) {
            return "shop-serialize-failed";
        }
        ShopListing l = new ShopListing(UUID.randomUUID().toString(), g.id(), encoded, base,
                take, Math.max(0, price), 0,
                player.getUniqueId(), player.getName(), System.currentTimeMillis());
        String opId = UUID.randomUUID().toString();
        UUID buyer = player.getUniqueId();
        // 背包扣除前/后的精确快照：崩溃恢复时用 Arrays.equals 判断到底扣没扣，
        // 不再用“背包里还有多少件”推断（玩家库存大于上架量时必然误判并吞物品）。
        final ItemStack[] before = cloneItems(player.getInventory().getStorageContents());
        final ItemStack[] after = cloneItems(before);
        if (removeFromArray(after, base, take) != take) {
            return "shop-hand-empty";
        }
        final String beforeData = com.liu.liuguild.util.ItemUtil.encode(before);
        final String afterData = com.liu.liuguild.util.ItemUtil.encode(after);
        if (beforeData == null || afterData == null || beforeData.isBlank() || afterData.isBlank()) {
            return "shop-serialize-failed";
        }
        plugin.async(() -> {
            try {
                repo.savePending(new ShopRepository.Pending(opId, buyer, g.id(), "ADD", null,
                        encoded, take, "TO_REMOVE", beforeData, afterData));
            } catch (Exception e) {
                plugin.getLogger().warning("创建上架交接记录失败: " + e.getMessage());
                plugin.sync(() -> plugin.messages().send(player, "shop-listing-failed"));
                return;
            }
            plugin.sync(() -> {
                if (!player.isOnline()
                        || !java.util.Arrays.equals(before, player.getInventory().getStorageContents())) {
                    deletePending(opId);
                    if (player.isOnline()) plugin.messages().send(player, "shop-listing-failed");
                    return;
                }
                player.getInventory().setStorageContents(cloneItems(after));
                plugin.async(() -> {
                    try {
                        // 扣背包后立即在同一事务里插入 listing 并把 pending 标记 DONE。
                        // 崩溃只会留下 TO_REMOVE（按 after 快照精确退物），不会既扣物又丢记录。
                        repo.insert(l, opId, plugin.settings().guild.shopMaxListings);
                        cache.put(l.id(), l);
                        deletePending(opId);
                        broadcast(g.id());
                    } catch (Exception e) {
                        plugin.getLogger().warning("上架工会商品失败: " + e.getMessage());
                        plugin.sync(() -> {
                            if (!player.isOnline()) {
                                // 离线：保留 TO_REMOVE，登录后按快照退物。
                                return;
                            }
                            ItemStack[] current = player.getInventory().getStorageContents();
                            if (java.util.Arrays.equals(current, after)) {
                                player.getInventory().setStorageContents(cloneItems(before));
                            } else {
                                give(player, base, take);
                            }
                            deletePending(opId);
                            plugin.messages().send(player, "shop-listing-failed");
                        });
                    }
                });
            });
        });
        return null;
    }

    private static ItemStack[] cloneItems(ItemStack[] items) {
        ItemStack[] copy = items.clone();
        for (int i = 0; i < copy.length; i++) {
            if (copy[i] != null) copy[i] = copy[i].clone();
        }
        return copy;
    }

    /** 在副本数组里扣除指定物品，返回实际扣除数量（主线程内存操作）。 */
    private static int removeFromArray(ItemStack[] items, ItemStack base, int amount) {
        int remaining = amount;
        for (int i = 0; i < items.length && remaining > 0; i++) {
            ItemStack item = items[i];
            if (item == null || item.getType().isAir() || !item.isSimilar(base)) {
                continue;
            }
            int take = Math.min(remaining, item.getAmount());
            item.setAmount(item.getAmount() - take);
            remaining -= take;
            if (item.getAmount() <= 0) {
                items[i] = null;
            }
        }
        return amount - remaining;
    }

        /** 改价（旧收购参数忽略）。 */
    public void updatePrices(Guild g, ShopListing l, long price, long buyPrice) {
        long oldPrice = l.price();
        long oldBuyPrice = l.buyPrice();
        l.price(Math.max(0, price));
        l.buyPrice(0);
        final String guildId = g == null ? l.guildId() : g.id();
        plugin.async(() -> {
            try {
                repo.updatePrices(l.id(), l.price(), l.buyPrice());
                // 广播必须在写库成功之后：否则其它子服会先 reload 到旧价，之后再无广播。
                broadcast(guildId);
            } catch (Exception e) {
                // 数据库失败时回滚内存价格，避免界面显示与真实扣款价格长期不一致。
                l.price(oldPrice);
                l.buyPrice(oldBuyPrice);
                plugin.getLogger().warning("修改工会商品价格失败: " + e.getMessage());
            }
        });
    }

    /**
     * 下架：剩余库存退回给操作者（背包放不下就掉在脚下，不吞物品）。
     *
     * <p>顺序：**先删库，成功后才退回物品**。反过来（先退回再删）一旦删库失败，
     * 重启后这条 listing 又会出现，而物品已经退了 —— 变成凭白多出一份。</p>
     */
    public String removeListing(Guild g, Player player, ShopListing l) {
        return removeListing(g, player, l, null);
    }

    /**
     * 带回调的下架（GUI 用；删库成功后才退回物品并刷新界面）。
     */
    public String removeListing(Guild g, Player player, ShopListing l, Runnable onDone) {
        if (g == null || !canManage(g, player)) return "shop-no-permission";
        if (l == null) {
            return "shop-not-found";
        }
        if (!l.guildId().equals(g.id())) {
            return "shop-not-found";
        }
        final Runnable done = onDone;
        String opId = UUID.randomUUID().toString();
        UUID uuid = player == null ? new UUID(0, 0) : player.getUniqueId();
        String itemData = ItemCodec.encode(l.preview());
        if (itemData == null || itemData.isBlank()) {
            if (player != null) plugin.messages().send(player, "shop-serialize-failed");
            if (onDone != null) onDone.run();
            return "shop-serialize-failed";
        }
        plugin.async(() -> {
            int stock;
            try {
                repo.savePending(new ShopRepository.Pending(opId, uuid, g.id(), "REMOVE", l.id(),
                        itemData, 0, "PREPARED", null, null));
                stock = repo.removeAndTakeStock(l.id(), opId);
            } catch (Exception e) {
                deletePending(opId);
                plugin.getLogger().warning("下架工会商品失败: " + e.getMessage());
                plugin.sync(() -> {
                    if (player != null) plugin.messages().send(player, "shop-remove-failed");
                    if (done != null) done.run();
                });
                return;
            }
            if (stock <= 0) {
                deletePending(opId);
                plugin.sync(() -> {
                    cache.remove(l.id());
                    if (player != null) plugin.messages().send(player, "shop-removed", "item", itemName(l.preview()), "amount", "0");
                    broadcast(l.guildId());
                    if (done != null) done.run();
                });
                return;
            }
            ShopRepository.Pending pending = new ShopRepository.Pending(opId, uuid, g.id(), "REMOVE",
                    l.id(), itemData, stock, "DELIVERING", null, null);
            boolean claimed;
            try {
                claimed = repo.claimDelivery(opId);
            } catch (Exception e) {
                claimed = false;
            }
            final boolean deliveryClaimed = claimed;
            plugin.sync(() -> {
                cache.remove(l.id());
                ShopRepository.Pending delivered = deliveryClaimed ? deliverNow(player, pending) : pending;
                if (player != null) {
                    plugin.messages().send(player, delivered == null ? "shop-removed" : "shop-remove-failed",
                            "item", itemName(l.preview()), "amount", String.valueOf(stock));
                }
                broadcast(l.guildId());
                if (done != null) done.run();
            });
        });
        return null;
    }

    // =====================================================================
    // 购买（玩家 → 工会）
    // =====================================================================

    /** 为什么不能买（返回消息 key；null = 可以）。 */
    public String validateBuy(Guild g, ShopListing l, int amount) {
        if (!plugin.settings().guild.shopEnabled) {
            return "shop-disabled";
        }
        if (l == null) {
            return "shop-not-found";
        }
        // 商店严格按工会隔离：只能购买本会自己的上架，工会之间不共享商品。
        if (!shopVisibleTo(g == null ? null : g.id(), l.guildId())) {
            return "shop-not-found";
        }
        if (amount <= 0) {
            return "shop-amount-invalid";
        }
        if (amount > plugin.settings().guild.shopMaxPerPurchase) {
            return "shop-amount-too-many";
        }
        if (l.stock() < amount) {
            return "shop-out-of-stock";
        }
        return null;
    }

    /**
     * 购买（成员个人工会资金 → 工会点数）。
     *
     * <p>顺序（每一步失败都能干净回退）：</p>
     * <ol>
     *   <li>背包空间预检 —— 放不下就别开始。</li>
     *   <li>异步查询个人工会资金余额，不足直接提示（真正扣款由数据库事务裁决）。</li>
     *   <li>数据库同一事务：扣个人资金 + 原子扣库存 + 税后点数入工会；任一步失败即整体回滚。</li>
     *   <li>发货（主线程）并刷新/广播库存。</li>
     * </ol>
     */
    public void buy(Player player, Guild viewerGuild, ShopListing l, int amount, Runnable onDone) {
        String err = validateBuy(viewerGuild, l, amount);
        if (err != null) {
            plugin.messages().send(player, err);
            if (onDone != null) onDone.run();
            return;
        }
        long unit = l.price();
        long total = safeMul(unit, amount);
        final long paid = total;
        final long net = applyTax(total);
        ItemStack preview = l.preview();
        if (preview == null) {
            plugin.messages().send(player, "shop-not-found");
            if (onDone != null) onDone.run();
            return;
        }
        if (!canFit(player.getInventory(), preview, amount)) {
            plugin.messages().send(player, "shop-inventory-full");
            if (onDone != null) onDone.run();
            return;
        }
        final UUID buyer = player.getUniqueId();
        final String opId = UUID.randomUUID().toString();
        final String itemData = ItemCodec.encode(preview);
        if (itemData == null) {
            plugin.messages().send(player, "shop-not-found");
            if (onDone != null) onDone.run();
            return;
        }
        plugin.async(() -> {
            try {
                repo.savePending(new ShopRepository.Pending(opId, buyer, l.guildId(), "BUY", l.id(),
                        itemData, amount, "PREPARED", null, null));
            } catch (Exception e) {
                plugin.getLogger().warning("创建购买交接记录失败: " + e.getMessage());
                plugin.sync(() -> {
                    plugin.messages().send(player, "shop-purchase-failed");
                    if (onDone != null) onDone.run();
                });
                return;
            }
            boolean success;
            try {
                success = repo.purchase(l.id(), l.guildId(), buyer, amount, unit, paid, net, opId);
            } catch (Exception e) {
                plugin.getLogger().warning("购买工会商品失败: " + e.getMessage());
                success = false;
            }
            final boolean ok = success;
            if (!ok) {
                deletePending(opId);
                plugin.sync(() -> {
                    plugin.messages().send(player, "shop-purchase-failed");
                    if (onDone != null) onDone.run();
                });
                return;
            }
            // 先原子领取（READY→DELIVERING）再回主线程发货：崩溃后不会重复发货。
            boolean claimed;
            try {
                claimed = repo.claimDelivery(opId);
            } catch (Exception e) {
                plugin.getLogger().warning("领取商店发货记录失败: " + e.getMessage());
                claimed = false;
            }
            final boolean deliveryClaimed = claimed;
            ShopRepository.Pending pending = new ShopRepository.Pending(opId, buyer, l.guildId(), "BUY",
                    l.id(), itemData, amount, "DELIVERING", null, null);
            plugin.sync(() -> {
                l.stock(Math.max(0, l.stock() - amount));
                if (deliveryClaimed) {
                    ShopRepository.Pending notDelivered = deliverNow(player, pending);
                    if (notDelivered == null) {
                        plugin.messages().send(player, "shop-bought",
                                "item", itemName(preview), "amount", String.valueOf(amount), "cost", String.valueOf(paid));
                        if (paid > net) plugin.messages().send(player, "shop-bought-tax",
                                "tax", String.valueOf(paid - net), "net", String.valueOf(net));
                    }
                }
                plugin.guilds().reloadGuild(l.guildId());
                plugin.broadcastGuildChange(l.guildId());
                broadcast(l.guildId());
                if (onDone != null) onDone.run();
            });
        });
    }

    private final java.util.Set<UUID> recovering = ConcurrentHashMap.newKeySet();

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.syncDelayed(() -> recoverPending(event.getPlayer()), 1L);
    }

    /** 玩家登录/打开商店前继续未完成的发货，避免“已付款但没收到”或上架失败吞物。 */
    public void recoverPending(Player player) {
        if (player == null || !player.isOnline() || !recovering.add(player.getUniqueId())) return;
        UUID uuid = player.getUniqueId();
        plugin.async(() -> {
            List<ShopRepository.Pending> list;
            try { list = repo.loadPending(uuid); }
            catch (Exception e) {
                plugin.getLogger().warning("读取商店交接记录失败: " + e.getMessage());
                recovering.remove(uuid);
                return;
            }
            plugin.sync(() -> {
                try {
                    for (ShopRepository.Pending pending : list) {
                        if (!player.isOnline()) return;
                        if ("DONE".equals(pending.state()) || "PREPARED".equals(pending.state())) {
                            deletePending(pending.opId());
                            continue;
                        }
                        ItemStack item = ItemCodec.decode(pending.itemData());
                        if ("ADD".equals(pending.action())
                                && ("TO_REMOVE".equals(pending.state()) || "REMOVED".equals(pending.state()))) {
                            if (item == null || item.getType().isAir()) {
                                deletePending(pending.opId());
                            } else if (hasSnapshots(pending)) {
                                // 精确比对：before = 还没扣；after = 已扣但 listing 未提交，退回物品。
                                ItemStack[] current = player.getInventory().getStorageContents();
                                ItemStack[] before = com.liu.liuguild.util.ItemUtil.decode(pending.beforeItems());
                                ItemStack[] after = com.liu.liuguild.util.ItemUtil.decode(pending.afterItems());
                                if (java.util.Arrays.equals(current, before)) {
                                    deletePending(pending.opId());
                                } else if (java.util.Arrays.equals(current, after)) {
                                    give(player, item, pending.amount());
                                    deletePending(pending.opId());
                                } else {
                                    plugin.getLogger().severe("商店上架交接恢复失败：玩家 " + player.getName()
                                            + " 背包已变化，保留记录待人工处理。pending=" + pending.opId());
                                }
                            } else if ("REMOVED".equals(pending.state())) {
                                // 旧版记录（无快照）：状态已持久化“已扣除”，必须退回。
                                give(player, item, pending.amount());
                                deletePending(pending.opId());
                            } else {
                                // 旧版记录（无快照）兜底：无法精确判断时按件数近似。
                                if (countInInventory(player.getInventory(), item) >= pending.amount()) {
                                    deletePending(pending.opId());
                                } else {
                                    give(player, item, pending.amount());
                                    deletePending(pending.opId());
                                }
                            }
                            continue;
                        }
                        if ("READY".equals(pending.state())) {
                            if (item == null || item.getType().isAir()) {
                                plugin.getLogger().severe("商店交接物品损坏，保留待人工处理: " + pending.opId());
                                continue;
                            }
                            // 先领取再发货；领取失败说明已在别处处理/正在处理。
                            plugin.async(() -> {
                                boolean claimed;
                                try {
                                    claimed = repo.claimDelivery(pending.opId());
                                } catch (Exception e) {
                                    claimed = false;
                                }
                                if (!claimed) {
                                    return;
                                }
                                plugin.sync(() -> {
                                    if (!player.isOnline()) {
                                        return;
                                    }
                                    give(player, item, pending.amount());
                                    deletePending(pending.opId());
                                });
                            });
                        } else if ("DELIVERING".equals(pending.state())) {
                            // 已经领取过：可能已发货（只是没来得及删记录），不再重复发。
                            plugin.getLogger().warning("商店发货记录处于 DELIVERING，已跳过以免重复发货: " + pending.opId());
                        } else {
                            plugin.getLogger().warning("未知商店交接状态: " + pending.state() + " / " + pending.opId());
                        }
                    }
                } finally {
                    recovering.remove(uuid);
                }
            });
        });
    }

    private static boolean hasSnapshots(ShopRepository.Pending pending) {
        return pending.beforeItems() != null && !pending.beforeItems().isBlank()
                && pending.afterItems() != null && !pending.afterItems().isBlank();
    }

    /** 在线时立即发货；离线则保留 READY 记录，登录后恢复。 */
    private ShopRepository.Pending deliverNow(Player player, ShopRepository.Pending pending) {
        if (player == null || !player.isOnline()) return pending;
        ItemStack item = ItemCodec.decode(pending.itemData());
        if (item == null || item.getType().isAir()) {
            plugin.getLogger().severe("商店发货物品损坏，保留待人工处理: " + pending.opId());
            return pending;
        }
        give(player, item, pending.amount());
        deletePending(pending.opId());
        return null;
    }

    private void deletePending(String opId) {
        plugin.async(() -> {
            try { repo.deletePending(opId); }
            catch (Exception e) { plugin.getLogger().warning("清理商店交接记录失败: " + e.getMessage()); }
        });
    }

    /**
     * 商店隔离判定：只有查看者工会与上架工会一致时商品才可见 / 可买。
     * 任一为空（未入会 / 脏数据）一律视为不可见，避免跨工会或幽灵条目泄露。
     */
    public static boolean shopVisibleTo(String viewerGuildId, String listingGuildId) {
        return viewerGuildId != null && viewerGuildId.equals(listingGuildId);
    }

    /** 交易税：按配置比例从货款里扣掉。 */
    private long applyTax(long amount) {
        return taxOf(amount, plugin.settings().guild.shopTaxPercent);
    }

    /**
     * 安全乘法。
     *
     * <p>价格和数量都来自配置/输入。直接相乘一旦溢出会变成<b>负数</b> ——
     * 在本代码里负的 total 意味着「购买时反而给玩家发钱」。宁可封顶到 Long.MAX_VALUE
     * （玩家肯定买不起），也绝不回绕。</p>
     */
    public static long safeMul(long a, long b) {
        if (a == 0 || b == 0) {
            return 0;
        }
        try {
            return Math.multiplyExact(a, b);
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    /** 交易税后的实收金额（抽成 0~100%，结果不会为负）。 */
    public static long taxOf(long amount, double percent) {
        if (amount <= 0) {
            return 0;
        }
        double pct = Math.max(0, Math.min(100, percent));
        if (pct <= 0) {
            return amount;
        }
        long keep = Math.round(amount * (1.0 - pct / 100.0));
        return Math.max(0, Math.min(amount, keep));
    }

    // =====================================================================
    // 供货（本会成员 → 工会收购）
    // =====================================================================

    /**
     * 成员把物品卖给工会（工会出货给玩家的是 {@code price}，向成员收的是 {@code buyPrice}）。
     *
     * <p>顺序：先验钱与货（都能失败，且未产生任何变更）→ 扣货、扣工会资金、
     * 给成员打钱（都成功）→ 最后异步加库存。库存加失败只影响展示，不会让任何一方亏。</p>
     */
            // =====================================================================
    // 工具
    // =====================================================================

    /** 展示名：自定义名优先，否则材质名。 */
    public String itemName(ItemStack item) {
        if (item == null) {
            return "?";
        }
        var meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            return com.liu.liuguild.util.Text.plain(meta.displayName());
        }
        return item.getType().name();
    }

    /** 背包里与 sample 同类的物品总数。 */
    public static int countInInventory(Inventory inv, ItemStack sample) {
        if (inv == null || sample == null) {
            return 0;
        }
        int n = 0;
        for (ItemStack s : inv.getContents()) {
            if (s != null && !s.getType().isAir() && s.isSimilar(sample)) {
                n += s.getAmount();
            }
        }
        return n;
    }

    /** 从背包精确扣除 amount 个与 sample 同类的物品（只动同类，不碰其它）。 */
    public static void removeFromInventory(Inventory inv, ItemStack sample, int amount) {
        if (inv == null || amount <= 0) {
            return;
        }
        int left = amount;
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length && left > 0; i++) {
            ItemStack s = contents[i];
            if (s == null || s.getType().isAir() || !s.isSimilar(sample)) {
                continue;
            }
            int take = Math.min(left, s.getAmount());
            left -= take;
            if (take >= s.getAmount()) {
                inv.setItem(i, null);
            } else {
                s.setAmount(s.getAmount() - take);
                inv.setItem(i, s);
            }
        }
    }

    /** 把 amount 个 item 发给玩家；背包放不下就掉在脚下（绝不吞物品）。 */
    public static void give(Player player, ItemStack item, int amount) {
        if (player == null || item == null || amount <= 0) {
            return;
        }
        int max = Math.max(1, item.getMaxStackSize());
        int left = amount;
        while (left > 0) {
            ItemStack stack = item.clone();
            stack.setAmount(Math.min(max, left));
            left -= stack.getAmount();
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(stack);
            for (ItemStack rest : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), rest);
            }
        }
    }

    /** 背包能否再装下 amount 个 item（含零散同类堆的空位）。 */
    public static boolean canFit(Inventory inv, ItemStack item, int amount) {
        if (inv == null || item == null || amount <= 0) {
            return false;
        }
        int max = Math.max(1, item.getMaxStackSize());
        int free = 0;
        for (ItemStack s : inv.getStorageContents()) {
            if (s == null || s.getType().isAir()) {
                free += max;
            } else if (s.isSimilar(item)) {
                free += Math.max(0, max - s.getAmount());
            }
            if (free >= amount) {
                return true;
            }
        }
        return free >= amount;
    }

    /** 成员离会 / 工会解散时清理。 */
    public void onGuildDisbanded(String guildId) {
        cache.values().removeIf(l -> l.guildId().equals(guildId));
        plugin.async(() -> {
            try {
                repo.deleteGuild(guildId);
            } catch (Exception e) {
                plugin.getLogger().warning("清空工会商店失败: " + e.getMessage());
            }
        });
    }

    /** 仅供测试/关闭使用。 */
    public void clearCache() {
        cache.clear();
    }

    /** 供 GUI 用：本会成员能否管理上架。 */
    public boolean canManage(Guild g, Player player) {
        if (player.hasPermission("liuguild.shop.manage")) {
            return true;
        }
        GuildMember m = g == null ? null : g.member(player.getUniqueId());
        return m != null && m.rank().atLeast(plugin.settings().guild.shopManageRank);
    }

    private void broadcast(String guildId) {
        plugin.async(() -> plugin.bus().publish("GUILD_SHOP",
                Map.of("guild", guildId, "server", plugin.serverId())));
    }

    }
