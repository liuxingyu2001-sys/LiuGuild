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
    /** 每名参赛者的初始生命次数（含当前这条命）。 */
    public int livesPerPlayer = 3;
    /** 队伍人数不等时，双方总生命池按较小一队的人数归一。 */
    public boolean balanceTeamLives = true;
    /** 复活回到己方出生点；0 秒表示立即复活。 */
    public int respawnDelaySeconds = 3;

    // ---- 节奏 ----
    public int prepareSeconds = 15;
    public int durationSeconds = 600;
    public int settleSeconds = 10;

    // ---- 计分 ----
    public int scoreKill = 1;
    public int scoreDeath = -1;
    public int scoreObjective = 5;

    // ---- 场地 / 承办 ----
    public boolean allowSpectators = true;
    /**
     * 准备阶段是否锁定参赛者：冻结位置与视角、禁止移动/转视角/交互/丢物，倒计时结束才放开（默认开）。
     * <p>保证开战瞬间双方都在出生点上，避免等待期间乱跑导致开战位置不确定。</p>
     */
    public boolean freezeOnPrepare = true;
    /** 引用 arena.yml 中的竞技场 id。 */
    public String arena = "main";
    /** 覆盖竞技场/全局的承办服；留空 = 使用竞技场或全局设置。 */
    public String dedicatedServer = "";

    // ---- 奖励：个人金币、工会资金、通行证经验、胜方称号 ----
    public double winPlayerMoney = 500.0;
    public double losePlayerMoney = 100.0;
    public long winGuildFunds = 5000L;
    public long loseGuildFunds = 1000L;
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
        m.livesPerPlayer = Math.max(1, Math.min(100, sec.getInt("lives.per-player", m.livesPerPlayer)));
        m.balanceTeamLives = sec.getBoolean("lives.balance-teams", m.balanceTeamLives);
        m.respawnDelaySeconds = Math.max(0, Math.min(60, sec.getInt("lives.respawn-delay-seconds", m.respawnDelaySeconds)));
        m.prepareSeconds = Math.max(1, sec.getInt("prepare-seconds", m.prepareSeconds));
        m.durationSeconds = Math.max(10, sec.getInt("duration-seconds", m.durationSeconds));
        m.settleSeconds = Math.max(1, sec.getInt("settle-seconds", m.settleSeconds));
        m.scoreKill = sec.getInt("score.kill", m.scoreKill);
        m.scoreDeath = sec.getInt("score.death", m.scoreDeath);
        m.scoreObjective = sec.getInt("score.objective", m.scoreObjective);
        m.allowSpectators = sec.getBoolean("allow-spectators", m.allowSpectators);
        m.freezeOnPrepare = sec.getBoolean("freeze-on-prepare", m.freezeOnPrepare);
        m.arena = sec.getString("arena", m.arena);
        m.dedicatedServer = sec.getString("dedicated-server", m.dedicatedServer);
        m.winPlayerMoney = Math.max(0, sec.getDouble("rewards.win-player-money", m.winPlayerMoney));
        m.losePlayerMoney = Math.max(0, sec.getDouble("rewards.lose-player-money", m.losePlayerMoney));
        m.winGuildFunds = Math.max(0, sec.getLong("rewards.win-guild-funds",
                (long) sec.getDouble("rewards.win-money", m.winGuildFunds)));
        m.loseGuildFunds = Math.max(0, sec.getLong("rewards.lose-guild-funds",
                (long) sec.getDouble("rewards.lose-money", m.loseGuildFunds)));
        m.winPassXp = Math.max(0, sec.getInt("rewards.win-pass-xp", m.winPassXp));
        m.losePassXp = Math.max(0, sec.getInt("rewards.lose-pass-xp", m.losePassXp));
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
        c.livesPerPlayer = livesPerPlayer;
        c.balanceTeamLives = balanceTeamLives;
        c.respawnDelaySeconds = respawnDelaySeconds;
        c.prepareSeconds = prepareSeconds;
        c.durationSeconds = durationSeconds;
        c.settleSeconds = settleSeconds;
        c.scoreKill = scoreKill;
        c.scoreDeath = scoreDeath;
        c.scoreObjective = scoreObjective;
        c.allowSpectators = allowSpectators;
        c.freezeOnPrepare = freezeOnPrepare;
        c.arena = arena;
        c.dedicatedServer = dedicatedServer;
        c.winPlayerMoney = winPlayerMoney;
        c.losePlayerMoney = losePlayerMoney;
        c.winGuildFunds = winGuildFunds;
        c.loseGuildFunds = loseGuildFunds;
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
