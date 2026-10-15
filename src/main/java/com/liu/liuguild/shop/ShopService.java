package com.liu.liuguild.shop;

import com.liu.liuguild.LiuGuild;
import com.liu.liuguild.model.Guild;
import com.liu.liuguild.model.GuildMember;
import com.liu.liuguild.model.GuildRank;
import com.liu.liuguild.model.ShopListing;
import com.liu.liuguild.module.GuildModule;
import com.liu.liuguild.util.ItemCodec;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工会商店：工会把自己的物品明码标价挂出来，<b>只供本会成员购买</b>，
 * 收入进工会金币银行（按配置抽税）。
 *
 * <p><b>工会隔离</b>：上架记录以 {@code guild_id} 为准，每个工会的商店物品完全独立，
 * 工会之间不能互相看到或购买对方的商品（因此也不存在“全服共享商店”）。</p>
 *
 * <p>不再提供收购；建设资金不参与商店交易。</p>
 *
 * <h3>钱与物的顺序</h3>
 * <p>买卖最怕「扣了钱没给货」或「给了货没扣钱」。这里的规则是：
 * <b>先把能失败的事做完、并且做完就能立刻回滚，再执行不可逆的那一步。</b>
 * 具体见 {@link #buy} / {@link #supply} 里的注释。</p>
 *
 * <h3>并发</h3>
 * <p>库存扣减走数据库原子 UPDATE（见 {@link ShopRepository#tryDecrement}），
 * 内存缓存只是展示用 —— 抢购的最后一件由数据库裁决，抢不到的人不会拿到东西。</p>
 */
public final class ShopService implements GuildModule {

    private final LiuGuild plugin;
    private final ShopRepository repo;

    /** listingId → listing（展示用缓存；库存以数据库为准）。 */
    private final Map<String, ShopListing> cache = new ConcurrentHashMap<>();

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
        // 先把物品从背包拿出来（这一步在主线程，失败就直接返回，没有副作用）
        removeFromInventory(player.getInventory(), base, take);
        String encoded = ItemCodec.encode(base);
        if (encoded == null) {
            // 序列化失败：把物品还回去，不能让玩家凭空丢东西
            give(player, base, take);
            return "shop-serialize-failed";
        }
        ShopListing l = new ShopListing(UUID.randomUUID().toString(), g.id(), encoded, base,
                take, Math.max(0, price), 0,
                player.getUniqueId(), player.getName(), System.currentTimeMillis());
        plugin.async(() -> {
            try {
                repo.insert(l);
                cache.put(l.id(), l);
                broadcast(g.id());
            } catch (Exception e) {
                // 落库失败：内存里的条目会在重启后消失，物品也已经被从玩家身上拿走了 ——
                // 必须把物品退回，否则就是凭白吞物
                plugin.getLogger().warning("上架工会商品失败: " + e.getMessage());
                cache.remove(l.id());
                plugin.sync(() -> {
                    give(player, base, take);
                    plugin.messages().send(player, "shop-listing-failed");
                });
            }
        });
        return null;
    }

    /** 收购已永久关闭；保留方法签名以兼容旧调用方，不移动钱或物品。 */
    public void supply(Guild g, Player player, ShopListing l, int amount, Runnable onDone) {
        plugin.messages().send(player, "shop-not-buying");
        if (onDone != null) onDone.run();
    }

    /** 改价（旧收购参数忽略）。 */
    public void updatePrices(Guild g, ShopListing l, long price, long buyPrice) {
        l.price(Math.max(0, price));
        l.buyPrice(0);
        plugin.async(() -> {
            try {
                repo.updatePrices(l.id(), l.price(), l.buyPrice());
            } catch (Exception e) {
                plugin.getLogger().warning("修改工会商品价格失败: " + e.getMessage());
            }
        });
        broadcast(g == null ? l.guildId() : g.id());
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
        if (g == null || !l.guildId().equals(g.id())) {
            return "shop-not-found";
        }
        final Runnable done = onDone;
        plugin.async(() -> {
            final int stock;
            try {
                stock = repo.removeAndTakeStock(l.id());
            } catch (Exception e) {
                plugin.getLogger().warning("下架工会商品失败: " + e.getMessage());
                plugin.sync(() -> {
                    if (player != null) {
                        plugin.messages().send(player, "shop-remove-failed");
                    }
                    if (done != null) {
                        done.run();
                    }
                });
                return;
            }
            plugin.sync(() -> {
                cache.remove(l.id());
                if (stock > 0 && player != null) {
                    give(player, l.preview(), stock);
                }
                if (player != null) {
                    plugin.messages().send(player, "shop-removed",
                            "item", itemName(l.preview()),
                            "amount", String.valueOf(stock));
                }
                broadcast(l.guildId());
                if (done != null) {
                    done.run();
                }
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
     * 购买。
     *
     * <p>顺序（每一步失败都能干净回退）：</p>
     * <ol>
     *   <li>背包空间预检 —— 放不下就别开始，避免「给了货又得收回」这种脏流程。</li>
     *   <li>扣钱（Vault，主线程同步）。失败 → 直接返回，无副作用。</li>
     *   <li>数据库原子扣库存。失败（被别人抢走）→ <b>退钱</b>。</li>
     *   <li>发货。此时一定成功（空间已预检）。</li>
     *   <li>货款进工会资金（扣税），异步。</li>
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
        var eco = plugin.economy();
        if (total > 0) {
            if (!eco.available()) {
                plugin.messages().send(player, "shop-no-economy");
                if (onDone != null) onDone.run();
                return;
            }
            if (!eco.has(player, total)) {
                plugin.messages().send(player, "shop-not-enough-money",
                        "cost", eco.format(total), "have", eco.format(eco.balance(player)));
                if (onDone != null) onDone.run();
                return;
            }
            if (!eco.withdraw(player, total)) {
                plugin.messages().send(player, "shop-not-enough-money",
                        "cost", eco.format(total), "have", eco.format(eco.balance(player)));
                if (onDone != null) onDone.run();
                return;
            }
        }
        final long paid = total;
        plugin.async(() -> {
            boolean ok;
            try {
                ok = repo.purchase(l.id(), l.guildId(), amount, unit, applyTax(paid));
            } catch (Exception e) {
                plugin.getLogger().warning("扣减工会商品库存失败: " + e.getMessage());
                ok = false;
            }
            final boolean success = ok;
            plugin.sync(() -> {
                if (!success) {
                    // 被别人抢走了：原样退钱
                    if (paid > 0) {
                        plugin.economy().deposit(player, paid);
                    }
                    plugin.messages().send(player, "shop-out-of-stock");
                    if (onDone != null) onDone.run();
                    return;
                }
                l.stock(l.stock() - amount);
                give(player, preview, amount);
                // 货款已随库存扣减在同一数据库事务中进入工会金币账户。
                long net = applyTax(paid);
                Guild seller = plugin.guilds().guild(l.guildId());
                plugin.guilds().reloadGuild(l.guildId());
                plugin.broadcastGuildChange(l.guildId());
                plugin.messages().send(player, "shop-bought",
                        "item", itemName(preview),
                        "amount", String.valueOf(amount),
                        "cost", plugin.economy().format(paid));
                if (seller != null && paid > net) {
                    plugin.messages().send(player, "shop-bought-tax",
                            "tax", plugin.economy().format(paid - net));
                }
                // 库存已变，通知其他子服刷新显示（否则它们会拿着陈旧库存
                // 把买得起的东西误报为“缺货”）
                broadcast(l.guildId());
                if (onDone != null) onDone.run();
            });
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
    /** 不再调用的历史供货实现，禁止重新接入任何命令或 GUI。 */
    private void disabledSupplyLegacy(Guild g, Player player, ShopListing l, int amount, Runnable onDone) {
        Runnable done = onDone == null ? () -> {
        } : onDone;
        if (!plugin.settings().guild.shopEnabled) {
            plugin.messages().send(player, "shop-disabled");
            done.run();
            return;
        }
        if (g == null || l == null || !l.guildId().equals(g.id())) {
            plugin.messages().send(player, "shop-not-found");
            done.run();
            return;
        }
        if (!l.buying()) {
            plugin.messages().send(player, "shop-not-buying");
            done.run();
            return;
        }
        if (amount <= 0) {
            plugin.messages().send(player, "shop-amount-invalid");
            done.run();
            return;
        }
        if (amount > plugin.settings().guild.shopMaxSupply) {
            plugin.messages().send(player, "shop-amount-too-many",
                    "max", String.valueOf(plugin.settings().guild.shopMaxSupply));
            done.run();
            return;
        }
        ItemStack preview = l.preview();
        if (preview == null) {
            plugin.messages().send(player, "shop-not-found");
            done.run();
            return;
        }
        // 1) 手里够不够
        int owned = countInInventory(player.getInventory(), preview);
        if (owned < amount) {
            plugin.messages().send(player, "shop-not-enough-items",
                    "amount", String.valueOf(owned), "need", String.valueOf(amount));
            done.run();
            return;
        }
        long payout = safeMul(l.buyPrice(), amount);
        // 2) 工会资金够不够
        if (payout > g.bank()) {
            plugin.messages().send(player, "shop-guild-bank-low",
                    "cost", plugin.economy().format(payout),
                    "bank", plugin.economy().format(g.bank()));
            done.run();
            return;
        }
        if (payout > 0 && !plugin.economy().available()) {
            plugin.messages().send(player, "shop-no-economy");
            done.run();
            return;
        }
        // 3) 全都验过了，开始变更：先扣货 —— 后面任何一步失败都能把货原样退还
        removeFromInventory(player.getInventory(), preview, amount);
        if (payout <= 0) {
            commitSupply(g, l, amount);
            plugin.messages().send(player, "shop-supplied",
                    "item", itemName(preview),
                    "amount", String.valueOf(amount),
                    "earned", plugin.economy().format(0));
            done.run();
            return;
        }
        // 4) 异步扣工会资金；成功了才发钱、才加库存
        plugin.guilds().changeBank(g.id(), -payout).whenComplete((ok, ex) -> plugin.sync(() -> {
            if (ex != null || !Boolean.TRUE.equals(ok)) {
                // 扣工会资金失败：货还回去，交易取消
                give(player, preview, amount);
                plugin.messages().send(player, "shop-guild-bank-low",
                        "cost", plugin.economy().format(payout),
                        "bank", plugin.economy().format(g.bank()));
                done.run();
                return;
            }
            if (!plugin.economy().deposit(player, payout)) {
                // 发钱失败：货还给玩家，钱退回工会（整体回滚）
                give(player, preview, amount);
                plugin.guilds().changeBank(g.id(), payout).whenComplete((back, backEx) -> {
                    if (backEx != null || !Boolean.TRUE.equals(back)) {
                        plugin.getLogger().severe("工会商店供货回滚失败: 工会=" + g.id()
                                + " 玩家=" + player.getUniqueId() + " 金额=" + payout);
                    }
                });
                plugin.messages().send(player, "shop-payout-failed");
                done.run();
                return;
            }
            commitSupply(g, l, amount);
            plugin.messages().send(player, "shop-supplied",
                    "item", itemName(preview),
                    "amount", String.valueOf(amount),
                    "earned", plugin.economy().format(payout));
            done.run();
        }));
    }

    /** 供货成功后的收尾：内存库存 + 异步落库 + 跨服广播。 */
    private void commitSupply(Guild g, ShopListing l, int amount) {
        l.stock(l.stock() + amount);
        plugin.async(() -> {
            try {
                repo.increment(l.id(), amount);
            } catch (Exception e) {
                plugin.getLogger().warning("工会商品加库存失败: " + e.getMessage());
            }
        });
        broadcast(g.id());
    }

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

    /** 供 GUI 展示：按工会分组统计。 */
    public Map<String, Integer> countsByGuild() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (ShopListing l : cache.values()) {
            out.merge(l.guildId(), 1, Integer::sum);
        }
        return out;
    }
}
