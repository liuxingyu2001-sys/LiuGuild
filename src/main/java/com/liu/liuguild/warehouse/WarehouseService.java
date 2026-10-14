package com.liu.liuguild.warehouse;

import com.liu.liuguild.LiuGuild;
import com.liu.liuguild.gui.GuiUtil;
import com.liu.liuguild.model.Guild;
import com.liu.liuguild.model.GuildMember;
import com.liu.liuguild.module.GuildModule;
import com.liu.liuguild.util.ItemCodec;
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
    private BukkitTask heartbeatTask;

    /** 已打开的仓库（玩家 → 会话），用于关服/掉线时兜底保存。 */
    private final Map<UUID, Open> open = new ConcurrentHashMap<>();

    /** 一个已打开的仓库会话。 */
    private static final class Open {
        final String guildId;
        final UUID player;
        final Inventory inv;
        final WarehouseHolder holder;

        Open(String guildId, UUID player, Inventory inv, WarehouseHolder holder) {
            this.guildId = guildId;
            this.player = player;
            this.inv = inv;
            this.holder = holder;
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
        Bukkit.getPluginManager().registerEvents(this, plugin);
        long period = lock.heartbeatSeconds() * 20L;
        heartbeatTask = Bukkit.getScheduler().runTaskTimer(plugin, lock::heartbeat, period, period);
    }

    @Override
    public void disable() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel();
            heartbeatTask = null;
        }
        // 关服：把还开着的仓库落库并释放锁
        for (Map.Entry<UUID, Open> e : open.entrySet()) {
            Open o = e.getValue();
            flush(o);
        }
        open.clear();
        lock.releaseAll();
    }

    public WarehouseLock lock() {
        return lock;
    }

    /** 仓库槽位数：基础 + 已购买升级级数 × 每级增长，受上限约束（结果是 9 的倍数）。 */
    public int slotsFor(Guild g) {
        var c = plugin.settings().guild;
        int purchased = g == null ? 0 : g.warehouseLevel();
        return com.liu.liuguild.config.GuildSettings.warehouseSlots(
                c.warehouseBaseSlots, c.warehouseSlotsPerLevel, c.warehouseMaxSlots,
                purchased + 1);
    }

    /** 仓库是否可用：关闭租赁要求时永久可用；否则必须在有效期内。 */
    public boolean isUsable(Guild g) {
        var c = plugin.settings().guild;
        if (!c.warehouseRequireRent) {
            return true;
        }
        return g != null && g.warehouseActive(System.currentTimeMillis());
    }

    /** 剩余的可用时长（毫秒，0 = 已过期/未开通）。 */
    public long remainingMillis(Guild g) {
        if (g == null || !plugin.settings().guild.warehouseRequireRent) {
            return Long.MAX_VALUE;
        }
        return Math.max(0, g.warehouseExpiresAt() - System.currentTimeMillis());
    }

    /** 本会成员能否购买时长 / 升级仓库。 */
    public boolean canManage(Guild g, Player player) {
        if (player != null && player.hasPermission("liuguild.admin")) {
            return true;
        }
        GuildMember m = g == null ? null : g.member(player == null ? null : player.getUniqueId());
        return m != null && m.rank().atLeast(plugin.settings().guild.warehouseManageRank);
    }

    /** 下次升级的花费（已到上限或无法再增加槽位时返回 -1）。 */
    public long nextUpgradeCost(Guild g) {
        var c = plugin.settings().guild;
        if (g == null || g.warehouseLevel() >= c.warehouseMaxLevel) {
            return -1;
        }
        // 已到槽位上限时不再允许升级，避免花了钱却拿不到新槽位
        if (slotsForLevel(g.warehouseLevel() + 1) <= slotsForLevel(g.warehouseLevel())) {
            return -1;
        }
        return com.liu.liuguild.config.GuildSettings.warehouseUpgradeCost(
                c.warehouseUpgradeBaseCost, c.warehouseUpgradeCostGrowth, g.warehouseLevel());
    }

    /**
     * 打开工会仓库。
     *
     * @return true = 已发起打开（异步加载后打开界面）。未开通/已过期时改为打开管理菜单。
     */
    public boolean open(Player player, Guild g) {
        if (player == null || g == null) {
            return false;
        }
        var c = plugin.settings().guild;
        if (!c.warehouseEnabled) {
            plugin.messages().send(player, "wh-disabled");
            return false;
        }
        // 未开通 / 已过期：打开管理菜单引导购买时长与升级
        if (!isUsable(g)) {
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
        WarehouseLock.Session session = lock.acquire(g.id(), player.getUniqueId(), player.getName());
        if (session == null) {
            plugin.messages().send(player, "wh-busy");
            return false;
        }
        int slots = slotsFor(g);
        String title = plugin.messages().raw("wh-title").replace("{guild}", g.name());
        player.closeInventory();
        plugin.async(() -> {
            Map<Integer, String> data;
            try {
                data = repo.load(g.id());
            } catch (Exception e) {
                plugin.getLogger().warning("读取工会仓库失败: " + e.getMessage());
                data = Map.of();
            }
            final Map<Integer, String> loaded = data;
            plugin.sync(() -> {
                if (!player.isOnline()) {
                    lock.release(g.id(), player.getUniqueId());
                    return;
                }
                WarehouseHolder holder = new WarehouseHolder(g.id());
                Inventory inv = Bukkit.createInventory(holder, slots, Text.mm(title));
                holder.inv = inv;
                for (Map.Entry<Integer, String> e : loaded.entrySet()) {
                    int slot = e.getKey();
                    if (slot < 0 || slot >= slots) {
                        continue; // 降级后多余槽位的物品保留在库里，不删
                    }
                    ItemStack stack = ItemCodec.decodeClamped(e.getValue());
                    if (stack != null) {
                        inv.setItem(slot, stack);
                    }
                }
                open.put(player.getUniqueId(), new Open(g.id(), player.getUniqueId(), inv, holder));
                player.openInventory(inv);
            });
        });
        return true;
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
        var c = plugin.settings().guild;
        return com.liu.liuguild.config.GuildSettings.warehouseSlots(
                c.warehouseBaseSlots, c.warehouseSlotsPerLevel, c.warehouseMaxSlots, warehouseLevel + 1);
    }

    /** 打开仓库管理菜单（购买时长 + 升级容量 + 进入仓库）。 */
    public void openMenu(Player player, Guild g) {
        if (player == null || g == null) {
            return;
        }
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
        info.put("level", String.valueOf(g.warehouseLevel()));
        info.put("max_level", String.valueOf(c.warehouseMaxLevel));
        info.put("slots", String.valueOf(slotsFor(g)));
        info.put("points", String.valueOf(g.points()));
        info.put("time", c.warehouseRequireRent ? formatDuration(remaining) : "永久");
        info.put("state", !c.warehouseRequireRent ? "永久可用" : (g.warehouseActive(now) ? "有效" : "已过期"));
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
                ph.put("cost", String.valueOf(cost));
                ph.put("points", String.valueOf(g.points()));
                inv.setItem(slot, GuiUtil.build(player, "GOLD_INGOT", "GOLD_INGOT",
                        plugin.messages().raw("wh-menu-rent-name"), plugin.messages().rawList("wh-menu-rent-lore"), ph, null));
            }
        }

        // 升级容量
        if (c.warehouseMaxLevel > 0) {
            actions.put(MENU_UPGRADE_SLOT, "upgrade");
            long upgradeCost = nextUpgradeCost(g);
            Map<String, String> uph = new HashMap<>();
            uph.put("level", String.valueOf(g.warehouseLevel()));
            uph.put("next_level", String.valueOf(g.warehouseLevel() + 1));
            uph.put("max_level", String.valueOf(c.warehouseMaxLevel));
            uph.put("slots", String.valueOf(slotsFor(g)));
            uph.put("next_slots", String.valueOf(slotsForLevel(g.warehouseLevel() + 1)));
            uph.put("cost", upgradeCost < 0 ? "-" : String.valueOf(upgradeCost));
            uph.put("points", String.valueOf(g.points()));
            inv.setItem(MENU_UPGRADE_SLOT, GuiUtil.build(player, "ANVIL", "ANVIL",
                    plugin.messages().raw("wh-menu-upgrade-name"), plugin.messages().rawList("wh-menu-upgrade-lore"), uph, null));
        }

        // 进入仓库（仅在可用时提供）
        if (isUsable(g)) {
            actions.put(MENU_OPEN_SLOT, "open");
            inv.setItem(MENU_OPEN_SLOT, GuiUtil.build(player, "ENDER_CHEST", "ENDER_CHEST",
                    plugin.messages().raw("wh-menu-open-name"), plugin.messages().rawList("wh-menu-open-lore"), Map.of(), null));
        } else {
            inv.setItem(MENU_OPEN_SLOT, GuiUtil.build(player, "BARRIER", "BARRIER",
                    plugin.messages().raw("wh-menu-locked-name"), plugin.messages().rawList("wh-menu-locked-lore"), Map.of(), null));
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
        String action = holder.action(event.getRawSlot());
        if (action == null) {
            return;
        }
        Guild g = plugin.guilds().guild(holder.guildId());
        if (g == null) {
            player.closeInventory();
            plugin.messages().send(player, "not-in-guild");
            return;
        }
        if (action.startsWith("rent:")) {
            rent(player, g, com.liu.liuguild.util.NumberUtil.toInt(action.substring(5), 0));
        } else if (action.equals("upgrade")) {
            upgrade(player, g);
        } else if (action.equals("open")) {
            open(player, g);
        }
    }

    /** 用工会点数（工会资金）购买仓库使用时间。 */
    public void rent(Player player, Guild g, int days) {
        var c = plugin.settings().guild;
        if (!c.warehouseEnabled) {
            plugin.messages().send(player, "wh-disabled");
            return;
        }
        if (!c.warehouseRequireRent) {
            plugin.messages().send(player, "wh-rent-not-needed");
            return;
        }
        if (!canManage(g, player)) {
            plugin.messages().send(player, "wh-no-permission");
            return;
        }
        if (days <= 0) {
            plugin.messages().send(player, "wh-rent-invalid");
            return;
        }
        long now = System.currentTimeMillis();
        long maxMs = c.warehouseRentMaxDays > 0 ? c.warehouseRentMaxDays * DAY_MILLIS : Long.MAX_VALUE;
        long base = Math.max(now, g.warehouseExpiresAt());
        long after = base > Long.MAX_VALUE - (long) days * DAY_MILLIS
                ? Long.MAX_VALUE : base + (long) days * DAY_MILLIS;
        if (after - now > maxMs) {
            plugin.messages().send(player, "wh-rent-max", "max", String.valueOf(c.warehouseRentMaxDays));
            return;
        }
        long cost = com.liu.liuguild.config.GuildSettings.warehouseRentCost(c.warehouseRentPricePerDay, days);
        if (g.points() < cost) {
            plugin.messages().send(player, "wh-points-low",
                    "cost", String.valueOf(cost), "points", String.valueOf(g.points()));
            return;
        }
        long durationMs = (long) days * DAY_MILLIS;
        plugin.async(() -> {
            boolean ok;
            try {
                ok = plugin.repo().rentWarehouse(g.id(), cost, now, durationMs);
            } catch (Exception e) {
                plugin.getLogger().warning("购买工会仓库时长失败: " + e.getMessage());
                ok = false;
            }
            final boolean success = ok;
            plugin.sync(() -> {
                if (!success) {
                    plugin.messages().send(player, "wh-rent-failed");
                    return;
                }
                plugin.guilds().reloadGuild(g.id());
                plugin.broadcastGuildChange(g.id());
                plugin.messages().send(player, "wh-rent-success",
                        "days", String.valueOf(days), "cost", String.valueOf(cost));
                if (player.isOnline()) {
                    Guild fresh = plugin.guilds().guild(g.id());
                    openMenu(player, fresh == null ? g : fresh);
                }
            });
        });
    }

    /** 用工会点数（工会资金）升级仓库容量。 */
    public void upgrade(Player player, Guild g) {
        var c = plugin.settings().guild;
        if (!c.warehouseEnabled) {
            plugin.messages().send(player, "wh-disabled");
            return;
        }
        if (!canManage(g, player)) {
            plugin.messages().send(player, "wh-no-permission");
            return;
        }
        long cost = nextUpgradeCost(g);
        if (cost < 0) {
            plugin.messages().send(player, "wh-upgrade-max", "max", String.valueOf(c.warehouseMaxLevel));
            return;
        }
        if (g.points() < cost) {
            plugin.messages().send(player, "wh-points-low",
                    "cost", String.valueOf(cost), "points", String.valueOf(g.points()));
            return;
        }
        int expected = g.warehouseLevel();
        plugin.async(() -> {
            boolean ok;
            try {
                ok = plugin.repo().upgradeWarehouse(g.id(), cost, expected);
            } catch (Exception e) {
                plugin.getLogger().warning("升级工会仓库失败: " + e.getMessage());
                ok = false;
            }
            final boolean success = ok;
            plugin.sync(() -> {
                if (!success) {
                    plugin.messages().send(player, "wh-upgrade-failed");
                    return;
                }
                plugin.guilds().reloadGuild(g.id());
                plugin.broadcastGuildChange(g.id());
                Guild fresh = plugin.guilds().guild(g.id());
                plugin.messages().send(player, "wh-upgrade-success",
                        "level", String.valueOf(expected + 1),
                        "cost", String.valueOf(cost),
                        "slots", String.valueOf(slotsFor(fresh)));
                if (player.isOnline()) {
                    openMenu(player, fresh == null ? g : fresh);
                }
            });
        });
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

    /**
     * 关闭时保存并释放锁。保存成功后才释放，避免下一个玩家读到旧数据。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof WarehouseHolder holder)) {
            return;
        }
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        Open o = open.get(player.getUniqueId());
        // 只处理“当前这一个”界面的关闭。
        // 快速连点会让第二次 open 覆盖会话并重开界面，旧的 close 事件随后才到；
        // 此时如果不比对引用，就会把新会话也一起释放掉。
        if (o == null || o.inv != event.getInventory()) {
            return;
        }
        open.remove(player.getUniqueId());
        flushThenRelease(o);
    }

    /** 掉线：同样保存并释放。 */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Open o = open.remove(event.getPlayer().getUniqueId());
        if (o == null) {
            return;
        }
        // 先把背包内容拷贝出来（玩家对象即将失效）
        flushThenRelease(o);
    }

    /** 同步落库 + 释放锁（关服路径用）。 */
    private void flush(Open o) {
        Map<Integer, String> snapshot = snapshot(o.inv);
        try {
            repo.replaceAll(o.guildId, snapshot);
        } catch (Exception e) {
            plugin.getLogger().warning("关服保存工会仓库失败: " + e.getMessage());
        }
        lock.release(o.guildId, o.player);
    }

    /** 异步落库，成功（或失败）后再释放锁。 */
    private void flushThenRelease(Open o) {
        Map<Integer, String> snapshot = snapshot(o.inv);
        plugin.async(() -> {
            try {
                repo.replaceAll(o.guildId, snapshot);
            } catch (Exception e) {
                plugin.getLogger().warning("保存工会仓库失败: " + e.getMessage());
            } finally {
                // 关服过程中调度器可能已拒绝新任务，兜底直接释放，避免日志抛异常
                try {
                    plugin.sync(() -> lock.release(o.guildId, o.player));
                } catch (Throwable t) {
                    lock.release(o.guildId, o.player);
                }
            }
        });
    }

    /** 把背包内容编码成 slot → base64。 */
    private Map<Integer, String> snapshot(Inventory inv) {
        Map<Integer, String> out = new LinkedHashMap<>();
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length; i++) {
            String encoded = ItemCodec.encode(contents[i]);
            if (encoded != null) {
                out.put(i, encoded);
            }
        }
        return out;
    }

    /** 强制关闭某玩家的仓库（例如被踢出工会 / 权限被收回）。 */
    public void kick(UUID player) {
        Open o = open.remove(player);
        if (o == null) {
            return;
        }
        Player p = plugin.player(player);
        if (p != null) {
            p.closeInventory();
        }
        flushThenRelease(o);
    }

    /** 清空某工会仓库（解散工会）。 */
    public void purge(String guildId) {
        plugin.async(() -> {
            try {
                repo.deleteAll(guildId);
            } catch (Exception e) {
                plugin.getLogger().warning("清空工会仓库失败: " + e.getMessage());
            }
        });
    }

    public boolean isOpen(UUID player) {
        return open.containsKey(player);
    }

    /** 供 GUI 点击入口使用。 */
    public Component busyMessage(String guildId) {
        String who = lock.localHolderName(guildId);
        return who == null
                ? plugin.messages().component("wh-busy")
                : plugin.messages().component("wh-busy-player", "player", who);
    }
}
