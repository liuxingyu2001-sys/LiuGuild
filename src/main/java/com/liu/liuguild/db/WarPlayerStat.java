package com.liu.liuguild.db;

import java.util.UUID;

/**
 * 单场工会战的玩家战绩。
 *
 * @param warId           工会战 id
 * @param player          玩家 UUID
 * @param playerName      结算时的名字快照
 * @param guildId         所属工会
 * @param side            A / B
 * @param kills           击杀
 * @param deaths          死亡
 * @param survivedSeconds 入场到离场/结算的存活秒数
 * @param mvp             是否本场 MVP
 * @param createdAt       写入时间
 */
public record WarPlayerStat(String warId, UUID player, String playerName, String guildId,
                            String side, int kills, int deaths, long survivedSeconds,
                            boolean mvp, long createdAt) {
}
