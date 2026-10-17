package com.liu.liuguild.guild;

import com.liu.liuguild.LiuGuild;
import com.liu.liuguild.api.event.GuildBankEvent;
import com.liu.liuguild.api.event.GuildCheckinEvent;
import com.liu.liuguild.api.event.GuildCreateEvent;
import com.liu.liuguild.api.event.GuildDisbandEvent;
import com.liu.liuguild.api.event.GuildJoinEvent;
import com.liu.liuguild.api.event.GuildLeaveEvent;
import com.liu.liuguild.api.event.GuildLevelUpEvent;
import com.liu.liuguild.config.GuildSettings;
import com.liu.liuguild.config.IconDef;
import com.liu.liuguild.crossserver.CrossServerBus;
import com.liu.liuguild.model.Guild;
import com.liu.liuguild.model.GuildMember;
import com.liu.liuguild.model.GuildRank;
import com.liu.liuguild.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工会核心管理器：内存缓存 + 异步持久化 + 跨服失效广播。
 * <p>缓存是「全量」的：启动时载入全部工会与成员，成员关系查询零 IO，
 * 可在主线程安全调用；写操作立即改内存、异步落库，并通过 Redis 通知其它子服刷新。</p>
 */
public final class GuildManager {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final LiuGuild plugin;
    // 缓存引用可整体原子替换，避免重载期间出现短暂的“无工会”状态。
    private volatile Map<String, Guild> guilds = new ConcurrentHashMap<>();
    private volatile Map<UUID, String> memberIndex = new ConcurrentHashMap<>();
    private final Map<String, String> nameIndex = new ConcurrentHashMap<>();
    private final Map<String, String> tagIndex = new ConcurrentHashMap<>();
    /** 正在创建中的名称/标签预留（归一化），避免并发创建同名。 */
    private final Set<String> pendingNames = ConcurrentHashMap.newKeySet();
    private final Set<String> pendingTags = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Invite> invites = new ConcurrentHashMap<>();
    private final Map<UUID, Long> inviteCooldown = new ConcurrentHashMap<>();
    /** 本地写版本；异步全量重载发现版本变化时丢弃旧快照重试，避免覆盖刚发生的变更。 */
    private final java.util.concurrent.atomic.AtomicLong mutationVersion = new java.util.concurrent.atomic.AtomicLong();

    public GuildManager(LiuGuild plugin) {
        this.plugin = plugin;
    }

    private GuildSettings conf() {
        return plugin.settings().guild;
    }

    private static final class Invite {
        final String guildId;
        final long expiresAt;

        Invite(String guildId, long expiresAt) {
            this.guildId = guildId;
            this.expiresAt = expiresAt;
        }
    }

    // =====================================================================
    // 载入 / 查询
    // =====================================================================

    /** 异步全量重载（启动与周期刷新）。 */
    public void reloadAll() {
        reloadAll(0);
    }

    private void reloadAll(int attempt) {
        long startVersion = mutationVersion.get();
        plugin.async(() -> {
            try {
                List<Guild> loaded = plugin.repo().loadGuilds();
                List<GuildMember> members = plugin.repo().loadMembers();
                Map<String, Guild> fresh = new ConcurrentHashMap<>();
                Map<UUID, String> idx = new ConcurrentHashMap<>();
                for (Guild g : loaded) {
                    fresh.put(g.id(), g);
                }
                for (GuildMember m : members) {
                    Guild g = fresh.get(m.guildId());
                    if (g == null) {
                        continue;
                    }
                    g.putMember(m);
                    idx.put(m.uuid(), m.guildId());
                }
                if (mutationVersion.get() != startVersion && attempt < 3) {
                    plugin.sync(() -> reloadAll(attempt + 1));
                    return;
                }
                guilds = fresh;
                memberIndex = idx;
                rebuildIndexes();
                plugin.sync(() -> { if (plugin.guildChat() != null) plugin.guildChat().refresh(); });
            } catch (Exception e) {
                plugin.getLogger().warning("载入工会数据失败: " + e.getMessage());
            }
        });
    }

    private void rebuildIndexes() {
        nameIndex.clear();
        tagIndex.clear();
        for (Guild g : guilds.values()) {
            nameIndex.put(g.name().toLowerCase(Locale.ROOT), g.id());
            tagIndex.put(g.tag().toLowerCase(Locale.ROOT), g.id());
        }
    }

    public Guild guild(String id) {
        return id == null ? null : guilds.get(id);
    }

    public Guild guildByName(String name) {
        if (name == null) {
            return null;
        }
        String id = nameIndex.get(name.toLowerCase(Locale.ROOT));
        if (id != null) {
            return guilds.get(id);
        }
        // 兜底：允许用 id 直接查
        return guilds.get(name);
    }

    public Guild guildOf(UUID uuid) {
        if (uuid == null) {
            return null;
        }
        String id = memberIndex.get(uuid);
        return id == null ? null : guilds.get(id);
    }

    public boolean isInGuild(UUID uuid) {
        return memberIndex.containsKey(uuid);
    }

    public Collection<Guild> all() {
        return guilds.values();
    }

    public int guildCount() {
        return guilds.size();
    }

    public List<Guild> top(String column, int limit) {
        List<Guild> list = topSorted(column);
        return list.subList(0, Math.min(Math.max(0, limit), list.size()));
    }

    /** 按列缓存的全量排序列表；排行榜占位符会被高频调用，避免每次重新排序。 */
    private static final long TOP_CACHE_MS = 3000L;
    private final Map<String, Object[]> topCache = new ConcurrentHashMap<>();

    private List<Guild> topSorted(String column) {
        String key = column == null ? "points" : column;
        long now = System.currentTimeMillis();
        Object[] cached = topCache.get(key);
        if (cached != null && now - (Long) cached[1] < TOP_CACHE_MS) {
            @SuppressWarnings("unchecked")
            List<Guild> hit = (List<Guild>) cached[0];
            return hit;
        }
        List<Guild> list = new ArrayList<>(guilds.values());
        switch (key) {
            case "level" -> list.sort((a, b) -> Integer.compare(b.level(), a.level()));
            case "wins" -> list.sort((a, b) -> Integer.compare(b.wins(), a.wins()));
            case "losses" -> list.sort((a, b) -> Integer.compare(b.losses(), a.losses()));
            case "bank" -> list.sort((a, b) -> Long.compare(b.bank(), a.bank()));
            case "members" -> list.sort((a, b) -> Integer.compare(b.memberCount(), a.memberCount()));
            default -> list.sort((a, b) -> Long.compare(b.points(), a.points()));
        }
        topCache.put(key, new Object[]{list, now});
        return list;
    }

    /** 失效某工会缓存：从内存移除，下次访问前由异步 reloadGuild 补回。 */
    public void invalidate(String guildId) {
        Guild g = guilds.remove(guildId);
        if (g != null) {
            for (UUID u : g.memberMap().keySet()) {
                memberIndex.remove(u);
            }
        }
        reloadGuild(guildId);
    }

    /** 只刷新某成员关联的工会，避免 MEMBER_CHANGED 空 guild 时全量 reloadAll。 */
    public void reloadMember(UUID uuid) {
        if (uuid == null) return;
        plugin.async(() -> {
            try {
                String guildId = plugin.repo().guildIdOf(uuid);
                if (guildId == null) {
                    Guild old = guildOf(uuid);
                    if (old != null) guildId = old.id();
                }
                if (guildId != null) reloadGuild(guildId);
            } catch (Exception e) {
                plugin.getLogger().warning("刷新成员所属工会失败(" + uuid + "): " + e.getMessage());
            }
        });
    }

    public void reloadGuild(String guildId) {
        if (guildId == null) {
            return;
        }
        plugin.async(() -> {
            try {
                Guild g = plugin.repo().loadGuild(guildId);
                if (g == null) {
                    Guild old = guilds.remove(guildId);
                    if (old != null) {
                        for (UUID uuid : old.memberMap().keySet()) {
                            memberIndex.remove(uuid, guildId);
                        }
                    }
                    rebuildIndexes();
                    return;
                }
                for (GuildMember m : plugin.repo().loadMembers(guildId)) {
                    g.putMember(m);
                }
                // 先构建完整的新对象，再原子替换，过程中不会出现“查不到工会”。
                Guild old = guilds.put(guildId, g);
                if (old != null) {
                    for (UUID uuid : old.memberMap().keySet()) {
                        if (!g.hasMember(uuid)) {
                            memberIndex.remove(uuid, guildId);
                        }
                    }
                }
                for (GuildMember m : g.members()) {
                    memberIndex.put(m.uuid(), guildId);
                }
                rebuildIndexes();
                plugin.sync(() -> { if (plugin.guildChat() != null) plugin.guildChat().refresh(); });
            } catch (Exception e) {
                plugin.getLogger().warning("刷新工会 " + guildId + " 失败: " + e.getMessage());
            }
        });
    }

    // =====================================================================
    // 创建 / 解散
    // =====================================================================

    public boolean create(Player player, String name, String tag) {
        if (!player.hasPermission("liuguild.create")) {
            plugin.messages().send(player, "no-permission");
            return false;
        }
        GuildSettings c = conf();
        String cleanName = Text.clean(name);
        String cleanTag = Text.clean(tag);
        if (Text.hasFormatting(name) || Text.hasFormatting(tag)
                || cleanName.length() < c.nameMin || cleanName.length() > c.nameMax) {
            plugin.messages().send(player, "create-name-invalid", "min", c.nameMin, "max", c.nameMax);
            return false;
        }
        if (cleanTag.length() < c.tagMin || cleanTag.length() > c.tagMax) {
            plugin.messages().send(player, "create-tag-invalid", "min", c.tagMin, "max", c.tagMax);
            return false;
        }
        if (isInGuild(player.getUniqueId())) {
            Guild cur = guildOf(player.getUniqueId());
            plugin.messages().send(player, "already-in-guild", "guild", cur == null ? "" : cur.name());
            return false;
        }
        String normName = cleanName.toLowerCase(Locale.ROOT);
        String normTag = cleanTag.toLowerCase(Locale.ROOT);
        if (nameIndex.containsKey(normName) || !pendingNames.add(normName)) {
            plugin.messages().send(player, "create-name-taken", "name", cleanName);
            return false;
        }
        if (tagIndex.containsKey(normTag) || !pendingTags.add(normTag)) {
            pendingNames.remove(normName);
            plugin.messages().send(player, "create-tag-taken", "tag", cleanTag);
            return false;
        }
        if (c.createCost > 0 && !player.hasPermission("liuguild.create.free")
                && !plugin.economy().has(player, c.createCost)) {
            pendingNames.remove(normName);
            pendingTags.remove(normTag);
            plugin.messages().send(player, "create-cost-missing", "cost", plugin.economy().format(c.createCost));
            return false;
        }

        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        Guild g = new Guild(id, cleanName, cleanTag, player.getUniqueId(), player.getName(),
                "", "", false, now, now, 0, 1, 0, 0, 0, plugin.serverId(), 1,
                conf().defaultIcon);
        g.memberPvp(c.friendlyFire);
        GuildMember leader = new GuildMember(player.getUniqueId(), player.getName(), id,
                GuildRank.LEADER, now, 0, 0, 0);
        g.putMember(leader);
        boolean charged = c.createCost <= 0 || player.hasPermission("liuguild.create.free")
                || plugin.economy().withdraw(player, c.createCost);
        if (!charged) {
            pendingNames.remove(normName);
            pendingTags.remove(normTag);
            plugin.messages().send(player, "create-cost-missing", "cost", plugin.economy().format(c.createCost));
            return false;
        }

        mutationVersion.incrementAndGet();
        guilds.put(id, g);
        memberIndex.put(player.getUniqueId(), id);
        rebuildIndexes();
        plugin.async(() -> {
            try {
                if (plugin.repo().nameOrTagTaken(cleanName, cleanTag, id)) {
                    rollbackCreate(player, g, id, c, "create-name-taken");
                    return;
                }
                plugin.repo().insertGuildWithLeader(g, leader);
                plugin.broadcastGuildChange(id);
                plugin.sync(() -> {
                    pendingNames.remove(normName);
                    pendingTags.remove(normTag);
                    plugin.messages().send(player, "create-success", "guild", cleanName);
                    Bukkit.getPluginManager().callEvent(new GuildCreateEvent(g, player));
                });
            } catch (Exception e) {
                if (com.liu.liuguild.db.GuildRepository.isDuplicateKey(e)) {
                    plugin.getLogger().info("创建工会名称/标签冲突（并发）: " + cleanName);
                    rollbackCreate(player, g, id, c, "create-name-taken");
                } else {
                    plugin.getLogger().warning("创建工会写库失败: " + e.getMessage());
                    rollbackCreate(player, g, id, c, "create-failed");
                }
            }
        });
        return true;
    }

    private void rollbackCreate(Player player, Guild guild, String id, GuildSettings settings, String message) {
        plugin.sync(() -> {
            pendingNames.remove(guild.name().toLowerCase(Locale.ROOT));
            pendingTags.remove(guild.tag().toLowerCase(Locale.ROOT));
            guilds.remove(id, guild);
            memberIndex.remove(player.getUniqueId(), id);
            rebuildIndexes();
            if (settings.createCost > 0 && !player.hasPermission("liuguild.create.free")
                    && plugin.economy().available()
                    && !plugin.economy().deposit(player, settings.createCost)) {
                plugin.getLogger().severe("工会创建回滚退款失败: 玩家=" + player.getUniqueId() + " guild=" + id);
            }
            plugin.messages().send(player, message, "name", guild.name());
        });
    }

    // =====================================================================
    // 僵尸工会清理
    // =====================================================================

    /** 找出当前符合“不活跃”判定、可被清理的工会（需在主线程调用）。 */
    public List<Guild> findInactiveGuilds() {
        GuildSettings c = conf();
        long now = System.currentTimeMillis();
        List<Guild> out = new ArrayList<>();
        for (Guild g : guilds.values()) {
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
    public void findInactiveGuildsAsync(java.util.function.Consumer<List<Guild>> callback) {
        plugin.async(() -> {
            List<Guild> targets = findInactiveGuilds();
            plugin.sync(() -> callback.accept(targets));
        });
    }

    /** 关服前同步刷写全部内存数据（主线程调用，尽力而为）。 */
    public void saveAllSync() {
        List<Guild> gs = new ArrayList<>(guilds.values());
        List<GuildMember> ms = new ArrayList<>();
        for (Guild g : gs) {
            ms.addAll(g.memberMap().values());
        }
        try {
            plugin.repo().flushAll(gs, ms);
            plugin.getLogger().info("关服前已刷写 " + gs.size() + " 个工会 / " + ms.size() + " 名成员。");
        } catch (Exception e) {
            plugin.getLogger().warning("关服前刷写工会数据失败: " + e.getMessage());
        }
    }

    /**
     * 校验内存数据一致性：member_count、会长成员关系、双向成员索引。
     *
     * @param fix true = 修正发现的问题
     * @return 问题描述列表（空 = 一致）
     */
    public List<String> verifyData(boolean fix) {
        List<String> issues = new ArrayList<>();
        for (Guild g : guilds.values()) {
            int actual = g.memberMap().size();
            if (g.memberCount() != actual) {
                issues.add("工会 " + g.name() + " member_count=" + g.memberCount() + " 实际=" + actual);
                if (fix) {
                    g.memberCount(actual);
                    saveGuild(g);
                }
            }
            if (g.leaderUuid() != null && !g.memberMap().containsKey(g.leaderUuid())) {
                issues.add("工会 " + g.name() + " 会长不在成员列表: " + g.leaderUuid());
            }
            for (UUID u : g.memberMap().keySet()) {
                if (!g.id().equals(memberIndex.get(u))) {
                    issues.add("成员未入索引: " + u + " @ " + g.name());
                    if (fix) {
                        memberIndex.put(u, g.id());
                    }
                }
            }
        }
        for (Map.Entry<UUID, String> e : memberIndex.entrySet()) {
            Guild g = guilds.get(e.getValue());
            if (g == null || !g.memberMap().containsKey(e.getKey())) {
                issues.add("孤立成员索引: " + e.getKey() + " -> " + e.getValue());
                if (fix) {
                    memberIndex.remove(e.getKey());
                }
            }
        }
        if (fix) {
            rebuildIndexes();
        }
        return issues;
    }

    /** 本服或其它子服是否有成员在线（跨服检查仅在 Redis 可用时进行）。 */
    public boolean anyMemberOnline(Guild g) {
        return countNetworkOnline(g) > 0;
    }

    /** 全服在线成员数（本服 + 其它子服，去重）。跨服检查会访问 Redis，请在异步线程调用。 */
    public int countNetworkOnline(Guild g) {
        if (g == null) {
            return 0;
        }
        int count = onlineMembers(g).size();
        if (plugin.bus() != null && plugin.bus().isReady()) {
            for (UUID uuid : g.memberMap().keySet()) {
                if (plugin.player(uuid) != null) {
                    continue;
                }
                if (plugin.bus().serverOf(uuid) != null) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * 清理不活跃工会。
     *
     * @param dryRun true 时只返回命中列表，不删除
     * @return 命中（或已删除）的工会列表
     */
    public List<Guild> purgeInactive(boolean dryRun) {
        List<Guild> targets = findInactiveGuilds();
        if (dryRun) {
            return targets;
        }
        for (Guild g : targets) {
            disband(g, conf().cleanupBroadcast, GuildDisbandEvent.Cause.INACTIVE);
        }
        return targets;
    }

    /** 自动清理入口（只能在主线程调用）；未启用或无命中时返回空列表。 */
    public void autoCleanup() {
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
                        disband(g, c.cleanupBroadcast, GuildDisbandEvent.Cause.INACTIVE);
                    }
                }
                plugin.getLogger().info("不活跃工会清理（" + (c.cleanupDryRun ? "dry-run" : "已执行")
                        + "，阈值 " + c.cleanupInactiveDays + " 天）：" + targets.size() + " 个");
            });
        });
    }

    public void disband(Guild g, boolean broadcast) {
        disband(g, broadcast, GuildDisbandEvent.Cause.PLAYER);
    }

    public boolean disband(Guild g, boolean broadcast, GuildDisbandEvent.Cause cause) {
        if (g == null) {
            return false;
        }
        if (broadcast) announceGuild(g.id(), plugin.messages().component("disband-broadcast", "guild", g.name()), null);
        mutationVersion.incrementAndGet();
        guilds.remove(g.id());
        for (UUID u : g.memberMap().keySet()) {
            memberIndex.remove(u);
        }
        rebuildIndexes();
        plugin.async(() -> {
            try {
                plugin.repo().deleteGuild(g.id(), plugin.settings().warehouse.disbandRetentionDays);
                plugin.broadcastGuildChange(g.id());
            } catch (Exception e) {
                plugin.getLogger().warning("解散工会写库失败: " + e.getMessage());
            }
        });
        if (plugin.guildChat() != null) plugin.guildChat().refresh();
        if (plugin.war() != null) plugin.war().onGuildDisbanded(g.id());
        // 释放仓库锁并清空仓库数据，避免锁残留导致同名新工会永远打不开
        if (plugin.warehouse() != null) {
            for (UUID u : g.memberMap().keySet()) {
                plugin.warehouse().kick(u);
            }
            plugin.warehouse().lock().release(g.id(), null);

        }
        // 清空该工会的头衔定义
        if (plugin.titles() != null) {
            plugin.titles().onGuildDisbanded(g.id());
        }
        // 清空该工会的商店上架
        if (plugin.shop() != null) {
            plugin.shop().onGuildDisbanded(g.id());
        }
        Bukkit.getPluginManager().callEvent(new GuildDisbandEvent(g, cause));
        return true;
    }

    // =====================================================================
    // 加入 / 邀请
    // =====================================================================

    public boolean join(Player player, Guild g, boolean viaInvite) {
        if (g == null) {
            plugin.messages().send(player, "guild-not-found", "name", "");
            return false;
        }
        if (isInGuild(player.getUniqueId())) {
            Guild cur = guildOf(player.getUniqueId());
            plugin.messages().send(player, "already-in-guild", "guild", cur == null ? "" : cur.name());
            return false;
        }
        if (!viaInvite && !g.open()) {
            plugin.messages().send(player, "join-closed", "guild", g.name());
            return false;
        }
        if (g.memberCount() >= conf().memberLimit(g.level())) {
            plugin.messages().send(player, "join-full", "guild", g.name());
            return false;
        }

        long now = System.currentTimeMillis();
        mutationVersion.incrementAndGet();
        GuildMember m = new GuildMember(player.getUniqueId(), player.getName(), g.id(), GuildRank.MEMBER, now, 0, 0, 0);
        g.putMember(m);
        memberIndex.put(player.getUniqueId(), g.id());
        invites.remove(player.getUniqueId());

        plugin.async(() -> {
            try {
                if (!plugin.repo().insertMemberAndIncrement(m, conf().memberLimit(g.level()))) {
                    rollbackJoin(player, g, "already-in-guild");
                    return;
                }
                plugin.broadcastMemberChange(player.getUniqueId(), g.id());
                plugin.broadcastGuildChange(g.id());
                plugin.sync(() -> {
                    plugin.messages().send(player, viaInvite ? "accept-success" : "join-success", "guild", g.name());
                    announceGuild(g.id(), plugin.messages().component(viaInvite ? "accept-notify-members" : "join-notify-members", "player", player.getName()), player.getUniqueId());
                    Bukkit.getPluginManager().callEvent(new GuildJoinEvent(g, player, viaInvite));
                });
            } catch (Exception e) {
                plugin.getLogger().warning("加入工会写库失败: " + e.getMessage());
                rollbackJoin(player, g, "operation-failed");
            }
        });

        return true;
    }

    private void rollbackJoin(Player player, Guild guild, String message) {
        plugin.sync(() -> {
            guild.removeMember(player.getUniqueId());
            memberIndex.remove(player.getUniqueId(), guild.id());
            plugin.guilds().reloadGuild(guild.id());
            plugin.messages().send(player, message, "guild", guild.name());
        });
    }

    public void invite(Player actor, Guild g, String targetName) {
        GuildSettings c = conf();
        GuildMember actorM = g.member(actor.getUniqueId());
        if (actorM == null) {
            plugin.messages().send(actor, "not-in-guild");
            return;
        }
        boolean leader = actorM.rank() == GuildRank.LEADER;
        boolean officer = actorM.rank() == GuildRank.OFFICER && c.officerInvite;
        if (!leader && !officer) {
            plugin.messages().send(actor, "no-permission-rank");
            return;
        }
        long now = System.currentTimeMillis();
        Long last = inviteCooldown.get(actor.getUniqueId());
        if (last != null && now - last < c.inviteCooldownSeconds * 1000L) {
            long left = (c.inviteCooldownSeconds * 1000L - (now - last)) / 1000L + 1;
            plugin.messages().send(actor, "cooldown", "seconds", left);
            return;
        }
        Player target = plugin.playerExact(targetName);
        if (target != null) {
            finishInvite(actor, g, target, target.getUniqueId(), target.getName(), now);
            return;
        }
        // 跨服解析玩家 UUID（Redis 查询为网络 IO，放到异步线程，避免卡主线程）
        if (!plugin.bus().isReady()) {
            plugin.messages().send(actor, "player-not-found", "player", targetName);
            return;
        }
        plugin.async(() -> {
            UUID targetId = plugin.bus().resolveOnlineUuid(targetName);
            String server = targetId == null ? null : plugin.bus().serverOf(targetId);
            plugin.sync(() -> {
                if (targetId == null) {
                    plugin.messages().send(actor, "player-not-found", "player", targetName);
                    return;
                }
                finishInvite(actor, g, null, targetId, targetName, System.currentTimeMillis());
                if (server != null && !server.equals(plugin.serverId())) {
                    java.util.Map<String, String> payload = CrossServerBus.map(
                            "target", targetId.toString(), "guild", g.id(), "name", g.name(), "server", plugin.serverId());
                    plugin.async(() -> plugin.bus().publishTo(server, "GUILD_INVITE", payload));
                }
            });
        });
    }

    /** 完成邀请登记并通知邀请人（target 可为 null，表示目标是跨服玩家）。 */
    private void finishInvite(Player actor, Guild g, Player target, UUID targetId, String targetDisplay, long now) {
        if (isInGuild(targetId)) {
            Guild cur = guildOf(targetId);
            plugin.messages().send(actor, "already-in-guild", "guild", cur == null ? "" : cur.name());
            return;
        }
        long expires = now + conf().inviteExpireSeconds * 1000L;
        invites.put(targetId, new Invite(g.id(), expires));
        inviteCooldown.put(actor.getUniqueId(), now);
        if (target != null) {
            target.sendMessage(plugin.messages().component("invite-received", "guild", g.name()));
        }
        plugin.messages().send(actor, "invite-sent", "player", targetDisplay);
    }

    /** 收到跨服邀请时调用（在目标服的本地内存登记）。 */
    public void receiveRemoteInvite(UUID target, String guildId, String guildName) {
        if (target == null || guildId == null) {
            return;
        }
        invites.put(target, new Invite(guildId, System.currentTimeMillis() + conf().inviteExpireSeconds * 1000L));
        plugin.sync(() -> {
            Player p = plugin.player(target);
            if (p != null) {
                p.sendMessage(plugin.messages().component("invite-received", "guild", guildName == null ? "" : guildName));
            }
        });
    }

    public boolean accept(Player player, String guildName) {
        Invite inv = invites.get(player.getUniqueId());
        if (inv == null) {
            plugin.messages().send(player, "invite-none");
            return false;
        }
        if (System.currentTimeMillis() > inv.expiresAt) {
            invites.remove(player.getUniqueId());
            plugin.messages().send(player, "invite-expired");
            return false;
        }
        Guild g = guilds.get(inv.guildId);
        if (g == null) {
            plugin.messages().send(player, "guild-not-found", "name", guildName);
            return false;
        }
        return join(player, g, true);
    }

    public void deny(Player player, String guildName) {
        Invite inv = invites.remove(player.getUniqueId());
        Guild g = inv == null ? null : guilds.get(inv.guildId);
        plugin.messages().send(player, "deny-success", "guild", g == null ? guildName : g.name());
    }

    /** 玩家当前待处理的入会邀请（一玩家最多一条，供 GUI 展示）。 */
    public record PendingInvite(String guildId, String guildName, long expiresAt) { }

    /**
     * 列出玩家当前仍有效的入会邀请。
     *
     * <p>邀请只存于内存且按玩家去重，这里是 GUI 唯一可读取的入口，
     * 避免玩家只能对着聊天里的 {@code /guild accept <工会>} 手动输入。</p>
     */
    public List<PendingInvite> pendingInvites(UUID uuid) {
        List<PendingInvite> out = new ArrayList<>();
        if (uuid == null) {
            return out;
        }
        Invite inv = invites.get(uuid);
        if (inv == null || inv.expiresAt <= System.currentTimeMillis()) {
            return out;
        }
        Guild g = guilds.get(inv.guildId);
        out.add(new PendingInvite(inv.guildId, g == null ? inv.guildId : g.name(), inv.expiresAt));
        return out;
    }

    // =====================================================================
    // 退出 / 踢人 / 职位
    // =====================================================================

    public boolean leave(Player player) {
        Guild g = guildOf(player.getUniqueId());
        if (g == null) {
            plugin.messages().send(player, "not-in-guild");
            return false;
        }
        GuildMember m = g.member(player.getUniqueId());
        if (m != null && m.rank() == GuildRank.LEADER) {
            plugin.messages().send(player, "leave-leader-denied");
            return false;
        }
        removeMember(g, player.getUniqueId());
        plugin.messages().send(player, "leave-success", "guild", g.name());
        notifyGuild(g.id(), plugin.messages().component("leave-notify-members", "player", player.getName()), player.getUniqueId());
        Bukkit.getPluginManager().callEvent(new GuildLeaveEvent(g, player, GuildLeaveEvent.Cause.QUIT));
        return true;
    }

    public boolean kick(Player actor, Guild g, UUID targetId, String targetName) {
        if (actor.getUniqueId().equals(targetId)) {
            plugin.messages().send(actor, "self-target");
            return false;
        }
        GuildMember actorM = g.member(actor.getUniqueId());
        GuildMember targetM = g.member(targetId);
        if (targetM == null) {
            plugin.messages().send(actor, "target-not-in-guild");
            return false;
        }
        if (targetM.rank() == GuildRank.LEADER) {
            plugin.messages().send(actor, "kick-leader-denied");
            return false;
        }
        boolean officerAllowed = actorM != null && actorM.rank() == GuildRank.OFFICER && conf().officerKick;
        boolean leader = actorM != null && actorM.rank() == GuildRank.LEADER;
        if (!leader && !officerAllowed) {
            plugin.messages().send(actor, "no-permission-rank");
            return false;
        }
        if (!leader && targetM.rank().atLeast(actorM.rank())) {
            plugin.messages().send(actor, "no-permission-rank");
            return false;
        }
        removeMember(g, targetId);
        plugin.messages().send(actor, "kick-success", "player", targetName);
        Player tp = plugin.player(targetId);
        if (tp != null) {
            plugin.messages().send(tp, "kick-notify", "guild", g.name());
            Bukkit.getPluginManager().callEvent(new GuildLeaveEvent(g, tp, GuildLeaveEvent.Cause.KICK));
        }
        return true;
    }

    private void removeMember(Guild g, UUID uuid) {
        mutationVersion.incrementAndGet();
        g.removeMember(uuid);
        memberIndex.remove(uuid);
        if (plugin.guildChat() != null) plugin.guildChat().refresh();
        // 若该成员正开着工会仓库，先落库并关掉，避免退会后继续操作
        if (plugin.warehouse() != null) {
            plugin.warehouse().kick(uuid);
        }
        // 退会/被踢：卸下固定头衔
        if (plugin.titles() != null) {
            plugin.titles().onMemberRemoved(uuid);
        }
        // 若正处于工会战，先把该玩家从战局里摘出来，避免继续上 BossBar / 领参赛奖励
        if (plugin.war() != null) {
            plugin.war().onMemberRemoved(uuid, g.id());
        }
        plugin.async(() -> {
            try {
                if (!plugin.repo().removeMember(uuid, g.id())) {
                    plugin.getLogger().info("成员关系已变更，跳过旧退会写入: " + uuid);
                    return;
                }
                plugin.broadcastMemberChange(uuid, g.id());
                plugin.broadcastGuildChange(g.id());
            } catch (Exception e) {
                plugin.getLogger().warning("移除成员写库失败: " + e.getMessage());
            }
        });
    }

    public boolean promote(Player actor, Guild g, UUID targetId, String targetName) {
        if (actor.getUniqueId().equals(targetId)) {
            plugin.messages().send(actor, "self-target");
            return false;
        }
        GuildMember actorM = g.member(actor.getUniqueId());
        GuildMember targetM = g.member(targetId);
        if (actorM == null || actorM.rank() != GuildRank.LEADER) {
            plugin.messages().send(actor, "no-permission-rank");
            return false;
        }
        if (targetM == null) {
            plugin.messages().send(actor, "target-not-in-guild");
            return false;
        }
        if (targetM.rank() == GuildRank.OFFICER) {
            // 已是官员：再提升即转让会长，交给 transfer 处理
            return transfer(actor, g, targetId, targetName);
        }
        targetM.rank(GuildRank.OFFICER);
        saveMember(targetM);
        plugin.messages().send(actor, "promote-success", "player", targetName, "rank", GuildRank.OFFICER.display());
        notifyMember(targetId, plugin.messages().component("promote-notify", "rank", GuildRank.OFFICER.display()));
        return true;
    }

    public boolean demote(Player actor, Guild g, UUID targetId, String targetName) {
        if (actor.getUniqueId().equals(targetId)) {
            plugin.messages().send(actor, "self-target");
            return false;
        }
        GuildMember actorM = g.member(actor.getUniqueId());
        GuildMember targetM = g.member(targetId);
        if (actorM == null || actorM.rank() != GuildRank.LEADER) {
            plugin.messages().send(actor, "no-permission-rank");
            return false;
        }
        if (targetM == null) {
            plugin.messages().send(actor, "target-not-in-guild");
            return false;
        }
        if (targetM.rank() != GuildRank.OFFICER) {
            plugin.messages().send(actor, "no-permission-rank");
            return false;
        }
        targetM.rank(GuildRank.MEMBER);
        saveMember(targetM);
        plugin.messages().send(actor, "demote-success", "player", targetName, "rank", GuildRank.MEMBER.display());
        notifyMember(targetId, plugin.messages().component("promote-notify", "rank", GuildRank.MEMBER.display()));
        return true;
    }

    public boolean transfer(Player actor, Guild g, UUID targetId, String targetName) {
        if (actor.getUniqueId().equals(targetId)) {
            plugin.messages().send(actor, "self-target");
            return false;
        }
        GuildMember actorM = g.member(actor.getUniqueId());
        GuildMember targetM = g.member(targetId);
        if (actorM == null || actorM.rank() != GuildRank.LEADER) {
            plugin.messages().send(actor, "no-permission-rank");
            return false;
        }
        if (targetM == null) {
            plugin.messages().send(actor, "target-not-in-guild");
            return false;
        }
        actorM.rank(GuildRank.OFFICER);
        targetM.rank(GuildRank.LEADER);
        g.leaderUuid(targetId);
        g.leaderName(targetName);
        saveMember(actorM);
        saveMember(targetM);
        saveGuild(g);
        plugin.messages().send(actor, "transfer-success", "player", targetName);
        notifyMember(targetId, plugin.messages().component("transfer-notify", "guild", g.name()));
        return true;
    }

    /** 异步保存成员数据（供本模块与工会战模块共用）。 */
    public void saveMemberAsync(GuildMember m) {
        plugin.async(() -> {
            try {
                plugin.repo().updateMember(m);
            } catch (Exception e) {
                plugin.getLogger().warning("保存成员失败: " + e.getMessage());
            }
        });
    }

    private void saveMember(GuildMember m) {
        saveMemberAsync(m);
    }

    public void saveGuild(Guild g) {
        mutationVersion.incrementAndGet();
        g.touchActive();
        plugin.async(() -> {
            // 同一实例的保存串行化：后一次必须基于前一次成功后的 updated_at。
            synchronized (g) {
                try {
                    if (!plugin.repo().saveGuild(g, g.updatedAt())) {
                        plugin.getLogger().warning("工会保存被更新版本拒绝，回读最新数据: " + g.id());
                        reloadGuild(g.id());
                    } else {
                        plugin.broadcastGuildChange(g.id());
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("保存工会失败: " + e.getMessage());
                }
            }
        });
    }

    /** 公告/简介列宽上限（与建表 VARCHAR(255) 一致）。 */
    public static final int TEXT_FIELD_MAX = 255;

    /** 改名字冷却记录：guildId -> 上次改名时间。 */
    private final Map<String, Long> lastRename = new ConcurrentHashMap<>();

    /** 距下次可改名还需多少秒；0 = 可改名。 */
    public long renameCooldownLeft(String guildId) {
        int cd = conf().renameCooldownSeconds;
        if (cd <= 0) {
            return 0;
        }
        long last = lastRename.getOrDefault(guildId, 0L);
        long left = (last + cd * 1000L) - System.currentTimeMillis();
        return Math.max(0, (left + 999) / 1000);
    }

    /**
     * 改工会名称（可选同时改标签）。仅会长可执行，需改名字开关打开。
     *
     * @param newTag 传 null 表示不改标签
     * @return true 表示已提交（异步写库完成后发消息）
     */
    public boolean rename(Player actor, Guild g, String newName, String newTag) {
        GuildSettings c = conf();
        if (!c.renameEnabled) {
            plugin.messages().send(actor, "rename-disabled");
            return false;
        }
        GuildMember m = g.member(actor.getUniqueId());
        if (m == null) {
            plugin.messages().send(actor, "not-in-guild");
            return false;
        }
        if (m.rank() != GuildRank.LEADER) {
            plugin.messages().send(actor, "no-permission-rank");
            return false;
        }
        long left = renameCooldownLeft(g.id());
        if (left > 0) {
            plugin.messages().send(actor, "rename-cooldown", "seconds", left);
            return false;
        }
        return applyRename(actor, g, newName, newTag, c.renameAllowTag, c.renameCost, true);
    }

    /**
     * 管理员改名：绕开开关、冷却、费用与「仅会长」限制（仍会校验名称合法性）。
     * 供 {@code /guildadmin} 与后台 GUI 使用。
     */
    public boolean adminRename(org.bukkit.command.CommandSender actor, Guild g, String newName, String newTag) {
        return applyRename(actor, g, newName, newTag, true, 0, false);
    }

    private boolean applyRename(org.bukkit.command.CommandSender actor, Guild g, String newName, String newTag,
                                boolean allowTag, double cost, boolean checkEconomy) {
        GuildSettings c = conf();
        String cleanName = Text.clean(newName);
        if (Text.hasFormatting(newName) || cleanName.length() < c.nameMin || cleanName.length() > c.nameMax) {
            plugin.messages().send(actor, "create-name-invalid", "min", c.nameMin, "max", c.nameMax);
            return false;
        }
        String oldName = g.name();
        if (cleanName.equalsIgnoreCase(oldName)) {
            plugin.messages().send(actor, "rename-same");
            return false;
        }

        String cleanTag = null;
        if (newTag != null && !newTag.isBlank()) {
            if (!allowTag) {
                plugin.messages().send(actor, "rename-tag-disabled");
                return false;
            }
            cleanTag = Text.clean(newTag);
            if (Text.hasFormatting(newTag) || cleanTag.length() < c.tagMin || cleanTag.length() > c.tagMax) {
                plugin.messages().send(actor, "create-tag-invalid", "min", c.tagMin, "max", c.tagMax);
                return false;
            }
            if (cleanTag.equalsIgnoreCase(g.tag())) {
                plugin.messages().send(actor, "rename-same");
                return false;
            }
        }

        String normName = cleanName.toLowerCase(Locale.ROOT);
        String normTag = cleanTag == null ? null : cleanTag.toLowerCase(Locale.ROOT);
        if (nameIndex.containsKey(normName) || !pendingNames.add(normName)) {
            plugin.messages().send(actor, "create-name-taken", "name", cleanName);
            return false;
        }
        if (normTag != null && (tagIndex.containsKey(normTag) || !pendingTags.add(normTag))) {
            pendingNames.remove(normName);
            plugin.messages().send(actor, "create-tag-taken", "tag", cleanTag);
            return false;
        }

        if (cost > 0 && checkEconomy && !plugin.economy().available()) {
            plugin.messages().send(actor, "rename-no-economy");
            pendingNames.remove(normName);
            if (normTag != null) {
                pendingTags.remove(normTag);
            }
            return false;
        }
        if (cost > 0 && checkEconomy) {
            if (!(actor instanceof Player payer)) {
                plugin.messages().send(actor, "players-only");
                return false;
            }
            if (!plugin.economy().withdraw(payer, cost)) {
                plugin.messages().send(actor, "rename-cost-missing", "cost", plugin.economy().format(cost));
                pendingNames.remove(normName);
                if (normTag != null) {
                    pendingTags.remove(normTag);
                }
                return false;
            }
        }

        String oldTag = g.tag();
        String oldNormName = oldName.toLowerCase(Locale.ROOT);
        String oldNormTag = oldTag == null ? "" : oldTag.toLowerCase(Locale.ROOT);
        g.name(cleanName);
        if (cleanTag != null) {
            g.tag(cleanTag);
        }
        rebuildIndexes();
        lastRename.put(g.id(), System.currentTimeMillis());
        final String appliedTag = cleanTag;
        final String tagForCheck = cleanTag == null ? oldTag : cleanTag;
        final String actorName = actor.getName();

        plugin.async(() -> {
            boolean ok;
            try {
                ok = !plugin.repo().nameOrTagTaken(cleanName, tagForCheck, g.id());
                if (ok) {
                    synchronized (g) { ok = plugin.repo().saveGuild(g, g.updatedAt()); }
                }
            } catch (Exception e) {
                ok = false;
                plugin.getLogger().warning("工会改名写库失败: " + e.getMessage());
            }
            final boolean success = ok;
            plugin.sync(() -> {
                pendingNames.remove(normName);
                if (normTag != null) {
                    pendingTags.remove(normTag);
                }
                if (success) {
                    plugin.broadcastGuildChange(g.id());
                    plugin.messages().send(actor, "rename-success", "old", oldName, "guild", cleanName);
                    plugin.guilds().notifyGuild(g.id(), plugin.messages().component("rename-broadcast",
                            "player", actorName, "old", oldName, "guild", cleanName), null);
                } else {
                    // 回滚内存中的名字
                    g.name(oldName);
                    if (appliedTag != null) {
                        g.tag(oldTag);
                    }
                    rebuildIndexes();
                    lastRename.remove(g.id());
                    if (cost > 0 && actor instanceof Player refundTo && plugin.economy().available()
                            && !plugin.economy().deposit(refundTo, cost)) {
                        plugin.getLogger().severe("工会改名回滚退款失败: 玩家=" + refundTo.getUniqueId() + " guild=" + g.id());
                    }
                    plugin.messages().send(actor, "create-name-taken", "name", cleanName);
                }
            });
        });
        return true;
    }

    /** 设置公告；长度为 0 时清空。超长返回 false 并提示。 */
    public boolean setNotice(Player actor, Guild g, String value) {        String v = value == null ? "" : value;
        if (v.length() > TEXT_FIELD_MAX) {
            plugin.messages().send(actor, "input-too-long", "max", TEXT_FIELD_MAX);
            return false;
        }
        g.notice(v);
        saveGuild(g);
        plugin.messages().send(actor, "set-notice");
        return true;
    }

    /** 设置简介；长度为 0 时清空。超长返回 false 并提示。 */
    public boolean setDescription(Player actor, Guild g, String value) {
        String v = value == null ? "" : value;
        if (v.length() > TEXT_FIELD_MAX) {
            plugin.messages().send(actor, "input-too-long", "max", TEXT_FIELD_MAX);
            return false;
        }
        g.description(v);
        saveGuild(g);
        plugin.messages().send(actor, "set-desc");
        return true;
    }

    // =====================================================================
    // 设置
    // =====================================================================

    /** 更换工会图标（需权限与费用）。 */
    public boolean setIcon(Player actor, Guild g, String iconId) {
        GuildMember m = g.member(actor.getUniqueId());
        if (m == null) {
            plugin.messages().send(actor, "not-in-guild");
            return false;
        }
        if (m.rank() != GuildRank.LEADER && !(m.rank() == GuildRank.OFFICER && conf().officerSetNotice)) {
            plugin.messages().send(actor, "no-permission-rank");
            return false;
        }
        IconDef def = plugin.settings().icons.icon(iconId);
        if (def == null) {
            plugin.messages().send(actor, "set-failed");
            return false;
        }
        if (!def.hasPermission(actor)) {
            plugin.messages().send(actor, "no-permission");
            return false;
        }
        double cost = conf().iconChangeCost;
        if (cost > 0) {
            if (!plugin.economy().has(actor, cost) || !plugin.economy().withdraw(actor, cost)) {
                plugin.messages().send(actor, "create-cost-missing", "cost", plugin.economy().format(cost));
                return false;
            }
        }
        g.icon(iconId);
        saveGuild(g);
        plugin.messages().send(actor, "set-icon", "icon", def.display);
        return true;
    }

    /** 同工会成员是否允许互相攻击；未单独设置时回退 guild.yml 的 friendly-fire。 */
    public boolean allowsMemberPvp(Guild g) {
        if (g == null) {
            return false;
        }
        Boolean value = g.memberPvp();
        return value == null ? conf().friendlyFire : value;
    }

    /** 切换本工会成员 PvP；需要会长或具备设置权限的官员。 */
    public boolean toggleMemberPvp(Player actor, Guild g) {
        if (g == null || !canManageSettings(actor, g)) {
            return false;
        }
        boolean next = !allowsMemberPvp(g);
        g.memberPvp(next);
        saveGuild(g);
        plugin.messages().send(actor, "set-pvp", "value", next ? "开启" : "关闭");
        return true;
    }

    public boolean canManageSettings(Player actor, Guild g) {
        GuildMember m = g.member(actor.getUniqueId());
        if (m == null) {
            plugin.messages().send(actor, "not-in-guild");
            return false;
        }
        if (m.rank() == GuildRank.LEADER) {
            return true;
        }
        if (m.rank() == GuildRank.OFFICER && conf().officerSetNotice) {
            return true;
        }
        plugin.messages().send(actor, "no-permission-rank");
        return false;
    }

    // =====================================================================
    // 银行 / 点数
    // =====================================================================

    public boolean deposit(Player player, Guild g, double amount) {
        if (!bankAccess(player, g, amount)) return false;
        long credited = (long) Math.floor(amount * (1 - conf().bankDepositTaxPercent / 100.0));
        if (credited <= 0) {
            plugin.messages().send(player, "input-invalid-number");
            return false;
        }
        if (!plugin.economy().withdraw(player, amount)) {
            plugin.messages().send(player, "bank-money-insufficient");
            return false;
        }
        changeBank(g.id(), credited).whenComplete((success, error) -> plugin.sync(() -> {
            if (error != null || !Boolean.TRUE.equals(success)) {
                if (!plugin.economy().deposit(player, amount)) {
                    plugin.getLogger().severe("银行存款退款失败: 玩家=" + player.getUniqueId() + " 金额=" + amount);
                }
                plugin.messages().send(player, "bank-transaction-failed");
                return;
            }
            GuildMember member = g.member(player.getUniqueId());
            if (member != null) { member.addContributed(credited); saveMember(member); }
            bankCompleted(player, g, credited, true);
        }));
        return true;
    }

    public boolean withdraw(Player player, Guild g, double amount) {
        if (!bankAccess(player, g, amount)) return false;
        long debit = (long) amount;
        changeBank(g.id(), -debit).whenComplete((success, error) -> plugin.sync(() -> {
            if (error != null || !Boolean.TRUE.equals(success)) {
                plugin.messages().send(player, "bank-insufficient");
                return;
            }
            if (!plugin.economy().deposit(player, debit)) {
                changeBank(g.id(), debit).whenComplete((refunded, refundError) -> {
                    if (refundError != null || !Boolean.TRUE.equals(refunded)) {
                        plugin.getLogger().severe("银行取款回滚失败: 工会=" + g.id() + " 玩家=" + player.getUniqueId() + " 金额=" + debit);
                    }
                });
                plugin.messages().send(player, "bank-transaction-failed");
                return;
            }
            bankCompleted(player, g, debit, false);
        }));
        return true;
    }

    private boolean bankAccess(Player player, Guild g, double amount) {
        if (!conf().bankEnabled) { plugin.messages().send(player, "bank-disabled"); return false; }
        if (g == null || !g.id().equals(memberIndex.get(player.getUniqueId()))) {
            plugin.messages().send(player, "not-in-guild"); return false;
        }
        if (plugin.economy() == null || !plugin.economy().available()) {
            plugin.messages().send(player, "bank-economy-unavailable"); return false;
        }
        if (!Double.isFinite(amount) || amount < 1 || amount >= Long.MAX_VALUE || amount != Math.floor(amount)) {
            plugin.messages().send(player, "input-invalid-number"); return false;
        }
        return true;
    }

    /** Atomic Vault-bank balance mutation; DB is authoritative. Never await on the server thread. */
    public java.util.concurrent.CompletableFuture<Boolean> changeBank(String guildId, long delta) {
        java.util.concurrent.CompletableFuture<Boolean> result = new java.util.concurrent.CompletableFuture<>();
        plugin.async(() -> {
            try {
                boolean changed = plugin.repo().addBank(guildId, delta);
                if (changed) {
                    plugin.sync(() -> {
                        Guild cached = guild(guildId);
                        if (cached != null) {
                            long balance = cached.bank();
                            cached.bank(delta > 0 && balance > Long.MAX_VALUE - delta ? Long.MAX_VALUE
                                    : Math.max(0, balance + delta));
                        }
                        plugin.broadcastGuildChange(guildId);
                        result.complete(true);
                        reloadGuild(guildId);
                    });
                } else result.complete(false);
            } catch (Exception e) { result.completeExceptionally(e); }
        });
        return result;
    }

    private void bankCompleted(Player player, Guild original, long amount, boolean deposit) {
        Guild g = guild(original.id());
        if (g == null) g = original;
        plugin.messages().send(player, deposit ? "bank-deposit" : "bank-withdraw",
                "amount", plugin.economy().format(amount), "balance", plugin.economy().format(g.bank()));
        announceGuild(g.id(), plugin.messages().component("bank-notify", "player", player.getName(),
                "action", deposit ? "存入" : "取出", "amount", plugin.economy().format(amount)), player.getUniqueId());
        Bukkit.getPluginManager().callEvent(new GuildBankEvent(g, player,
                deposit ? GuildBankEvent.Action.DEPOSIT : GuildBankEvent.Action.WITHDRAW, amount, g.bank()));
    }

    /**
     * 增减工会点数（工会资金）。
     *
     * <p>点数与银行一样只通过数据库原子 UPDATE 写入（{@code saveGuild} 不再写 points），
     * 否则仓库购买/升级等原子扣费会被并发的整行保存覆盖回去（假扣费）。
     * 写入成功后回读数据库刷新缓存，并按新点数重算等级。</p>
     */
    public void addPoints(Guild g, long delta) {
        if (g == null || delta == 0) {
            return;
        }
        String id = g.id();
        plugin.async(() -> {
            try {
                plugin.repo().addPoints(id, delta);
            } catch (Exception e) {
                plugin.getLogger().warning("更新工会点数失败: " + e.getMessage());
            }
            pullPoints(id, true);
        });
    }

    /** 从数据库回读点数并刷新缓存；{@code recompute} = 按新点数重算等级。 */
    private void pullPoints(String guildId, boolean recompute) {
        long points;
        try {
            points = plugin.repo().loadPoints(guildId);
        } catch (Exception e) {
            return;
        }
        // 等级只增不减：在异步线程算好目标并以原子「只升」UPDATE 落库，
        // 避免两台子服并发加点时，较旧的等级被整行保存写回退。
        int raised = -1;
        if (recompute) {
            Guild cached = guild(guildId);
            int oldLevel = cached == null ? 1 : cached.level();
            int target = levelForPoints(points);
            if (target > oldLevel) {
                try {
                    if (plugin.repo().raiseLevel(guildId, target)) {
                        raised = target;
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("更新工会等级失败: " + e.getMessage());
                }
            }
        }
        final int newLevel = raised;
        plugin.sync(() -> {
            Guild cached = guild(guildId);
            if (cached == null) {
                return;
            }
            cached.points(points);
            if (newLevel > cached.level()) {
                int old = cached.level();
                cached.level(newLevel);
                Bukkit.getPluginManager().callEvent(new GuildLevelUpEvent(cached, old, newLevel));
                notifyGuild(guildId, plugin.messages().component("guild-level-up",
                        "guild", cached.name(), "level", newLevel), null);
            }
        });
    }

    // =====================================================================
    // 带每日上限的工会资金（工会战 / 在线活跃）
    // =====================================================================

    /** 工会战奖励（带每日上限）；返回预计发放额（用于展示），实际以数据库原子判定为准。 */
    public long grantWarFunds(Guild g, long nominal, long dailyCap) {
        return grantDailyCapped(g, nominal, dailyCap, true);
    }

    /** 玩家在线活跃奖励（每日上限来自 guild.activity.daily-cap）。 */
    public long grantActivityFunds(Guild g, long nominal) {
        return grantDailyCapped(g, nominal, conf().activityDailyCap, false);
    }

    /** 某工会当日已通过工会战 / 在线活跃获得的工会资金（用于展示/占位符）。 */
    public long dailyEarned(Guild g, boolean war) {
        if (g == null) {
            return 0;
        }
        long day = com.liu.liuguild.util.TimeUtil.todayEpochDay();
        return war ? (g.warFundsDay() == day ? g.warFundsEarned() : 0)
                : (g.activityDay() == day ? g.activityEarned() : 0);
    }

    /**
     * 发放带每日上限的工会资金。
     *
     * <p>先按本地缓存算出发放额（用于即时展示，并保证同一 tick 内多次发放也受上限约束），
     * 再异步用数据库的原子「读-算-乐观锁写」落库，多台子服并发也不会超发；
     * 写入后回读点数与每日计数校正缓存。</p>
     */
    public long grantDailyCapped(Guild g, long nominal, long dailyCap, boolean war) {
        if (g == null || nominal <= 0) {
            return 0;
        }
        long day = com.liu.liuguild.util.TimeUtil.todayEpochDay();
        long used = war ? (g.warFundsDay() == day ? g.warFundsEarned() : 0)
                : (g.activityDay() == day ? g.activityEarned() : 0);
        long granted = dailyCap <= 0 ? nominal : Math.min(nominal, Math.max(0, dailyCap - used));
        if (granted <= 0) {
            return 0;
        }
        // 乐观累加：同一 tick 内多次发放也受上限约束
        if (war) {
            g.warFundsDay(day);
            g.warFundsEarned(used + granted);
        } else {
            g.activityDay(day);
            g.activityEarned(used + granted);
        }
        final String id = g.id();
        final long amount = granted;
        plugin.async(() -> {
            try {
                plugin.repo().grantDailyCapped(id, amount, day, dailyCap, war);
            } catch (Exception e) {
                plugin.getLogger().warning("发放每日上限工会资金失败: " + e.getMessage());
            }
            plugin.broadcastGuildChange(id);
            pullPoints(id, true);
            pullDaily(id);
        });
        return granted;
    }

    /** 从数据库回读每日计数并校正缓存。 */
    private void pullDaily(String guildId) {
        long[] d;
        try {
            d = plugin.repo().loadDailyFunds(guildId);
        } catch (Exception e) {
            return;
        }
        plugin.sync(() -> {
            Guild cached = guild(guildId);
            if (cached == null) {
                return;
            }
            cached.warFundsDay(d[0]);
            cached.warFundsEarned(d[1]);
            cached.activityDay(d[2]);
            cached.activityEarned(d[3]);
        });
    }

    /**
     * 每日工会签到：以本地自然日为准，连续签到获得额外工会资金。
     */
    public void checkin(Player player) {
        if (player == null) {
            return;
        }
        Guild g = guildOf(player.getUniqueId());
        if (g == null) {
            plugin.messages().send(player, "not-in-guild");
            return;
        }
        GuildSettings c = conf();
        if (!c.checkinEnabled) {
            plugin.messages().send(player, "checkin-disabled");
            return;
        }
        GuildMember m = g.member(player.getUniqueId());
        if (m == null) {
            plugin.messages().send(player, "not-in-guild");
            return;
        }
        long today = com.liu.liuguild.util.TimeUtil.todayEpochDay();
        if (GuildSettings.alreadyCheckedIn(today, m.lastCheckin())) {
            plugin.messages().send(player, "checkin-already");
            return;
        }

        // ---- 先扣费：任一费用不足则直接返回，不消耗签到次数也不发奖励 ----
        if (c.checkinCost > 0) {
            if (!plugin.economy().available()) {
                plugin.messages().send(player, "checkin-no-economy");
                return;
            }
            if (!plugin.economy().has(player, c.checkinCost)) {
                plugin.messages().send(player, "checkin-cost-missing",
                        "cost", plugin.economy().format(c.checkinCost));
                return;
            }
        }
        if (c.checkinGuildCost > 0 && g.points() < c.checkinGuildCost) {
            plugin.messages().send(player, "checkin-guild-cost-missing",
                    "cost", String.valueOf(c.checkinGuildCost), "guild", g.name());
            return;
        }
        if (c.checkinCost > 0 && !plugin.economy().withdraw(player, c.checkinCost)) {
            plugin.messages().send(player, "checkin-cost-missing",
                    "cost", plugin.economy().format(c.checkinCost));
            return;
        }
        int streak = GuildSettings.nextStreak(today, m.lastCheckin(), m.checkinStreak());
        long reward = GuildSettings.checkinReward(c.checkinPoints, c.checkinStreakBonus,
                c.checkinMaxStreakBonus, streak);
        plugin.async(() -> {
            boolean guildDebited = false;
            try {
                // 工会点数扣费必须在同一异步路径内确认真实结果，失败时不能再“退款”出免费点数。
                if (c.checkinGuildCost > 0) {
                    guildDebited = plugin.repo().addPoints(g.id(), -c.checkinGuildCost);
                    if (!guildDebited) {
                        plugin.sync(() -> {
                            if (c.checkinCost > 0) plugin.economy().deposit(player, c.checkinCost);
                            plugin.messages().send(player, "checkin-guild-cost-missing",
                                    "cost", String.valueOf(c.checkinGuildCost), "guild", g.name());
                        });
                        return;
                    }
                    pullPoints(g.id(), false);
                }
                boolean granted = new com.liu.liuguild.db.PersonalFundsRepository(plugin.db())
                        .grantCheckin(player.getUniqueId(), today, reward);
                if (!granted) {
                    if (guildDebited) plugin.repo().addPoints(g.id(), c.checkinGuildCost);
                    if (guildDebited) pullPoints(g.id(), false);
                    plugin.sync(() -> {
                        if (c.checkinCost > 0) plugin.economy().deposit(player, c.checkinCost);
                        plugin.messages().send(player, "checkin-already");
                    });
                    return;
                }
                plugin.sync(() -> completeCheckin(player, g, m, c, today, streak, reward));
            } catch (java.sql.SQLException e) {
                plugin.getLogger().severe("个人签到资金入账失败: " + e.getMessage());
                if (guildDebited) {
                    try { plugin.repo().addPoints(g.id(), c.checkinGuildCost); pullPoints(g.id(), false); }
                    catch (Exception ignored) { }
                }
                plugin.sync(() -> {
                    if (c.checkinCost > 0) plugin.economy().deposit(player, c.checkinCost);
                    plugin.messages().send(player, "funds-failed");
                });
            }
        });
    }

    private void completeCheckin(Player player, Guild g, GuildMember m, GuildSettings c,
                                 long today, int streak, long reward) {
        m.lastCheckin(today);
        m.checkinStreak(streak);
        saveMemberAsync(m);
        boolean moneyGiven = c.checkinMoney > 0 && plugin.economy().available();
        if (moneyGiven) {
            plugin.economy().deposit(player, c.checkinMoney);
        }
        Bukkit.getPluginManager().callEvent(new GuildCheckinEvent(g, player, streak, reward));
        // 分行提示：只列出实际发生的项目，0 / 未配置的不占行
        plugin.messages().sendRaw(player, "checkin-success-header", "streak", streak);
        if (c.checkinCost > 0) {
            plugin.messages().sendRaw(player, "checkin-line-cost",
                    "cost", plugin.economy().format(c.checkinCost));
        }
        if (c.checkinGuildCost > 0) {
            plugin.messages().sendRaw(player, "checkin-line-guild-cost",
                    "guild_cost", String.valueOf(c.checkinGuildCost));
        }
        if (reward > 0) {
            plugin.messages().sendRaw(player, "checkin-line-points", "points", String.valueOf(reward));
        }
        if (moneyGiven) {
            plugin.messages().sendRaw(player, "checkin-line-money",
                    "money", plugin.economy().format(c.checkinMoney));
        }
        plugin.messages().sendRaw(player, "checkin-success-footer");
        if (c.checkinBroadcast && reward > 0) {
            notifyGuild(g.id(), plugin.messages().component("checkin-broadcast",
                    "player", player.getName(), "streak", streak, "points", reward),
                    player.getUniqueId());
        } else if (c.checkinBroadcast) {
            notifyGuild(g.id(), plugin.messages().component("checkin-broadcast-plain",
                    "player", player.getName(), "streak", streak),
                    player.getUniqueId());
        }
        try {
            player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.4f);
        } catch (Throwable ignored) {
        }
    }

    /** 管理员强制设置等级（不触发升级事件）。 */
    public void setLevel(Guild g, int level) {
        if (g == null) {
            return;
        }
        int max = conf().maxLevel;
        g.level(Math.max(1, Math.min(max, level)));
        saveGuild(g);
    }

    /** 管理员强制设置点数（不重算等级）。 */
    public void setPoints(Guild g, long points) {
        if (g == null) {
            return;
        }
        String id = g.id();
        long value = Math.max(0, points);
        plugin.async(() -> {
            try {
                plugin.repo().setPoints(id, value);
            } catch (Exception e) {
                plugin.getLogger().warning("设置工会点数失败: " + e.getMessage());
                return;
            }
            pullPoints(id, false);
        });
    }

    public void recomputeLevel(Guild g) {
        int old = g.level();
        int lvl = levelForPoints(g.points());
        g.level(lvl);
        if (lvl > old) {
            Bukkit.getPluginManager().callEvent(new GuildLevelUpEvent(g, old, lvl));
            notifyGuild(g.id(), plugin.messages().component("guild-level-up", "guild", g.name(), "level", lvl), null);
        }
    }

    /** 纯函数：给定点数时对应的工会等级（不触发事件、不读模型）。 */
    private int levelForPoints(long points) {
        GuildSettings c = conf();
        long cumulative = 0;
        int lvl = 1;
        while (lvl < c.maxLevel && points >= cumulative + c.requiredPoints(lvl)) {
            cumulative += c.requiredPoints(lvl);
            lvl++;
        }
        return lvl;
    }

    // =====================================================================
    // 通知
    // =====================================================================

    /** 只发给本服在线成员（本地即时）。 */
    public void notifyGuild(String guildId, Component text, UUID except) {
        Guild g = guilds.get(guildId);
        if (g == null) {
            return;
        }
        for (UUID u : g.memberMap().keySet()) {
            if (except != null && except.equals(u)) {
                continue;
            }
            Player p = plugin.player(u);
            if (p != null) {
                p.sendMessage(text);
            }
        }
    }

    /** 跨服广播给所有子服的同会成员（本服也会收到，通过 Redis 广播回环，需跳过来源服）。 */
    public void guildBroadcast(String guildId, Component text, UUID except) {
        if (!plugin.bus().isReady()) {
            return; // 单服：notifyGuild 已处理
        }
        String payload = MM.serialize(text);
        final String exceptId = except == null ? "" : except.toString();
        final String server = plugin.serverId();
        // Redis 发布是阻塞 IO，放异步线程，避免主线程卡顿（工会战公告可能很频繁）。
        plugin.async(() -> plugin.bus().publish("GUILD_ANNOUNCE", CrossServerBus.map(
                "guild", guildId, "text", payload, "except", exceptId, "server", server)));
    }

    /**
     * 向工会成员公告：本服即时投递 + 跨服投递（其它子服通过 Redis 收到）。
     * 推荐统一使用此入口，避免重复调用 {@link #notifyGuild} 与 {@link #guildBroadcast}。
     */
    public void announceGuild(String guildId, Component text, UUID except) {
        if (guildId == null || text == null) {
            return;
        }
        notifyGuild(guildId, text, except);
        guildBroadcast(guildId, text, except);
    }

    /** 清理解除过期邀请与冷却记录（心跳调用，防止内存泄漏）。 */
    public void purge() {
        long now = System.currentTimeMillis();
        invites.entrySet().removeIf(e -> e.getValue().expiresAt < now);
        long cooldownMs = Math.max(1, conf().inviteCooldownSeconds) * 1000L;
        inviteCooldown.entrySet().removeIf(e -> now - e.getValue() > cooldownMs * 4);
    }

    /** 向本服所有在线玩家广播（用于匹配提示等）。 */
    public void broadcastAll(Component text) {
        Bukkit.getServer().broadcast(text);
    }

    private void notifyMember(UUID uuid, Component text) {
        Player p = plugin.player(uuid);
        if (p != null) {
            p.sendMessage(text);
        }
    }

    /** 收到跨服公告：投递给本服成员（可能在 Redis 线程调用，需切回主线程）。 */
    public void deliverAnnounce(String guildId, String miniText, String except) {
        Component text = Text.mm(miniText);
        UUID skip = null;
        if (except != null && !except.isBlank()) {
            try {
                skip = UUID.fromString(except);
            } catch (IllegalArgumentException ignored) {
            }
        }
        UUID skipFinal = skip;
        plugin.sync(() -> notifyGuild(guildId, text, skipFinal));
    }

    // =====================================================================
    // 在线成员工具
    // =====================================================================

    public List<Player> onlineMembers(Guild g) {
        List<Player> out = new ArrayList<>();
        if (g == null) {
            return out;
        }
        for (UUID u : g.memberMap().keySet()) {
            Player p = plugin.player(u);
            if (p != null) {
                out.add(p);
            }
        }
        return out;
    }
}
