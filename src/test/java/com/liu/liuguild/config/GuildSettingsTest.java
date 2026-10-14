package com.liu.liuguild.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Round-1 (second pass) tests for guild-home slot progression. */
class GuildSettingsTest {

    @Test
    void homeSlotsScaleEveryNLevels() {
        // max 3 slots, +1 every 5 levels
        assertEquals(1, GuildSettings.homeSlots(3, 5, 1));
        assertEquals(1, GuildSettings.homeSlots(3, 5, 5));
        assertEquals(2, GuildSettings.homeSlots(3, 5, 6));
        assertEquals(2, GuildSettings.homeSlots(3, 5, 10));
        assertEquals(3, GuildSettings.homeSlots(3, 5, 11));
    }

    @Test
    void homeSlotsNeverExceedCap() {
        assertEquals(3, GuildSettings.homeSlots(3, 1, 999));
        assertEquals(1, GuildSettings.homeSlots(1, 5, 999));
    }

    @Test
    void homeSlotsAreAtLeastOneAtLevelOne() {
        assertEquals(1, GuildSettings.homeSlots(3, 5, 1));
        assertEquals(1, GuildSettings.homeSlots(3, 5, 0));
        assertEquals(1, GuildSettings.homeSlots(0, 0, 1));
    }

    private static final long DAY = 86_400_000L;

    @Test
    void inactiveWhenNoOnlineAndStale() {
        long now = 1_000 * DAY;
        assertTrue(GuildSettings.isInactive(now, now - 31 * DAY, 30, 10, 0, 0));
    }

    @Test
    void activeWhenRecentOrOnline() {
        long now = 1_000 * DAY;
        assertFalse(GuildSettings.isInactive(now, now - 5 * DAY, 30, 10, 0, 0));
        // Someone online always counts as active
        assertFalse(GuildSettings.isInactive(now, now - 100 * DAY, 30, 10, 0, 1));
    }

    @Test
    void largeGuildsCanBeProtected() {
        long now = 1_000 * DAY;
        assertFalse(GuildSettings.isInactive(now, now - 100 * DAY, 30, 50, 10, 0));
        assertTrue(GuildSettings.isInactive(now, now - 100 * DAY, 30, 5, 10, 0));
    }

    @Test
    void unknownLastActiveTreatedAsInactive() {
        long now = 1_000 * DAY;
        assertTrue(GuildSettings.isInactive(now, 0, 30, 3, 0, 0));
    }

    @Test
    void checkinStreakIncrementsOnlyOnConsecutiveDays() {
        assertEquals(1, GuildSettings.nextStreak(100, 0, 0));
        assertEquals(5, GuildSettings.nextStreak(100, 100, 5)); // 同一天，保持不变
        assertEquals(6, GuildSettings.nextStreak(101, 100, 5)); // 连签
        assertEquals(1, GuildSettings.nextStreak(103, 100, 5)); // 断签重置
    }

    @Test
    void checkinAlreadyCheckedInOnlySameDay() {
        assertTrue(GuildSettings.alreadyCheckedIn(200, 200));
        assertFalse(GuildSettings.alreadyCheckedIn(200, 199));
        assertFalse(GuildSettings.alreadyCheckedIn(200, 0));
    }

    @Test
    void checkinRewardAddsCappedStreakBonus() {
        // 第 1 天：无额外奖励
        assertEquals(10, GuildSettings.checkinReward(10, 5, 50, 1));
        // 第 3 天：+5*(3-1)=10
        assertEquals(20, GuildSettings.checkinReward(10, 5, 50, 3));
        // 连续奖励封顶 50
        assertEquals(60, GuildSettings.checkinReward(10, 5, 50, 100));
    }

    @Test
    void checkinCostIsParsedAndZeroByDefault() {
        GuildSettings def = GuildSettings.parse(new org.bukkit.configuration.file.YamlConfiguration());
        assertEquals(0.0, def.checkinCost, "默认不收费");
        assertEquals(0, def.checkinGuildCost, "默认不扣工会资金");

        org.bukkit.configuration.file.YamlConfiguration c =
                new org.bukkit.configuration.file.YamlConfiguration();
        try {
            c.loadFromString("""
                    guild:
                      checkin:
                        enabled: true
                        cost: 500.0
                        guild-cost: 20
                    """);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        GuildSettings s = GuildSettings.parse(c);
        assertEquals(500.0, s.checkinCost);
        assertEquals(20, s.checkinGuildCost);
    }

    @Test
    void negativeCheckinCostIsClampedToZero() {
        org.bukkit.configuration.file.YamlConfiguration c =
                new org.bukkit.configuration.file.YamlConfiguration();
        try {
            c.loadFromString("""
                    guild:
                      checkin:
                        cost: -100.0
                        guild-cost: -5
                    """);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        GuildSettings s = GuildSettings.parse(c);
        assertEquals(0.0, s.checkinCost, "负费用应被归零，否则会变成发钱");
        assertEquals(0, s.checkinGuildCost);
    }

    // =====================================================================
    // 工会仓库槽位
    // =====================================================================

    @Test
    void warehouseSlotsScaleWithLevelAndStayMultiplesOfNine() {
        // base 27, +9/级, 上限 54
        assertEquals(27, GuildSettings.warehouseSlots(27, 9, 54, 1));
        assertEquals(36, GuildSettings.warehouseSlots(27, 9, 54, 2));
        assertEquals(45, GuildSettings.warehouseSlots(27, 9, 54, 3));
        assertEquals(54, GuildSettings.warehouseSlots(27, 9, 54, 4));
        // 超过上限后恒为上限
        assertEquals(54, GuildSettings.warehouseSlots(27, 9, 54, 99));
    }

    @Test
    void warehouseSlotsAreAlwaysLegalInventorySizes() {
        // Bukkit 只接受 9 的倍数且 9..54；配错数字不能让 createInventory 抛异常
        int[][] cases = {
                {0, 0, 0, 1}, {1, 1, 1, 1}, {100, 100, 200, 50},
                {27, 9, 27, 1}, {9, 3, 20, 3}, {54, 0, 9, 1}, {5, 2, 7, 10},
        };
        for (int[] c : cases) {
            int slots = GuildSettings.warehouseSlots(c[0], c[1], c[2], c[3]);
            assertTrue(slots >= 9 && slots <= 54,
                    "槽位必须落在 9..54，实际=" + slots + " case=" + java.util.Arrays.toString(c));
            assertEquals(0, slots % 9,
                    "槽位必须是 9 的倍数，实际=" + slots + " case=" + java.util.Arrays.toString(c));
        }
    }

    @Test
    void warehouseSlotsNeverBelowNineEvenWhenMisconfigured() {
        assertEquals(9, GuildSettings.warehouseSlots(0, 0, 0, 1));
        assertEquals(9, GuildSettings.warehouseSlots(-5, -5, -5, 1));
        assertEquals(9, GuildSettings.warehouseSlots(1, 1, 1, 1));
    }

    @Test
    void warehouseBaseCannotExceedCap() {
        // 基础槽位配得比上限还大时，不能突破上限
        assertEquals(9, GuildSettings.warehouseSlots(54, 9, 9, 1));
        assertEquals(18, GuildSettings.warehouseSlots(54, 9, 18, 1));
    }
}
