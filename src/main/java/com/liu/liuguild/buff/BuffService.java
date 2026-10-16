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
    public void reload() { generation++; active = Map.of(); refresh(); }
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
                plugin.sync(() -> { refreshing = false; if (running && version == generation) active = snapshot; });
            } catch (Exception e) {
                plugin.getLogger().warning("读取工会增益失败: " + e.getMessage());
                plugin.sync(() -> { refreshing = false; active = Map.of(); });
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
    private PotionEffectType effect(BuffSettings.Buff buff) {
        String key = switch (buff.effect()) {
            case "SPEED" -> "speed"; case "HASTE" -> "haste"; case "WATER_BREATHING" -> "water_breathing"; default -> "";
        };
        return key.isEmpty() ? null : Registry.EFFECT.get(NamespacedKey.minecraft(key));
    }
    private void apply(Player player) {
        Guild guild = eligible(player);
        Map<PotionEffectType, BuffSettings.Buff> desired = new HashMap<>();
        long now = System.currentTimeMillis();
        if (guild != null) for (var buff : settings().buffs.values()) {
            PotionEffectType type = effect(buff);
            if (type != null && expires(guild.id(), buff.id()) > now)
                desired.merge(type, buff, (a,b) -> a.amplifier() >= b.amplifier() ? a : b);
        }
        Set<PotionEffectType> mine = owned.computeIfAbsent(player.getUniqueId(), u -> new HashSet<>());
        for (PotionEffectType type : new HashSet<>(mine)) if (!desired.containsKey(type)) remove(player, type);
        for (var entry : desired.entrySet()) {
            var current = player.getPotionEffect(entry.getKey());
            // Never overwrite an effect not issued by this service, even a weaker one.
            if (current != null && !mine.contains(entry.getKey())) continue;
            long remaining = expires(guild.id(), entry.getValue().id()) - now;
            int ticks = (int) Math.min(60, Math.max(1, remaining / 50));
            changing = true;
            try {
                if (player.addPotionEffect(new PotionEffect(entry.getKey(), ticks, entry.getValue().amplifier(), true, false, true))) mine.add(entry.getKey());
            } finally { changing = false; }
        }
        if (mine.isEmpty()) owned.remove(player.getUniqueId());
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
