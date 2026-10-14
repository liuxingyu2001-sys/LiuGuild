package com.liu.liuguild.activity;

import com.liu.liuguild.LiuGuild;
import com.liu.liuguild.config.GuildSettings;
import com.liu.liuguild.model.Guild;
import com.liu.liuguild.module.GuildModule;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家在线活跃度 → 工会资金。
 *
 * <h3>为什么是「活跃」而不是「在线」</h3>
 * <p>只按在线时长发钱的话，挂机党会白嫖工会资金。这里用移动/交互/战斗/钓鱼等事件
 * 记录「最近活跃时间」，结算时只统计在 {@code afk-seconds} 内动过的成员，
 * 从而让奖励真正流向在玩的玩家。把 {@code afk-seconds} 设为 0 则退化为纯在线统计。</p>
 *
 * <h3>怎么发</h3>
 * <p>每隔 {@code interval-seconds} 结算一次：按工会统计活跃在线人数，
 * 发放 {@code 人数 × points-per-player} 的工会资金；每个工会每天还有
 * {@code daily-cap} 总上限（0 = 不限），上限判定在数据库原子完成，跨服也不会超发。</p>
 */
public final class ActivityService implements GuildModule, Listener {

    /** 活跃时间写入节流：同一玩家 1 秒内只记一次，避免移动事件高频写 map。 */
    private static final long THROTTLE_MS = 1000L;

    private final LiuGuild plugin;
    private final Map<UUID, Long> lastActive = new ConcurrentHashMap<>();
    private BukkitTask task;

    public ActivityService(LiuGuild plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "activity";
    }

    @Override
    public int order() {
        return 60;
    }

    @Override
    public void enable(LiuGuild plugin) {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        start();
    }

    @Override
    public void disable() {
        stop();
        lastActive.clear();
    }

    @Override
    public void reload() {
        // 开关 / 间隔可能被改：重启结算任务
        start();
    }

    private void start() {
        stop();
        GuildSettings c = plugin.settings().guild;
        if (!c.activityEnabled) {
            return;
        }
        long ticks = Math.max(30, c.activityIntervalSeconds) * 20L;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::settle, ticks, ticks);
    }

    private void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /** 结算一次：按工会统计活跃在线成员，发放工会资金（受每日上限约束）。 */
    void settle() {
        GuildSettings c = plugin.settings().guild;
        if (!c.activityEnabled || c.activityPointsPerPlayer <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        long afkMillis = c.activityAfkSeconds * 1000L;

        Map<String, List<UUID>> byGuild = new HashMap<>();
        Map<String, Guild> guilds = new HashMap<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Guild g = plugin.guilds().guildOf(p.getUniqueId());
            if (g == null) {
                continue;
            }
            byGuild.computeIfAbsent(g.id(), k -> new ArrayList<>()).add(p.getUniqueId());
            guilds.putIfAbsent(g.id(), g);
        }
        for (Map.Entry<String, List<UUID>> e : byGuild.entrySet()) {
            Guild g = guilds.get(e.getKey());
            if (g == null) {
                continue;
            }
            int active = activeCount(e.getValue(), lastActive, now, afkMillis);
            long delta = GuildSettings.activityReward(active, c.activityMinOnline, c.activityPointsPerPlayer);
            if (delta > 0) {
                plugin.guilds().grantActivityFunds(g, delta);
            }
        }
    }

    /**
     * 纯函数：过滤出「活跃」成员数。
     *
     * @param online    候选成员
     * @param lastActive 成员 UUID → 最近活跃毫秒
     * @param now       当前毫秒
     * @param afkMillis 活跃判定窗口（≤0 表示不检测挂机，全部算活跃）
     */
    static int activeCount(Iterable<UUID> online, Map<UUID, Long> lastActive, long now, long afkMillis) {
        int n = 0;
        for (UUID u : online) {
            if (afkMillis <= 0) {
                n++;
                continue;
            }
            Long t = lastActive.get(u);
            if (t != null && now - t <= afkMillis) {
                n++;
            }
        }
        return n;
    }

    // =====================================================================
    // 活跃标记：只记「最近动过」，由结算任务读取
    // =====================================================================

    private void mark(UUID uuid) {
        long now = System.currentTimeMillis();
        Long prev = lastActive.get(uuid);
        if (prev != null && now - prev < THROTTLE_MS) {
            return;
        }
        lastActive.put(uuid, now);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (e.getFrom().getBlockX() == e.getTo().getBlockX()
                && e.getFrom().getBlockY() == e.getTo().getBlockY()
                && e.getFrom().getBlockZ() == e.getTo().getBlockZ()) {
            return; // 只转视角不算活跃
        }
        mark(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        mark(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        mark(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        mark(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent e) {
        mark(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamaged(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p) {
            mark(p.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamageDealt(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player p) {
            mark(p.getUniqueId());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        mark(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        lastActive.remove(e.getPlayer().getUniqueId());
    }
}
