package com.liu.liuguild.hook;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * LuckyPeaches 钩子（软依赖，反射调用）：工会战期间屏蔽「蟠桃血量加成」，保证公平血量。
 * <p>API：{@code com.luckypeaches.PeachIntegrationAPI}
 * <ul>
 *   <li>{@code setPeachBonusSuppressed(Player, boolean)} — 屏蔽/恢复蟠桃 modifier（主线程）</li>
 *   <li>{@code setPlayerInBattle(Player)} / {@code setPlayerNotInBattle(Player)} — 死亡豁免标记</li>
 *   <li>{@code clearBattleStatus(UUID)} — 离线时清理</li>
 * </ul>
 * 旧版 LuckyPeaches 可能没有屏蔽 API，此时 {@link #supportsSuppress()} 返回 false，工会战将跳过公平血量。</p>
 */
public final class LuckyPeachesHook {

    private Method setSuppressed;
    private Method restoreKeepingHealth;
    private Method setInBattle;
    private Method setNotInBattle;
    private Method clearBattle;
    private Method isSuppressed;
    private Method persistentBonus;
    private boolean available;

    public LuckyPeachesHook() {
        try {
            Plugin plugin = Bukkit.getPluginManager().getPlugin("LuckyPeaches");
            if (plugin == null) {
                return;
            }
            Class<?> api = Class.forName("com.luckypeaches.PeachIntegrationAPI");
            setSuppressed = method(api, "setPeachBonusSuppressed", Player.class, boolean.class);
            restoreKeepingHealth = method(api, "restoreSuppressedBonusKeepingHealth", Player.class);
            setInBattle = method(api, "setPlayerInBattle", Player.class);
            setNotInBattle = method(api, "setPlayerNotInBattle", Player.class);
            clearBattle = method(api, "clearBattleStatus", UUID.class);
            isSuppressed = method(api, "isPeachBonusSuppressed", UUID.class);
            persistentBonus = method(api, "persistentPeachBonus", Player.class);
            available = setInBattle != null;
        } catch (Throwable ignored) {
        }
    }

    private static Method method(Class<?> owner, String name, Class<?>... types) {
        try {
            return owner.getMethod(name, types);
        } catch (Throwable t) {
            return null;
        }
    }

    public boolean available() {
        return available;
    }

    /** 是否支持血量屏蔽（旧版本可能仅支持死亡豁免）。 */
    public boolean supportsSuppress() {
        return setSuppressed != null;
    }

    /** 进入战斗：屏蔽蟠桃血量 + 标记死亡豁免（主线程）。 */
    public void enter(Player player, boolean suppressHealth) {
        if (!available || player == null) {
            return;
        }
        if (suppressHealth) {
            invoke(setSuppressed, player, true);
        }
        invoke(setInBattle, player);
    }

    /** 离开战斗：恢复蟠桃血量（保留真实当前血量）+ 清除死亡豁免（主线程）。 */
    public void exit(Player player) {
        if (!available || player == null) {
            return;
        }
        // 2.3.1+ 优先：只恢复属性、保留真实当前血量，避免把「满血」写库
        if (restoreKeepingHealth != null) {
            invoke(restoreKeepingHealth, player);
        } else if (setSuppressed != null) {
            invoke(setSuppressed, player, false);
        }
        if (player.isOnline()) {
            invoke(setNotInBattle, player);
        } else if (clearBattle != null) {
            invoke(clearBattle, player.getUniqueId());
        }
    }

    /** 离线清理（不加载数据库）。 */
    public void clear(UUID uuid) {
        if (clearBattle != null && uuid != null) {
            invoke(clearBattle, uuid);
        }
    }

    public boolean isSuppressed(UUID uuid) {
        if (isSuppressed == null || uuid == null) {
            return false;
        }
        Object r = invoke(isSuppressed, uuid);
        return r instanceof Boolean b && b;
    }

    /** 真实持久化蟠桃加成（供状态展示 / 跨服快照）。 */
    public double persistentBonus(Player player) {
        Object r = invoke(persistentBonus, player);
        return r instanceof Number n ? n.doubleValue() : 0.0;
    }

    private Object invoke(Method m, Object... args) {
        if (m == null) {
            return null;
        }
        try {
            return m.invoke(null, args);
        } catch (Throwable t) {
            return null;
        }
    }
}
