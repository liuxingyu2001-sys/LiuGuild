package com.liu.liuguild.warehouse;

import com.liu.liuguild.LiuGuild;
import com.liu.liuguild.model.Guild;
import com.liu.liuguild.module.GuildModule;
import com.liu.liuguild.util.ItemCodec;
import com.liu.liuguild.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.LinkedHashMap;
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

    /** 仓库槽位数：基础 + 每级增长，受上限约束（结果保证是 9 的倍数）。 */
    public int slotsFor(Guild g) {
        var c = plugin.settings().guild;
        return com.liu.liuguild.config.GuildSettings.warehouseSlots(
                c.warehouseBaseSlots, c.warehouseSlotsPerLevel, c.warehouseMaxSlots,
                g == null ? 1 : g.level());
    }

    /**
     * 打开工会仓库。
     *
     * @return true = 已发起打开（异步加载后打开界面）
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
        Open o = open.remove(player.getUniqueId());
        if (o == null) {
            return;
        }
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
                plugin.sync(() -> lock.release(o.guildId, o.player));
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
