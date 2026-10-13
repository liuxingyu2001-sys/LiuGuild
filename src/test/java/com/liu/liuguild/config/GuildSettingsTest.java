package com.liu.liuguild.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
