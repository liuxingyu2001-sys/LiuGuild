package com.liu.liuguild.mail;

import com.liu.liuguild.LiuGuild;
import com.liu.liuguild.db.MailEntry;
import com.liu.liuguild.db.MailRepository;
import com.liu.liuguild.hook.LiuChatMailHook;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** 工会系统邮件：离线通知与登录摘要。 */
public final class MailService {

    public static final long TTL_WAR = 14L * 24L * 3600L * 1000L;

    private final LiuGuild plugin;
    private final MailRepository repo;
    private final LiuChatMailHook liuChatMail;

    public MailService(LiuGuild plugin) {
        this.plugin = plugin;
        this.repo = new MailRepository(plugin.db());
        this.liuChatMail = new LiuChatMailHook(plugin);
    }

    /** 在 LiuChat 启用后调用；失败时继续使用内置邮件表。 */
    public void startExternal() {
        liuChatMail.start();
    }

    public boolean externalAvailable() {
        return liuChatMail.available();
    }

    public void send(UUID recipient, String guildId, String type, String title, String body) {
        send(recipient, guildId, type, title, body, 30L * 24L * 3600L * 1000L);
    }

    public void send(UUID recipient, String guildId, String type, String title, String body, long ttlMillis) {
        if (recipient == null) {
            return;
        }
        List<MailEntry> one = new ArrayList<>(1);
        one.add(entry(recipient, guildId, type, title, body, ttlMillis));
        sendMany(one);
    }

    /** 批量发送；一次异步批量 INSERT。 */
    public void sendMany(Collection<MailEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        List<MailEntry> copy = new ArrayList<>(entries);
        if (liuChatMail.available()) {
            for (MailEntry entry : copy) {
                liuChatMail.send(entry, ok -> {
                    if (!ok) {
                        plugin.getLogger().warning("工会邮件未写入 LiuChat，回退内置邮件表: " + entry.recipient());
                        plugin.async(() -> {
                            try {
                                repo.insertMany(List.of(entry));
                            } catch (Exception e) {
                                plugin.getLogger().warning("回退写入内置邮件失败: " + e.getMessage());
                            }
                        });
                    }
                });
            }
            return;
        }
        plugin.async(() -> {
            try {
                repo.insertMany(copy);
            } catch (Exception e) {
                plugin.getLogger().warning("发送工会邮件失败: " + e.getMessage());
            }
        });
    }

    public void sendToGuild(String guildId, String type, String title, String body, long ttlMillis) {
        if (guildId == null) {
            return;
        }
        var guild = plugin.guilds().guild(guildId);
        if (guild == null) {
            return;
        }
        List<MailEntry> list = new ArrayList<>();
        for (var member : guild.memberMap().values()) {
            list.add(entry(member.uuid(), guildId, type, title, body, ttlMillis));
        }
        sendMany(list);
    }

    /** 供业务构造专属邮件（如战报按阵营不同）。 */
    public MailEntry build(UUID recipient, String guildId, String type, String title, String body, long ttlMillis) {
        return entry(recipient, guildId, type, title, body, ttlMillis);
    }

    private MailEntry entry(UUID recipient, String guildId, String type, String title, String body, long ttlMillis) {
        long now = System.currentTimeMillis();
        return new MailEntry(UUID.randomUUID().toString(), guildId, recipient, type,
                trim(title, 128), trim(body, 512), now, 0, ttlMillis > 0 ? now + ttlMillis : 0);
    }

    /** 登录时提示未读数；异步查询，回主线程发送。 */
    public void notifyOnJoin(Player player) {
        if (player == null) {
            return;
        }
        UUID uuid = player.getUniqueId();
        // 代理切服也会触发 PlayerJoinEvent；先等 presence / Quit 标记落库，再消费
        // “刚离开其他子服”标记，避免同一封未读邮件每切一次服就提醒一次。
        plugin.syncDelayed(() -> {
            if (!player.isOnline()) {
                return;
            }
            plugin.async(() -> {
                if (plugin.bus() != null && plugin.bus().consumeRecentCrossServerLeave(uuid)) {
                    return;
                }
                plugin.sync(() -> {
                    if (player.isOnline()) {
                        notifyOnJoinNow(player);
                    }
                });
            });
        }, 10L);
    }

    private void notifyOnJoinNow(Player player) {
        UUID uuid = player.getUniqueId();
        if (liuChatMail.available()) {
            liuChatMail.inbox(uuid, list -> plugin.sync(() -> {
                int unread = 0;
                for (MailEntry entry : list) {
                    if (entry.readAt() == 0) {
                        unread++;
                    }
                }
                if (unread > 0 && player.isOnline()) {
                    plugin.messages().send(player, "mail-login-summary", "count", String.valueOf(unread));
                }
            }));
            return;
        }
        plugin.async(() -> {
            int unread;
            try {
                unread = repo.unreadCount(uuid);
            } catch (Exception e) {
                return;
            }
            if (unread <= 0) {
                return;
            }
            plugin.sync(() -> {
                if (player.isOnline()) {
                    plugin.messages().send(player, "mail-login-summary", "count", String.valueOf(unread));
                }
            });
        });
    }

    public void inboxAsync(UUID recipient, int limit, Consumer<List<MailEntry>> callback) {
        if (liuChatMail.available()) {
            liuChatMail.inbox(recipient, list ->
                    plugin.sync(() -> callback.accept(list.stream().limit(limit).toList())));
            return;
        }
        plugin.async(() -> {
            List<MailEntry> list;
            try {
                list = repo.inbox(recipient, limit);
            } catch (Exception e) {
                plugin.getLogger().warning("读取工会邮件失败: " + e.getMessage());
                list = List.of();
            }
            final List<MailEntry> result = list;
            plugin.sync(() -> callback.accept(result));
        });
    }

    public void markReadAsync(UUID recipient, String id) {
        if (liuChatMail.available()) {
            liuChatMail.markRead(recipient, id);
            return;
        }
        plugin.async(() -> {
            try {
                repo.markRead(recipient, id);
            } catch (Exception e) {
                plugin.getLogger().warning("标记邮件已读失败: " + e.getMessage());
            }
        });
    }

    public void markAllReadAsync(UUID recipient) {
        if (liuChatMail.available()) {
            liuChatMail.markAllRead(recipient);
            return;
        }
        plugin.async(() -> {
            try {
                repo.markAllRead(recipient);
            } catch (Exception e) {
                plugin.getLogger().warning("清空邮件未读失败: " + e.getMessage());
            }
        });
    }

    public void purgeAsync() {
        plugin.async(() -> {
            try {
                int removed = repo.purge(System.currentTimeMillis());
                if (removed > 0) {
                    plugin.getLogger().info("已清理过期工会邮件 " + removed + " 封");
                }
            } catch (Exception e) {
                plugin.getLogger().warning("清理工会邮件失败: " + e.getMessage());
            }
        });
    }

    private static String trim(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
