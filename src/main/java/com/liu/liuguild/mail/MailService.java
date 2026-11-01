package com.liu.liuguild.mail;

import com.liu.liuguild.LiuGuild;
import com.liu.liuguild.config.GuildSettings;
import com.liu.liuguild.db.MailEntry;
import com.liu.liuguild.db.MailRepository;
import com.liu.liuguild.hook.LiuChatMailHook;
import com.liu.liuguild.model.Guild;
import com.liu.liuguild.model.GuildMember;
import com.liu.liuguild.model.GuildRank;
import com.liu.liuguild.util.Text;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** 工会系统邮件：离线通知、登录摘要与会长/官员群发。 */
public final class MailService {

    public static final long TTL_WAR = 14L * 24L * 3600L * 1000L;

    /** 会长/官员手动群发的邮件类型（与系统自动邮件区分）。 */
    public static final String TYPE_BROADCAST = "guild.broadcast";

    private static final long DAY_MILLIS = 24L * 3600L * 1000L;
    /** 群发冷却记录超过该数量时顺手清掉早已过期的工会，避免长期运行累积。 */
    private static final int COOLDOWN_MAP_SOFT_LIMIT = 512;

    /** 群发结果：命令与 GUI 共用，避免两处各写一套校验。 */
    public enum BroadcastResult {
        SENT, DISABLED, NO_GUILD, NO_PERMISSION, COOLDOWN,
        EMPTY_TITLE, EMPTY_BODY, TITLE_TOO_LONG, BODY_TOO_LONG, INVALID_FORMAT, NO_MEMBERS
    }

    private final LiuGuild plugin;
    private final MailRepository repo;
    private final LiuChatMailHook liuChatMail;
    /** guildId → 上次群发时间（毫秒），用于工会级冷却。 */
    private final Map<String, Long> lastBroadcastAt = new ConcurrentHashMap<>();

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

    // =====================================================================
    // 会长/官员群发
    // =====================================================================

    /**
     * 静默判断玩家是否可以向该工会群发邮件（用于 GUI 按钮显隐，不发任何提示）。
     * 真正的发送仍由 {@link #broadcast} 再校验一次，避免 GUI 状态过期。
     */
    public boolean canBroadcast(Player player, Guild guild) {
        if (player == null || guild == null || plugin.settings() == null) {
            return false;
        }
        GuildSettings conf = plugin.settings().guild;
        if (!conf.mailBroadcastEnabled || !player.hasPermission("liuguild.mail.send")) {
            return false;
        }
        GuildMember member = guild.member(player.getUniqueId());
        if (member == null) {
            return false;
        }
        return member.rank() == GuildRank.LEADER
                || (member.rank() == GuildRank.OFFICER && conf.officerSendMail);
    }

    /** 标题校验（纯文本标题，长度按原始输入计，含标签）。null = 合法。 */
    public static BroadcastResult validateTitle(String title, int maxLength) {
        if (Text.clean(title).isEmpty()) {
            return BroadcastResult.EMPTY_TITLE;
        }
        if (title.length() > maxLength) {
            return BroadcastResult.TITLE_TOO_LONG;
        }
        return null;
    }

    /** 正文校验（允许 MiniMessage 格式，长度按原始输入计，含标签）。null = 合法。 */
    public static BroadcastResult validateBody(String body, int maxLength) {
        if (body == null || body.isBlank()) {
            return BroadcastResult.EMPTY_BODY;
        }
        if (!Text.isValidMini(body)) {
            return BroadcastResult.INVALID_FORMAT;
        }
        if (body.length() > maxLength) {
            return BroadcastResult.BODY_TOO_LONG;
        }
        return null;
    }

    /** 纯函数：按工会级冷却计算还需等待的秒数（0 = 可以发送）。 */
    public static long cooldownRemainingSeconds(long lastMillis, long nowMillis, int cooldownSeconds) {
        if (lastMillis <= 0 || cooldownSeconds <= 0) {
            return 0;
        }
        long remain = cooldownSeconds * 1000L - Math.max(0, nowMillis - lastMillis);
        return remain <= 0 ? 0 : (remain + 999) / 1000;
    }

    /**
     * 会长/官员向本会全员群发工会邮件（含离线成员）。
     *
     * <p>邮件走 {@link #sendMany}：LiuChat 可用时交给其 MailApi，否则回退内置
     * {@code guild_mail} 表；标题按纯文本保存，正文允许 MiniMessage 并清除交互事件。</p>
     */
    public BroadcastResult broadcast(Player sender, Guild guild, String rawTitle, String rawBody) {
        if (sender == null || guild == null) {
            if (sender != null) {
                plugin.messages().send(sender, "not-in-guild");
            }
            return BroadcastResult.NO_GUILD;
        }
        GuildSettings conf = plugin.settings().guild;
        if (!conf.mailBroadcastEnabled) {
            plugin.messages().send(sender, "mail-send-disabled");
            return BroadcastResult.DISABLED;
        }
        if (!sender.hasPermission("liuguild.mail.send")) {
            plugin.messages().send(sender, "no-permission");
            return BroadcastResult.NO_PERMISSION;
        }
        GuildMember member = guild.member(sender.getUniqueId());
        if (member == null) {
            plugin.messages().send(sender, "not-in-guild");
            return BroadcastResult.NO_GUILD;
        }
        if (member.rank() != GuildRank.LEADER
                && !(member.rank() == GuildRank.OFFICER && conf.officerSendMail)) {
            plugin.messages().send(sender, "no-permission-rank");
            return BroadcastResult.NO_PERMISSION;
        }
        String title = rawTitle == null ? "" : rawTitle.trim();
        String body = rawBody == null ? "" : rawBody.trim();
        BroadcastResult invalid = validateTitle(title, conf.mailMaxTitleLength);
        if (invalid == null) {
            invalid = validateBody(body, conf.mailMaxBodyLength);
        }
        if (invalid != null) {
            notifyInvalid(sender, invalid, invalid == BroadcastResult.TITLE_TOO_LONG
                    ? conf.mailMaxTitleLength : conf.mailMaxBodyLength);
            return invalid;
        }
        long now = System.currentTimeMillis();
        long remain = cooldownRemainingSeconds(lastBroadcastAt.getOrDefault(guild.id(), 0L), now,
                conf.mailBroadcastCooldownSeconds);
        if (remain > 0) {
            plugin.messages().send(sender, "mail-send-cooldown", "seconds", String.valueOf(remain));
            return BroadcastResult.COOLDOWN;
        }
        long ttl = conf.mailTtlDays > 0 ? conf.mailTtlDays * DAY_MILLIS : 0;
        String mailTitle = "【工会邮件】" + normalizeTitle(title);
        String mailBody = sender.getName() + "：" + normalizeBody(body);
        List<MailEntry> entries = new ArrayList<>(guild.memberMap().size());
        for (GuildMember target : guild.memberMap().values()) {
            entries.add(entry(target.uuid(), guild.id(), TYPE_BROADCAST, mailTitle, mailBody, ttl));
        }
        if (entries.isEmpty()) {
            plugin.messages().send(sender, "mail-send-no-members");
            return BroadcastResult.NO_MEMBERS;
        }
        lastBroadcastAt.put(guild.id(), now);
        purgeCooldownCache(now, conf.mailBroadcastCooldownSeconds);
        sendMany(entries);
        plugin.messages().send(sender, "mail-send-success",
                "count", String.valueOf(entries.size()), "title", normalizeTitle(title));
        if (plugin.audit() != null) {
            plugin.audit().record(guild.id(), sender.getUniqueId(), sender.getName(),
                    "mail.broadcast", normalizeTitle(title), 0, "recipients=" + entries.size());
        }
        return BroadcastResult.SENT;
    }

    /** 把内容校验失败翻译成玩家提示（GUI 在标题输入后立即调用，避免填完正文才发现问题）。 */
    public void notifyInvalid(Player player, BroadcastResult result, int maxLength) {
        if (player == null || result == null) {
            return;
        }
        switch (result) {
            case EMPTY_TITLE -> plugin.messages().send(player, "mail-send-title-empty");
            case EMPTY_BODY -> plugin.messages().send(player, "mail-send-body-empty");
            case TITLE_TOO_LONG, BODY_TOO_LONG ->
                    plugin.messages().send(player, "input-too-long", "max", String.valueOf(maxLength));
            case INVALID_FORMAT -> plugin.messages().send(player, "input-format-invalid");
            case DISABLED -> plugin.messages().send(player, "mail-send-disabled");
            case NO_MEMBERS -> plugin.messages().send(player, "mail-send-no-members");
            case NO_PERMISSION -> plugin.messages().send(player, "no-permission-rank");
            case NO_GUILD -> plugin.messages().send(player, "not-in-guild");
            case COOLDOWN, SENT -> {
                // 由 broadcast 自己发送更具体的提示，这里无需重复。
            }
        }
    }

    /** 标题走纯文本：避免占位符转义后把 MiniMessage 标签原样显示给收件人。 */
    private static String normalizeTitle(String title) {
        return Text.clean(title);
    }

    /** 正文允许颜色/装饰，但清除点击/悬浮/插入等交互事件。 */
    private static String normalizeBody(String body) {
        return Text.sanitizeToMini(body);
    }

    private void purgeCooldownCache(long now, int cooldownSeconds) {
        if (lastBroadcastAt.size() <= COOLDOWN_MAP_SOFT_LIMIT) {
            return;
        }
        long keep = Math.max(60_000L, (long) Math.max(cooldownSeconds, 60) * 1000L);
        lastBroadcastAt.entrySet().removeIf(e -> now - e.getValue() > keep);
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
