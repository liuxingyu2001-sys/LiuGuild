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

import java.util.UUID;

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
    // 准备阶段锁定：交互类拦截（移动/跳跃由 WarFreezeListener 负责）
    // =====================================================================

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

    // =====================================================================
    // 工会战全程限制：禁止打开面板/菜单/容器、禁止传送、禁止外部指令
    // =====================================================================

    private static final java.util.Set<String> ALLOWED_WAR_COMMANDS = java.util.Set.of(
            "msg", "tell", "w", "whisper", "r", "reply", "me");

    /** 工会战期间禁止打开任何容器/菜单/面板（防止往末影箱/工会仓库/跨服背包转移竞技场统一装备）。 */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryOpen(org.bukkit.event.inventory.InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        if (player.hasPermission("liuguild.admin")) {
            return;
        }
        if (plugin.war().isWarRestricted(player.getUniqueId())) {
            event.setCancelled(true);
            plugin.messages().send(player, "war-menu-blocked");
        }
    }

    /** 工会战期间禁止非工会战内部发起的传送（如 /home、/spawn、/tpa、传送卷轴等）。 */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onTeleport(org.bukkit.event.player.PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("liuguild.admin")) {
            return;
        }
        UUID uuid = player.getUniqueId();
        if (!plugin.war().isWarRestricted(uuid)) {
            return;
        }
        if (plugin.war().isAllowingWarTeleport(uuid)) {
            return;
        }
        org.bukkit.event.player.PlayerTeleportEvent.TeleportCause cause = event.getCause();
        // 战斗期间允许在同一个竞技场世界内使用末影珍珠/紫颂果/未知内部位移
        org.bukkit.Location from = event.getFrom();
        org.bukkit.Location to = event.getTo();
        boolean sameWorld = to != null && from.getWorld() != null && from.getWorld().equals(to.getWorld());
        if (sameWorld && !plugin.war().isFrozen(uuid)
                && (cause == org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.ENDER_PEARL
                || cause == org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.CHORUS_FRUIT
                || cause == org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.UNKNOWN)) {
            return;
        }
        event.setCancelled(true);
        plugin.messages().send(player, "war-teleport-blocked");
    }

    /** 工会战期间拦截菜单/面板/传送指令（仅放行查看战况与聊天）。 */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommandPreprocess(org.bukkit.event.player.PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("liuguild.admin")) {
            return;
        }
        if (!plugin.war().isWarRestricted(player.getUniqueId())) {
            return;
        }
        String raw = event.getMessage();
        if (raw == null || raw.isBlank()) {
            return;
        }
        String trimmed = raw.startsWith("/") ? raw.substring(1).trim() : raw.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        String[] parts = trimmed.split("\\s+");
        String label = parts[0].toLowerCase(java.util.Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0 && colon + 1 < label.length()) {
            label = label.substring(colon + 1);
        }
        if (isAllowedWarCommand(label, parts)) {
            return;
        }
        event.setCancelled(true);
        plugin.messages().send(player, "war-command-blocked");
    }

    /** 判断工会战期间是否放行该指令。 */
    public static boolean isAllowedWarCommand(String label, String[] parts) {
        if (label == null || label.isEmpty()) {
            return false;
        }
        if (ALLOWED_WAR_COMMANDS.contains(label)) {
            return true;
        }
        if (label.equals("guild") || label.equals("g") || label.equals("gh") || label.equals("gonghui")) {
            if (parts.length < 2) {
                return false; // 裸 /guild 打开主菜单，禁止
            }
            String sub = parts[1].toLowerCase(java.util.Locale.ROOT);
            if (sub.equals("chat") || sub.equals("c") || sub.equals("help") || sub.equals("info")) {
                return true;
            }
            if (sub.equals("war")) {
                if (parts.length < 3) {
                    return true; // /guild war 默认看 status
                }
                String action = parts[2].toLowerCase(java.util.Locale.ROOT);
                return action.equals("status") || action.equals("history") || action.equals("leave");
            }
            return false;
        }
        return false;
    }
}
