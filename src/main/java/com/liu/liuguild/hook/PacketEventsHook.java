package com.liu.liuguild.hook;

import com.liu.liuguild.LiuGuild;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;

import java.util.UUID;
import java.util.function.Predicate;

/**
 * PacketEvents 软依赖：在**网络包层**拦截玩家动作。
 *
 * <p>相比 Bukkit 事件，包级拦截更彻底、更省开销（无需事件总线、不会产生副作用）：
 * <ul>
 *   <li>移动 / 转视角包（{@code PLAYER_POSITION*} / {@code PLAYER_ROTATION} / {@code PLAYER_FLYING}）
 *       —— 把坐标与朝向改写成锁定值后放行，客户端不会抖动也无需拉回；</li>
 *   <li>挖掘 / 放置 / 交互 / 使用物品 / 攻击 / 丢物 / 快捷栏 —— 直接取消。</li>
 * </ul>
 * 未安装 PacketEvents 时 {@link #available()} 返回 false，调用方回退到 Bukkit 事件拦截。</p>
 */
public final class PacketEventsHook {

    /** 该玩家此刻是否应被锁包（由 WarManager 提供判定）。 */
    private final LiuGuild plugin;
    private boolean available;
    private PacketListenerAbstract listener;

    public PacketEventsHook(LiuGuild plugin) {
        this.plugin = plugin;
    }

    public void init() {
        try {
            if (!PacketEvents.getAPI().isInitialized()) {
                plugin.getLogger().info("PacketEvents 尚未初始化，工会战准备阶段改用 Bukkit 事件拦截。");
                return;
            }
            listener = new PacketListenerAbstract(PacketListenerPriority.HIGHEST) {
                @Override
                public void onPacketReceive(PacketReceiveEvent event) {
                    handle(event);
                }
            };
            PacketEvents.getAPI().getEventManager().registerListener(listener);
            available = true;
            plugin.getLogger().info("PacketEvents 包拦截已启用（工会战准备阶段锁定）。");
        } catch (Throwable t) {
            available = false;
            plugin.getLogger().warning("PacketEvents 不可用，回退 Bukkit 事件拦截: " + t);
        }
    }

    public boolean available() {
        return available;
    }

    private void handle(PacketReceiveEvent event) {
        try {
            User user = event.getUser();
            if (user == null) {
                return;
            }
            UUID uuid = user.getUUID();
            if (uuid == null || !plugin.war().isFrozen(uuid)) {
                return;
            }
            PacketType.Play.Client type = (PacketType.Play.Client) event.getPacketType();
            switch (type) {
                // 锁定位置与朝向：改写坐标后放行，避免客户端抖动与拉回闪烁
                case PLAYER_POSITION, PLAYER_POSITION_AND_ROTATION, PLAYER_ROTATION, PLAYER_FLYING -> {
                    if (rewriteMovement(event, uuid)) {
                        return; // 已改写为锁定值
                    }
                    event.setCancelled(true);
                }
                // 交互类一律拦截：挖掘/放置/使用/攻击/丢物/切换手持/载具
                case PLAYER_DIGGING, PLAYER_BLOCK_PLACEMENT, USE_ITEM, INTERACT_ENTITY,
                     ANIMATION, ENTITY_ACTION, HELD_ITEM_CHANGE, CLICK_WINDOW, CREATIVE_INVENTORY_ACTION,
                     PLAYER_INPUT, STEER_VEHICLE, VEHICLE_MOVE, PICK_ITEM, EDIT_BOOK, UPDATE_SIGN,
                     SPECTATE, CLIENT_STATUS -> event.setCancelled(true);
                default -> {
                }
            }
        } catch (Throwable ignored) {
            // 包处理异常绝不能影响服务器
        }
    }

    /**
     * 把移动包的位置 / 朝向改写为锁定值，返回 true 表示已成功改写。
     */
    private boolean rewriteMovement(PacketReceiveEvent event, UUID uuid) {
        org.bukkit.Location lock = plugin.war().freezeLocation(uuid);
        org.bukkit.entity.Player player = plugin.player(uuid);
        if (lock == null || player == null) {
            return false;
        }
        WrapperPlayClientPlayerFlying wrapper = new WrapperPlayClientPlayerFlying(event);
        com.github.retrooper.packetevents.protocol.world.Location fixed =
                new com.github.retrooper.packetevents.protocol.world.Location(
                        lock.getX(), lock.getY(), lock.getZ(), lock.getYaw(), lock.getPitch());
        // 位置与朝向都对齐锁定值
        wrapper.setLocation(fixed);
        wrapper.setPositionChanged(false);
        wrapper.setRotationChanged(false);
        wrapper.write();
        return true;
    }

    /** 注销监听器（插件卸载时调用）。 */
    public void close() {
        if (listener == null) {
            return;
        }
        try {
            PacketEvents.getAPI().getEventManager().unregisterListener(listener);
        } catch (Throwable ignored) {
            // 忽略
        }
        listener = null;
        available = false;
    }
}
