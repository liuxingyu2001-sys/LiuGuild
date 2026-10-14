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
 * <p>全局开关 + {@code war.defaults} 默认值；各模式由 {@link WarModeSettings}（war-modes.yml）解析后注入
 * {@link #modes}，场地由 {@link ArenaSettings}（arena.yml）提供。</p>
 */
public final class WarSettings {

    // ---- 全局 ----
    public boolean enabled = true;
    /** true = 本服为工会战专属承办服（竞技场服）。 */
    public boolean serverMode = false;
    /** 全局默认承办服 server-id；留空 = 由发起匹配的服务器承办。 */
    public String dedicatedServer = "";
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
    /** 工会战事件是否全服（含跨服）公告；默认仅通知参赛工会，避免刷屏与重复。 */
    public boolean announceServer = false;

    /** 模式公共默认值（来自 war.defaults 或 war 根节点）。 */
    public WarMode defaults = new WarMode();
    /** 全部模式（由 ConfigManager 从 war-modes.yml 注入）。 */
    public Map<String, WarMode> modes = new LinkedHashMap<>();

    public static WarSettings parse(FileConfiguration c) {
        WarSettings s = new WarSettings();
        s.enabled = c.getBoolean("war.enabled", s.enabled);
        s.serverMode = c.getBoolean("war.server-mode", s.serverMode);
        s.dedicatedServer = c.getString("war.dedicated-server", s.dedicatedServer);
        s.queueIntervalSeconds = Math.max(1, Math.min(60, c.getInt("war.queue-interval-seconds", s.queueIntervalSeconds)));
        s.matchTimeoutSeconds = Math.max(0, c.getInt("war.match-timeout-seconds", s.matchTimeoutSeconds));
        s.defaultMode = c.getString("war.default-mode", s.defaultMode);
        s.broadcastQueue = c.getBoolean("war.broadcast-queue", s.broadcastQueue);
        s.bossBarEnabled = c.getBoolean("war.bossbar", s.bossBarEnabled);
        s.settleBatchPerTick = Math.max(1, Math.min(200, c.getInt("war.settle-batch-per-tick", s.settleBatchPerTick)));
        s.announceServer = c.getBoolean("war.announce-server", s.announceServer);

        WarMode base = new WarMode();
        ConfigurationSection defaults = c.getConfigurationSection("war.defaults");
        if (defaults != null) {
            base = WarMode.parse("default", defaults, base);
        }
        applyLegacyRoot(c, base);
        s.defaults = base;
        return s;
    }

    /** 兼容旧版扁平 war.* 配置。 */
    private static void applyLegacyRoot(FileConfiguration c, WarMode base) {
        base.fairEquipment = c.getBoolean("war.fair-equipment", base.fairEquipment);
        base.keepInventory = c.getBoolean("war.keep-inventory", base.keepInventory);
        base.teamSize = Math.max(1, c.getInt("war.team-size", base.teamSize));
        base.minMembers = Math.max(1, c.getInt("war.min-members", base.minMembers));
        base.prepareSeconds = Math.max(1, c.getInt("war.prepare-seconds", base.prepareSeconds));
        base.durationSeconds = Math.max(10, c.getInt("war.duration-seconds", base.durationSeconds));
        base.settleSeconds = Math.max(1, c.getInt("war.settle-seconds", base.settleSeconds));
        base.allowSpectators = c.getBoolean("war.allow-spectators", base.allowSpectators);
        base.arena = c.getString("war.arena", base.arena);
        base.scoreKill = c.getInt("war.score.kill", base.scoreKill);
        base.scoreDeath = c.getInt("war.score.death", base.scoreDeath);
        base.scoreObjective = c.getInt("war.score.objective", base.scoreObjective);
        base.winGuildFunds = Math.max(0, c.getLong("war.rewards.win-guild-funds",
                (long) c.getDouble("war.rewards.win-money", base.winGuildFunds)));
        base.loseGuildFunds = Math.max(0, c.getLong("war.rewards.lose-guild-funds",
                (long) c.getDouble("war.rewards.lose-money", base.loseGuildFunds)));
        base.winPlayerMoney = Math.max(0, c.getDouble("war.rewards.win-player-money", base.winPlayerMoney));
        base.losePlayerMoney = Math.max(0, c.getDouble("war.rewards.lose-player-money", base.losePlayerMoney));
        base.winPassXp = Math.max(0, c.getInt("war.rewards.win-pass-xp", base.winPassXp));
        base.losePassXp = Math.max(0, c.getInt("war.rewards.lose-pass-xp", base.losePassXp));
        base.passId = c.getString("war.rewards.pass-id", base.passId);
        base.winTitleId = Math.max(0, c.getInt("war.rewards.win-title-id", base.winTitleId));
    }

    /** 取某模式配置；未知模式回退默认值构造成临时模式。 */
    public WarMode mode(String key) {
        if (key != null && modes.containsKey(key.toLowerCase(Locale.ROOT))) {
            return modes.get(key.toLowerCase(Locale.ROOT));
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

    public boolean dedicated() {
        return dedicatedServer != null && !dedicatedServer.isBlank();
    }
}
