package com.liu.liuguild.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WarehouseSettingsTest {
    private WarehouseSettings parse(String yaml) throws Exception {
        var config = new YamlConfiguration();
        config.loadFromString(yaml);
        return WarehouseSettings.parse(config, new GuildSettings());
    }

    @Test void removedUpperLevelsUseHighestRemainingCapacity() throws Exception {
        var s = parse("base-slots: 45\nlevels:\n  '1': {slots: 90, price: {currency: FUNDS, amount: 100}, duration-days: 0}\n");
        assertEquals(45, s.slots(0));
        assertEquals(90, s.slots(1));
        assertEquals(90, s.slots(9));
    }

    @Test void currencyAndPriceErrorsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> parse("access: {price-per-day: {currency: UNKNOWN, amount: 10}}"));
        assertThrows(IllegalArgumentException.class, () -> parse("access: {price-per-day: {currency: PLAYER_POINTS, amount: 2147483648}}"));
        assertThrows(IllegalArgumentException.class, () -> parse("access: {price-per-day: {currency: MONEY, amount: 1.5}}"));
        assertThrows(IllegalArgumentException.class, () -> parse("access: {price-per-day: {currency: FUNDS, amount: -1}}"));
    }

    @Test void malformedCapacityAndRetentionAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> parse("base-slots: 1.5"));
        assertThrows(IllegalArgumentException.class, () -> parse("base-slots: 4501"));
        assertThrows(IllegalArgumentException.class, () -> parse("expiry: {retain-days: 1.5}"));
        assertThrows(IllegalArgumentException.class, () -> parse("disband: {retain-days: -2}"));
    }

    @Test void invalidManagementRankMustNotGrantMemberAccess() {
        assertThrows(IllegalArgumentException.class, () -> parse("manage-rank: OFICER"));
        assertThrows(IllegalArgumentException.class, () -> parse("manage-rank: ''"));
    }

    @Test void permanentAccessRequiresExplicitEnable() throws Exception {
        assertNull(parse("{}").permanentPrice);
        var s = parse("access: {permanent: {enabled: true, price: {currency: PLAYER_POINTS, amount: 100}}}");
        assertEquals(WarehouseSettings.Currency.PLAYER_POINTS, s.permanentPrice.currency());
        assertEquals(100, s.permanentPrice.amount());
    }

    @Test void temporaryLevelExpiryRetainsPermanentLevel() {
        var g = new com.liu.liuguild.model.Guild("test");
        g.warehouseLevel(3);
        g.warehousePermanentLevel(1);
        g.warehouseLevelExpiresAt(100);
        assertEquals(3, g.effectiveWarehouseLevel(99));
        assertEquals(1, g.effectiveWarehouseLevel(100));
        g.warehouseLevelExpiresAt(0);
        assertEquals(3, g.effectiveWarehouseLevel(101));
    }
}
