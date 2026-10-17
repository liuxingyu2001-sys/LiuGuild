package com.liu.liuguild.warehouse;

import com.liu.liuguild.LiuGuild;
import com.liu.liuguild.gui.GuiUtil;
import com.liu.liuguild.model.Guild;
import com.liu.liuguild.model.GuildMember;
import com.liu.liuguild.module.GuildModule;
import com.liu.liuguild.util.ItemCodec;
import com.liu.liuguild.util.ItemUtil;
import com.liu.liuguild.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工会仓库：跨服共享的物品存储。
 *
 * <h3>为什么是“共享且独占”</h3>
 * <p>仓库内容存共享数据库，多台子服的成员都能访问；但同一时刻<b>只允许一个玩家打开</b>
 * （由 {@link WarehouseLock} 跨服抢锁）。否则两个玩家各自读进内存再写回，
 * 后写的会覆盖先写的 → 物品凭空消失或复制。</p>
 *
 * <h3>写入顺序（关键）</h3>
 * <p>关闭时：<b>先把内容写库成功，再释放锁</b>。如果先放锁，下一个玩家可能在我们的写入
 * 落库前就读到旧数据，出现“物品回滚”。</p>
 */
public final class WarehouseService implements GuildModule, Listener {

    private final LiuGuild plugin;
    private final WarehouseRepository repo;
    private final WarehouseLock lock;
    private volatile boolean running;
    private volatile long lifecycle;
    private BukkitTask heartbeatTask;
    private BukkitTask purgeTask;

    /** 已打开的仓库（玩家 → 会话），用于关服/掉线时兜底保存。 */
    private final Map<UUID, Open> open = new ConcurrentHashMap<>();
    private final java.util.Set<UUID> loading = ConcurrentHashMap.newKeySet();

    /** 一个已打开的仓库会话。 */
    private static final class Open {
        final String guildId;
        final UUID player;
        final Inventory inv;
        final WarehouseHolder holder;
        final String sessionToken;
        WarehouseContents contents;
        long version;
        boolean busy;
        boolean closed;
        boolean input;
        int page;
        int category;
        int sort;
        boolean descending;
        String search = "";
        List<WarehouseContents.Entry> visible = List.of();

        Open(String guildId, UUID player, Inventory inv, WarehouseHolder holder,
             WarehouseContents contents, long version, String sessionToken) {
            this.guildId = guildId;
            this.player = player;
            this.inv = inv;
            this.holder = holder;
            this.contents = contents;
            this.version = version;
            this.sessionToken = sessionToken;
        }
    }

    /** 仓库背包的持有者标记（与普通 GUI 区分，允许自由存取物品）。 */
    public static final class WarehouseHolder implements InventoryHolder {
        private final String guildId;
        private Inventory inv;

        WarehouseHolder(String guildId) {
            this.guildId = guildId;
        }

        public String guildId() {
            return guildId;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    /** 仓库管理菜单位置标记（点击购买时长 / 升级，不掉落物品）。 */
    public static final class MenuHolder implements InventoryHolder {
        private final String guildId;
        private final Map<Integer, String> actions;
        private Inventory inv;

        MenuHolder(String guildId, Map<Integer, String> actions) {
            this.guildId = guildId;
            this.actions = actions;
        }

        public String guildId() {
            return guildId;
        }

        public String action(int slot) {
            return actions.get(slot);
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    public WarehouseService(LiuGuild plugin) {
        this.plugin = plugin;
        this.repo = new WarehouseRepository(plugin.db());
        this.lock = new WarehouseLock(plugin);
    }

    @Override
    public String id() {
        return "warehouse";
    }

    @Override
    public int order() {
        return 55;
    }

    @Override
    public void enable(LiuGuild plugin) {
        if (running) return;
        running = true;
        lifecycle++;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        long period = lock.heartbeatSeconds() * 20L;
        // 心跳保持轻量：只续期，避免 DB 慢时把续期一起拖到锁超时。
        heartbeatTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            if (running) lock.heartbeat();
        }, period, period);
        // 过期清理按分钟级执行，并用 Redis 抢锁保证多服只跑一台（保留期本来就是“天”粒度）。
        purgeTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            if (!running) return;
            if (plugin.bus() != null && plugin.bus().isReady()
                    && !plugin.bus().acquireOnce("warehouse-purge", 300)) {
                return;
            }
            try {
                repo.cleanExpired(plugin.settings().warehouse, System.currentTimeMillis());
            } catch (Exception e) {
                plugin.getLogger().warning("空间过期清理失败: " + e.getMessage());
            }
        }, 20L * 60L * 5L, 20L * 60L * 5L);
    }

    @Override
    public void disable() {
        running = false;
        lifecycle++;
        org.bukkit.event.HandlerList.unregisterAll(this);
        loading.clear();
        if (heartbeatTask != null) {
            heartbeatTask.cancel();
            heartbeatTask = null;
        }
        if (purgeTask != null) {
            purgeTask.cancel();
            purgeTask = null;
        }
        for (Open o : open.values()) {
            o.closed = true;
            Player p = plugin.player(o.player);
            if (p != null) p.closeInventory();
        }
        open.clear();
        lock.releaseAll();
    }

    @Override
    public void reload() {
        // 新配置立即生效，关闭旧展示和聊天输入会话，忙碌操作按原有回滚路径结束。
        lifecycle++;
        for (UUID player : new java.util.ArrayList<>(open.keySet())) kick(player);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof MenuHolder)
                player.closeInventory();
        }
    }

    public WarehouseLock lock() {
        return lock;
    }

    /** 仓库槽位数：基础 + 已购买升级级数 × 每级增长，受上限约束（结果是 9 的倍数）。 */
    public int slotsFor(Guild g) {
        return slotsForLevel(g == null ? 0 : g.effectiveWarehouseLevel(System.currentTimeMillis()));
    }

    /** 仓库是否可用：关闭租赁要求时永久可用；否则必须在有效期内。 */
    public boolean isUsable(Guild g) {
        var c = plugin.settings().warehouse;
        if (!c.requireRent) {
            return true;
        }
        return g != null && g.warehouseActive(System.currentTimeMillis());
    }

    /** 剩余的可用时长（毫秒，0 = 已过期/未开通）。 */
    public long remainingMillis(Guild g) {
        if (g == null || !plugin.settings().warehouse.requireRent || g.warehouseExpiresAt() == Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return Math.max(0, g.warehouseExpiresAt() - System.currentTimeMillis());
    }

    /** 本会成员能否购买时长 / 升级仓库。 */
    static boolean allowed(boolean permission, boolean admin, boolean member) {
        return permission && (admin || member);
    }

    private boolean authorized(Player player, Guild g) {
        if (!running || player == null || g == null) return false;
        boolean ok = allowed(player.hasPermission("liuguild.warehouse"),
                player.hasPermission("liuguild.admin"), g.member(player.getUniqueId()) != null);
        if (!ok) plugin.messages().send(player, "no-permission");
        return ok;
    }

    public boolean canManage(Guild g, Player player) {
        if (!authorized(player, g)) return false;
        if (player != null && player.hasPermission("liuguild.admin")) {
            return true;
        }
        GuildMember m = g == null ? null : g.member(player == null ? null : player.getUniqueId());
        return m != null && m.rank().atLeast(plugin.settings().warehouse.manageRank);
    }

    /** 下次升级的花费（已到上限或无法再增加槽位时返回 -1）。 */
    public long nextUpgradeCost(Guild g) {
        if (g == null) return -1;
        var next = plugin.settings().warehouse.levels.get(g.effectiveWarehouseLevel(System.currentTimeMillis()) + 1);
        return next == null ? -1 : next.price().amount();
    }

    /**
     * 打开工会仓库。
     *
     * @return true = 已发起打开（异步加载后打开界面）。未开通/已过期时改为打开管理菜单。
     */
    public boolean open(Player player, Guild g) {
        if (!authorized(player, g)) return false;
        var c = plugin.settings().guild;
        if (!c.warehouseEnabled) {
            plugin.messages().send(player, "wh-disabled");
            return false;
        }
        // 未开通 / 已过期：按策略允许只取不存
        if (!canAccess(g)) {
            openMenu(player, g);
            return false;
        }
        // 已经开着就直接返回。
        // 否则下面 player.closeInventory() 会触发 InventoryCloseEvent，
        // 把刚抢到的锁当成“旧会话”释放掉 —— 变成没锁裸奔。
        if (open.containsKey(player.getUniqueId())) {
            return true;
        }
        if (lock.isLockedByOther(g.id(), player.getUniqueId())) {
            String who = lock.localHolderName(g.id());
            if (who == null) {
                plugin.messages().send(player, "wh-busy");
            } else {
                plugin.messages().send(player, "wh-busy-player", "player", who);
            }
            return false;
        }
        final long openedLifecycle = lifecycle;
        UUID uuid = player.getUniqueId();
        if (!loading.add(uuid)) {
            // 上一次恢复/打开尚未结束：给出提示，避免玩家以为命令没生效。
            plugin.messages().send(player, "wh-busy");
            return false;
        }
        player.closeInventory();
        // 先处理上次崩溃遗留的背包/仓库交接，再允许重新打开。
        recoverPending(player, () -> openNow(player, g, openedLifecycle, uuid));
        return true;
    }


    /**
     * 恢复上次崩溃遗留的仓库/背包交接。数据库版本已从 expected 前进一格才应用 after，
     * 否则说明事务未提交（或已回滚），只清理日志。
     */
    private void recoverPending(Player player, Runnable after) {
        if (player == null || !player.isOnline() || open.containsKey(player.getUniqueId())) {
            if (after != null) plugin.sync(after);
            return;
        }
        UUID uuid = player.getUniqueId();
        plugin.async(() -> {
            WarehouseRepository.Pending pending;
            long version;
            try {
                pending = repo.loadPending(uuid);
                if (pending == null) {
                    if (after != null) plugin.sync(after);
                    return;
                }
                version = repo.loadSnapshot(pending.guildId()).version();
            } catch (Exception e) {
                plugin.getLogger().warning("读取仓库交接记录失败: " + e.getMessage());
                // 不能让 loading 永久占位：否则该玩家之后每次打开仓库都会被静默拒绝。
                plugin.sync(() -> failOpen(player, "wh-storage-failed"));
                return;
            }
            plugin.sync(() -> applyPending(player, pending, version, after));
        });
    }

    /** 恢复/打开失败时清理 loading 并给玩家可见提示（主线程）。 */
    private void failOpen(Player player, String messageKey) {
        UUID uuid = player.getUniqueId();
        loading.remove(uuid);
        if (player.isOnline() && messageKey != null) {
            plugin.messages().send(player, messageKey);
        }
    }

    private void applyPending(Player player, WarehouseRepository.Pending pending, long version, Runnable after) {
        if (!player.isOnline()) {
            loading.remove(player.getUniqueId());
            return;
        }
        UUID uuid = player.getUniqueId();
        try {
            ItemStack[] current = cloneItems(player.getInventory().getStorageContents());
            ItemStack[] before = ItemUtil.decode(pending.beforeItems());
            ItemStack[] afterItems = ItemUtil.decode(pending.afterItems());
            if (version == pending.expectedVersion() + 1) {
                if (inventoryMatches(current, afterItems)) {
                    // 上次已经改过背包，只是没来得及删交接记录。
                } else if (inventoryMatches(current, before)) {
                    player.getInventory().setStorageContents(cloneItems(afterItems));
                } else {
                    plugin.getLogger().severe("仓库交接恢复失败：玩家 " + player.getName()
                            + " 背包已变化，保留记录待人工处理。pending=" + pending.sessionToken());
                    failOpen(player, "wh-storage-failed");
                    return;
                }
            }
            // version != expected + 1：数据库未提交或已回滚，丢弃交接记录。
            final String token = pending.sessionToken();
            plugin.async(() -> {
                try { repo.deletePending(uuid, token); }
                catch (Exception e) { plugin.getLogger().warning("清理仓库交接记录失败: " + e.getMessage()); }
                if (after != null) plugin.sync(after);
            });
        } catch (Exception e) {
            plugin.getLogger().warning("恢复仓库交接失败: " + e.getMessage());
            failOpen(player, "wh-storage-failed");
        }
    }

    private static boolean inventoryMatches(ItemStack[] current, ItemStack[] expected) {
        if (expected.length == 0) {
            for (ItemStack item : current) {
                if (item != null && !item.getType().isAir()) return false;
            }
            return true;
        }
        if (current.length != expected.length) return false;
        return java.util.Arrays.equals(current, expected);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // 背包恢复必须等玩家数据加载完成后再做。
        plugin.syncDelayed(() -> recoverPending(event.getPlayer(), null), 1L);
    }

    private void openNow(Player player, Guild g, long openedLifecycle, UUID uuid) {
        plugin.async(() -> {
            if (!running || lifecycle != openedLifecycle) {
                loading.remove(uuid);
                return;
            }
            WarehouseLock.Session session = lock.acquire(g.id(), uuid, player.getName());
            if (session == null) {
                loading.remove(uuid);
                plugin.sync(() -> plugin.messages().send(player, "wh-busy"));
                return;
            }
            final WarehouseRepository.Snapshot loaded;
            try {
                loaded = repo.loadSnapshot(g.id());
            } catch (Exception e) {
                loading.remove(uuid);
                lock.release(g.id(), uuid, session.token());
                plugin.getLogger().warning("读取工会仓库失败，已拒绝打开: " + e.getMessage());
                plugin.sync(() -> plugin.messages().send(player, "wh-storage-failed"));
                return;
            }
            Guild fresh = loadFresh(g.id());
            plugin.sync(() -> {
                loading.remove(uuid);
                applyWarehouseState(g.id(), fresh);
                Guild current = plugin.guilds().guildOf(uuid);
                if (fresh == null || !running || lifecycle != openedLifecycle || !player.isOnline()
                        || !player.hasPermission("liuguild.warehouse") || current == null || !current.id().equals(g.id())
                        || !canAccess(current) || !lock.isHeldLocallyBy(g.id(), uuid)) {
                    plugin.async(() -> lock.release(g.id(), uuid, session.token()));
                    return;
                }
                WarehouseContents contents = new WarehouseContents();
                try {
                    for (WarehouseRepository.Stored stored : loaded.items().values()) {
                        ItemStack stack = ItemCodec.decode(stored.itemData());
                        long amount = stored.amount() == 0 && stack != null ? stack.getAmount() : stored.amount();
                        if (stack == null || stack.getType().isAir() || amount <= 0 || amount > Integer.MAX_VALUE)
                            throw new IllegalStateException("损坏的仓库条目");
                        // 合并旧槽位但保留全部物品，即使配置缩容也不丢失。
                        contents.deposit(stack, (int) amount, Integer.MAX_VALUE, false);
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("仓库解码失败，保留原数据并拒绝打开: " + e.getMessage());
                    plugin.messages().send(player, "wh-storage-failed");
                    plugin.async(() -> lock.release(g.id(), uuid, session.token()));
                    return;
                }
                WarehouseHolder holder = new WarehouseHolder(g.id());
                Inventory inv = Bukkit.createInventory(holder, 54,
                        Text.mm(plugin.messages().raw("wh-title").replace("{guild}", g.name())));
                holder.inv = inv;
                Open o = new Open(g.id(), uuid, inv, holder, contents, loaded.version(), session.token());
                open.put(uuid, o);
                render(player, o);
                // 真实仓库库存需要玩家自由存取物品，必须关闭 Sparrow 菜单会话后再打开。
                plugin.gui().closeMenu(player);
                player.openInventory(inv);
            });
        });
    }

    // =====================================================================
    // 仓库管理菜单：用工会点数（工会资金）购买使用时间 / 升级容量
    // =====================================================================

    private static final int MENU_SIZE = 27;
    private static final int MENU_INFO_SLOT = 4;
    private static final int[] MENU_RENT_SLOTS = {10, 11, 12, 13, 14};
    private static final int MENU_UPGRADE_SLOT = 16;
    private static final int MENU_OPEN_SLOT = 22;
    private static final long DAY_MILLIS = 86_400_000L;

    /** 给定仓库升级等级时的槽位数。 */
    public int slotsForLevel(int warehouseLevel) {
        return plugin.settings().warehouse.slots(warehouseLevel);
    }

    /** 打开仓库管理菜单（购买时长 + 升级容量 + 进入仓库）。 */
    public void openMenu(Player player, Guild g) {
        if (!authorized(player, g)) return;
        var c = plugin.settings().guild;
        if (!c.warehouseEnabled) {
            plugin.messages().send(player, "wh-disabled");
            return;
        }
        String title = plugin.messages().raw("wh-menu-title").replace("{guild}", g.name());
        Map<Integer, String> actions = new HashMap<>();
        MenuHolder holder = new MenuHolder(g.id(), actions);
        Inventory inv = Bukkit.createInventory(holder, MENU_SIZE, Text.mm(title));
        holder.inv = inv;

        ItemStack filler = GuiUtil.build(player, "GRAY_STAINED_GLASS_PANE", "GRAY_STAINED_GLASS_PANE",
                " ", List.of(), Map.of(), null);
        for (int i = 0; i < MENU_SIZE; i++) {
            inv.setItem(i, filler);
        }

        long now = System.currentTimeMillis();
        long remaining = remainingMillis(g);
        Map<String, String> info = new HashMap<>();
        info.put("guild", g.name());
        int effective = g.effectiveWarehouseLevel(now);
        var space = plugin.settings().warehouse;
        info.put("level", String.valueOf(effective));
        info.put("max_level", String.valueOf(c.warehouseMaxLevel));
        info.put("slots", String.valueOf(slotsFor(g)));
        info.put("points", String.valueOf(g.points()));
        info.put("time", c.warehouseRequireRent ? formatDuration(remaining) : "永久");
        info.put("state", isUsable(g) ? "有效" : space.withdrawExpired ? "已过期，只取不存" : "已过期，禁止访问");
        info.put("capacity_time", g.warehouseLevelExpiresAt() == 0 ? "永久" : formatDuration(Math.max(0, g.warehouseLevelExpiresAt() - now)));
        info.put("retention", space.expiredRetentionDays < 0 ? "永久保留" : "到期后" + space.expiredRetentionDays + "天删除物品");
        inv.setItem(MENU_INFO_SLOT, GuiUtil.build(player, "BOOK", "BOOK",
                plugin.messages().raw("wh-menu-info-name"), plugin.messages().rawList("wh-menu-info-lore"), info, null));

        // 购买使用时长
        if (c.warehouseRequireRent) {
            int[] options = c.warehouseRentOptions;
            for (int i = 0; i < MENU_RENT_SLOTS.length && i < options.length; i++) {
                int days = options[i];
                long cost = com.liu.liuguild.config.GuildSettings.warehouseRentCost(c.warehouseRentPricePerDay, days);
                int slot = MENU_RENT_SLOTS[i];
                actions.put(slot, "rent:" + days);
                Map<String, String> ph = new HashMap<>();
                ph.put("days", String.valueOf(days));
                ph.put("currency", space.rentPrice.currency().display);
                ph.put("cost", String.valueOf(cost));
                ph.put("points", String.valueOf(g.points()));
                inv.setItem(slot, GuiUtil.build(player, "GOLD_INGOT", "GOLD_INGOT",
                        plugin.messages().raw("wh-menu-rent-name"), plugin.messages().rawList("wh-menu-rent-lore"), ph, null));
            }
        }

        if (space.requireRent && space.permanentPrice != null && g.warehouseExpiresAt() != Long.MAX_VALUE) {
            actions.put(15, "rent:0");
            inv.setItem(15, GuiUtil.build(player, "NETHER_STAR", "NETHER_STAR", "<gold>永久解锁使用权",
                    List.of("<gray>花费：" + space.permanentPrice.amount() + " " + space.permanentPrice.currency().display,
                            "<gray>容量等级单独购买", "<yellow>点击购买（不可撤回）"), Map.of(), null));
        }
        // 升级容量
        if (c.warehouseMaxLevel > 0) {
            actions.put(MENU_UPGRADE_SLOT, "upgrade");
            long upgradeCost = nextUpgradeCost(g);
            Map<String, String> uph = new HashMap<>();
            var next = space.levels.get(effective + 1);
            uph.put("currency", next == null ? "-" : next.price().currency().display);
            uph.put("duration", next == null ? "已达最高等级" : next.days() == 0 ? "永久" : next.days() + "天（到期退回永久等级）");
            uph.put("level", String.valueOf(effective));
            uph.put("next_level", String.valueOf(effective + 1));
            uph.put("max_level", String.valueOf(c.warehouseMaxLevel));
            uph.put("slots", String.valueOf(slotsFor(g)));
            uph.put("next_slots", String.valueOf(slotsForLevel(effective + 1)));
            uph.put("cost", upgradeCost < 0 ? "-" : String.valueOf(upgradeCost));
            uph.put("points", String.valueOf(g.points()));
            inv.setItem(MENU_UPGRADE_SLOT, GuiUtil.build(player, "ANVIL", "ANVIL",
                    plugin.messages().raw("wh-menu-upgrade-name"), plugin.messages().rawList("wh-menu-upgrade-lore"), uph, null));
        }

        // 进入仓库（仅在可用时提供）
        if (canAccess(g)) {
            actions.put(MENU_OPEN_SLOT, "open");
            inv.setItem(MENU_OPEN_SLOT, GuiUtil.build(player, "ENDER_CHEST", "ENDER_CHEST",
                    plugin.messages().raw("wh-menu-open-name"), plugin.messages().rawList("wh-menu-open-lore"), Map.of(), null));
        } else {
            inv.setItem(MENU_OPEN_SLOT, GuiUtil.build(player, "BARRIER", "BARRIER",
                    plugin.messages().raw("wh-menu-locked-name"), plugin.messages().rawList("wh-menu-locked-lore"), Map.of(), null));
        }

        actions.put(26, "back");
        inv.setItem(26, GuiUtil.build(player, "ARROW", "ARROW", "<white>返回工会主页", List.of(), Map.of(), null));
        com.liu.liuguild.hook.SparrowUiHook sparrow = plugin.sparrowUi();
        if (sparrow != null && sparrow.openWindow(player, Text.mm(title), inv, holder::action,
                (slot, click) -> plugin.sync(() -> handleMenuAction(player, holder, holder.action(slot))))) {
            return;
        }
        player.closeInventory();
        player.openInventory(inv);
    }

    /** 管理菜单点击：只处理菜单自带按钮，且取消所有物品交互。 */
    @EventHandler
    public void onMenuClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof MenuHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getRawSlot() < 0 || event.getRawSlot() >= MENU_SIZE) {
            return;
        }
        handleMenuAction(player, holder, holder.action(event.getRawSlot()));
    }

    /** 仓库管理菜单动作处理（内置点击与 Sparrow 点击共用）。 */
    private void handleMenuAction(Player player, MenuHolder holder, String action) {
        if (action == null) {
            return;
        }
        Guild g = plugin.guilds().guild(holder.guildId());
        if (g == null) {
            plugin.gui().closeMenu(player);
            plugin.messages().send(player, "not-in-guild");
            return;
        }
        if (action.startsWith("rent:")) {
            rent(player, g, com.liu.liuguild.util.NumberUtil.toInt(action.substring(5), 0));
        } else if (action.equals("upgrade")) {
            upgrade(player, g);
        } else if (action.equals("back")) {
            plugin.gui().closeMenu(player);
            plugin.gui().openMain(player);
        } else if (action.equals("open")) {
            open(player, g);
        }
    }

    /** 管理菜单禁止拖拽（菜单是临时界面，拖进去的物品不会保存）。仓库背包则允许自由拖拽。 */
    @EventHandler
    public void onMenuDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof MenuHolder
                || event.getView().getTopInventory().getHolder() instanceof WarehouseHolder) {
            event.setCancelled(true);
        }
    }

    /** 用工会点数（工会资金）购买仓库使用时间。 */
    public void rent(Player player, Guild g, int days) {
        var c = plugin.settings().warehouse;
        if (days < 0 || (days == 0 && c.permanentPrice == null)) {
            plugin.messages().send(player, "wh-rent-invalid"); return;
        }
        try {
            var price = days == 0 ? c.permanentPrice : new com.liu.liuguild.config.WarehouseSettings.Price(
                    c.rentPrice.currency(), Math.multiplyExact(c.rentPrice.amount(), days));
            purchase(player, g, false, days, price);
        } catch (ArithmeticException e) { plugin.messages().send(player, "wh-rent-invalid"); }
    }

    public void upgrade(Player player, Guild g) {
        if (g == null) return;
        var next = plugin.settings().warehouse.levels.get(g.effectiveWarehouseLevel(System.currentTimeMillis()) + 1);
        if (next == null) { plugin.messages().send(player, "wh-upgrade-max", "max", plugin.settings().warehouse.levels.size()); return; }
        purchase(player, g, true, 0, next.price());
    }

    private final java.util.Set<UUID> purchasing = ConcurrentHashMap.newKeySet();
    private boolean externalPrice(Player player, com.liu.liuguild.config.WarehouseSettings.Price price, boolean refund) {
        if (price.amount() == 0) return true;
        return switch (price.currency()) {
            case MONEY -> plugin.economy().available() && (refund
                    ? plugin.economy().deposit(player, price.amount()) : plugin.economy().withdraw(player, price.amount()));
            case PLAYER_POINTS -> new com.liu.liuguild.hook.PlayerPointsHook().change(player.getUniqueId(), price.amount(), refund);
            default -> true;
        };
    }

    private void purchase(Player player, Guild g, boolean upgrade, int days,
                          com.liu.liuguild.config.WarehouseSettings.Price price) {
        var c = plugin.settings().warehouse;
        if (player == null || g == null || !c.enabled || !canManage(g, player)) return;
        if (!upgrade && (!c.requireRent || g.warehouseExpiresAt() == Long.MAX_VALUE)) {
            plugin.messages().send(player, "wh-rent-not-needed"); return;
        }
        if (price == null || (price.currency() == com.liu.liuguild.config.WarehouseSettings.Currency.PLAYER_POINTS
                && price.amount() > Integer.MAX_VALUE)) return;
        if (!purchasing.add(player.getUniqueId())) return;
        if (!externalPrice(player, price, false)) {
            purchasing.remove(player.getUniqueId());
            player.sendMessage(Text.mm("<red>购买失败：" + price.currency().display + "不足或经济接口不可用。")); return;
        }
        int expected = g.effectiveWarehouseLevel(System.currentTimeMillis());
        long expiry = g.warehouseExpiresAt();
        boolean admin = player.hasPermission("liuguild.admin");
        plugin.async(() -> {
            boolean result;
            try { result = repo.purchase(g.id(), player.getUniqueId(), admin, c, upgrade, days, expected, expiry, price); }
            catch (Exception e) { result = false; plugin.getLogger().warning("空间购买失败: " + e.getMessage()); }
            final boolean ok = result;
            Guild fresh = loadFresh(g.id());
            plugin.sync(() -> {
                purchasing.remove(player.getUniqueId());
                if (!ok && !externalPrice(player, price, true))
                    plugin.getLogger().severe("空间退款失败，需人工补偿: " + player.getUniqueId() + " " + price);
                applyWarehouseState(g.id(), fresh);
                if (ok) plugin.broadcastGuildChange(g.id());
                player.sendMessage(Text.mm(ok ? "<green>空间购买成功，花费 " + price.amount() + " " + price.currency().display
                        : "<red>购买失败：余额不足、权限/等级变化或超过租期上限；已尝试退还外部扣款。"));
                if (player.isOnline() && plugin.guilds().guild(g.id()) != null) openMenu(player, plugin.guilds().guild(g.id()));
            });
        });
    }

        /** 异步读取工会行；失败返回 null。 */
    private Guild loadFresh(String guildId) {
        try {
            return plugin.repo().loadGuild(guildId);
        } catch (Exception e) {
            return null;
        }
    }

    /** 把刚写入的仓库/点数/等级同步回本服缓存（精确值，不做乐观估算）。 */
    private void applyWarehouseState(String guildId, Guild fresh) {
        if (fresh == null) {
            return;
        }
        Guild cached = plugin.guilds().guild(guildId);
        if (cached == null) {
            return;
        }
        cached.bank(fresh.bank());
        cached.warehouseLevelExpiresAt(fresh.warehouseLevelExpiresAt());
        cached.warehousePermanentLevel(fresh.warehousePermanentLevel());
        cached.points(fresh.points());
        cached.level(fresh.level());
        cached.warehouseLevel(fresh.warehouseLevel());
        cached.warehouseExpiresAt(fresh.warehouseExpiresAt());
    }

        /** 把毫秒时长格式化成「N天N小时」之类的中文描述。 */
    static String formatDuration(long millis) {
        if (millis == Long.MAX_VALUE) {
            return "永久";
        }
        if (millis <= 0) {
            return "已过期";
        }
        long totalMinutes = millis / 60_000L;
        long days = totalMinutes / (60 * 24);
        long hours = (totalMinutes % (60 * 24)) / 60;
        long minutes = totalMinutes % 60;
        StringBuilder sb = new StringBuilder();
        if (days > 0) {
            sb.append(days).append("天");
        }
        if (hours > 0) {
            sb.append(hours).append("小时");
        }
        if (days == 0 && minutes > 0) {
            sb.append(minutes).append("分钟");
        }
        if (sb.length() == 0) {
            sb.append("不足1分钟");
        }
        return sb.toString();
    }

    private static String itemName(ItemStack item) {
        var meta = item.getItemMeta();
        return meta != null && meta.hasDisplayName() ? Text.plain(meta.displayName()) : item.getType().name();
    }

    private static int category(ItemStack item) {
        if (item.getType().isEdible()) return 1;
        String type = item.getType().name();
        if (type.endsWith("_SWORD") || type.endsWith("_AXE") || type.endsWith("_PICKAXE")
                || type.endsWith("_HELMET") || type.endsWith("_CHESTPLATE") || type.endsWith("_LEGGINGS")
                || type.endsWith("_BOOTS") || type.contains("BOW") || type.equals("SHIELD")) return 2;
        return item.getType().isBlock() ? 3 : 4;
    }

    private void button(Player p, Open o, int slot, String material, String name, String... lore) {
        o.inv.setItem(slot, GuiUtil.build(p, material, material, name, List.of(lore), Map.of(), null));
    }

    private void render(Player p, Open o) {
        o.inv.clear();
        Guild g = plugin.guilds().guild(o.guildId);
        int capacity = slotsFor(g);
        String query = o.search.toLowerCase(java.util.Locale.ROOT);
        List<WarehouseContents.Entry> visible = new ArrayList<>(o.contents.entries());
        visible.removeIf(e -> (o.category > 0 && category(e.sample()) != o.category)
                || !(itemName(e.sample()).toLowerCase(java.util.Locale.ROOT).contains(query)
                || e.sample().getType().name().toLowerCase(java.util.Locale.ROOT).contains(query)));
        java.util.Comparator<WarehouseContents.Entry> comparator = o.sort == 1
                ? java.util.Comparator.comparingInt(WarehouseContents.Entry::amount)
                : java.util.Comparator.comparing(e -> itemName(e.sample()));
        visible.sort(o.descending ? comparator.reversed() : comparator);
        o.visible = visible;
        int pages = Math.max(1, (Math.max(visible.size(), o.search.isEmpty() && o.category == 0 ? capacity : 0) + 44) / 45);
        o.page = Math.max(0, Math.min(o.page, pages - 1));
        for (int slot = 0; slot < 45; slot++) {
            int index = o.page * 45 + slot;
            if (index >= visible.size()) {
                if (index >= capacity) button(p, o, slot, "BLACK_STAINED_GLASS_PANE", "<gray>未解锁容量");
                continue;
            }
            WarehouseContents.Entry e = visible.get(index);
            ItemStack display = e.sample();
            display.setAmount(1);
            var meta = display.getItemMeta();
            if (meta != null) {
                List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
                lore.add(Text.mm("<gold>仓库存量：" + e.amount()));
                lore.add(Text.mm("<yellow>左键 1 / 右键 64"));
                lore.add(Text.mm("<yellow>Shift+左键 输入数量 / Shift+右键 尽量取出"));
                meta.lore(lore);
                display.setItemMeta(meta);
            }
            o.inv.setItem(slot, display);
        }
        button(p, o, 45, "ARROW", "<white>上一页");
        button(p, o, 46, "HOPPER", "<green>批量存入", "<gray>左键：已有同类", "<yellow>Shift+左键：背包全部（含快捷栏）", "<gray>不存护甲、副手或光标物品");
        button(p, o, 47, "COMPARATOR", "<white>排序：" + (o.sort == 0 ? "名称" : "数量"), "<gray>左键切换 / 右键升降序");
        button(p, o, 48, "COMPASS", "<white>搜索：" + (o.search.isEmpty() ? "全部" : o.search), "<gray>左键输入 / 右键清除");
        button(p, o, 49, "BOOK", "<gold>第 " + (o.page + 1) + " / " + pages + " 页",
                "<gray>已用类型：" + o.contents.entries().size() + " / " + capacity,
                "<gray>点击背包：左键存1 / 右键存64", "<gray>Shift+左键存一组 / Shift+右键同类全部");
        button(p, o, 50, "CHEST", "<white>分类：" + new String[]{"全部", "食物", "装备工具", "方块", "其他"}[o.category]);
        button(p, o, 51, "ANVIL", "<gold>容量与续期");
        button(p, o, 52, "ARROW", "<white>返回工会主页");
        button(p, o, 53, "ARROW", "<white>下一页");
    }

    private boolean canAccess(Guild g) {
        return g != null && plugin.settings().warehouse.enabled && (isUsable(g)
                || (plugin.settings().warehouse.withdrawExpired && g.warehouseExpiresAt() > 0));
    }

    private boolean valid(Player p, Open o) {
        Guild g = plugin.guilds().guildOf(p.getUniqueId());
        return running && !o.closed && !o.busy && g != null && g.id().equals(o.guildId)
                && canAccess(g) && p.hasPermission("liuguild.warehouse")
                && lock.isHeldLocallyBy(o.guildId, o.player);
    }

    /** 保存候选内容，再在主线程校验背包快照。背包变动或掉线时回滚仓库，不发货。 */
    private void commit(Player p, Open o, WarehouseContents next, ItemStack[] before, ItemStack[] after) {
        // 主线程只持有快照；整仓/背包的 NBT 序列化（可能上万次 serializeAsBytes）全部放到异步，
        // 避免一次仓库存取卡主线程。busy 期间不会再有为该会话生成新内容。
        final WarehouseContents previousContents = o.contents;
        o.busy = true;
        plugin.async(() -> {
            Map<Integer, WarehouseRepository.Stored> encoded = new LinkedHashMap<>();
            int index = 0;
            for (var e : next.entries()) {
                String data = ItemCodec.encode(e.sample());
                if (data == null) {
                    plugin.sync(() -> {
                        o.busy = false;
                        plugin.messages().send(p, "wh-storage-failed");
                    });
                    return;
                }
                encoded.put(index++, new WarehouseRepository.Stored(data, e.amount()));
            }
            Map<Integer, WarehouseRepository.Stored> previous = new LinkedHashMap<>();
            index = 0;
            for (var e : previousContents.entries()) {
                previous.put(index++, new WarehouseRepository.Stored(ItemCodec.encode(e.sample()), e.amount()));
            }
            // 先记录“从 before 到 after”的交接，再提交仓库。崩溃后按数据库版本决定是否补做背包变更。
            final String beforeData = ItemUtil.encode(before);
            final String afterData = ItemUtil.encode(after);
            if (beforeData == null || afterData == null || beforeData.isBlank() || afterData.isBlank()) {
                plugin.sync(() -> {
                    o.busy = false;
                    plugin.messages().send(p, "wh-storage-failed");
                });
                return;
            }
            boolean saved;
            try {
                repo.savePending(o.player, o.guildId, o.sessionToken, o.version, beforeData, afterData);
                saved = repo.save(o.guildId, o.version, encoded);
            } catch (Exception e) {
                saved = false;
                plugin.getLogger().warning("仓库存取提交失败: " + e.getMessage());
            }
            final boolean success = saved;
            plugin.sync(() -> {
                Guild memberGuild = plugin.guilds().guildOf(p.getUniqueId());
                boolean unchanged = p.isOnline() && !o.closed && memberGuild != null
                        && memberGuild.id().equals(o.guildId) && canAccess(memberGuild)
                        && lock.isHeldLocallyBy(o.guildId, o.player)
                        && java.util.Arrays.equals(before, p.getInventory().getStorageContents());
                if (success && unchanged) {
                    p.getInventory().setStorageContents(after);
                    o.contents = next;
                    o.version++;
                    o.busy = false;
                    deletePendingAsync(o.player, o.sessionToken);
                    render(p, o);
                } else if (success) {
                    plugin.async(() -> {
                        boolean rolledBack = false;
                        try {
                            rolledBack = repo.save(o.guildId, o.version + 1, previous);
                            if (!rolledBack)
                                plugin.getLogger().severe("仓库回滚版本冲突，需人工核查: " + o.guildId);
                        } catch (Exception e) { plugin.getLogger().severe("仓库回滚失败，需人工核查: " + o.guildId + " " + e); }
                        // 回滚成功时数据库已前进两格；保留交接记录会在恢复时被安全丢弃。
                        if (rolledBack) deletePendingAsync(o.player, o.sessionToken);
                        plugin.sync(() -> { o.busy = false; kick(o.player); });
                    });
                } else {
                    deletePendingAsync(o.player, o.sessionToken);
                    o.busy = false;
                    plugin.messages().send(p, "wh-storage-failed");
                    kick(o.player);
                }
            });
        });
    }

    private void deletePendingAsync(UUID player, String token) {
        plugin.async(() -> {
            try { repo.deletePending(player, token); }
            catch (Exception e) { plugin.getLogger().warning("清理仓库交接记录失败: " + e.getMessage()); }
        });
    }

    private static ItemStack[] cloneItems(ItemStack[] items) {
        ItemStack[] copy = items.clone();
        for (int i = 0; i < copy.length; i++) if (copy[i] != null) copy[i] = copy[i].clone();
        return copy;
    }

    private void deposit(Player p, Open o, int selected, int requested, boolean allSimilar, boolean existingOnly) {
        if (!valid(p, o)) return;
        if (!isUsable(plugin.guilds().guild(o.guildId))) {
            p.sendMessage(Text.mm("<red>空间使用权已过期，仅允许取出物品，请先续期。")); return;
        }
        ItemStack[] before = cloneItems(p.getInventory().getStorageContents());
        ItemStack[] after = cloneItems(before);
        WarehouseContents next = o.contents.copy();
        ItemStack sample = selected >= 0 && selected < before.length ? before[selected] : null;
        int moved = 0;
        for (int i = 0; i < after.length; i++) {
            ItemStack item = after[i];
            if (item == null || item.getType().isAir()) continue;
            if (selected >= 0 && (allSimilar ? sample == null || !item.isSimilar(sample) : i != selected)) continue;
            int take = next.deposit(item, Math.min(item.getAmount(), requested), slotsFor(plugin.guilds().guild(o.guildId)), existingOnly);
            if (take == 0) continue;
            moved += take;
            item.setAmount(item.getAmount() - take);
            if (item.getAmount() == 0) after[i] = null;
        }
        if (moved > 0) commit(p, o, next, before, after);
        else plugin.messages().send(p, "wh-no-space");
    }

    private void withdraw(Player p, Open o, WarehouseContents.Entry entry, int requested) {
        if (!valid(p, o) || !o.contents.entries().contains(entry)) return;
        ItemStack[] before = cloneItems(p.getInventory().getStorageContents());
        ItemStack[] after = cloneItems(before);
        ItemStack sample = entry.sample();
        int remaining = Math.min(requested, entry.amount());
        int moved = 0;
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < after.length && remaining > 0; i++) {
                ItemStack current = after[i];
                if (pass == 0 && (current == null || !current.isSimilar(sample))) continue;
                if (pass == 1 && current != null && !current.getType().isAir()) continue;
                int count = current == null || current.getType().isAir() ? 0 : current.getAmount();
                int take = Math.min(remaining, Math.max(0, sample.getMaxStackSize() - count));
                if (take == 0) continue;
                ItemStack stack = sample.clone();
                stack.setAmount(count + take);
                after[i] = stack;
                remaining -= take;
                moved += take;
            }
        }
        if (moved == 0) { plugin.messages().send(p, "wh-no-space"); return; }
        WarehouseContents next = o.contents.copy();
        int index = o.contents.entries().indexOf(entry);
        next.withdraw(next.entries().get(index), moved);
        commit(p, o, next, before, after);
    }

    @EventHandler
    public void onWarehouseCommand(org.bukkit.event.player.PlayerCommandPreprocessEvent event) {
        Open o = open.get(event.getPlayer().getUniqueId());
        if (o != null && o.busy) {
            event.setCancelled(true);
            plugin.messages().send(event.getPlayer(), "wh-processing");
        }
    }

    @EventHandler
    public void onWarehouseDrop(org.bukkit.event.player.PlayerDropItemEvent event) {
        Open o = open.get(event.getPlayer().getUniqueId());
        if (o != null && o.busy) event.setCancelled(true);
    }

    @EventHandler
    public void onWarehouseClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof WarehouseHolder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player p)) return;
        Open o = open.get(p.getUniqueId());
        if (o == null || !valid(p, o)) return;
        // 不允许数字键、丢弃键、创造模式克隆等拿走展示图标。
        if (!(event.isLeftClick() || event.isRightClick())) return;
        int slot = event.getRawSlot();
        if (slot >= 54) {
            if (event.getClickedInventory() == p.getInventory() && event.getSlot() < 36)
                deposit(p, o, event.getSlot(), event.isShiftClick() ? (event.isRightClick() ? Integer.MAX_VALUE : 64)
                        : event.isRightClick() ? 64 : 1, event.isShiftClick() && event.isRightClick(), false);
            return;
        }
        if (slot < 0) return;
        if (slot < 45) {
            int index = o.page * 45 + slot;
            if (index >= o.visible.size()) return;
            var entry = o.visible.get(index);
            if (event.isShiftClick() && event.isLeftClick()) {
                o.input = true;
                plugin.syncDelayed(() -> { if (o.input) kick(o.player); }, 600L);
                plugin.gui().chatInput().request(p, plugin.messages().component("wh-input-amount"), text -> {
                    o.input = false;
                    int amount = com.liu.liuguild.util.NumberUtil.toInt(text.trim(), -1);
                    if (amount > 0) withdraw(p, o, entry, amount);
                    if (!o.closed && p.isOnline()) p.openInventory(o.inv);
                }, () -> kick(o.player));
            } else withdraw(p, o, entry, event.isShiftClick() ? Integer.MAX_VALUE : event.isRightClick() ? 64 : 1);
            return;
        }
        switch (slot) {
            case 45 -> { o.page--; render(p, o); }
            case 53 -> { o.page++; render(p, o); }
            case 46 -> deposit(p, o, -1, Integer.MAX_VALUE, false, !event.isShiftClick());
            case 47 -> { if (event.isRightClick()) o.descending = !o.descending; else o.sort = 1 - o.sort; render(p, o); }
            case 48 -> {
                if (event.isRightClick()) { o.search = ""; o.page = 0; render(p, o); }
                else {
                    o.input = true;
                    plugin.syncDelayed(() -> { if (o.input) kick(o.player); }, 600L);
                    plugin.gui().chatInput().request(p, plugin.messages().component("wh-input-search"), text -> {
                        o.input = false;
                        o.search = text.trim(); o.page = 0;
                        if (!o.closed && p.isOnline()) { render(p, o); p.openInventory(o.inv); }
                    }, () -> kick(o.player));
                }
            }
            case 50 -> { o.category = (o.category + 1) % 5; o.page = 0; render(p, o); }
            case 51 -> openMenu(p, plugin.guilds().guild(o.guildId));
            case 52 -> plugin.gui().openMain(p);
            default -> { }
        }
    }

    private void finish(Open o) {
        o.closed = true;
        if (o.busy) return;
        open.remove(o.player, o);
        plugin.async(() -> lock.release(o.guildId, o.player, o.sessionToken));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof WarehouseHolder)) return;
        Open o = open.get(event.getPlayer().getUniqueId());
        if (o != null && o.inv == event.getInventory() && !o.input) finish(o);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Open o = open.get(event.getPlayer().getUniqueId());
        if (o != null) { o.input = false; finish(o); }
    }

    /** 强制关闭会话；事务中的存取待提交/回滚完成再释放锁。 */
    public void kick(UUID player) {
        Open o = open.get(player);
        if (o == null) return;
        o.input = false;
        plugin.gui().chatInput().cancel(o.player);
        finish(o);
        Player p = plugin.player(player);
        if (p != null) p.closeInventory();
    }

            }
