package com.liu.liuguild.guild;

import com.liu.liuguild.LiuGuild;
import com.liu.liuguild.api.event.GuildDisbandEvent;
import com.liu.liuguild.config.GuildSettings;
import com.liu.liuguild.model.Guild;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 僵尸工会清理与跨服在线统计。
 * <p>扫描涉及 Redis 探活，必须在异步线程调用；删除回主线程执行。</p>
 */
final class GuildCleanup {

    private final LiuGuild plugin;
    private final GuildManager guilds;

    GuildCleanup(LiuGuild plugin, GuildManager guilds) {
        this.plugin = plugin;
        this.guilds = guilds;
    }

    private GuildSettings conf() {
        return plugin.settings().guild;
    }

    /** 找出当前符合“不活跃”判定、可被清理的工会（需在异步线程调用：会访问 Redis）。 */
    List<Guild> findInactiveGuilds() {
        GuildSettings c = conf();
        long now = System.currentTimeMillis();
        List<Guild> out = new ArrayList<>();
        for (Guild g : guilds.all()) {
            // 必须同时考虑其它子服：只要跨服有任一成员在线，就不能算不活跃。
            int online = anyMemberOnline(g) ? 1 : 0;
            if (GuildSettings.isInactive(now, g.lastActiveAt(), c.cleanupInactiveDays,
                    g.memberCount(), c.cleanupMaxMembers, online)) {
                out.add(g);
            }
        }
        return out;
    }

    /** 异步扫描不活跃工会；完成回调在主线程。 */
    void findInactiveGuildsAsync(Consumer<List<Guild>> callback) {
        plugin.async(() -> {
            List<Guild> targets = findInactiveGuilds();
            plugin.sync(() -> callback.accept(targets));
        });
    }

    /** 本服或其它子服是否有成员在线（跨服检查仅在 Redis 可用时进行）。 */
    boolean anyMemberOnline(Guild g) {
        return countNetworkOnline(g) > 0;
    }

    /** 全服在线成员数（本服 + 其它子服，去重）。跨服检查会访问 Redis，请在异步线程调用。 */
    int countNetworkOnline(Guild g) {
        if (g == null) {
            return 0;
        }
        int count = guilds.onlineMemberCount(g);
        if (plugin.bus() != null && plugin.bus().isReady()) {
            // 只查本服不在线的成员，且用一次 MGET 批量取，避免 N 次串行 Redis 往返。
            List<UUID> offline = new ArrayList<>();
            for (UUID uuid : g.memberMap().keySet()) {
                if (plugin.player(uuid) == null) {
                    offline.add(uuid);
                }
            }
            int remote = plugin.bus().onlineCount(offline);
            if (remote > 0) {
                count += remote;
            }
        }
        return count;
    }

    /**
     * 历史数据保留窗口（战报 1 年、已发放奖励 90 天、邮件/申请/审计定期清理）。
     * 即使关闭“清理不活跃工会”，也必须周期性执行，否则这些表会无限增长。
     */
    void purgeHistory() {
        long nowMs = System.currentTimeMillis();
        try {
            plugin.repo().purgeWarsBefore(nowMs - 365L * 24L * 3600L * 1000L);
            plugin.repo().purgeWarRewardsBefore(nowMs - 90L * 24L * 3600L * 1000L);
            plugin.repo().purgeWarOriginsBefore(nowMs - 7L * 24L * 3600L * 1000L);
            if (plugin.audit() != null) {
                plugin.audit().purgeAsync(nowMs - 180L * 24L * 3600L * 1000L);
            }
            if (plugin.war() != null) {
                plugin.war().purgeStatsAsync(nowMs - 365L * 24L * 3600L * 1000L);
            }
            if (plugin.mail() != null) {
                plugin.mail().purgeAsync();
            }
            if (plugin.applications() != null) {
                plugin.applications().purgeAsync(nowMs - 90L * 24L * 3600L * 1000L);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("清理历史数据失败: " + e.getMessage());
        }
    }

    /** 自动清理入口（只能在主线程调用）；未启用或无命中时返回空列表。 */
    void autoCleanup() {
        GuildSettings c = conf();
        if (!c.cleanupEnabled) {
            return;
        }
        // 扫描涉及跨服探活（阻塞 Redis），放异步；删除回主线程。
        plugin.async(() -> {
            // 共享配置下多台子服会同时触发，用 Redis 抢锁保证每个周期只清理一次。
            long intervalSec = Math.max(60, c.cleanupIntervalMinutes * 60L);
            if (plugin.bus() != null && plugin.bus().isReady()
                    && !plugin.bus().acquireOnce("cleanup", intervalSec)) {
                return;
            }
            List<Guild> targets = findInactiveGuilds();
            if (targets.isEmpty()) {
                return;
            }
            plugin.sync(() -> {
                if (!c.cleanupDryRun) {
                    for (Guild g : targets) {
                        guilds.disband(g, c.cleanupBroadcast, GuildDisbandEvent.Cause.INACTIVE);
                    }
                }
                plugin.getLogger().info("不活跃工会清理（" + (c.cleanupDryRun ? "dry-run" : "已执行")
                        + "，阈值 " + c.cleanupInactiveDays + " 天）：" + targets.size() + " 个");
            });
        });
    }
}
