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
 * mcMMO 软依赖：工会战期间静默屏蔽技能/能力，保证公平竞技。
 *
 * <p>仅通过 mcMMO 公开事件生效，不修改 mcMMO 本身：</p>
 * <ul>
 *   <li>{@code McMMOPlayerAbilityActivateEvent}（可取消）→ 取消能力激活；</li>
 *   <li>{@code McMMOPlayerPreXpGainEvent}（不可取消）→ 将经验改为 0。</li>
 * </ul>
 *
 * <p>默认开启屏蔽技能，可在 config.yml 的 {@code hooks.mcmmo} 下开关。</p>
 */
public final class McMmoHook implements Listener {

    private final LiuGuild plugin;
    private boolean available;
    private Class<? extends Event> abilityEvent;
    private Class<? extends Event> preXpEvent;

    public McMmoHook(LiuGuild plugin) {
        this.plugin = plugin;
    }

    /** 初始化并登记事件；mcMMO 缺失或功能关闭时静默跳过。 */
    public void register() {
        Plugin dependency = Bukkit.getPluginManager().getPlugin("mcMMO");
        if (dependency == null || !dependency.isEnabled()) {
            return;
        }
        if (!plugin.settings().main.mcmmoEnabled) {
            return;
        }
        abilityEvent = eventClass("com.gmail.nossr50.events.skills.abilities.McMMOPlayerAbilityActivateEvent");
        preXpEvent = eventClass("com.gmail.nossr50.events.experience.McMMOPlayerPreXpGainEvent");
        if (abilityEvent == null && preXpEvent == null) {
            plugin.getLogger().warning("检测到 mcMMO，但未找到预期的技能事件；mcMMO 屏蔽未启用。");
            return;
        }
        available = true;
        if (abilityEvent != null && plugin.settings().main.mcmmoSuppressAbilities) {
            registerEvent(abilityEvent, this::suppressAbility);
        }
        if (preXpEvent != null && plugin.settings().main.mcmmoSuppressXp) {
            registerEvent(preXpEvent, this::suppressXp);
        }
        plugin.getLogger().info("mcMMO 兼容已启用：工会战期间静默屏蔽技能="
                + plugin.settings().main.mcmmoSuppressAbilities + "，经验=" + plugin.settings().main.mcmmoSuppressXp);
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

    private void suppressXp(Event event) {
        Player player = playerOf(event);
        if (player == null || !inWar(player)) {
            return;
        }
        try {
            Method setXp = event.getClass().getMethod("setXpGained", int.class);
            setXp.invoke(event, 0);
        } catch (ReflectiveOperationException ignored) {
        }
    }

    private boolean inWar(Player player) {
        return plugin.war() != null && plugin.war().isInWar(player.getUniqueId());
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
    private Class<? extends Event> eventClass(String name) {
        try {
            Class<?> type = Class.forName(name);
            if (Event.class.isAssignableFrom(type)) {
                return (Class<? extends Event>) type;
            }
        } catch (ClassNotFoundException ignored) {
        }
        return null;
    }
}
