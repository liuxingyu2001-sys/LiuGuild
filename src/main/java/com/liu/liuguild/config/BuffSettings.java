package com.liu.liuguild.config;

import com.liu.liuguild.model.GuildRank;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.LinkedHashMap;
import java.util.Map;

/** 工会公共资金购买的限时增益。配置错误拒绝加载。 */
public final class BuffSettings {
    public record Buff(String id, String name, String effect, int amplifier, long cost,
                       long seconds, double experienceBonus) {}
    public boolean enabled = true;
    public GuildRank purchaseRank = GuildRank.LEADER;
    public long maxSeconds = 21600;
    public final Map<Integer, Integer> slots = new java.util.TreeMap<>();
    public final Map<String, Buff> buffs = new LinkedHashMap<>();

    public int slots(int level) {
        int count = 0;
        for (var entry : slots.entrySet()) {
            if (level >= entry.getKey()) count = entry.getValue();
        }
        return count;
    }

    public static BuffSettings parse(FileConfiguration config) {
        BuffSettings s = new BuffSettings();
        s.enabled = config.getBoolean("enabled", true);
        try { s.purchaseRank = GuildRank.valueOf(config.getString("purchase-rank", "LEADER").toUpperCase(java.util.Locale.ROOT)); }
        catch (Exception e) { throw new IllegalArgumentException("buffs.yml purchase-rank 无效", e); }
        s.maxSeconds = config.getLong("max-duration-seconds", 21600);
        if (s.maxSeconds < 1 || s.maxSeconds > 31536000) throw new IllegalArgumentException("增益最长累计时间必须在1秒至365天内");
        var tiers = config.getConfigurationSection("slots-by-level");
        if (tiers == null) s.slots.put(1, 1);
        else for (String key : tiers.getKeys(false)) {
            int level = Integer.parseInt(key), count = tiers.getInt(key);
            if (level < 1 || count < 1 || count > 4) throw new IllegalArgumentException("增益槽位配置无效: " + key);
            s.slots.put(level, count);
        }
        var defs = config.getConfigurationSection("buffs");
        if (defs == null) return s;
        for (String id : defs.getKeys(false)) {
            String p = id + ".";
            if (!defs.getBoolean(p + "enabled", true)) continue;
            String effect = defs.getString(p + "effect", "").toUpperCase(java.util.Locale.ROOT);
            if (!id.matches("[a-z0-9_-]{1,32}") || !java.util.Set.of("SPEED", "HASTE", "WATER_BREATHING", "EXPERIENCE").contains(effect))
                throw new IllegalArgumentException("无效增益: " + id);
            int amplifier = defs.getInt(p + "level", 1) - 1;
            long cost = defs.getLong(p + "cost", 1000), seconds = defs.getLong(p + "duration-seconds", 3600);
            double bonus = defs.getDouble(p + "experience-bonus", 0.1);
            if (amplifier < 0 || amplifier > 1 || cost < 1 || seconds < 1 || seconds > s.maxSeconds
                    || !Double.isFinite(bonus) || bonus < 0 || bonus > 1)
                throw new IllegalArgumentException("增益等级/费用/时长/经验比例无效: " + id);
            s.buffs.put(id, new Buff(id, defs.getString(p + "name", id), effect, amplifier, cost, seconds, bonus));
        }
        if (s.buffs.size() > 4) throw new IllegalArgumentException("最多启用4种工会增益");
        return s;
    }
}
