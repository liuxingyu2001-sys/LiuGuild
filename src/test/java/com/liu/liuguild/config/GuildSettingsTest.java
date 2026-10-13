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
}
