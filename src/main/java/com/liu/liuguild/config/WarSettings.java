package com.liu.liuguild.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 工会战全局模块（war.yml）。
 * <p>全局开关 + {@code war.defaults} 默认值；各模式可由 war.yml 的同级段/{@code war.modes} 或
 * {@link WarModeSettings}（war-modes.yml）定义，最终注入 {@link #modes}；场地由 {@link ArenaSettings} 提供。</p>
 */
public final class WarSettings {

    // ---- 全局 ----
    public boolean enabled = true;
    public int queueIntervalSeconds = 5;
    public int matchTimeoutSeconds = 180;
    /** 默认模式（未指定模式时使用）。 */
    public String defaultMode = "match";
    /** 有玩家点击参与匹配时是否全服广播（模式可单独覆盖）。 */
    public boolean broadcastQueue = true;
    /** 比赛期间是否显示比分 BossBar。 */
    public boolean bossBarEnabled = true;
    /** 结算阶段每 tick 分帧发放奖励的人数（防止大名单阻塞主线程）。 */
    public int settleBatchPerTick = 8;
    /** 匹配成功后是否自动把双方在线成员强制传送进竞技场并参战（无需手动 /guild war join）。 */
    public boolean autoEnter = true;

    /** 模式公共默认值（来自 war.defaults）。 */
    public WarMode defaults = new WarMode();
    /** war.yml 中直接写出的模式覆盖（与 defaults 同级，或位于 war.modes 下）。 */
    public Map<String, WarMode> inlineModes = new LinkedHashMap<>();
    /** 全部模式（由 ConfigManager 从 war-modes.yml 注入）。 */
    public Map<String, WarMode> modes = new LinkedHashMap<>();

    public static WarSettings parse(FileConfiguration c) {
        WarSettings s = new WarSettings();
        s.enabled = c.getBoolean("war.enabled", s.enabled);
        s.queueIntervalSeconds = Math.max(1, Math.min(60, c.getInt("war.queue-interval-seconds", s.queueIntervalSeconds)));
        s.matchTimeoutSeconds = Math.max(0, c.getInt("war.match-timeout-seconds", s.matchTimeoutSeconds));
        s.defaultMode = String.valueOf(c.getString("war.default-mode", s.defaultMode)).toLowerCase(Locale.ROOT);
        s.broadcastQueue = c.getBoolean("war.broadcast-queue", s.broadcastQueue);
        s.bossBarEnabled = c.getBoolean("war.bossbar", s.bossBarEnabled);
        s.settleBatchPerTick = Math.max(1, Math.min(200, c.getInt("war.settle-batch-per-tick", s.settleBatchPerTick)));
        s.autoEnter = c.getBoolean("war.auto-enter", s.autoEnter);

        WarMode base = new WarMode();
        // 全局开关作为未显式配置该模式的默认值；模式里写 broadcast-queue 仍可单独覆盖。
        base.broadcastQueue = s.broadcastQueue;
        ConfigurationSection defaults = c.getConfigurationSection("war.defaults");
        if (defaults != null) {
            base = WarMode.parse("default", defaults, base);
        }
        s.defaults = base;
        s.inlineModes = parseInlineModes(c, base);
        return s;
    }

    /**
     * 解析 war.yml 里与 {@code defaults} 同级的自定义模式，以及 {@code war.modes} 下的模式。
     * <p>同名模式以 {@code war-modes.yml} 为准；这里只作为模板/覆盖层，未写的项继续继承 defaults。</p>
     */
    private static Map<String, WarMode> parseInlineModes(FileConfiguration c, WarMode base) {
        Map<String, WarMode> out = new LinkedHashMap<>();
        ConfigurationSection root = c.getConfigurationSection("war");
        if (root != null) for (String key : root.getKeys(false)) {
            String lower = key.toLowerCase(Locale.ROOT);
            if (INLINE_RESERVED.contains(lower)) {
                continue;
            }
            ConfigurationSection sec = root.getConfigurationSection(key);
            if (sec == null || !looksLikeMode(sec)) {
                continue; // 全局标量或未知全局段，不当作模式
            }
            out.put(lower, WarMode.parse(lower, sec, base));
        }
        ConfigurationSection explicit = c.getConfigurationSection("war.modes");
        if (explicit != null) {
            for (String key : explicit.getKeys(false)) {
                String lower = key.toLowerCase(Locale.ROOT);
                WarMode start = out.getOrDefault(lower, base);
                out.put(lower, WarMode.parse(lower, explicit.getConfigurationSection(key), start));
            }
        }
        return out;
    }

    /** war 下已被全局配置占用的段名，不能当作模式名。 */
    private static final java.util.Set<String> INLINE_RESERVED = java.util.Set.of(
            "defaults", "modes", "score", "lives", "rewards");

    /** 判断一个段是否像模式配置，避免把将来的全局段误认成模式。 */
    private static boolean looksLikeMode(ConfigurationSection sec) {
        for (String key : java.util.List.of("enabled", "display", "description", "arena", "kit", "fair-equipment",
                "fair-health", "team-size", "min-members", "min-level",
                "prepare-seconds", "duration-seconds", "settle-seconds", "allow-spectators",
                "freeze-on-prepare", "prepare-platform", "broadcast-queue", "schedule", "season",
                "lives", "score", "rewards")) {
            if (sec.contains(key)) {
                return true;
            }
        }
        return false;
    }

    /** 取某模式配置；未知模式回退默认值构造成临时模式。 */
    public WarMode mode(String key) {
        if (key != null && modes.containsKey(key.toLowerCase(Locale.ROOT))) {
            return modes.get(key.toLowerCase(Locale.ROOT));
        }
        if (key != null && inlineModes.containsKey(key.toLowerCase(Locale.ROOT))) {
            return inlineModes.get(key.toLowerCase(Locale.ROOT));
        }
        WarMode m = defaults.copy();
        m.key = key == null ? defaultMode : key;
        return m;
    }

    public WarMode defaultModeObj() {
        return mode(defaultMode);
    }

    public List<WarMode> enabledModes() {
        List<WarMode> out = new ArrayList<>();
        for (WarMode m : modes.values()) {
            if (m.enabled) {
                out.add(m);
            }
        }
        return out;
    }


}
