package com.liu.liuguild.guild;

import com.liu.liuguild.LiuGuild;
import com.liu.liuguild.config.GuildConfig;
import com.liu.liuguild.crossserver.CrossServerBus;
import com.liu.liuguild.model.Guild;
import com.liu.liuguild.model.GuildMember;
import com.liu.liuguild.model.GuildRank;
import com.liu.liuguild.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
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
    private final Map<String, Guild> guilds = new ConcurrentHashMap<>();
    private final Map<UUID, String> memberIndex = new ConcurrentHashMap<>();
    private final Map<String, String> nameIndex = new ConcurrentHashMap<>();
    private final Map<String, String> tagIndex = new ConcurrentHashMap<>();
    private final Map<UUID, Invite> invites = new ConcurrentHashMap<>();
    private final Map<UUID, Long> inviteCooldown = new ConcurrentHashMap<>();

    public GuildManager(LiuGuild plugin) {
        this.plugin = plugin;
    }

    private GuildConfig conf() {
        return plugin.config();
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
                guilds.clear();
                guilds.putAll(fresh);
                memberIndex.clear();
                memberIndex.putAll(idx);
                rebuildIndexes();
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
        List<Guild> list = new ArrayList<>(guilds.values());
        switch (column) {
            case "level" -> list.sort((a, b) -> Integer.compare(b.level(), a.level()));
            case "wins" -> list.sort((a, b) -> Integer.compare(b.wins(), a.wins()));
            case "bank" -> list.sort((a, b) -> Long.compare(b.bank(), a.bank()));
            case "members" -> list.sort((a, b) -> Integer.compare(b.memberCount(), a.memberCount()));
            default -> list.sort((a, b) -> Long.compare(b.points(), a.points()));
        }
        return list.subList(0, Math.min(limit, list.size()));
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
                    guilds.remove(guildId);
                    return;
                }
                for (GuildMember m : plugin.repo().loadMembers(guildId)) {
                    g.putMember(m);
                    memberIndex.put(m.uuid(), guildId);
                }
                guilds.put(guildId, g);
                rebuildIndexes();
            } catch (Exception e) {
                plugin.getLogger().warning("刷新工会 " + guildId + " 失败: " + e.getMessage());
            }
        });
    }

    // =====================================================================
    // 创建 / 解散
    // =====================================================================

    public boolean create(Player player, String name, String tag) {
        GuildConfig c = conf();
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
        if (c.createCost > 0 && !player.hasPermission("liuguild.create.free")) {
            if (!plugin.economy().has(player, c.createCost)) {
                plugin.messages().send(player, "create-cost-missing", "cost", plugin.economy().format(c.createCost));
                return false;
            }
        }

        String id = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        Guild g = new Guild(id, cleanName, cleanTag, player.getUniqueId(), player.getName(),
                "", "", false, now, now, 0, 1, 0, 0, 0, plugin.serverId(), 1);
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
                    plugin.sync(() -> {
                        guilds.remove(id);
                        memberIndex.remove(player.getUniqueId());
                        rebuildIndexes();
                        plugin.messages().send(player, "create-name-taken", "name", cleanName);
                        if (c.createCost > 0 && !player.hasPermission("liuguild.create.free")) {
                            plugin.economy().deposit(player, c.createCost);
                        }
                    });
                    return;
                }
                plugin.repo().insertGuild(g);
                plugin.repo().insertMember(leader);
                plugin.broadcastGuildChange(id);
            } catch (Exception e) {
                plugin.getLogger().warning("创建工会写库失败: " + e.getMessage());
            }
        });
        plugin.messages().send(player, "create-success", "guild", cleanName);
        return true;
    }

    public boolean disband(Guild g, boolean broadcast) {
        if (g == null) {
            return false;
        }
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
        if (broadcast) {
            notifyGuild(g.id(), plugin.messages().component("disband-broadcast", "guild", g.name()), null);
        }
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
                plugin.repo().insertMember(m);
                plugin.repo().updateGuild(g);
                plugin.broadcastMemberChange(player.getUniqueId(), g.id());
                plugin.broadcastGuildChange(g.id());
            } catch (Exception e) {
                plugin.getLogger().warning("加入工会写库失败: " + e.getMessage());
            }
        });

        plugin.messages().send(player, viaInvite ? "accept-success" : "join-success", "guild", g.name());
        notifyGuild(g.id(), plugin.messages().component("join-notify-members", "player", player.getName()), player.getUniqueId());
        guildBroadcast(g.id(), plugin.messages().component("join-notify-members", "player", player.getName()), player.getUniqueId());
        return true;
    }

    public void invite(Player actor, Guild g, String targetName) {
        GuildConfig c = conf();
        long now = System.currentTimeMillis();
        Long last = inviteCooldown.get(actor.getUniqueId());
        if (last != null && now - last < c.inviteCooldownSeconds * 1000L) {
            long left = (c.inviteCooldownSeconds * 1000L - (now - last)) / 1000L + 1;
            plugin.messages().send(actor, "cooldown", "seconds", left);
            return;
        }
        Player target = plugin.playerExact(targetName);
        UUID targetId;
        String targetDisplay;
        if (target != null) {
            targetId = target.getUniqueId();
            targetDisplay = target.getName();
        } else {
            targetId = plugin.bus().resolveOnlineUuid(targetName);
            targetDisplay = targetName;
            if (targetId == null) {
                plugin.messages().send(actor, "player-not-found", "player", targetName);
                return;
            }
        }
        if (isInGuild(targetId)) {
            Guild cur = guildOf(targetId);
            plugin.messages().send(actor, "already-in-guild", "guild", cur == null ? "" : cur.name());
            return;
        }
        long expires = now + c.inviteExpireSeconds * 1000L;
        invites.put(targetId, new Invite(g.id(), expires));
        inviteCooldown.put(actor.getUniqueId(), now);

        Component inviteMsg = plugin.messages().component("invite-received", "guild", g.name());
        if (target != null) {
            target.sendMessage(inviteMsg);
        } else {
            // 跨服投递：目标在别的子服，交给那台服本地投递
            String server = plugin.bus().serverOf(targetId);
            if (plugin.bus().isReady() && server != null && !server.equals(plugin.serverId())) {
                plugin.bus().publishTo(server, "GUILD_INVITE", CrossServerBus.map(
                        "player", targetId.toString(), "guild", g.id(), "guildName", g.name(), "server", plugin.serverId()));
            }
            // 目标服务端收到后会本地入库邀请
        }
        plugin.messages().send(actor, "invite-sent", "player", targetDisplay);
    }

    /** 收到跨服邀请时调用（在目标服的本地内存登记）。 */
    public void receiveRemoteInvite(UUID target, String guildId, String guildName) {
        invites.put(target, new Invite(guildId, System.currentTimeMillis() + conf().inviteExpireSeconds * 1000L));
        Player p = plugin.player(target);
        if (p != null) {
            p.sendMessage(plugin.messages().component("invite-received", "guild", guildName));
        }
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
        return true;
    }

    public boolean kick(Player actor, Guild g, UUID targetId, String targetName) {
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
        }
        return true;
    }

    private void removeMember(Guild g, UUID uuid) {
        g.removeMember(uuid);
        memberIndex.remove(uuid);
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
            plugin.messages().send(actor, "promote-success", "player", targetName, "rank", GuildRank.LEADER.display());
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

    private void saveMember(GuildMember m) {
        plugin.async(() -> {
            try {
                plugin.repo().updateMember(m);
            } catch (Exception e) {
                plugin.getLogger().warning("保存成员失败: " + e.getMessage());
            }
        });
    }

    public void saveGuild(Guild g) {
        g.touch();
        plugin.async(() -> {
            try {
                plugin.repo().updateGuild(g);
                plugin.broadcastGuildChange(g.id());
            } catch (Exception e) {
                plugin.getLogger().warning("保存工会失败: " + e.getMessage());
            }
        });
    }

    // =====================================================================
    // 设置
    // =====================================================================

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
        if (!conf().bankEnabled) {
            plugin.messages().send(player, "bank-disabled");
            return false;
        }
        if (amount <= 0) {
            plugin.messages().send(player, "set-failed");
            return false;
        }
        if (!plugin.economy().has(player, amount)) {
            plugin.messages().send(player, "bank-money-insufficient");
            return false;
        }
        double tax = amount * conf().bankDepositTaxPercent / 100.0;
        long credited = (long) Math.floor(amount - tax);
        if (!plugin.economy().withdraw(player, amount)) {
            plugin.messages().send(player, "bank-money-insufficient");
            return false;
        }
        g.bank(g.bank() + credited);
        GuildMember m = g.member(player.getUniqueId());
        if (m != null) {
            m.addContributed(credited);
            saveMember(m);
        }
        saveGuild(g);
        plugin.messages().send(player, "bank-deposit",
                "amount", plugin.economy().format(credited), "balance", plugin.economy().format(g.bank()));
        notifyGuild(g.id(), plugin.messages().component("bank-notify",
                "player", player.getName(), "action", "存入", "amount", plugin.economy().format(credited)), player.getUniqueId());
        return true;
    }

    public boolean withdraw(Player player, Guild g, double amount) {
        if (!conf().bankEnabled) {
            plugin.messages().send(player, "bank-disabled");
            return false;
        }
        GuildMember m = g.member(player.getUniqueId());
        if (m == null || m.rank() != GuildRank.LEADER) {
            plugin.messages().send(player, "no-permission-rank");
            return false;
        }
        if (amount <= 0 || g.bank() < amount) {
            plugin.messages().send(player, "bank-insufficient");
            return false;
        }
        g.bank(g.bank() - (long) amount);
        plugin.economy().deposit(player, amount);
        saveGuild(g);
        plugin.messages().send(player, "bank-withdraw",
                "amount", plugin.economy().format(amount), "balance", plugin.economy().format(g.bank()));
        return true;
    }

    public void addPoints(Guild g, long delta) {
        if (g == null || delta == 0) {
            return;
        }
        g.points(Math.max(0, g.points() + delta));
        recomputeLevel(g);
        saveGuild(g);
    }

    public void recomputeLevel(Guild g) {
        GuildConfig c = conf();
        long cumulative = 0;
        int lvl = 1;
        while (lvl < c.maxLevel && g.points() >= cumulative + c.requiredPoints(lvl)) {
            cumulative += c.requiredPoints(lvl);
            lvl++;
        }
        g.level(lvl);
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

    private void notifyMember(UUID uuid, Component text) {
        Player p = plugin.player(uuid);
        if (p != null) {
            p.sendMessage(text);
        }
    }

    /** 收到跨服公告：投递给本服成员。 */
    public void deliverAnnounce(String guildId, String miniText, String except) {
        Guild g = guilds.get(guildId);
        if (g == null) {
            return;
        }
        Component text = Text.mm(miniText);
        UUID skip = null;
        if (except != null && !except.isBlank()) {
            try {
                skip = UUID.fromString(except);
            } catch (IllegalArgumentException ignored) {
            }
        }
        for (UUID u : g.memberMap().keySet()) {
            if (skip != null && skip.equals(u)) {
                continue;
            }
            Player p = plugin.player(u);
            if (p != null) {
                p.sendMessage(text);
            }
        }
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
