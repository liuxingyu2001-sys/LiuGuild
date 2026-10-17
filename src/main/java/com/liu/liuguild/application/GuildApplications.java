package com.liu.liuguild.application;

import com.liu.liuguild.LiuGuild;
import com.liu.liuguild.api.event.GuildApplicationEvent;
import com.liu.liuguild.db.GuildApplication;
import com.liu.liuguild.db.GuildApplicationRepository;
import com.liu.liuguild.model.Guild;
import com.liu.liuguild.model.GuildMember;
import com.liu.liuguild.model.GuildRank;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** 工会入会申请：提交、官员审批、取消与通知。 */
public final class GuildApplications {

    private final LiuGuild plugin;
    private final GuildApplicationRepository repo;

    public GuildApplications(LiuGuild plugin) {
        this.plugin = plugin;
        this.repo = new GuildApplicationRepository(plugin.db());
    }

    public boolean enabled() {
        return plugin.settings().guild.applicationEnabled;
    }

    /** 提交申请；guild.open 时由调用方直接 join，本方法只处理非开放工会。 */
    public void apply(Player player, Guild guild, String message) {
        if (!enabled()) {
            plugin.messages().send(player, "app-disabled");
            return;
        }
        if (player == null || guild == null) {
            return;
        }
        if (plugin.guilds().guildOf(player.getUniqueId()) != null) {
            plugin.messages().send(player, "app-already-in-guild");
            return;
        }
        String text = message == null ? "" : message.trim();
        int max = Math.max(1, plugin.settings().guild.applicationMessageMax);
        if (text.length() > max) {
            text = text.substring(0, max);
        }
        final String note = text;
        plugin.async(() -> {
            try {
                int pending = repo.countPending(player.getUniqueId());
                if (pending >= Math.max(1, plugin.settings().guild.applicationMaxPending)) {
                    plugin.sync(() -> plugin.messages().send(player, "app-too-many"));
                    return;
                }
                for (GuildApplication a : repo.pendingForPlayer(player.getUniqueId())) {
                    if (a.guildId().equals(guild.id())) {
                        plugin.sync(() -> plugin.messages().send(player, "app-already-pending"));
                        return;
                    }
                }
                long now = System.currentTimeMillis();
                GuildApplication app = new GuildApplication(UUID.randomUUID().toString(), guild.id(),
                        player.getUniqueId(), player.getName(), note, "PENDING", now, now);
                repo.insert(app);
                plugin.sync(() -> {
                    plugin.messages().send(player, "app-sent", "guild", guild.name());
                    notifyOfficers(guild, app);
                    if (plugin.audit() != null) {
                        plugin.audit().record(guild.id(), player.getUniqueId(), player.getName(),
                                "application.submit", app.id(), 0, note);
                    }
                    if (plugin.webhook() != null) {
                        plugin.webhook().application(guild, player.getName(), note);
                    }
                    Bukkit.getPluginManager().callEvent(new GuildApplicationEvent(guild, player,
                            GuildApplicationEvent.Action.SUBMIT, app.id()));
                });
            } catch (Exception e) {
                plugin.getLogger().warning("提交入会申请失败: " + e.getMessage());
                plugin.sync(() -> plugin.messages().send(player, "app-failed"));
            }
        });
    }

    private void notifyOfficers(Guild guild, GuildApplication app) {
        List<com.liu.liuguild.db.MailEntry> mails = new ArrayList<>();
        boolean anyOnline = false;
        for (GuildMember m : guild.memberMap().values()) {
            if (m.rank() != GuildRank.LEADER && m.rank() != GuildRank.OFFICER) {
                continue;
            }
            Player officer = plugin.player(m.uuid());
            if (officer != null) {
                anyOnline = true;
                plugin.messages().send(officer, "app-notify",
                        "player", app.playerName(), "guild", guild.name(), "message", app.message());
            } else if (plugin.mail() != null) {
                mails.add(plugin.mail().build(m.uuid(), guild.id(), "application.new",
                        "新的入会申请", app.playerName() + " 申请加入 " + guild.name()
                                + (app.message().isBlank() ? "" : "：" + app.message()),
                        14L * 24L * 3600L * 1000L));
            }
        }
        if (!mails.isEmpty()) {
            plugin.mail().sendMany(mails);
        }
        if (!anyOnline) {
            plugin.getLogger().info("工会 " + guild.name() + " 收到入会申请（官员均离线，已写入邮件）: " + app.playerName());
        }
    }

    public void listAsync(String guildId, Consumer<List<GuildApplication>> callback) {
        plugin.async(() -> {
            List<GuildApplication> list;
            try {
                list = repo.pendingForGuild(guildId, 50);
            } catch (Exception e) {
                plugin.getLogger().warning("读取入会申请失败: " + e.getMessage());
                list = List.of();
            }
            final List<GuildApplication> result = list;
            plugin.sync(() -> callback.accept(result));
        });
    }

    /** 按 id 前缀或 1 起序号审批；approve=true 批准，false 拒绝。 */
    public void decide(Player officer, Guild guild, String idOrIndex, boolean approve) {
        if (officer == null || guild == null) {
            return;
        }
        GuildMember actor = guild.member(officer.getUniqueId());
        if (actor == null || !actor.rank().atLeast(GuildRank.OFFICER)) {
            plugin.messages().send(officer, "no-permission-rank");
            return;
        }
        plugin.async(() -> {
            GuildApplication target = null;
            try {
                List<GuildApplication> list = repo.pendingForGuild(guild.id(), 50);
                target = find(list, idOrIndex);
            } catch (Exception e) {
                plugin.getLogger().warning("读取待审批申请失败: " + e.getMessage());
            }
            final GuildApplication app = target;
            plugin.sync(() -> {
                if (app == null) {
                    plugin.messages().send(officer, "app-not-found");
                    return;
                }
                if (approve) {
                    approve(officer, guild, app);
                } else {
                    reject(officer, guild, app);
                }
            });
        });
    }

    private GuildApplication find(List<GuildApplication> list, String idOrIndex) {
        if (idOrIndex == null || idOrIndex.isBlank()) {
            return null;
        }
        try {
            int idx = Integer.parseInt(idOrIndex);
            if (idx >= 1 && idx <= list.size()) {
                return list.get(idx - 1);
            }
        } catch (NumberFormatException ignored) {
        }
        for (GuildApplication a : list) {
            if (a.id().startsWith(idOrIndex)) {
                return a;
            }
        }
        return null;
    }

    private void approve(Player officer, Guild guild, GuildApplication app) {
        if (guild.memberCount() >= plugin.settings().guild.memberLimit(guild.level())) {
            plugin.messages().send(officer, "app-full", "guild", guild.name());
            return;
        }
        Player applicant = plugin.player(app.player());
        if (applicant == null || !applicant.isOnline()) {
            plugin.messages().send(officer, "app-offline", "player", app.playerName());
            return;
        }
        plugin.async(() -> {
            try {
                if (!repo.decide(app.id(), "APPROVED")) {
                    plugin.sync(() -> plugin.messages().send(officer, "app-not-found"));
                    return;
                }
                plugin.sync(() -> {
                    if (!plugin.guilds().join(applicant, guild, true)) {
                        plugin.messages().send(officer, "app-failed");
                        plugin.async(() -> {
                            try {
                                repo.reopen(app.id());
                            } catch (Exception ex) {
                                plugin.getLogger().warning("恢复入会申请状态失败: " + ex.getMessage());
                            }
                        });
                        return;
                    }
                    plugin.messages().send(officer, "app-approved", "player", app.playerName());
                    if (plugin.mail() != null) {
                        plugin.mail().send(app.player(), guild.id(), "application.result",
                                "入会申请已通过", guild.name() + " 已通过你的入会申请。",
                                30L * 24L * 3600L * 1000L);
                    }
                    if (plugin.audit() != null) {
                        plugin.audit().record(guild.id(), officer.getUniqueId(), officer.getName(),
                                "application.approve", app.id(), 0, app.playerName());
                    }
                    Bukkit.getPluginManager().callEvent(new GuildApplicationEvent(guild, officer,
                            GuildApplicationEvent.Action.APPROVE, app.id()));
                });
            } catch (Exception e) {
                plugin.getLogger().warning("批准入会申请失败: " + e.getMessage());
                plugin.sync(() -> plugin.messages().send(officer, "app-failed"));
            }
        });
    }

    private void reject(Player officer, Guild guild, GuildApplication app) {
        plugin.async(() -> {
            try {
                boolean ok = repo.decide(app.id(), "REJECTED");
                plugin.sync(() -> {
                    if (!ok) {
                        plugin.messages().send(officer, "app-not-found");
                        return;
                    }
                    plugin.messages().send(officer, "app-rejected", "player", app.playerName());
                    if (plugin.audit() != null) {
                        plugin.audit().record(guild.id(), officer.getUniqueId(), officer.getName(),
                                "application.reject", app.id(), 0, app.playerName());
                    }
                    if (plugin.mail() != null) {
                        plugin.mail().send(app.player(), guild.id(), "application.result",
                                "入会申请未通过", guild.name() + " 拒绝了你的入会申请。",
                                30L * 24L * 3600L * 1000L);
                    }
                    Bukkit.getPluginManager().callEvent(new GuildApplicationEvent(guild, officer,
                            GuildApplicationEvent.Action.REJECT, app.id()));
                });
            } catch (Exception e) {
                plugin.getLogger().warning("拒绝入会申请失败: " + e.getMessage());
                plugin.sync(() -> plugin.messages().send(officer, "app-failed"));
            }
        });
    }

    public void cancel(Player player) {
        if (player == null) {
            return;
        }
        plugin.async(() -> {
            try {
                int changed = repo.cancelPendingForPlayer(player.getUniqueId());
                plugin.sync(() -> {
                    if (changed > 0) {
                        plugin.messages().send(player, "app-cancelled");
                    } else {
                        plugin.messages().send(player, "app-cancel-none");
                    }
                });
            } catch (Exception e) {
                plugin.getLogger().warning("取消入会申请失败: " + e.getMessage());
                plugin.sync(() -> plugin.messages().send(player, "app-failed"));
            }
        });
    }

    /** 玩家加入其它工会/退会时清理其待处理申请。 */
    public void clearForPlayer(UUID player) {
        if (player == null) {
            return;
        }
        plugin.async(() -> {
            try {
                repo.cancelPendingForPlayer(player);
            } catch (Exception e) {
                plugin.getLogger().warning("清理待处理入会申请失败: " + e.getMessage());
            }
        });
    }

    public void clearForGuild(String guildId) {
        if (guildId == null) {
            return;
        }
        plugin.async(() -> {
            try {
                repo.cancelPendingForGuild(guildId);
            } catch (Exception e) {
                plugin.getLogger().warning("清理工会待审批申请失败: " + e.getMessage());
            }
        });
    }

    public void purgeAsync(long before) {
        plugin.async(() -> {
            try {
                int removed = repo.purgeBefore(before);
                if (removed > 0) {
                    plugin.getLogger().info("已清理过期入会申请 " + removed + " 条");
                }
            } catch (Exception e) {
                plugin.getLogger().warning("清理入会申请失败: " + e.getMessage());
            }
        });
    }
}
