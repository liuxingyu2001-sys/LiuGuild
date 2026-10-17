package com.liu.liuguild.db;

import java.util.UUID;

/**
 * 跨服参赛者的出发地记录：赛后/崩溃恢复时用于把玩家送回原服与原坐标。
 *
 * @param warId        所属工会战
 * @param player       玩家
 * @param sourceServer 出发服（优先代理登记名，回退 server-id）
 * @param world        出发世界名（可能为空，表示目的地默认世界）
 * @param x/y/z        出发坐标
 * @param yaw/pitch    出发视角
 */
public record WarOrigin(String warId, UUID player, String sourceServer, String world,
                        double x, double y, double z, float yaw, float pitch) {
}
