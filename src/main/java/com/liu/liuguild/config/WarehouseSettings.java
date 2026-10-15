package com.liu.liuguild.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import java.util.LinkedHashMap;
import java.util.Map;

/** 工会空间独立配置；未知货币、负价格等错误拒绝加载，绝不静默免费。 */
public final class WarehouseSettings {
    public enum Currency {
        FUNDS("工会公共资金"), BANK("工会银行金币"), MONEY("个人金币"), PLAYER_POINTS("个人点券");
        public final String display;
        Currency(String display) { this.display = display; }
    }
    public record Price(Currency currency, long amount) { }
    public record Level(int number, int slots, Price price, int days) {
        public boolean permanent() { return days == 0; }
    }
    public boolean enabled = true;
    public boolean requireRent = true;
    public int baseSlots = 45;
    public int maxDays = 90;
    public int[] rentOptions = {1, 7, 30};
    public Price permanentPrice;
    public Price rentPrice = new Price(Currency.FUNDS, 200);
    public com.liu.liuguild.model.GuildRank manageRank = com.liu.liuguild.model.GuildRank.OFFICER;
    public boolean withdrawExpired = true;
    /** -1 永久保留，0 到期即清空，正数表示保留天数。 */
    public int expiredRetentionDays = -1;
    public int disbandRetentionDays = 0;
    public final Map<Integer, Level> levels = new LinkedHashMap<>();

    public static WarehouseSettings parse(FileConfiguration c, GuildSettings legacy) {
        WarehouseSettings s = new WarehouseSettings();
        s.enabled = c.getBoolean("enabled", legacy.warehouseEnabled);
        s.requireRent = c.getBoolean("access.require-rent", legacy.warehouseRequireRent);
        Object base = c.get("base-slots");
        if (base != null && (!(base instanceof Integer) || (int) base < 9 || (int) base > 4500))
            throw new IllegalArgumentException("warehouse.yml base-slots 必须为9~4500的整数");
        s.baseSlots = c.getInt("base-slots", legacy.warehouseBaseSlots);
        s.maxDays = Math.max(0, c.getInt("access.max-days", legacy.warehouseRentMaxDays));
        s.rentOptions = GuildSettings.parseDayOptions(c.getIntegerList("access.options"), legacy.warehouseRentOptions);
        s.rentPrice = price(c.getConfigurationSection("access.price-per-day"), legacy.warehouseRentPricePerDay);
        if (c.getBoolean("access.permanent.enabled", false))
            s.permanentPrice = price(c.getConfigurationSection("access.permanent.price"), -1);
        s.manageRank = com.liu.liuguild.model.GuildRank.valueOf(c.getString("manage-rank", legacy.warehouseManageRank.name()).trim().toUpperCase(java.util.Locale.ROOT));
        s.withdrawExpired = c.getBoolean("expiry.allow-withdraw", true);
        s.expiredRetentionDays = retention(c, "expiry.retain-days", -1);
        s.disbandRetentionDays = retention(c, "disband.retain-days", 0);
        ConfigurationSection levels = c.getConfigurationSection("levels");
        if (levels != null) {
            int previous = s.baseSlots;
            for (int number = 1; number <= levels.getKeys(false).size(); number++) {
                ConfigurationSection level = levels.getConfigurationSection(String.valueOf(number));
                if (level == null) throw new IllegalArgumentException("warehouse.yml levels 必须从1连续编号");
                if (!(level.get("slots") instanceof Integer) || (level.contains("duration-days") && !(level.get("duration-days") instanceof Integer)))
                    throw new IllegalArgumentException("warehouse.yml slots和duration-days必须为整数");
                int slots = level.getInt("slots", -1);
                int days = level.getInt("duration-days", 0);
                if (slots <= previous || slots > 4500 || days < 0 || days > 3650)
                    throw new IllegalArgumentException("warehouse.yml 等级容量必须递增且<=4500，期限0~3650");
                s.levels.put(number, new Level(number, slots, price(level.getConfigurationSection("price"), -1), days));
                previous = slots;
            }
        } else {
            for (int n = 1; n <= legacy.warehouseMaxLevel; n++) {
                int slots = GuildSettings.warehouseSlots(s.baseSlots, legacy.warehouseSlotsPerLevel, legacy.warehouseMaxSlots, n + 1);
                int prev = n == 1 ? s.baseSlots : s.levels.get(n - 1).slots();
                if (slots <= prev) break;
                s.levels.put(n, new Level(n, slots, new Price(Currency.FUNDS,
                        GuildSettings.warehouseUpgradeCost(legacy.warehouseUpgradeBaseCost, legacy.warehouseUpgradeCostGrowth, n - 1)), 0));
            }
        }
        return s;
    }
    private static int retention(FileConfiguration c, String path, int def) {
        if (c.contains(path) && !(c.get(path) instanceof Integer))
            throw new IllegalArgumentException("warehouse.yml " + path + " 必须为整数");
        int days = c.getInt(path, def);
        if (days < -1 || days > 36500) throw new IllegalArgumentException("warehouse.yml " + path + " 必须是-1~36500");
        return days;
    }
    private static Price price(ConfigurationSection c, long fallback) {
        Currency currency = Currency.valueOf(c == null ? "FUNDS" : c.getString("currency", "FUNDS").toUpperCase(java.util.Locale.ROOT));
        if (c != null && c.contains("amount") && !(c.get("amount") instanceof Integer || c.get("amount") instanceof Long))
            throw new IllegalArgumentException("warehouse.yml amount 必须为整数");
        long amount = c == null ? fallback : c.getLong("amount", fallback);
        if (amount < 0 || (currency == Currency.PLAYER_POINTS && amount > Integer.MAX_VALUE)
                || (currency == Currency.MONEY && amount > 9_007_199_254_740_991L))
            throw new IllegalArgumentException("warehouse.yml 非法价格或超出货币精度");
        return new Price(currency, amount);
    }
    public int slots(int level) {
        int slots = baseSlots;
        for (Level configured : levels.values()) {
            if (configured.number() <= level) slots = Math.max(slots, configured.slots());
        }
        return slots;
    }
}
