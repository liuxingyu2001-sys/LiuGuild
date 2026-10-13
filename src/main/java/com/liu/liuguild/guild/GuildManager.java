package com.liu.liuguild.guild;

import com.liu.liuguild.LiuGuild;
import com.liu.liuguild.api.event.GuildBankEvent;
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
    private final Map<UUID, Invite> invites = new ConcurrentHashMap<>();
    private final Map<UUID, Long> inviteCooldown = new ConcurrentHashMap<>();

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
        if (nameIndex.containsKey(cleanName.toLowerCase(Locale.ROOT))) {
            plugin.messages().send(player, "create-name-taken", "name", cleanName);
            return false;
        }
        if (tagIndex.containsKey(cleanTag.toLowerCase(Locale.ROOT))) {
            plugin.messages().send(player, "create-tag-taken", "tag", cleanTag);
            return false;
        }
        if (c.createCost > 0 && !player.hasPermission("liuguild.create.free")
                && !plugin.economy().has(player, c.createCost)) {
            plugin.messages().send(player, "create-cost-missing", "cost", plugin.economy().format(c.createCost));
            return false;
        }

        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        Guild g = new Guild(id, cleanName, cleanTag, player.getUniqueId(), player.getName(),
                "", "", false, now, now, 0, 1, 0, 0, 0, plugin.serverId(), 1,
                conf().defaultIcon);
        GuildMember leader = new GuildMember(player.getUniqueId(), player.getName(), id,
                GuildRank.LEADER, now, 0, 0, 0);
        g.putMember(leader);
        boolean charged = c.createCost <= 0 || player.hasPermission("liuguild.create.free")
                || plugin.economy().withdraw(player, c.createCost);
        if (!charged) {
            plugin.messages().send(player, "create-cost-missing", "cost", plugin.economy().format(c.createCost));
            return false;
        }

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
                    plugin.messages().send(player, "create-success", "guild", cleanName);
                    Bukkit.getPluginManager().callEvent(new GuildCreateEvent(g, player));
                });
            } catch (Exception e) {
                plugin.getLogger().warning("创建工会写库失败: " + e.getMessage());
                rollbackCreate(player, g, id, c, "create-failed");
            }
        });
        return true;
    }

    private void rollbackCreate(Player player, Guild guild, String id, GuildSettings settings, String message) {
        plugin.sync(() -> {
            guilds.remove(id, guild);
            memberIndex.remove(player.getUniqueId(), id);
            rebuildIndexes();
            if (settings.createCost > 0 && !player.hasPermission("liuguild.create.free")
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
            int online = onlineMembers(g).size();
            if (GuildSettings.isInactive(now, g.lastActiveAt(), c.cleanupInactiveDays,
                    g.memberCount(), c.cleanupMaxMembers, online)) {
                out.add(g);
            }
        }
        return out;
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
    public List<Guild> autoCleanup() {
        GuildSettings c = conf();
        if (!c.cleanupEnabled) {
            return List.of();
        }
        List<Guild> targets = purgeInactive(c.cleanupDryRun);
        if (!targets.isEmpty()) {
            plugin.getLogger().info("不活跃工会清理（" + (c.cleanupDryRun ? "dry-run" : "已执行")
                    + "，阈值 " + c.cleanupInactiveDays + " 天）：" + targets.size() + " 个");
        }
        return targets;
    }

    public void disband(Guild g, boolean broadcast) {
        disband(g, broadcast, GuildDisbandEvent.Cause.PLAYER);
    }

    public boolean disband(Guild g, boolean broadcast, GuildDisbandEvent.Cause cause) {
        if (g == null) {
            return false;
        }
        if (broadcast) announceGuild(g.id(), plugin.messages().component("disband-broadcast", "guild", g.name()), null);
        guilds.remove(g.id());
        for (UUID u : g.memberMap().keySet()) {
            memberIndex.remove(u);
        }
        rebuildIndexes();
        plugin.async(() -> {
            try {
                plugin.repo().deleteGuild(g.id());
                plugin.broadcastGuildChange(g.id());
            } catch (Exception e) {
                plugin.getLogger().warning("解散工会写库失败: " + e.getMessage());
            }
        });
        if (plugin.guildChat() != null) plugin.guildChat().refresh();
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
        GuildMember m = new GuildMember(player.getUniqueId(), player.getName(), g.id(), GuildRank.MEMBER, now, 0, 0, 0);
        g.putMember(m);
        memberIndex.put(player.getUniqueId(), g.id());
        invites.remove(player.getUniqueId());

        plugin.async(() -> {
            try {
                if (!plugin.repo().insertMemberAndIncrement(m)) {
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
                    plugin.bus().publishTo(server, "GUILD_INVITE", CrossServerBus.map(
                            "target", targetId.toString(), "guild", g.id(), "name", g.name(), "server", plugin.serverId()));
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
        g.removeMember(uuid);
        memberIndex.remove(uuid);
        if (plugin.guildChat() != null) plugin.guildChat().refresh();
        plugin.async(() -> {
            try {
                plugin.repo().deleteMember(uuid);
                plugin.repo().updateGuild(g);
                plugin.broadcastMemberChange(uuid, "");
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
        g.touch();
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
        g.touch();
        g.touchActive();
        plugin.async(() -> {
            try {
                plugin.repo().updateGuild(g);
                plugin.broadcastGuildChange(g.id());
            } catch (Exception e) {
                plugin.getLogger().warning("保存工会失败: " + e.getMessage());
            }
        });
    }

    /** 公告/简介列宽上限（与建表 VARCHAR(255) 一致）。 */
    public static final int TEXT_FIELD_MAX = 255;

    /** 设置公告；长度为 0 时清空。超长返回 false 并提示。 */
    public boolean setNotice(Player actor, Guild g, String value) {
        String v = value == null ? "" : value;
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

    public void addPoints(Guild g, long delta) {
        if (g == null || delta == 0) {
            return;
        }
        g.points(Math.max(0, g.points() + delta));
        recomputeLevel(g);
        saveGuild(g);
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
        g.points(Math.max(0, points));
        saveGuild(g);
    }

    public void recomputeLevel(Guild g) {
        GuildSettings c = conf();
        int old = g.level();
        long cumulative = 0;
        int lvl = 1;
        while (lvl < c.maxLevel && g.points() >= cumulative + c.requiredPoints(lvl)) {
            cumulative += c.requiredPoints(lvl);
            lvl++;
        }
        g.level(lvl);
        if (lvl > old) {
            Bukkit.getPluginManager().callEvent(new GuildLevelUpEvent(g, old, lvl));
            notifyGuild(g.id(), plugin.messages().component("guild-level-up", "guild", g.name(), "level", lvl), null);
        }
    }

    public void recordWarResult(Guild winner, Guild loser, long winPoints, long losePoints) {
        if (winner != null) {
            winner.wins(winner.wins() + 1);
            addPoints(winner, winPoints);
            notifyGuild(winner.id(), plugin.messages().component("war-reward-win",
                    "money", "-", "points", winPoints), null);
        }
        if (loser != null) {
            loser.losses(loser.losses() + 1);
            addPoints(loser, losePoints);
        }
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
        plugin.bus().publish("GUILD_ANNOUNCE", CrossServerBus.map(
                "guild", guildId, "text", MM.serialize(text), "except", except == null ? "" : except.toString(),
                "server", plugin.serverId()));
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

    public List<Player> onlineMembers(Collection<UUID> uuids) {
        List<Player> out = new ArrayList<>();
        for (UUID u : uuids) {
            Player p = plugin.player(u);
            if (p != null) {
                out.add(p);
            }
        }
        return out;
    }
}
