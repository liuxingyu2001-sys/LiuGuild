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

    // =====================================================================
    // 准备阶段锁定（Bukkit 回退；装了 PacketEvents 时由包拦截更彻底）
    // =====================================================================

    /** 阻止移动：无条件锁死坐标与朝向（PacketEvents 只做额外拦截，位置权威始终在这里）。 */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMove(org.bukkit.event.player.PlayerMoveEvent event) {
        if (event.isAsynchronous()) {
            return; // 只处理主线程事件，避免跨线程改位置
        }
        if (!plugin.war().isFrozen(event.getPlayer().getUniqueId())) {
            return;
        }
        org.bukkit.Location from = event.getFrom();
        org.bukkit.Location to = event.getTo();
        if (to == null) {
            return;
        }
        // 锁死坐标与视角（含 Y 轴，确保不会因下落/卡地面而漂移）
        to.setX(from.getX());
        to.setY(from.getY());
        to.setZ(from.getZ());
        to.setYaw(from.getYaw());
        to.setPitch(from.getPitch());
        event.setTo(to);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteract(org.bukkit.event.player.PlayerInteractEvent event) {
        if (plugin.war().isFrozen(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDrop(org.bukkit.event.player.PlayerDropItemEvent event) {
        if (plugin.war().isFrozen(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreak(org.bukkit.event.block.BlockBreakEvent event) {
        if (plugin.war().isFrozen(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlace(org.bukkit.event.block.BlockPlaceEvent event) {
        if (plugin.war().isFrozen(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /** 冻结期间禁止攻击（避免倒计时偷伤害）。 */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player attacker
                && plugin.war().isFrozen(attacker.getUniqueId())) {
            event.setCancelled(true);
        }
        if (event.getEntity() instanceof Player victim
                && plugin.war().isFrozen(victim.getUniqueId())) {
            event.setCancelled(true);
        }
    }
}
