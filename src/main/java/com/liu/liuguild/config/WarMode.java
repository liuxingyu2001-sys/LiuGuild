package com.liu.liuguild.config;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 单种工会战模式的可独立配置（war-modes.yml，或 war.yml 中与 defaults 同级的模式段）。
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

    // ---- 场地 / 承办 ----
    public boolean allowSpectators = true;
    /**
     * 准备阶段是否锁定参赛者：冻结位置与视角、禁止移动/转视角/交互/丢物，倒计时结束才放开（默认开）。
     * <p>保证开战瞬间双方都在出生点上，避免等待期间乱跑导致开战位置不确定。</p>
     */
    public boolean freezeOnPrepare = true;
    /** 准备阶段是否允许跳跃（默认关闭；关闭时连按空格也不会跳）。 */
    public boolean allowJumpOnPrepare = false;
    /**
     * 准备阶段出生点下方是否自动垫一层屏障，从物理上完全禁止跳跃（默认开）。
     * <p>仅在有 PacketEvents 时无需开启；开启可兼顾未安装 PacketEvents 的服务器。</p>
     */
    public boolean preparePlatform = true;
    /** 引用 arena.yml 中的竞技场 id。 */
    public String arena = "main";

    // ---- 奖励：个人金币、工会资金、通行证经验、胜方称号 ----
    public double winPlayerMoney = 500.0;
    public double losePlayerMoney = 100.0;
    public long winGuildFunds = 5000L;
    public long loseGuildFunds = 1000L;
    /** 该模式每个工会每天最多能通过工会战获得的工会资金（0 = 不限）。 */
    public long dailyGuildFundsCap = 0L;
    public int winPassXp = 500;
    public int losePassXp = 100;
    public String passId = "";
    public int winTitleId = 0;

    /**
     * 自定义「指令奖励」：结算时以控制台身份执行。
     * <p>占位符：{player} 玩家名、{uuid}、{guild} 工会名、{guild_id}、{mode} 模式 key、
     * {score} 本方比分、{enemy_score} 对方比分。</p>
     * <p>推荐写法：用 {@code rewards.extra} 列表，每一项自带名称与指令，
     * 名称就是结算消息里显示的文字，无需两个列表按索引对齐：</p>
     * <pre>
     * rewards:
     *   extra:
     *     - name: 金币 x1000
     *       commands: ["money give {player} 1000"]
     *     - name: 工会礼包
     *       scope: win          # both（默认）/ win / lose
     *       commands: ["give {player} diamond 3"]
     * </pre>
     * <p>占位符：{player} 玩家名、{uuid}、{guild} 工会名、{guild_id}、{mode} 模式 key、
     * {score} 本方比分、{enemy_score} 对方比分。</p>
     */
    public List<CommandReward> extraRewards = new ArrayList<>();

    /** 一条自定义指令奖励：显示名称 + 一条或多条控制台指令。 */
    public static final class CommandReward {
        /** 结算消息里显示的名称，如「金币 x1000」。 */
        public String name = "";
        /** 生效范围：both（默认）/ win / lose。 */
        public String scope = "both";
        /** 控制台指令列表（不带前导 /）。 */
        public List<String> commands = new ArrayList<>();

        public boolean appliesTo(boolean win) {
            return switch (scope == null ? "both" : scope.toLowerCase(Locale.ROOT)) {
                case "win" -> win;
                case "lose" -> !win;
                default -> true;
            };
        }

        public CommandReward copy() {
            CommandReward c = new CommandReward();
            c.name = name;
            c.scope = scope;
            c.commands = new ArrayList<>(commands);
            return c;
        }
    }

    // ---- 匹配提示 ----
    /** 有玩家点击「参与匹配」时是否全服广播提示。 */
    public boolean broadcastQueue = true;

    // ---- 赛季专属 ----
    /** 开战时间表；支持 "20:00"、"sat 20:00"、"mon-fri 20:00"、"* 20:00"。 */
    public List<String> schedule = new ArrayList<>();
    /** 到点后开放报名/匹配的窗口时长（分钟）。 */
    public int seasonWindowMinutes = 10;
    /** 混战报名汇总窗口（秒）；到点后等待各子服写入名单。 */
    public int seasonRegistrationSeconds = 15;
    /** 到点是否自动把符合条件的工会加入匹配队列。 */
    public boolean autoEnqueue = false;
    /** 赛季期间额外奖励点数倍率（1.0 = 不变）。 */
    public double seasonPointsMultiplier = 1.0;

    // ---- 多工会混战 ----
    /** 是否使用多工会混战（按排名结算），而不是两两匹配。 */
    public boolean melee = false;
    /** 混战按名次发放的工会公共资金；显式配置会整体替换默认前三名。 */
    public Map<Integer, Long> rankRewards = new LinkedHashMap<>(Map.of(
            1, 5000L, 2, 3000L, 3, 1000L));

    /**
     * 读取 {@code rewards.extra}：一个奖励对象列表，每项 = 名称 + 作用范围 + 指令列表。
     */
    private static List<CommandReward> readExtraRewards(ConfigurationSection sec) {
        List<CommandReward> out = new ArrayList<>();
        for (Map<?, ?> raw : sec.getMapList("rewards.extra")) {
            CommandReward r = new CommandReward();
            Object name = raw.get("name");
            Object scope = raw.get("scope");
            if (name != null) {
                r.name = String.valueOf(name).trim();
            }
            if (scope != null) {
                String s = String.valueOf(scope).trim().toLowerCase(Locale.ROOT);
                if (s.equals("win") || s.equals("lose") || s.equals("both")) {
                    r.scope = s;
                }
            }
            Object cmds = raw.get("commands");
            if (cmds instanceof List<?> list) {
                for (Object o : list) {
                    addCommand(r.commands, String.valueOf(o));
                }
            } else if (cmds != null) {
                addCommand(r.commands, String.valueOf(cmds));
            }
            if (r.commands.isEmpty()) {
                continue;
            }
            if (r.name.isBlank()) {
                r.name = r.commands.get(0);
            }
            out.add(r);
        }
        return out;
    }

    /** 归一化单条指令：去空白、去前导 /、忽略注释行。 */
    private static void addCommand(List<String> into, String raw) {
        if (raw == null) {
            return;
        }
        String v = raw.trim();
        if (!v.isEmpty() && !v.startsWith("#")) {
            into.add(v.startsWith("/") ? v.substring(1) : v);
        }
    }

    /** 读取字符串列表（兼容单个字符串、YAML 列表两种写法）。 */
    private static List<String> readStringList(ConfigurationSection sec, String path, List<String> def) {
        if (sec.isList(path)) {
            List<String> out = new ArrayList<>();
            for (String s : sec.getStringList(path)) {
                if (s != null && !s.isBlank()) {
                    out.add(s.trim());
                }
            }
            return out;
        }
        String single = sec.getString(path);
        return single == null ? def : new ArrayList<>(List.of(single));
    }

    /** 读取指令列表：支持 YAML 列表与单个字符串（列表时忽略以 # 开头的注释行）。 */

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
        m.allowSpectators = sec.getBoolean("allow-spectators", m.allowSpectators);
        m.freezeOnPrepare = sec.getBoolean("freeze-on-prepare", m.freezeOnPrepare);
        m.allowJumpOnPrepare = sec.getBoolean("allow-jump-on-prepare", m.allowJumpOnPrepare);
        m.preparePlatform = sec.getBoolean("prepare-platform", m.preparePlatform);
        m.arena = sec.getString("arena", m.arena);
        m.winPlayerMoney = Math.max(0, sec.getDouble("rewards.win-player-money", m.winPlayerMoney));
        m.losePlayerMoney = Math.max(0, sec.getDouble("rewards.lose-player-money", m.losePlayerMoney));
        m.winGuildFunds = Math.max(0, sec.getLong("rewards.win-guild-funds", m.winGuildFunds));
        m.loseGuildFunds = Math.max(0, sec.getLong("rewards.lose-guild-funds", m.loseGuildFunds));
        m.dailyGuildFundsCap = Math.max(0, sec.getLong("rewards.daily-guild-funds-cap", m.dailyGuildFundsCap));
        m.winPassXp = Math.max(0, sec.getInt("rewards.win-pass-xp", m.winPassXp));
        m.losePassXp = Math.max(0, sec.getInt("rewards.lose-pass-xp", m.losePassXp));
        m.passId = sec.getString("rewards.pass-id", m.passId);
        m.winTitleId = Math.max(0, sec.getInt("rewards.win-title-id", m.winTitleId));
        // 只有模式显式写了 rewards.extra 才覆盖 defaults，否则继承公共额外奖励。
        if (sec.contains("rewards.extra")) {
            m.extraRewards = readExtraRewards(sec);
        }
        m.broadcastQueue = sec.getBoolean("broadcast-queue", m.broadcastQueue);
        m.schedule = readStringList(sec, "schedule", m.schedule);
        m.seasonWindowMinutes = Math.max(1, sec.getInt("season.window-minutes", m.seasonWindowMinutes));
        m.seasonRegistrationSeconds = Math.max(5, Math.min(300, sec.getInt("season.registration-seconds", m.seasonRegistrationSeconds)));
        m.autoEnqueue = sec.getBoolean("season.auto-enqueue", m.autoEnqueue);
        m.seasonPointsMultiplier = Math.max(1.0, sec.getDouble("season.points-multiplier", m.seasonPointsMultiplier));
        // 名为 season 的内置模式默认就是混战；自定义模式必须显式写 season.melee: true。
        m.melee = sec.getBoolean("season.melee", "season".equalsIgnoreCase(key) || m.melee);
        ConfigurationSection rankSection = sec.getConfigurationSection("season.rank-rewards");
        if (rankSection != null) {
            Map<Integer, Long> ranks = new LinkedHashMap<>();
            for (String rank : rankSection.getKeys(false)) {
                try {
                    int place = Integer.parseInt(rank.trim());
                    long value = rankSection.getLong(rank, -1);
                    if (place >= 1 && value >= 0) {
                        ranks.put(place, value);
                    }
                } catch (NumberFormatException ignored) {
                    // 非数字名次忽略，不影响其余奖励
                }
            }
            m.rankRewards = ranks;
        }
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
        c.allowSpectators = allowSpectators;
        c.freezeOnPrepare = freezeOnPrepare;
        c.allowJumpOnPrepare = allowJumpOnPrepare;
        c.preparePlatform = preparePlatform;
        c.arena = arena;
        c.winPlayerMoney = winPlayerMoney;
        c.losePlayerMoney = losePlayerMoney;
        c.winGuildFunds = winGuildFunds;
        c.loseGuildFunds = loseGuildFunds;
        c.dailyGuildFundsCap = dailyGuildFundsCap;
        c.winPassXp = winPassXp;
        c.losePassXp = losePassXp;
        c.passId = passId;
        c.winTitleId = winTitleId;
        c.extraRewards = new ArrayList<>();
        for (CommandReward r : extraRewards) {
            c.extraRewards.add(r.copy());
        }
        c.broadcastQueue = broadcastQueue;
        c.schedule = new ArrayList<>(schedule);
        c.seasonWindowMinutes = seasonWindowMinutes;
        c.seasonRegistrationSeconds = seasonRegistrationSeconds;
        c.autoEnqueue = autoEnqueue;
        c.seasonPointsMultiplier = seasonPointsMultiplier;
        c.melee = melee;
        c.rankRewards = new LinkedHashMap<>(rankRewards);
        return c;
    }

    /** 是否按时间表开启（赛季类模式）。 */
    public boolean scheduled() {
        return schedule != null && !schedule.isEmpty();
    }

    public boolean hasSchedule(String entry) {
        return schedule.contains(entry);
    }

    public String displayUpper() {
        return display == null ? key : display.toUpperCase(Locale.ROOT);
    }
}
