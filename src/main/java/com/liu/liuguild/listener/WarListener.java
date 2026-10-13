package com.liu.liuguild.listener;

import com.liu.liuguild.LiuGuild;
import com.liu.liuguild.module.GuildModule;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/** 工会战战斗事件：击杀计分与复活处理。 */
public final class WarListener implements GuildModule, Listener {

    private final LiuGuild plugin;

    public WarListener(LiuGuild plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "war-listener";
    }

    @Override
    public int order() {
        return 61;
    }

    @Override
    public void enable(LiuGuild plugin) {
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLethalDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim)
                || event.getFinalDamage() < victim.getHealth()) {
            return;
        }
        Player attacker = null;
        if (event instanceof EntityDamageByEntityEvent byEntity) {
            Entity damager = byEntity.getDamager();
            if (damager instanceof Player p) {
                attacker = p;
            } else if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player p) {
                attacker = p;
            }
        }
        if (plugin.war().handleLethalDamage(victim, attacker)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        if (!plugin.war().interceptDeath(event)) {
            Player killer = victim.getKiller();
            if (killer != null && !killer.getUniqueId().equals(victim.getUniqueId())) {
                plugin.war().onKill(killer, victim);
            }
            return;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        plugin.syncDelayed(() -> {
            Player player = event.getPlayer();
            if (player.isOnline()) {
                plugin.war().onRespawn(player);
            }
        }, 1L);
    }
}
