package com.liu.liuguild.hook;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Sparrow Sync 钩子（软依赖，反射调用）。
 * <p>Sparrow Sync 提供玩家数据快照 API：{@code SparrowSync.api().save(uuid)} / {@code restore(uuid, snapshotId)}，
 * 用于「跨服工会战统一装备、赛后完整还原」：
 * <ol>
 *   <li>入场前 capture 一份快照，记录返回的快照 id；</li>
 *   <li>发放统一装备开打；</li>
 *   <li>赛后 restore 该快照，玩家原有数据（背包/经验/位置等）恢复如初。</li>
 * </ol>
 * 未安装时返回空结果，调用方回退到插件内置的本地快照机制。</p>
 */
public final class SparrowSyncHook {

    private final Method apiMethod;
    private final Method saveMethod;
    private final Method restoreMethod;
    private final Method pinMethod;
    private final Method unpinMethod;
    private final boolean available;

    public SparrowSyncHook() {
        Method api = null;
        Method save = null;
        Method restore = null;
        Method pin = null;
        Method unpin = null;
        boolean ok = false;
        try {
            Plugin sync = Bukkit.getPluginManager().getPlugin("SparrowSync");
            if (sync != null) {
                Class<?> pluginClass = Class.forName("net.momirealms.sparrow.sync.plugin.SparrowSync");
                api = pluginClass.getMethod("api");
                Object apiObj = api.invoke(null);
                if (apiObj != null) {
                    save = apiObj.getClass().getMethod("save", UUID.class);
                    restore = apiObj.getClass().getMethod("restore", UUID.class, UUID.class);
                    pin = apiObj.getClass().getMethod("pin", UUID.class);
                    unpin = apiObj.getClass().getMethod("unpin", UUID.class);
                    // 实现类可能是包私有，允许反射调用其公有方法
                    trySetAccessible(save);
                    trySetAccessible(restore);
                    trySetAccessible(pin);
                    trySetAccessible(unpin);
                    ok = true;
                }
            }
        } catch (Throwable t) {
            ok = false;
        }
        this.apiMethod = api;
        this.saveMethod = save;
        this.restoreMethod = restore;
        this.pinMethod = pin;
        this.unpinMethod = unpin;
        this.available = ok;
    }

    private static void trySetAccessible(Method m) {
        if (m == null) {
            return;
        }
        try {
            m.setAccessible(true);
        } catch (Throwable ignored) {
        }
    }

    /** 已安装同步插件时必须等 ACTIVE 会话，拒绝在跨服数据仍加载时托管。 */
    public boolean playerReady(UUID uuid) {
        Plugin installed = Bukkit.getPluginManager().getPlugin("SparrowSync");
        if (installed == null || !installed.isEnabled()) return true;
        try {
            Class<?> type = Class.forName("net.momirealms.sparrow.sync.plugin.SparrowSync");
            Object instance = type.getMethod("instance").invoke(null);
            Object manager = type.getMethod("sessionManager").invoke(instance);
            Object session = manager.getClass().getMethod("find", UUID.class).invoke(manager, uuid);
            return session != null && "ACTIVE".equals(String.valueOf(session.getClass().getMethod("state").invoke(session)));
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    public boolean available() {
        return available;
    }

    /** 采集玩家当前数据快照；成功返回快照 id，失败/离线返回 null。 */
    @SuppressWarnings("unchecked")
    public CompletableFuture<UUID> capture(UUID player) {
        if (!available || player == null) {
            return CompletableFuture.completedFuture(null);
        }
        try {
            Object api = apiMethod.invoke(null);
            CompletableFuture<Object> future = (CompletableFuture<Object>) saveMethod.invoke(api, player);
            return future.thenApply(result -> {
                if (result == null || !"Captured".equals(result.getClass().getSimpleName())) {
                    return null;
                }
                try {
                    Object id = result.getClass().getMethod("snapshotId").invoke(result);
                    return id instanceof UUID u ? u : null;
                } catch (Throwable t) {
                    return null;
                }
            }).exceptionally(t -> null);
        } catch (Throwable t) {
            return CompletableFuture.completedFuture(null);
        }
    }

    /** 恢复玩家数据到指定快照；返回是否成功。 */
    @SuppressWarnings("unchecked")
    public CompletableFuture<Boolean> restore(UUID player, UUID snapshotId) {
        if (!available || player == null || snapshotId == null) {
            return CompletableFuture.completedFuture(false);
        }
        try {
            Object api = apiMethod.invoke(null);
            CompletableFuture<Object> future = (CompletableFuture<Object>) restoreMethod.invoke(api, player, snapshotId);
            return future.thenApply(result -> {
                if (result == null) {
                    return false;
                }
                String name = result.getClass().getSimpleName();
                return "Restored".equals(name) || "RestoredOffline".equals(name);
            }).exceptionally(t -> false);
        } catch (Throwable t) {
            return CompletableFuture.completedFuture(false);
        }
    }

    /** 固定指定快照，豁免自动轮转清理（工会战托管快照需固定，避免被战中死亡/切世界触发的快照挤掉）。 */
    @SuppressWarnings("unchecked")
    public CompletableFuture<Boolean> pin(UUID snapshotId) {
        if (!available || snapshotId == null || pinMethod == null) {
            return CompletableFuture.completedFuture(false);
        }
        try {
            Object api = apiMethod.invoke(null);
            CompletableFuture<Object> future = (CompletableFuture<Object>) pinMethod.invoke(api, snapshotId);
            return future.thenApply(result -> {
                if (result == null) {
                    return false;
                }
                String name = result.getClass().getSimpleName();
                return "Pinned".equals(name) || "Unchanged".equals(name);
            }).exceptionally(t -> false);
        } catch (Throwable t) {
            return CompletableFuture.completedFuture(false);
        }
    }

    /** 取消固定快照，允许参与后续自动轮转清理。 */
    @SuppressWarnings("unchecked")
    public CompletableFuture<Boolean> unpin(UUID snapshotId) {
        if (!available || snapshotId == null || unpinMethod == null) {
            return CompletableFuture.completedFuture(false);
        }
        try {
            Object api = apiMethod.invoke(null);
            CompletableFuture<Object> future = (CompletableFuture<Object>) unpinMethod.invoke(api, snapshotId);
            return future.thenApply(result -> {
                if (result == null) {
                    return false;
                }
                String name = result.getClass().getSimpleName();
                return "Unpinned".equals(name) || "Unchanged".equals(name);
            }).exceptionally(t -> false);
        } catch (Throwable t) {
            return CompletableFuture.completedFuture(false);
        }
    }
}
