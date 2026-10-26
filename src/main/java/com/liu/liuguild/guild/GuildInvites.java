package com.liu.liuguild.guild;

import com.liu.liuguild.LiuGuild;
import com.liu.liuguild.config.GuildSettings;
import com.liu.liuguild.crossserver.CrossServerBus;
import com.liu.liuguild.model.Guild;
import com.liu.liuguild.model.GuildMember;
import com.liu.liuguild.model.GuildRank;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 入会邀请子系统：本地登记、跨服投递、接受/拒绝与过期清理。
 * <p>从 {@link GuildManager} 拆出，避免成员管理类继续膨胀；对外 API 仍由 GuildManager 代理。</p>
 */
final class GuildInvites {

    private final LiuGuild plugin;
    private final GuildManager guilds;
    /** 玩家 -> (工会 id -> 邀请)。每人可同时保留多个工会的邀请，接受时按工会名精确匹配。 */
    private final Map<UUID, Map<String, Invite>> invites = new ConcurrentHashMap<>();
    private final Map<UUID, Long> inviteCooldown = new ConcurrentHashMap<>();

    GuildInvites(LiuGuild plugin, GuildManager guilds) {
        this.plugin = plugin;
        this.guilds = guilds;
    }

    private GuildSettings conf() {
        return plugin.settings().guild;
    }

    void invite(Player actor, Guild g, String targetName) {
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
                    Map<String, String> payload = CrossServerBus.map(
                            "target", targetId.toString(), "guild", g.id(), "name", g.name(), "server", plugin.serverId());
                    plugin.async(() -> plugin.bus().publishTo(server, "GUILD_INVITE", payload));
                }
            });
        });
    }

    /** 完成邀请登记并通知邀请人（target 可为 null，表示目标是跨服玩家）。 */
    private void finishInvite(Player actor, Guild g, Player target, UUID targetId, String targetDisplay, long now) {
        if (guilds.isInGuild(targetId)) {
            Guild cur = guilds.guildOf(targetId);
            plugin.messages().send(actor, "already-in-guild", "guild", cur == null ? "" : cur.name());
            return;
        }
        long expires = now + conf().inviteExpireSeconds * 1000L;
        invites.computeIfAbsent(targetId, k -> new ConcurrentHashMap<>())
                .put(g.id(), new Invite(g.id(), expires));
        inviteCooldown.put(actor.getUniqueId(), now);
        if (target != null) {
            target.sendMessage(plugin.messages().component("invite-received", "guild", g.name()));
        }
        plugin.messages().send(actor, "invite-sent", "player", targetDisplay);
    }

    /** 收到跨服邀请时调用（在目标服的本地内存登记）。 */
    void receiveRemoteInvite(UUID target, String guildId, String guildName) {
        if (target == null || guildId == null) {
            return;
        }
        invites.computeIfAbsent(target, k -> new ConcurrentHashMap<>())
                .put(guildId, new Invite(guildId, System.currentTimeMillis() + conf().inviteExpireSeconds * 1000L));
        plugin.sync(() -> {
            Player p = plugin.player(target);
            if (p != null) {
                p.sendMessage(plugin.messages().component("invite-received", "guild", guildName == null ? "" : guildName));
            }
        });
    }

    boolean accept(Player player, String guildName) {
        Invite inv = select(player.getUniqueId(), guildName);
        if (inv == null) {
            plugin.messages().send(player, "invite-none");
            return false;
        }
        Guild g = guilds.guild(inv.guildId);
        if (g == null) {
            removeInvite(player.getUniqueId(), inv);
            plugin.messages().send(player, "guild-not-found", "name", guildName == null ? "" : guildName);
            return false;
        }
        // 写库失败/人数满时邀请仍然有效，玩家可以稍后重试；只有真正入会才消费。
        boolean joined = guilds.join(player, g, true);
        if (joined) {
            removeInvite(player.getUniqueId(), inv);
        }
        return joined;
    }

    void deny(Player player, String guildName) {
        Invite inv = select(player.getUniqueId(), guildName);
        Guild g = inv == null ? null : guilds.guild(inv.guildId);
        if (inv != null) {
            removeInvite(player.getUniqueId(), inv);
        }
        plugin.messages().send(player, "deny-success", "guild", g == null
                ? (guildName == null ? "" : guildName) : g.name());
    }

    /**
     * 按工会名/id 精确选择邀请；未指定时选最早到期的一条。
     * 这样聊天里的“接受 A”不会因为 B 后邀请而加入 B。
     */
    private Invite select(UUID player, String guildName) {
        Map<String, Invite> map = invites.get(player);
        if (map == null || map.isEmpty()) {
            return null;
        }
        long now = System.currentTimeMillis();
        map.entrySet().removeIf(e -> e.getValue().expiresAt <= now);
        if (map.isEmpty()) {
            invites.remove(player, map);
            return null;
        }
        if (guildName == null || guildName.isBlank()) {
            return map.values().stream().min(java.util.Comparator.comparingLong(i -> i.expiresAt)).orElse(null);
        }
        String wanted = guildName.trim();
        for (Invite inv : map.values()) {
            Guild g = guilds.guild(inv.guildId);
            if (inv.guildId.equalsIgnoreCase(wanted)
                    || (g != null && g.name().equalsIgnoreCase(wanted))
                    || (g != null && g.tag().equalsIgnoreCase(wanted))) {
                return inv;
            }
        }
        return null;
    }

    private void removeInvite(UUID player, Invite inv) {
        Map<String, Invite> map = invites.get(player);
        if (map == null) {
            return;
        }
        map.remove(inv.guildId, inv);
        if (map.isEmpty()) {
            invites.remove(player, map);
        }
    }

    List<GuildManager.PendingInvite> pendingInvites(UUID uuid) {
        List<GuildManager.PendingInvite> out = new ArrayList<>();
        if (uuid == null) {
            return out;
        }
        Map<String, Invite> map = invites.get(uuid);
        if (map == null || map.isEmpty()) {
            return out;
        }
        long now = System.currentTimeMillis();
        map.entrySet().removeIf(e -> e.getValue().expiresAt <= now);
        if (map.isEmpty()) {
            invites.remove(uuid, map);
            return out;
        }
        for (Invite inv : map.values()) {
            Guild g = guilds.guild(inv.guildId);
            out.add(new GuildManager.PendingInvite(inv.guildId, g == null ? inv.guildId : g.name(), inv.expiresAt));
        }
        out.sort(java.util.Comparator.comparingLong(GuildManager.PendingInvite::expiresAt));
        return out;
    }

    /** 玩家成功入会后清掉其待处理邀请。 */
    void clear(UUID uuid) {
        if (uuid != null) {
            invites.remove(uuid);
        }
    }

    /** 清理解除过期邀请与冷却记录（心跳调用，防止内存泄漏）。 */
    void purge() {
        long now = System.currentTimeMillis();
        invites.entrySet().removeIf(e -> {
            e.getValue().entrySet().removeIf(inv -> inv.getValue().expiresAt < now);
            return e.getValue().isEmpty();
        });
        long cooldownMs = Math.max(1, conf().inviteCooldownSeconds) * 1000L;
        inviteCooldown.entrySet().removeIf(e -> now - e.getValue() > cooldownMs * 4);
    }

    private static final class Invite {
        final String guildId;
        final long expiresAt;

        Invite(String guildId, long expiresAt) {
            this.guildId = guildId;
            this.expiresAt = expiresAt;
        }
    }
}
