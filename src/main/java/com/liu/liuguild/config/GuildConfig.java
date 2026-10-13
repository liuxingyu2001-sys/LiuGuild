package com.liu.liuguild.config;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;

/** config.yml 解析结果（原子替换，解析错误单独收集）。 */
public final class GuildConfig {

    public final List<String> parseErrors = new ArrayList<>();

    // ---- settings ----
    public String language = "zh_CN";
    public boolean autoReload = true;
    public String serverId = "";

    // ---- cross-server ----
    public boolean crossServerEnabled = true;
    public String redisHost = "127.0.0.1";
    public int redisPort = 6379;
    public String redisPassword = "";
    public int redisDatabase = 0;
    public int redisPoolSize = 8;
    public int redisTimeoutMs = 2000;
    public String redisChannel = "liuguild";
    public boolean transferEnabled = true;
    public int heartbeatSeconds = 5;

    // ---- guild ----
    public int nameMin = 2;
    public int nameMax = 16;
    public int tagMin = 2;
    public int tagMax = 5;
    public double createCost = 10000.0;
    public int maxMembers = 50;
    public int maxMembersPerLevel = 5;
    public int inviteExpireSeconds = 300;
    public int inviteCooldownSeconds = 30;
    public boolean bankEnabled = true;
    public double bankDepositTaxPercent = 0.0;
    public long baseRequiredPoints = 1000L;
    public double requiredGrowth = 1.5;
    public int maxLevel = 20;
    public boolean friendlyFire = false;
    public boolean officerInvite = true;
    public boolean officerKick = true;
    public boolean officerDeclareWar = true;
    public boolean officerSetNotice = true;

    // ---- war ----
    public boolean warEnabled = true;
    public int queueIntervalSeconds = 5;
    public int matchTimeoutSeconds = 180;
    public int prepareSeconds = 30;
    public int durationSeconds = 600;
    public int settleSeconds = 10;
    public int teamSize = 10;
    public int warMinMembers = 1;
    public int scoreKill = 1;
    public int scoreDeath = -1;
    public int scoreObjective = 5;
    public String arenaTemplate = "guild_arena";
    public String arenaWorld = "";
    /** 竞技场预热半径（方块），围绕两个出生点中心；0 = 不预热。 */
    public int arenaRadius = 48;
    public Spawn spawnA = new Spawn(0.5, 70, 0.5, 0, 0);
    public Spawn spawnB = new Spawn(20.5, 70, 0.5, 180, 0);
    public double winMoney = 5000.0;
    public double loseMoney = 1000.0;
    public long winPoints = 200L;
    public long losePoints = 50L;
    public int winPassXp = 500;
    public int losePassXp = 100;
    public String passId = "";

    // ---- 工会战专属服 / 统一装备 ----
    /** 本服是否为工会战专属承办服：只会承接工会战，不参与常规玩法分发。 */
    public boolean warServer = false;
    /** 进入工会战时清空背包并发放统一装备。 */
    public boolean kitEnabled = true;
    public boolean kitClearInventory = true;
    /** 统一装备物品（支持 {@code CE:namespace:id} 引用 CraftEngine 自定义物品）。 */
    public java.util.List<String> kitItems = new java.util.ArrayList<>(java.util.List.of(
            "DIAMOND_SWORD", "GOLDEN_APPLE:8", "COOKED_BEEF:16"));
    /** 统一护甲：helmet/chestplate/leggings/boots → 物品。 */
    public java.util.Map<String, String> kitArmor = new java.util.LinkedHashMap<>();
    /** 统一药水效果：TYPE:amplifier（amplifier 从 0 开始）。 */
    public java.util.List<String> kitEffects = new java.util.ArrayList<>();
    /** 战斗期间死亡是否保留物品（统一装备下建议 false，死亡直接重生）。 */
    public boolean keepInventory = false;
    /** 允许旁观者进入竞技场。 */
    public boolean allowSpectators = true;

    public void parse(FileConfiguration c) {
        parseErrors.clear();
        language = c.getString("settings.language", language);
        autoReload = c.getBoolean("settings.auto-reload-config", autoReload);
        serverId = c.getString("server-id", serverId);

        crossServerEnabled = c.getBoolean("cross-server.enabled", crossServerEnabled);
        redisHost = c.getString("cross-server.redis.host", redisHost);
        redisPort = clamp(c.getInt("cross-server.redis.port", redisPort), 1, 65535);
        redisPassword = c.getString("cross-server.redis.password", redisPassword);
        redisDatabase = Math.max(0, c.getInt("cross-server.redis.database", redisDatabase));
        redisPoolSize = clamp(c.getInt("cross-server.redis.pool-size", redisPoolSize), 1, 64);
        redisTimeoutMs = Math.max(200, c.getInt("cross-server.redis.timeout-ms", redisTimeoutMs));
        redisChannel = sanitize(c.getString("cross-server.redis.channel", redisChannel), "liuguild");
        transferEnabled = c.getBoolean("cross-server.transfer.enabled", transferEnabled);
        heartbeatSeconds = clamp(c.getInt("cross-server.heartbeat-seconds", heartbeatSeconds), 2, 60);

        nameMin = Math.max(1, c.getInt("guild.name.min-length", nameMin));
        nameMax = Math.max(nameMin, c.getInt("guild.name.max-length", nameMax));
        tagMin = Math.max(1, c.getInt("guild.tag.min-length", tagMin));
        tagMax = Math.max(tagMin, c.getInt("guild.tag.max-length", tagMax));
        createCost = c.getDouble("guild.create-cost", createCost);
        maxMembers = Math.max(1, c.getInt("guild.max-members", maxMembers));
        maxMembersPerLevel = Math.max(0, c.getInt("guild.max-members-per-level", maxMembersPerLevel));
        inviteExpireSeconds = Math.max(10, c.getInt("guild.invite.expire-seconds", inviteExpireSeconds));
        inviteCooldownSeconds = Math.max(0, c.getInt("guild.invite.cooldown-seconds", inviteCooldownSeconds));
        bankEnabled = c.getBoolean("guild.bank.enabled", bankEnabled);
        bankDepositTaxPercent = clamp(c.getDouble("guild.bank.deposit-tax-percent", bankDepositTaxPercent), 0, 100);
        baseRequiredPoints = Math.max(1, c.getLong("guild.level.base-required-points", baseRequiredPoints));
        requiredGrowth = Math.max(1.0, c.getDouble("guild.level.required-growth", requiredGrowth));
        maxLevel = Math.max(1, c.getInt("guild.level.max-level", maxLevel));
        friendlyFire = c.getBoolean("guild.friendly-fire", friendlyFire);
        officerInvite = c.getBoolean("guild.officer.invite", officerInvite);
        officerKick = c.getBoolean("guild.officer.kick", officerKick);
        officerDeclareWar = c.getBoolean("guild.officer.declare-war", officerDeclareWar);
        officerSetNotice = c.getBoolean("guild.officer.set-notice", officerSetNotice);

        warEnabled = c.getBoolean("war.enabled", warEnabled);
        queueIntervalSeconds = clamp(c.getInt("war.queue-interval-seconds", queueIntervalSeconds), 1, 60);
        matchTimeoutSeconds = Math.max(0, c.getInt("war.match-timeout-seconds", matchTimeoutSeconds));
        prepareSeconds = Math.max(1, c.getInt("war.prepare-seconds", prepareSeconds));
        durationSeconds = Math.max(10, c.getInt("war.duration-seconds", durationSeconds));
        settleSeconds = Math.max(1, c.getInt("war.settle-seconds", settleSeconds));
        teamSize = Math.max(1, c.getInt("war.team-size", teamSize));
        warMinMembers = Math.max(1, c.getInt("war.min-members", warMinMembers));
        scoreKill = c.getInt("war.score.kill", scoreKill);
        scoreDeath = c.getInt("war.score.death", scoreDeath);
        scoreObjective = c.getInt("war.score.objective", scoreObjective);
        arenaTemplate = c.getString("war.arena.template", arenaTemplate);
        arenaWorld = c.getString("war.arena.world", arenaWorld);
        arenaRadius = Math.max(0, Math.min(1024, c.getInt("war.arena.radius", arenaRadius)));
        spawnA = readSpawn(c, "war.arena.spawn-a", spawnA);
        spawnB = readSpawn(c, "war.arena.spawn-b", spawnB);
        winMoney = c.getDouble("war.rewards.win-money", winMoney);
        loseMoney = c.getDouble("war.rewards.lose-money", loseMoney);
        winPoints = c.getLong("war.rewards.win-points", winPoints);
        losePoints = c.getLong("war.rewards.lose-points", losePoints);
        winPassXp = c.getInt("war.rewards.win-pass-xp", winPassXp);
        losePassXp = c.getInt("war.rewards.lose-pass-xp", losePassXp);
        passId = c.getString("war.rewards.pass-id", passId);

        warServer = c.getBoolean("war.server-mode", warServer);
        kitEnabled = c.getBoolean("war.kit.enabled", kitEnabled);
        kitClearInventory = c.getBoolean("war.kit.clear-inventory", kitClearInventory);
        kitItems = new java.util.ArrayList<>(c.getStringList("war.kit.items"));
        kitEffects = new java.util.ArrayList<>(c.getStringList("war.kit.effects"));
        kitArmor = new java.util.LinkedHashMap<>();
        ConfigurationSection armor = c.getConfigurationSection("war.kit.armor");
        if (armor != null) {
            for (String key : armor.getKeys(false)) {
                kitArmor.put(key.toLowerCase(java.util.Locale.ROOT), String.valueOf(armor.get(key)));
            }
        }
        keepInventory = c.getBoolean("war.keep-inventory", keepInventory);
        allowSpectators = c.getBoolean("war.allow-spectators", allowSpectators);

        if (redisHost == null || redisHost.isBlank()) {
            parseErrors.add("cross-server.redis.host 为空，跨服将退回仅本服模式");
        }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private static String sanitize(String v, String fallback) {
        if (v == null) {
            return fallback;
        }
        String out = v.trim().replace(":", "").replace(" ", "");
        return out.isEmpty() ? fallback : out;
    }

    private Spawn readSpawn(FileConfiguration c, String path, Spawn def) {
        ConfigurationSection s = c.getConfigurationSection(path);
        if (s == null) {
            return def;
        }
        return new Spawn(
                s.getDouble("x", def.x),
                s.getDouble("y", def.y),
                s.getDouble("z", def.z),
                (float) s.getDouble("yaw", def.yaw),
                (float) s.getDouble("pitch", def.pitch));
    }

    /** 成员上限 = 基础 + (等级-1) * 每级增量。 */
    public int memberLimit(int level) {
        return maxMembers + Math.max(0, level - 1) * maxMembersPerLevel;
    }

    /** 从 level 升到 level+1 所需点数。 */
    public long requiredPoints(int level) {
        return Math.max(1L, Math.round(baseRequiredPoints * Math.pow(requiredGrowth, Math.max(0, level - 1))));
    }

    public record Spawn(double x, double y, double z, float yaw, float pitch) {
        public Location toLocation(World world) {
            return new Location(world, x, y, z, yaw, pitch);
        }
    }
}
