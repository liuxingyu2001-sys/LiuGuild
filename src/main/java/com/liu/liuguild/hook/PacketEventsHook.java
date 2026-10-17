package com.liu.liuguild.hook;

import com.liu.liuguild.LiuGuild;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * PacketEvents 软依赖：在**网络包层**拦截参赛者在准备阶段（开战倒计时）的动作。
 *
 * <p>相比 Bukkit 事件更彻底、开销更低（无需事件总线、无副作用）。
 * <b>为绝对安全起见，这里只做「取消包」，绝不改写任何包头/字节</b>——
 * 改写包字节极易破坏协议同步（曾导致 {@code accept_teleportation} 解析失败而踢人）。</p>
 *
 * <ul>
 *   <li>挖掘 / 放置 / 使用物品 / 攻击 / 交互 / 丢物 / 快捷栏 / 载具 / 输入 —— 直接取消；</li>
 *   <li>移动包（{@code PLAYER_POSITION*} / {@code PLAYER_ROTATION} / {@code PLAYER_FLYING}）——
 *       同样取消；客户端会因服务端不确认而自动回弹到上次服务端确认的位置，
 *       再由 {@code WarManager} 的 Bukkit {@code PlayerMoveEvent} 兜底锁死坐标与朝向。</li>
 * </ul>
 *
 * <p>未安装 PacketEvents 时 {@link #available()} 返回 false，调用方完全依赖 Bukkit 事件拦截。</p>
 */
public final class PacketEventsHook {

    /** 准备阶段需要拦截的客户端包（只取消，不改写）。 */
    private static final Set<PacketType.Play.Client> BLOCKED = EnumSet.of(
            // 移动 / 视角：取消后客户端会回弹，坐标由 Bukkit 侧锁死
            PacketType.Play.Client.PLAYER_POSITION,
            PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION,
            PacketType.Play.Client.PLAYER_ROTATION,
            PacketType.Play.Client.PLAYER_FLYING,
            // 交互类
            PacketType.Play.Client.PLAYER_DIGGING,
            PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT,
            PacketType.Play.Client.USE_ITEM,
            PacketType.Play.Client.INTERACT_ENTITY,
            PacketType.Play.Client.ANIMATION,
            PacketType.Play.Client.ENTITY_ACTION,
            // 不拦截 HELD_ITEM_CHANGE：切换快捷栏不破坏准备阶段冻结，
            // 拦截会让服务端 held slot 与客户端脱节，开战后仍按空手判定攻击。
            PacketType.Play.Client.CLICK_WINDOW,
            PacketType.Play.Client.CREATIVE_INVENTORY_ACTION,
            PacketType.Play.Client.PLAYER_INPUT,
            PacketType.Play.Client.STEER_VEHICLE,
            PacketType.Play.Client.VEHICLE_MOVE,
            PacketType.Play.Client.PICK_ITEM,
            PacketType.Play.Client.EDIT_BOOK,
            PacketType.Play.Client.UPDATE_SIGN,
            PacketType.Play.Client.SPECTATE);

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
            plugin.getLogger().info("PacketEvents 已启用（工会战准备阶段仅取消包，不改写字节）。");
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
            if (!(event.getPacketType() instanceof PacketType.Play.Client type) || !BLOCKED.contains(type)) {
                return;
            }
            User user = event.getUser();
            if (user == null) {
                return;
            }
            UUID uuid = user.getUUID();
            if (uuid == null || !plugin.war().isFrozen(uuid)) {
                return;
            }
            // 只取消，绝不触碰 buffer / 不调用 write()，避免破坏协议同步
            event.setCancelled(true);
        } catch (Throwable ignored) {
            // 包处理异常绝不能影响服务器
        }
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
