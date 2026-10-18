package com.liu.liuguild.hook;

import com.liu.liuguild.LiuGuild;
import com.liu.liuguild.db.MailEntry;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 通过 LiuChat 的公开 {@code MailApi} 投递工会系统邮件。
 *
 * <p>LiuGuild 不把 LiuChat 作为编译期依赖：LiuChat 缺失或 API 版本不匹配时
 * {@link #available()} 为 false，{@code MailService} 自动回退到内置邮件表。</p>
 *
 * <p>系统邮件没有真实发件人。每封邮件生成一个独立发件人 UUID，
 * 避免 LiuChat 的「同一发件人发送冷却」把批量战报/申请通知吞掉；
 * 展示时统一使用「工会系统」作为发件人名称，读取时也按该名称过滤。</p>
 */
public final class LiuChatMailHook {
    public static final String SENDER_NAME = "工会系统";

    private final LiuGuild plugin;
    private volatile Object api;
    private Class<?> apiType;
    private Class<?> draftType;
    private Class<?> pageType;
    private Class<?> mailType;
    private Method sendMethod;
    private Method inboxMethod;
    private Method markReadMethod;
    private Constructor<?> draftConstructor;
    private Method pageMails;
    private Method pageHasNext;
    private Method mailId;
    private Method mailSenderName;
    private Method mailSubject;
    private Method mailBody;
    private Method mailCreatedAt;
    private Method mailExpiresAt;
    private Method mailRead;

    public LiuChatMailHook(LiuGuild plugin) {
        this.plugin = plugin;
    }

    /** Called after LiuChat has been enabled. */
    public void start() {
        Plugin dependency = Bukkit.getPluginManager().getPlugin("LiuChat");
        if (dependency == null || !dependency.isEnabled()) {
            return;
        }
        try {
            ClassLoader loader = dependency.getClass().getClassLoader();
            apiType = loader.loadClass("com.liu.liuchat.api.MailApi");
            draftType = loader.loadClass("com.liu.liuchat.api.MailDraft");
            pageType = loader.loadClass("com.liu.liuchat.api.MailPage");
            mailType = loader.loadClass("com.liu.liuchat.api.Mail");
            api = dependency.getClass().getMethod("getMailApi").invoke(dependency);
            if (api == null || !Boolean.TRUE.equals(apiType.getMethod("isEnabled").invoke(api))) {
                api = null;
                return;
            }
            sendMethod = apiType.getMethod("send", draftType);
            inboxMethod = apiType.getMethod("inbox", UUID.class, int.class);
            markReadMethod = apiType.getMethod("markRead", UUID.class, String.class);
            draftConstructor = draftType.getConstructor(UUID.class, String.class, UUID.class,
                    String.class, String.class, String.class, List.class);
            pageMails = pageType.getMethod("mails");
            pageHasNext = pageType.getMethod("hasNext");
            mailId = mailType.getMethod("id");
            mailSenderName = mailType.getMethod("senderName");
            mailSubject = mailType.getMethod("subject");
            mailBody = mailType.getMethod("body");
            mailCreatedAt = mailType.getMethod("createdAt");
            mailExpiresAt = mailType.getMethod("expiresAt");
            mailRead = mailType.getMethod("read");
        } catch (ReflectiveOperationException | RuntimeException e) {
            api = null;
            plugin.getLogger().warning("LiuChat 邮件 API 不可用，工会邮件回退到内置存储: " + e.getMessage());
        }
    }

    public boolean available() {
        return api != null;
    }

    public void send(MailEntry entry, Consumer<Boolean> callback) {
        if (!available() || entry == null) {
            callback.accept(false);
            return;
        }
        try {
            UUID senderId = UUID.nameUUIDFromBytes(
                    ("liuguild-mail:" + entry.id()).getBytes(StandardCharsets.UTF_8));
            Object draft = draftConstructor.newInstance(senderId, SENDER_NAME, entry.recipient(), "",
                    entry.title(), entry.body(), List.of());
            CompletableFuture<?> future = (CompletableFuture<?>) sendMethod.invoke(api, draft);
            future.whenComplete((result, error) -> {
                boolean ok = error == null && result != null && "OK".equals(String.valueOf(result));
                if (!ok) {
                    plugin.getLogger().warning("LiuChat 邮件投递失败(" + entry.recipient() + "): "
                            + (error == null ? result : error.getMessage()));
                }
                callback.accept(ok);
            });
        } catch (ReflectiveOperationException | RuntimeException e) {
            plugin.getLogger().warning("LiuChat 邮件投递失败(" + entry.recipient() + "): " + e.getMessage());
            callback.accept(false);
        }
    }

    /** 只返回由工会系统发出的邮件，避免把玩家私信显示进工会邮箱。 */
    public void inbox(UUID recipient, Consumer<List<MailEntry>> callback) {
        if (!available()) {
            callback.accept(List.of());
            return;
        }
        inboxPage(recipient, 1, new ArrayList<>(), callback);
    }

    /**
     * LiuChat 的 inbox 分页包含玩家私信；系统邮件可能被大量私信挤到后面的页。
     * 因此逐页收集并按发件人过滤，而不是只看第一页。
     */
    private void inboxPage(UUID recipient, int page, List<MailEntry> collected,
                           Consumer<List<MailEntry>> callback) {
        if (!available() || page > 200) {
            finishInbox(collected, callback);
            return;
        }
        try {
            CompletableFuture<?> future = (CompletableFuture<?>) inboxMethod.invoke(api, recipient, page);
            future.whenComplete((result, error) -> {
                if (error != null || result == null) {
                    if (error != null) {
                        plugin.getLogger().warning("读取 LiuChat 邮件失败: " + error.getMessage());
                    }
                    finishInbox(collected, callback);
                    return;
                }
                boolean more = false;
                try {
                    Object raw = pageMails.invoke(result);
                    if (raw instanceof Iterable<?> iterable) {
                        for (Object mail : iterable) {
                            if (mail == null || !SENDER_NAME.equals(mailSenderName.invoke(mail))) {
                                continue;
                            }
                            boolean read = Boolean.TRUE.equals(mailRead.invoke(mail));
                            collected.add(new MailEntry(
                                    String.valueOf(mailId.invoke(mail)),
                                    "",
                                    recipient,
                                    "liuchat",
                                    String.valueOf(mailSubject.invoke(mail)),
                                    String.valueOf(mailBody.invoke(mail)),
                                    ((Number) mailCreatedAt.invoke(mail)).longValue(),
                                    read ? 1L : 0L,
                                    ((Number) mailExpiresAt.invoke(mail)).longValue()));
                        }
                    }
                    more = Boolean.TRUE.equals(pageHasNext.invoke(result));
                } catch (ReflectiveOperationException | RuntimeException e) {
                    plugin.getLogger().warning("读取 LiuChat 邮件失败: " + e.getMessage());
                }
                if (more) {
                    inboxPage(recipient, page + 1, collected, callback);
                } else {
                    finishInbox(collected, callback);
                }
            });
        } catch (ReflectiveOperationException | RuntimeException e) {
            plugin.getLogger().warning("读取 LiuChat 邮件失败: " + e.getMessage());
            finishInbox(collected, callback);
        }
    }

    private void finishInbox(List<MailEntry> collected, Consumer<List<MailEntry>> callback) {
        List<MailEntry> result = List.copyOf(collected);
        plugin.sync(() -> callback.accept(result));
    }

    public void markRead(UUID recipient, String id) {
        if (!available() || id == null) {
            return;
        }
        try {
            CompletableFuture<?> future = (CompletableFuture<?>) markReadMethod.invoke(api, recipient, id);
            future.whenComplete((result, error) -> {
                if (error == null && result != null && "NOT_FOUND".equals(String.valueOf(result))) {
                    return;
                }
                if (error != null) {
                    plugin.getLogger().warning("标记 LiuChat 邮件已读失败: " + error.getMessage());
                }
            });
        } catch (ReflectiveOperationException | RuntimeException e) {
            plugin.getLogger().warning("标记 LiuChat 邮件已读失败: " + e.getMessage());
        }
    }

    /** LiuChat 没有批量已读 API，逐页处理系统邮件。 */
    public void markAllRead(UUID recipient) {
        markAllRead(recipient, 1);
    }

    private void markAllRead(UUID recipient, int page) {
        if (!available() || page > 200) {
            return;
        }
        try {
            CompletableFuture<?> future = (CompletableFuture<?>) inboxMethod.invoke(api, recipient, page);
            future.whenComplete((result, error) -> {
                if (error != null || result == null) {
                    return;
                }
                try {
                    Object raw = pageMails.invoke(result);
                    if (raw instanceof Iterable<?> iterable) {
                        for (Object mail : iterable) {
                            if (mail != null && SENDER_NAME.equals(mailSenderName.invoke(mail))
                                    && !Boolean.TRUE.equals(mailRead.invoke(mail))) {
                                markRead(recipient, String.valueOf(mailId.invoke(mail)));
                            }
                        }
                    }
                    if (Boolean.TRUE.equals(pageHasNext.invoke(result))) {
                        markAllRead(recipient, page + 1);
                    }
                } catch (ReflectiveOperationException | RuntimeException e) {
                    plugin.getLogger().warning("清理 LiuChat 邮件未读失败: " + e.getMessage());
                }
            });
        } catch (ReflectiveOperationException | RuntimeException e) {
            plugin.getLogger().warning("清理 LiuChat 邮件未读失败: " + e.getMessage());
        }
    }

    public void unread(UUID recipient, Consumer<Integer> callback) {
        if (!available()) {
            callback.accept(0);
            return;
        }
        // 只统计工会系统邮件，因此借用 inbox 过滤；unread() 会包含玩家私信。
        inbox(recipient, list -> callback.accept((int) list.stream().filter(m -> m.readAt() == 0).count()));
    }
}
