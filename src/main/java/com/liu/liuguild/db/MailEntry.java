package com.liu.liuguild.db;

import java.util.UUID;

/**
 * 一封工会系统邮件。
 *
 * @param id         邮件 id
 * @param guildId    关联工会（可空）
 * @param recipient  收件人
 * @param type       类型，例如 war.result / application.result
 * @param title      标题
 * @param body       正文（纯文本/MiniMessage）
 * @param createdAt  创建时间
 * @param readAt     已读时间，0=未读
 * @param expiresAt  过期时间，0=不过期
 */
public record MailEntry(String id, String guildId, UUID recipient, String type,
                        String title, String body, long createdAt, long readAt, long expiresAt) {
}
