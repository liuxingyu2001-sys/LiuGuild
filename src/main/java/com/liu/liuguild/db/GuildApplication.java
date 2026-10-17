package com.liu.liuguild.db;

import java.util.UUID;

/**
 * 一条工会入会申请。
 *
 * @param id         申请 id
 * @param guildId    目标工会
 * @param player     申请人
 * @param playerName 申请人名字快照
 * @param message    申请留言
 * @param state      PENDING / APPROVED / REJECTED / CANCELLED
 * @param createdAt  创建时间
 * @param updatedAt  状态变更时间
 */
public record GuildApplication(String id, String guildId, UUID player, String playerName,
                               String message, String state, long createdAt, long updatedAt) {
}
