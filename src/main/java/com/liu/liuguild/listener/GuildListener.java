package com.liu.liuguild.listener;

import com.liu.liuguild.LiuGuild;
import com.liu.liuguild.model.Guild;
import com.liu.liuguild.model.GuildWar;
import com.liu.liuguild.model.WarState;
import com.liu.liuguild.module.GuildModule;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** 基础事件监听：在线状态、入服、友伤保护、工会战期间 PvP 限制。 */
public final class GuildListener implements GuildModule, Listener {

    private final LiuGuild plugin;

    public GuildListener(LiuGuild plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "listener";
    }

    @Override
    public int order() {
        return 60;
    }

    @Override
    public void enable(LiuGuild plugin) {
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.bus().presence(player.getUniqueId(), player.getName());
        plugin.syncDelayed(() -> {
            plugin.war().onPlayerJoinServer(player);
            plugin.guildChat().refresh();
            // 记录工会活跃时间（节流：距上次活跃超过 1 小时才写库）
            Guild guild = plugin.guilds().guildOf(player.getUniqueId());
            if (guild != null && System.currentTimeMillis() - guild.lastActiveAt() > 3_600_000L) {
                guild.touchActive();
                plugin.guilds().saveGuild(guild);
            }
        }, 20L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        plugin.bus().clearPresence(event.getPlayer().getUniqueId(), event.getPlayer().getName());
        plugin.guildChat().forget(event.getPlayer());
        plugin.war().onPlayerQuit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        Player attacker = resolveAttacker(event);
        if (attacker == null || attacker.getUniqueId().equals(victim.getUniqueId())) {
            return;
        }
        Guild ga = plugin.guilds().guildOf(attacker.getUniqueId());
        Guild gb = plugin.guilds().guildOf(victim.getUniqueId());

        // 同工会友伤保护
        if (ga != null && gb != null && ga.id().equals(gb.id()) && !plugin.settings().guild.friendlyFire) {
            event.setCancelled(true);
            return;
        }

        // 工会战期间：仅允许交战双方在 ACTIVE 阶段互相伤害
        if (plugin.war() != null) {
            boolean attackerInWar = plugin.war().isInWar(attacker.getUniqueId());
            boolean victimInWar = plugin.war().isInWar(victim.getUniqueId());
            if (attackerInWar || victimInWar) {
                if (!isValidWarPvp(attacker, victim, ga, gb)) {
                    event.setCancelled(true);
                }
            }
        }
    }

    private boolean isValidWarPvp(Player attacker, Player victim, Guild ga, Guild gb) {
        if (ga == null || gb == null || ga.id().equals(gb.id())) {
            return false;
        }
        GuildWar war = plugin.war().currentWar();
        if (war == null || war.state() != WarState.ACTIVE) {
            return false;
        }
        boolean sameWar = (war.guildA().equals(ga.id()) && war.guildB().equals(gb.id()))
                || (war.guildB().equals(ga.id()) && war.guildA().equals(gb.id()));
        if (!sameWar) {
            return false;
        }
        String world = war.arenaWorld();
        if (world != null && !world.isBlank()) {
            return attacker.getWorld().getName().equals(world) && victim.getWorld().getName().equals(world);
        }
        return true;
    }

    private Player resolveAttacker(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player p) {
            return p;
        }
        if (event.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player p) {
            return p;
        }
        return null;
    }
}
