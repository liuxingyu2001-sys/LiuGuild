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

    /** 战绩 MVP 分：击杀×3 - 死亡×2 + 存活分钟；允许负分。 */
    public static long mvpScore(int kills, int deaths, long survivedSeconds) {
        return Math.max(0, kills) * 3L - Math.max(0, deaths) * 2L
                + Math.max(0, survivedSeconds) / 60L;
    }

    /** KOTH 控制方：只有一方在点内才得分，双方/无人返回空串。 */
    public static String kothOwner(int countA, int countB) {
        if (countA > 0 && countB == 0) {
            return "A";
        }
        if (countB > 0 && countA == 0) {
            return "B";
        }
        return "";
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

    /** 跨服工会战镜像的默认安全余量（毫秒）：阶段总时长之外额外给 5 分钟容错。 */
    public static final long MIRROR_SAFETY_MARGIN_MILLIS = 5L * 60L * 1000L;

    /**
     * 跨服镜像允许存活的最长时间（毫秒）：各阶段总时长 + 安全余量。
     *
     * <p>承办服正常收尾会广播终态让镜像移除；但承办服崩溃、消息丢失或 Redis 抖动时
     * 镜像会残留，导致其它子服玩家一直被判定为“正在工会战中”而无法匹配。超出该时长
     * 即视为无效镜像并清理。阶段时长均非正时退化为 2 小时兜底。</p>
     */
    public static long mirrorMaxAgeMillis(int prepareSeconds, int durationSeconds, int settleSeconds,
                                          long marginMillis) {
        long seconds = Math.max(0, prepareSeconds) + Math.max(0, durationSeconds) + Math.max(0, settleSeconds);
        long base = seconds > 0 ? seconds * 1000L : 2L * 60L * 60L * 1000L;
        return base + Math.max(0, marginMillis);
    }

    /** 镜像是否已超过最大存活时长（{@code createdAt} 为镜像建立时间）。 */
    public static boolean mirrorExpired(long createdAt, long now, int prepareSeconds, int durationSeconds,
                                         int settleSeconds, long marginMillis) {
        return now - createdAt > mirrorMaxAgeMillis(prepareSeconds, durationSeconds, settleSeconds, marginMillis);
    }
}
