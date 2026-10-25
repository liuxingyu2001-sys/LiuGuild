package com.liu.liuguild.hook;

import com.liu.liuguild.LiuGuild;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;

/**
 * mcMMO 软依赖：工会战期间完全屏蔽技能/能力与全部提示，保证公平竞技。
 *
 * <p>仅通过 mcMMO 公开事件生效，不修改 mcMMO 本身：</p>
 * <ul>
 *   <li>{@code McMMOPlayerAbilityActivateEvent}（可取消）→ 取消能力激活；</li>
 *   <li>{@code McMMOPlayerNotificationEvent}（可取消）→ 取消聊天/动作栏等全部提示。</li>
 * </ul>
 *
 * <p>只有一个开关：{@code hooks.mcmmo.block-in-war}，默认开启。</p>
 */
public final class McMmoHook implements Listener {

    private final LiuGuild plugin;
    private boolean available;
    private Class<? extends Event> abilityEvent;
    private Class<? extends Event> notificationEvent;

    public McMmoHook(LiuGuild plugin) {
        this.plugin = plugin;
    }

    /** 初始化并登记事件；mcMMO 缺失或功能关闭时静默跳过。 */
    public void register() {
        Plugin dependency = Bukkit.getPluginManager().getPlugin("mcMMO");
        if (dependency == null || !dependency.isEnabled()) {
            return;
        }
        if (!plugin.settings().main.mcmmoBlockInWar) {
            return;
        }
        abilityEvent = eventClass(dependency,
                "com.gmail.nossr50.events.skills.abilities.McMMOPlayerAbilityActivateEvent");
        notificationEvent = eventClass(dependency,
                "com.gmail.nossr50.events.skills.McMMOPlayerNotificationEvent");
        if (abilityEvent == null && notificationEvent == null) {
            plugin.getLogger().warning("检测到 mcMMO，但未找到预期的技能事件；mcMMO 屏蔽未启用。");
            return;
        }
        available = true;
        if (abilityEvent != null) {
            registerEvent(abilityEvent, this::suppressAbility);
        }
        if (notificationEvent != null) {
            registerEvent(notificationEvent, this::suppressNotification);
        }
        plugin.getLogger().info("mcMMO 兼容已启用：工会战期间完全屏蔽技能与全部提示");
    }

    public boolean available() {
        return available;
    }

    private void registerEvent(Class<? extends Event> type, java.util.function.Consumer<Event> handler) {
        Bukkit.getPluginManager().registerEvent(type, this, EventPriority.HIGHEST,
                (listener, event) -> handler.accept(event), plugin, true);
    }

    private void suppressAbility(Event event) {
        Player player = playerOf(event);
        if (player == null || !inWar(player)) {
            return;
        }
        try {
            Method cancel = event.getClass().getMethod("setCancelled", boolean.class);
            cancel.invoke(event, true);
        } catch (ReflectiveOperationException ignored) {
            // 事件不可取消时忽略，避免影响主流程
        }
    }

    /** 屏蔽 mcMMO 的全部聊天/动作栏提示，避免战斗中暴露技能触发信息。 */
    private void suppressNotification(Event event) {
        Player player = playerOf(event);
        if (player == null || !inWar(player)) {
            return;
        }
        try {
            Method cancel = event.getClass().getMethod("setCancelled", boolean.class);
            cancel.invoke(event, true);
        } catch (ReflectiveOperationException ignored) {
        }
    }

    private boolean inWar(Player player) {
        return plugin.war() != null && plugin.war().isWarRestricted(player.getUniqueId());
    }

    private Player playerOf(Event event) {
        try {
            Method getPlayer = event.getClass().getMethod("getPlayer");
            Object value = getPlayer.invoke(event);
            return value instanceof Player p ? p : null;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Class<? extends Event> eventClass(Plugin dependency, String name) {
        try {
            Class<?> type = Class.forName(name, false, dependency.getClass().getClassLoader());
            if (Event.class.isAssignableFrom(type)) {
                return (Class<? extends Event>) type;
            }
        } catch (ClassNotFoundException | LinkageError ignored) {
        }
        return null;
    }
}
