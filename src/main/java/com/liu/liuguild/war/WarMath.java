package com.liu.liuguild.war;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 工会战纯计算逻辑（不依赖 Bukkit，便于单元测试）。
 */
public final class WarMath {

    private WarMath() {
    }

    /**
     * 把 {@code total} 条生命均分给 {@code players}，余数从前到后各多 1 条。
     * 每个玩家至少 1 条（除非名单为空）。
     *
     * @return 玩家 UUID -> 生命数
     */
    public static Map<UUID, Integer> distributeLives(List<UUID> players, int total) {
        Map<UUID, Integer> out = new HashMap<>();
        if (players == null || players.isEmpty()) {
            return out;
        }
        int safeTotal = Math.max(players.size(), total);
        int each = safeTotal / players.size();
        int extra = safeTotal % players.size();
        for (int i = 0; i < players.size(); i++) {
            out.put(players.get(i), each + (i < extra ? 1 : 0));
        }
        return out;
    }

    /**
     * 人数均衡时计算双方各自应分配的总生命池。
     *
     * @param balanced     是否启用人数均衡
     * @param livesPerPlayer 每人默认生命
     * @param teamA        队伍 A 人数
     * @param teamB        队伍 B 人数
     * @return 长度 2 的数组 {A 总生命, B 总生命}
     */
    public static int[] teamLifePools(boolean balanced, int livesPerPlayer, int teamA, int teamB) {
        int baseLives = Math.max(1, livesPerPlayer);
        if (!balanced) {
            return new int[]{Math.max(0, teamA) * baseLives, Math.max(0, teamB) * baseLives};
        }
        int max = Math.max(teamA, teamB);
        int min = Math.min(teamA, teamB);
        // 均衡池：至少保证每名玩家 1 条，同时向人数较多的一方对齐总生命。
        int total = Math.max(max, min * baseLives);
        return new int[]{total, total};
    }
}
