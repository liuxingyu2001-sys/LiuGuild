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
    private final boolean available;

    public SparrowSyncHook() {
        Method api = null;
        Method save = null;
        Method restore = null;
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
                    ok = true;
                }
            }
        } catch (Throwable t) {
            ok = false;
        }
        this.apiMethod = api;
        this.saveMethod = save;
        this.restoreMethod = restore;
        this.available = ok;
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
}
