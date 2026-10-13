package com.liu.liuguild.config;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

/**
 * 单个竞技场场地定义（arena.yml 中的一项）。
 * <p>直接写清楚「承办服 + 世界 + 坐标」，因此每条竞技场都是自描述、可完全自定义的：
 * <ul>
 *   <li>{@link #server} 承办服 server-id（配合 {@code war.server-mode}，跨服时玩家会被传送到该服）</li>
 *   <li>{@link #world} 世界名（可直接用固定世界，无需 liudungeon）</li>
 *   <li>{@link #spawnA}/{@link #spawnB}/{@link #spawnSpectator} 精确坐标与朝向</li>
 *   <li>{@link #template} 可选的 liudungeon 动态模板（填了且存在则动态开世界，否则用固定世界坐标）</li>
 * </ul>
 */
public final class ArenaDef {

    public String id = "main";
    /** 承办服 server-id；留空 = 全局 war.dedicated-server / 发起匹配的服务器。 */
    public String server = "";
    /** 固定世界名；留空 = 主世界。 */
    public String world = "";
    /** 可选：liudungeon 动态模板名（留空或不存在则使用固定世界坐标）。 */
    public String template = "";
    /** 预热半径（方块）；0 = 不预热。 */
    public int radius = 48;
    /** 坐标：X / Y / Z / yaw / pitch。 */
    public Spawn spawnA = new Spawn(0.5, 70, 0.5, 0, 0);
    public Spawn spawnB = new Spawn(20.5, 70, 0.5, 180, 0);
    public Spawn spawnSpectator = new Spawn(10.5, 80, 10.5, 0, 30);

    /** 临时（动态）世界设置，仅在 template 生效且有 liudungeon 时使用。 */
    public final TempWorld tempWorld = new TempWorld();
    /** 战斗结束后的返回 / 集合行为。 */
    public final AfterWar afterWar = new AfterWar();

    /** 临时世界（动态模板）设置。 */
    public static final class TempWorld {
        /** 结束后是否保留该世界（false = 销毁）。 */
        public boolean keepOnEnd = false;
        /** 环境：NORMAL / NETHER / THE_END。 */
        public String environment = "NORMAL";
        /** 动态世界名格式，{id} 会被替换为竞技场 id。 */
        public String nameFormat = "guild_arena_{id}";
        /** 战斗期间应用的 gamerule（键为 gamerule 名称）。 */
        public final java.util.Map<String, Boolean> gamerules = new java.util.LinkedHashMap<>();
        /** 固定世界时间（-1 = 不修改）。 */
        public long time = -1;
        /** 天气：clear / rain / thunder（空 = 不修改）。 */
        public String weather = "";
        /** 难度：PEACEFUL / EASY / NORMAL / HARD（空 = 不修改）。 */
        public String difficulty = "";
    }

    /** 战后行为。 */
    public static final class AfterWar {
        /** 是否自动把玩家送回参赛前位置。 */
        public boolean autoReturn = true;
        /** 返回前延迟（秒）。 */
        public int returnDelaySeconds = 0;
        /** 是否启用统一集合点（优先级高于 auto-return）。 */
        public boolean endSpawnEnabled = false;
        /** 集合点世界名；留空 = 使用竞技场世界。 */
        public String endSpawnWorld = "";
        /** 统一集合点坐标。 */
        public Spawn endSpawn = new Spawn(0.5, 70, 0.5, 0, 0);
    }

    public static ArenaDef parse(String id, ConfigurationSection sec) {
        ArenaDef a = new ArenaDef();
        a.id = id;
        if (sec == null) {
            return a;
        }
        // 兼容旧的 dedicated-server 写法
        a.server = sec.getString("server", sec.getString("dedicated-server", a.server));
        a.world = sec.getString("world", a.world);
        a.template = sec.getString("template", a.template);
        a.radius = Math.max(0, Math.min(4096, sec.getInt("radius", a.radius)));
        a.spawnA = readSpawn(sec.getConfigurationSection("spawn-a"), a.spawnA);
        a.spawnB = readSpawn(sec.getConfigurationSection("spawn-b"), a.spawnB);
        a.spawnSpectator = readSpawn(sec.getConfigurationSection("spawn-spectator"), a.spawnSpectator);
        readTempWorld(sec.getConfigurationSection("temporary-world"), a.tempWorld);
        readAfterWar(sec.getConfigurationSection("after-war"), a.afterWar);
        return a;
    }

    private static void readTempWorld(ConfigurationSection sec, TempWorld tw) {
        if (sec == null) {
            return;
        }
        tw.keepOnEnd = sec.getBoolean("keep-on-end", tw.keepOnEnd);
        tw.environment = sec.getString("environment", tw.environment);
        tw.nameFormat = sec.getString("name-format", tw.nameFormat);
        tw.time = sec.getLong("time", tw.time);
        tw.weather = sec.getString("weather", tw.weather);
        tw.difficulty = sec.getString("difficulty", tw.difficulty);
        ConfigurationSection rules = sec.getConfigurationSection("gamerules");
        if (rules != null) {
            for (String key : rules.getKeys(false)) {
                tw.gamerules.put(key, rules.getBoolean(key));
            }
        }
    }

    private static void readAfterWar(ConfigurationSection sec, AfterWar aw) {
        if (sec == null) {
            return;
        }
        aw.autoReturn = sec.getBoolean("auto-return", aw.autoReturn);
        aw.returnDelaySeconds = Math.max(0, sec.getInt("return-delay-seconds", aw.returnDelaySeconds));
        ConfigurationSection end = sec.getConfigurationSection("end-spawn");
        if (end != null) {
            aw.endSpawnEnabled = end.getBoolean("enabled", aw.endSpawnEnabled);
            aw.endSpawnWorld = end.getString("world", aw.endSpawnWorld);
            aw.endSpawn = readSpawn(end, aw.endSpawn);
        }
    }

    public boolean hasTemplate() {
        return template != null && !template.isBlank();
    }

    private static Spawn readSpawn(ConfigurationSection sec, Spawn def) {
        if (sec == null) {
            return def;
        }
        return new Spawn(sec.getDouble("x", def.x), sec.getDouble("y", def.y), sec.getDouble("z", def.z),
                (float) sec.getDouble("yaw", def.yaw), (float) sec.getDouble("pitch", def.pitch));
    }

    /** 竞技场出生点：X / Y / Z / yaw / pitch。 */
    public record Spawn(double x, double y, double z, float yaw, float pitch) {
        public Location toLocation(World world) {
            return new Location(world, x, y, z, yaw, pitch);
        }
    }
}
