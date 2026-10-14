package com.liu.liuguild.listener;

import com.liu.liuguild.LiuGuild;
import com.liu.liuguild.module.GuildModule;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;

/**
 * 工会战准备阶段的位置锁定。
 *
 * <p>负责把参赛者钉在出生点上，并处理「跳跃」这个 PacketEvents 取消 {@code PLAYER_INPUT}
 * 无法可靠拦住的情况（客户端跳跃在输入状态更新前就已生效）。</p>
 *
 * <p>三层保障：</p>
 * <ol>
 *   <li>{@link PlayerMoveEvent}：无条件把目标位置与视角改回上一帧（含 Y 轴）；</li>
 *   <li>{@code WarManager} 每秒回拉：发现被锁定玩家有 Y 轴位移/速度时清零并拉回出生点；</li>
 *   <li>可选屏障地盘：出生点脚下自动垫一层 BARRIER，从物理上禁止起跳。</li>
 * </ol>
 */
public final class WarFreezeListener implements GuildModule, Listener {

    private final LiuGuild plugin;

    public WarFreezeListener(LiuGuild plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "war-freeze";
    }

    @Override
    public int order() {
        return 62;
    }

    @Override
    public void enable(LiuGuild plugin) {
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    // =====================================================================
    // 移动 / 视角锁定
    // =====================================================================

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.isAsynchronous()) {
            return;
        }
        Player player = event.getPlayer();
        if (!plugin.war().isFrozen(player.getUniqueId())) {
            return;
        }
        if (plugin.war().allowJumpOnPrepare(player.getUniqueId())) {
            // 允许跳跃：只锁水平位置与视角，保留 Y 自由
            Location from = event.getFrom();
            Location to = event.getTo();
            if (to == null) {
                return;
            }
            to.setX(from.getX());
            to.setZ(from.getZ());
            to.setYaw(from.getYaw());
            to.setPitch(from.getPitch());
            event.setTo(to);
            return;
        }
        // 完全锁定：包含 Y 轴
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        to.setX(from.getX());
        to.setY(from.getY());
        to.setZ(from.getZ());
        to.setYaw(from.getYaw());
        to.setPitch(from.getPitch());
        event.setTo(to);
    }

    /** 禁止鞘翅/飞行等离地行为。 */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onToggleFlight(PlayerToggleFlightEvent event) {
        if (plugin.war().isFrozen(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }
}
