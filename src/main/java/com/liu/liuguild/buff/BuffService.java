package com.liu.liuguild.buff;

import com.liu.liuguild.LiuGuild;
import com.liu.liuguild.config.BuffSettings;
import com.liu.liuguild.model.Guild;
import com.liu.liuguild.module.GuildModule;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

/** IO异步刷新；玩家效果、菜单与权限缓存操作在主线程执行。 */
public final class BuffService implements GuildModule, Listener {
    private final LiuGuild plugin;
    private final BuffRepository repo;
    private Map<String, Map<String, Long>> active = Map.of();
    /** 配置里 effect 名 -> 已解析的效果类型（reload 时重建，避免每 tick 查 Registry）。 */
    private volatile Map<String, PotionEffectType> effectTypes = Map.of();
    /** 工会 -> 生效中的效果（refresh 时重建，apply 只做只读遍历）。 */
    private volatile Map<String, Map<PotionEffectType, ActiveBuff>> activeEffects = Map.of();
    private final Map<UUID, Set<PotionEffectType>> owned = new HashMap<>();
    private final Set<UUID> buying = new HashSet<>();
    private final Map<UUID, Double> xpRemainder = new HashMap<>();
    private BukkitTask refreshTask;
    private BukkitTask task;
    private boolean changing;
    private boolean refreshing;
    private boolean running;
    private long generation;
    public BuffService(LiuGuild plugin) { this.plugin = plugin; repo = new BuffRepository(plugin.db()); }
    public String id() { return "buffs"; }
    public int order() { return 61; }
    public void enable(LiuGuild plugin) {
        running = true;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        reload();
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) apply(p);
        }, 20, 20);
        refreshTask = Bukkit.getScheduler().runTaskTimer(plugin, this::refresh, 200, 200);
    }
    public void reload() {
        generation++;
        active = Map.of();
        activeEffects = Map.of();
        effectTypes = parseEffectTypes();
        refresh();
    }

    private Map<String, PotionEffectType> parseEffectTypes() {
        Map<String, PotionEffectType> out = new HashMap<>();
        for (var buff : settings().buffs.values()) {
            PotionEffectType type = effectType(buff.effect());
            if (type != null) out.put(buff.effect(), type);
        }
        return out;
    }

    /** 由 {@link #active} + 配置重建「工会 -> 效果」，主线程调用。 */
    private void rebuildActiveEffects() {
        Map<String, Map<PotionEffectType, ActiveBuff>> out = new HashMap<>();
        long now = System.currentTimeMillis();
        for (var entry : active.entrySet()) {
            Map<PotionEffectType, ActiveBuff> perGuild = new HashMap<>();
            for (var buff : settings().buffs.values()) {
                PotionEffectType type = effectTypes.get(buff.effect());
                if (type == null) {
                    continue;
                }
                long expiresAt = entry.getValue().getOrDefault(buff.id(), 0L);
                if (expiresAt <= now) {
                    continue;
                }
                perGuild.merge(type, new ActiveBuff(buff.amplifier(), expiresAt),
                        (a, b) -> a.amplifier() >= b.amplifier() ? a : b);
            }
            if (!perGuild.isEmpty()) {
                out.put(entry.getKey(), perGuild);
            }
        }
        activeEffects = out;
    }

    private record ActiveBuff(int amplifier, long expiresAt) {
    }
    public void disable() {
        running = false;
        if (task != null) task.cancel();
        if (refreshTask != null) refreshTask.cancel();
        // Bukkit cancels remaining plugin tasks on disable.
        for (Player p : Bukkit.getOnlinePlayers()) suspend(p);
        active = Map.of(); buying.clear(); xpRemainder.clear();
    }
    public void onCrossServer(String type, Map<String, String> data) {
        if ("GUILD_BUFFS".equals(type)) plugin.sync(this::refresh);
    }
    private BuffSettings settings() { return plugin.settings().buffs; }
    public long expires(String guild, String id) { return active.getOrDefault(guild, Map.of()).getOrDefault(id, 0L); }
    public int activeCount(String guild) {
        long now = System.currentTimeMillis();
        return (int) settings().buffs.keySet().stream().filter(id -> expires(guild, id) > now).count();
    }
    private void refresh() {
        if (!running || refreshing) return;
        refreshing = true;
        long version = generation;
        plugin.async(() -> {
            try {
                var snapshot = repo.loadActive(System.currentTimeMillis());
                plugin.sync(() -> {
                    refreshing = false;
                    if (running && version == generation) {
                        active = snapshot;
                        rebuildActiveEffects();
                    }
                });
            } catch (Exception e) {
                plugin.getLogger().warning("读取工会增益失败: " + e.getMessage());
                plugin.sync(() -> {
                    refreshing = false;
                    active = Map.of();
                    activeEffects = Map.of();
                });
            }
        });
    }
    public void purchase(Player player, Guild guild, String id) {
        if (!buying.add(player.getUniqueId())) return;
        UUID uuid = player.getUniqueId();
        BuffSettings config = settings();
        plugin.async(() -> {
            BuffRepository.Purchase result;
            try {
                result = repo.purchase(guild.id(), uuid, config, id, System.currentTimeMillis());
                if (result.error() == null) {
                    try {
                        plugin.broadcastGuildChange(guild.id());
                        if (plugin.bus() != null) plugin.bus().publish("GUILD_BUFFS", Map.of("guild", guild.id(), "server", plugin.serverId()));
                    } catch (Exception notificationFailure) {
                        plugin.getLogger().warning("工会增益已购买，跨服通知失败，将由轮询同步: " + notificationFailure.getMessage());
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().warning("购买工会增益失败: " + e.getMessage());
                result = new BuffRepository.Purchase("buff-storage-failed", 0);
            }
            var completed = result;
            plugin.sync(() -> {
                buying.remove(uuid);
                if (!running) return;
                if (completed.error() == null) {
                    // Never replace a newer DB snapshot with an older cached purchase result.
                    refresh(); plugin.guilds().reloadGuild(guild.id());
                }
                if (player.isOnline()) {
                    plugin.messages().send(player, completed.error() == null ? "buff-purchased" : completed.error());
                    plugin.gui().openBuffs(player);
                }
            });
        });
    }
    private Guild eligible(Player player) {
        if (!settings().enabled || (plugin.war() != null && plugin.war().isWarRestricted(player.getUniqueId()))) return null;
        return plugin.guilds().guildOf(player.getUniqueId());
    }
    /** 效果名（配置值）→ 原版效果类型；只在 reload 时调用。 */
    static PotionEffectType effectType(String effect) {
        String key = switch (effect == null ? "" : effect.toUpperCase(Locale.ROOT)) {
            case "SPEED" -> "speed";
            case "HASTE" -> "haste";
            case "WATER_BREATHING" -> "water_breathing";
            default -> "";
        };
        return key.isEmpty() ? null : Registry.EFFECT.get(NamespacedKey.minecraft(key));
    }

    private void apply(Player player) {
        UUID uuid = player.getUniqueId();
        Guild guild = eligible(player);
        Map<PotionEffectType, ActiveBuff> desired = guild == null
                ? Map.of() : activeEffects.getOrDefault(guild.id(), Map.of());
        Set<PotionEffectType> mine = owned.get(uuid);
        long now = System.currentTimeMillis();
        if (mine != null) {
            // 先移除已失效/已不属于本服发放的效果（先收集再删，避免边迭代边结构修改）
            List<PotionEffectType> stale = new ArrayList<>();
            for (PotionEffectType type : mine) {
                if (!desired.containsKey(type)) {
                    stale.add(type);
                }
            }
            for (PotionEffectType type : stale) {
                remove(player, type);
            }
            if (mine.isEmpty()) {
                owned.remove(uuid);
                mine = null;
            }
        }
        if (desired.isEmpty()) {
            return;
        }
        if (mine == null) {
            mine = new HashSet<>();
            owned.put(uuid, mine);
        }
        for (var entry : desired.entrySet()) {
            PotionEffectType type = entry.getKey();
            ActiveBuff buff = entry.getValue();
            long remaining = buff.expiresAt() - now;
            if (remaining <= 0) {
                continue;
            }
            PotionEffect current = player.getPotionEffect(type);
            boolean ours = mine.contains(type);
            // 不覆盖其它插件/药水自己给的效果（即使更弱）
            if (current != null && !ours) {
                continue;
            }
            // 剩余时间还充足就不必每秒重发，减少药水事件与属性刷新。
            if (current != null && ours && current.getDuration() > 40
                    && current.getAmplifier() >= buff.amplifier()) {
                continue;
            }
            int ticks = (int) Math.min(100, Math.max(20, remaining / 50));
            changing = true;
            try {
                if (player.addPotionEffect(new PotionEffect(type, ticks, buff.amplifier(), true, false, true))) {
                    mine.add(type);
                }
            } finally {
                changing = false;
            }
        }
    }
    private void remove(Player player, PotionEffectType type) {
        changing = true;
        try { player.removePotionEffect(type); }
        finally { changing = false; }
        var mine = owned.get(player.getUniqueId());
        if (mine != null) mine.remove(type);
    }
    /** 入战快照前调用，避免把工会效果装进装备托管快照。 */
    public void suspend(Player player) {
        var mine = owned.get(player.getUniqueId());
        if (mine != null) for (var type : new HashSet<>(mine)) remove(player, type);
        owned.remove(player.getUniqueId()); xpRemainder.remove(player.getUniqueId());
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void potionChanged(EntityPotionEffectEvent event) {
        if (changing || !(event.getEntity() instanceof Player player)) return;
        var mine = owned.get(player.getUniqueId());
        if (mine != null) mine.remove(event.getModifiedType()); // external potion/beacon/plugin takes ownership
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void experience(PlayerExpChangeEvent event) {
        Guild guild = eligible(event.getPlayer());
        if (guild == null || event.getAmount() <= 0) return;
        double bonus = 0;
        for (var buff : settings().buffs.values()) if ("EXPERIENCE".equals(buff.effect()) && expires(guild.id(), buff.id()) > System.currentTimeMillis()) bonus = Math.max(bonus, buff.experienceBonus());
        if (bonus == 0) return;
        UUID uuid = event.getPlayer().getUniqueId();
        double extra = event.getAmount() * bonus + xpRemainder.getOrDefault(uuid, 0.0);
        int whole = (int) Math.min(Integer.MAX_VALUE - event.getAmount(), Math.floor(extra));
        xpRemainder.put(uuid, extra - Math.floor(extra));
        if (whole > 0 && event.getAmount() > Integer.MAX_VALUE - whole) return;
        event.setAmount(event.getAmount() + whole);
    }
    @EventHandler public void quit(PlayerQuitEvent event) { suspend(event.getPlayer()); }
}
