package com.liu.liuguild.war;

import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

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

    /**
     * 从队列头部取出至多 {@code batch} 个元素并逐个执行（分帧处理）。
     * 单元素异常不会中断其余元素；返回实际处理数量。
     */
    public static <T> int drainBatch(Deque<T> queue, int batch, Consumer<T> action) {
        if (queue == null || action == null || batch <= 0) {
            return 0;
        }
        int done = 0;
        for (int i = 0; i < batch; i++) {
            T item = queue.pollFirst();
            if (item == null) {
                break;
            }
            try {
                action.accept(item);
            } catch (Throwable ignored) {
                // 单个奖励发放失败不影响其余玩家
            }
            done++;
        }
        return done;
    }
}
