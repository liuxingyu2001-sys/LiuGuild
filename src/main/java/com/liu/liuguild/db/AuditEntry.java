package com.liu.liuguild.db;

import java.util.UUID;

/**
 * 一条工会审计记录。
 *
 * @param guildId   所属工会
 * @param actorUuid 操作者（系统操作可为 null）
 * @param actorName 操作者名称快照
 * @param action    动作 key，例如 bank.deposit / shop.buy / admin.setlevel
 * @param target    操作对象（商品 id、成员 uuid、目标工会等，可空）
 * @param amount    金额/数量（无则 0）
 * @param detail    补充说明（物品名、原因等，可空）
 * @param createdAt 毫秒时间戳
 */
public record AuditEntry(String id, String guildId, UUID actorUuid, String actorName,
                         String action, String target, long amount, String detail, long createdAt) {
}
