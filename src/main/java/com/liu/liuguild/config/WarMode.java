package com.liu.liuguild.config;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 单种工会战模式的可独立配置（war-modes.yml）。
 * <p>每种模式都有自己完整的配置：是否启用公平统一装备、使用哪套装备、引用哪个竞技场、
 * 承办服、时长、计分、奖励、开放时间等。未写的项继承 {@code war.defaults}。</p>
 */
public final class WarMode {

    public String key;
    public boolean enabled = true;
    public String display = "工会战";
    public String description = "";

    // ---- 公平性 / 装备 ----
    /** 是否发放统一装备（公平竞技）；false = 保留玩家自身装备。 */
    public boolean fairEquipment = true;
    /** kit.yml 中的装备套装 key。 */
    public String kit = "default";
    public boolean keepInventory = false;
    /** 是否屏蔽 LuckyPeaches 等插件的额外血量，保证公平血量。 */
    public boolean fairHealth = true;

    // ---- 人数 ----
    public int teamSize = 10;
    public int minMembers = 1;
    public int minLevel = 1;

    // ---- 节奏 ----
    public int prepareSeconds = 30;
    public int durationSeconds = 600;
    public int settleSeconds = 10;

    // ---- 计分 ----
    public int scoreKill = 1;
    public int scoreDeath = -1;
    public int scoreObjective = 5;

    // ---- 场地 / 承办 ----
    public boolean allowSpectators = true;
    /** 引用 arena.yml 中的竞技场 id。 */
    public String arena = "main";
    /** 覆盖竞技场/全局的承办服；留空 = 使用竞技场或全局设置。 */
    public String dedicatedServer = "";

    // ---- 奖励 ----
    public double winMoney = 5000.0;
    public double loseMoney = 1000.0;
    public long winPoints = 200L;
    public long losePoints = 50L;
    public int winPassXp = 500;
    public int losePassXp = 100;
    public String passId = "";
    public int winTitleId = 0;

    // ---- 匹配提示 ----
    /** 有玩家点击「参与匹配」时是否全服广播提示。 */
    public boolean broadcastQueue = true;

    // ---- 赛季专属 ----
    /** 开战时间表；支持 "20:00"、"sat 20:00"、"mon-fri 20:00"、"* 20:00"。 */
    public List<String> schedule = new ArrayList<>();
    /** 到点后开放报名/匹配的窗口时长（分钟）。 */
    public int seasonWindowMinutes = 10;
    /** 到点是否自动把符合条件的工会加入匹配队列。 */
    public boolean autoEnqueue = false;
    /** 赛季期间额外奖励点数倍率（1.0 = 不变）。 */
    public double seasonPointsMultiplier = 1.0;

    public static WarMode parse(String key, ConfigurationSection sec, WarMode base) {
        WarMode m = base.copy();
        m.key = key;
        if (sec == null) {
            return m;
        }
        m.enabled = sec.getBoolean("enabled", m.enabled);
        m.display = sec.getString("display", m.display);
        m.description = sec.getString("description", m.description);
        m.fairEquipment = sec.getBoolean("fair-equipment", m.fairEquipment);
        m.kit = sec.getString("kit", m.kit);
        m.keepInventory = sec.getBoolean("keep-inventory", m.keepInventory);
        m.fairHealth = sec.getBoolean("fair-health", m.fairHealth);
        m.teamSize = Math.max(1, sec.getInt("team-size", m.teamSize));
        m.minMembers = Math.max(1, sec.getInt("min-members", m.minMembers));
        m.minLevel = Math.max(1, sec.getInt("min-level", m.minLevel));
        m.prepareSeconds = Math.max(1, sec.getInt("prepare-seconds", m.prepareSeconds));
        m.durationSeconds = Math.max(10, sec.getInt("duration-seconds", m.durationSeconds));
        m.settleSeconds = Math.max(1, sec.getInt("settle-seconds", m.settleSeconds));
        m.scoreKill = sec.getInt("score.kill", m.scoreKill);
        m.scoreDeath = sec.getInt("score.death", m.scoreDeath);
        m.scoreObjective = sec.getInt("score.objective", m.scoreObjective);
        m.allowSpectators = sec.getBoolean("allow-spectators", m.allowSpectators);
        m.arena = sec.getString("arena", m.arena);
        m.dedicatedServer = sec.getString("dedicated-server", m.dedicatedServer);
        m.winMoney = sec.getDouble("rewards.win-money", m.winMoney);
        m.loseMoney = sec.getDouble("rewards.lose-money", m.loseMoney);
        m.winPoints = sec.getLong("rewards.win-points", m.winPoints);
        m.losePoints = sec.getLong("rewards.lose-points", m.losePoints);
        m.winPassXp = sec.getInt("rewards.win-pass-xp", m.winPassXp);
        m.losePassXp = sec.getInt("rewards.lose-pass-xp", m.losePassXp);
        m.passId = sec.getString("rewards.pass-id", m.passId);
        m.winTitleId = Math.max(0, sec.getInt("rewards.win-title-id", m.winTitleId));
        m.broadcastQueue = sec.getBoolean("broadcast-queue", m.broadcastQueue);
        if (sec.isList("schedule")) {
            m.schedule = new ArrayList<>(sec.getStringList("schedule"));
        } else if (sec.isString("schedule")) {
            m.schedule = new ArrayList<>(List.of(sec.getString("schedule", "")));
        }
        m.seasonWindowMinutes = Math.max(1, sec.getInt("season.window-minutes", m.seasonWindowMinutes));
        m.autoEnqueue = sec.getBoolean("season.auto-enqueue", m.autoEnqueue);
        m.seasonPointsMultiplier = sec.getDouble("season.points-multiplier", m.seasonPointsMultiplier);
        return m;
    }

    public WarMode copy() {
        WarMode c = new WarMode();
        c.key = key;
        c.enabled = enabled;
        c.display = display;
        c.description = description;
        c.fairEquipment = fairEquipment;
        c.kit = kit;
        c.keepInventory = keepInventory;
        c.fairHealth = fairHealth;
        c.teamSize = teamSize;
        c.minMembers = minMembers;
        c.minLevel = minLevel;
        c.prepareSeconds = prepareSeconds;
        c.durationSeconds = durationSeconds;
        c.settleSeconds = settleSeconds;
        c.scoreKill = scoreKill;
        c.scoreDeath = scoreDeath;
        c.scoreObjective = scoreObjective;
        c.allowSpectators = allowSpectators;
        c.arena = arena;
        c.dedicatedServer = dedicatedServer;
        c.winMoney = winMoney;
        c.loseMoney = loseMoney;
        c.winPoints = winPoints;
        c.losePoints = losePoints;
        c.winPassXp = winPassXp;
        c.losePassXp = losePassXp;
        c.passId = passId;
        c.winTitleId = winTitleId;
        c.broadcastQueue = broadcastQueue;
        c.schedule = new ArrayList<>(schedule);
        c.seasonWindowMinutes = seasonWindowMinutes;
        c.autoEnqueue = autoEnqueue;
        c.seasonPointsMultiplier = seasonPointsMultiplier;
        return c;
    }

    /** 是否按时间表开启（赛季类模式）。 */
    public boolean scheduled() {
        return schedule != null && !schedule.isEmpty();
    }

    /** 该模式使用的承办服（模式级 > 竞技场级 > 全局 > 本服）。 */
    public String hostServer(String arenaDedicated, String globalDedicated, String localServer) {
        if (dedicatedServer != null && !dedicatedServer.isBlank()) {
            return dedicatedServer;
        }
        if (arenaDedicated != null && !arenaDedicated.isBlank()) {
            return arenaDedicated;
        }
        if (globalDedicated != null && !globalDedicated.isBlank()) {
            return globalDedicated;
        }
        return localServer;
    }

    public boolean hasSchedule(String entry) {
        return schedule.contains(entry);
    }

    public String displayUpper() {
        return display == null ? key : display.toUpperCase(Locale.ROOT);
    }
}
