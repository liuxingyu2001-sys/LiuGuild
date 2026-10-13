package com.liu.liuguild.hook;

import com.liu.liudungeon.LiuDungeonPlugin;
import com.liu.liudungeon.api.CreateOptions;
import com.liu.liudungeon.api.CreateOptions.ChunkPosition;
import com.liu.liudungeon.api.WorldHandle;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * liudungeon 外部世界钩子。只有插件存在且启用时才实例化本类，
 * 因此缺少 liudungeon 时不会触发类加载错误（软依赖标准写法）。
 */
public final class DungeonHook {

    private final Plugin owner;
    private final LiuDungeonPlugin dungeon;

    public DungeonHook(Plugin owner) {
        this.owner = owner;
        this.dungeon = LiuDungeonPlugin.get();
    }

    public boolean available() {
        return dungeon != null && dungeon.isEnabled();
    }

    public boolean hasTemplate(String template) {
        try {
            return dungeon != null && template != null && !template.isBlank()
                    && dungeon.templates().hasTemplate(template);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 由模板创建外部竞技场世界（异步；区块预热成功后交付）。 */
    public CompletableFuture<ArenaWorld> create(String template, Set<ChunkPosition> chunks, Duration timeout) {
        CompletableFuture<ArenaWorld> out = new CompletableFuture<>();
        try {
            CreateOptions options = new CreateOptions(chunks, timeout);
            dungeon.worlds().createExternalWorld(owner, template, options).whenComplete((handle, error) -> {
                if (error != null) {
                    out.completeExceptionally(error);
                    return;
                }
                Bukkit.getScheduler().runTask(owner, () -> {
                    WorldHandle.State state = handle.state();
                    if (state != WorldHandle.State.READY || handle.world() == null) {
                        out.completeExceptionally(new IllegalStateException("竞技场世界未就绪: " + state));
                        return;
                    }
                    out.complete(new ArenaWorld(handle.worldName(), handle.world(), handle, true));
                });
            });
        } catch (Throwable t) {
            out.completeExceptionally(t);
        }
        return out;
    }

    /** 销毁外部竞技场世界。 */
    public void destroy(ArenaWorld arena) {
        if (arena == null || !arena.external() || !(arena.handle() instanceof WorldHandle handle)) {
            return;
        }
        try {
            dungeon.worlds().destroyExternalWorld(owner, handle).whenComplete((unused, error) -> {
                if (error != null) {
                    owner.getLogger().warning("竞技场世界回收未完成: " + error.getMessage());
                }
            });
        } catch (Throwable t) {
            owner.getLogger().warning("竞技场世界回收异常: " + t.getMessage());
        }
    }
}
