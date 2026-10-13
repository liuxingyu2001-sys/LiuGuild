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
        return a;
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
