package com.liu.liuguild.activity;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 在线活跃结算的纯逻辑：只统计「最近活跃」的成员。 */
class ActivityTest {

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();
    private static final UUID C = UUID.randomUUID();

    @Test
    void onlyRecentlyActiveMembersCount() {
        long now = 100_000L;
        Map<UUID, Long> last = Map.of(
                A, now - 1_000L,   // 刚动过
                B, now - 10_000L,  // 超时挂机
                C, now - 2_000L);  // 仍在窗口内
        assertEquals(2, ActivityService.activeCount(List.of(A, B, C), last, now, 5_000L));
    }

    @Test
    void membersWithoutRecordAreTreatedAsAfk() {
        long now = 100_000L;
        assertEquals(0, ActivityService.activeCount(List.of(A, B), Map.of(), now, 5_000L));
    }

    @Test
    void zeroAfkWindowCountsAllOnline() {
        long now = 100_000L;
        assertEquals(3, ActivityService.activeCount(List.of(A, B, C), Map.of(), now, 0));
    }

    @Test
    void boundaryOfAfkWindowIsInclusive() {
        long now = 100_000L;
        Map<UUID, Long> last = Map.of(A, now - 5_000L);
        assertEquals(1, ActivityService.activeCount(List.of(A), last, now, 5_000L));
        assertEquals(0, ActivityService.activeCount(List.of(A), last, now, 4_999L));
    }
}
