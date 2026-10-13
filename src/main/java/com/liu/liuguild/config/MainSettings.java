package com.liu.liuguild.config;

import org.bukkit.configuration.file.FileConfiguration;

/** 主配置模块（config.yml：settings / server-id / cross-server）。 */
public final class MainSettings {

    public String language = "zh_CN";
    public boolean autoReload = true;
    public boolean debug = false;
    public String serverId = "";

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
    /** Redis 不可用时是否自动降级为单服模式。 */
    public boolean localFallback = true;

    public static MainSettings parse(FileConfiguration c) {
        MainSettings s = new MainSettings();
        s.language = c.getString("settings.language", s.language);
        s.autoReload = c.getBoolean("settings.auto-reload-config", s.autoReload);
        s.debug = c.getBoolean("settings.debug", s.debug);
        s.serverId = c.getString("server-id", s.serverId);

        s.crossServerEnabled = c.getBoolean("cross-server.enabled", s.crossServerEnabled);
        s.redisHost = c.getString("cross-server.redis.host", s.redisHost);
        s.redisPort = clamp(c.getInt("cross-server.redis.port", s.redisPort), 1, 65535);
        s.redisPassword = c.getString("cross-server.redis.password", s.redisPassword);
        s.redisDatabase = Math.max(0, c.getInt("cross-server.redis.database", s.redisDatabase));
        s.redisPoolSize = clamp(c.getInt("cross-server.redis.pool-size", s.redisPoolSize), 1, 64);
        s.redisTimeoutMs = Math.max(200, c.getInt("cross-server.redis.timeout-ms", s.redisTimeoutMs));
        s.redisChannel = sanitize(c.getString("cross-server.redis.channel", s.redisChannel), "liuguild");
        s.transferEnabled = c.getBoolean("cross-server.transfer.enabled", s.transferEnabled);
        s.heartbeatSeconds = clamp(c.getInt("cross-server.heartbeat-seconds", s.heartbeatSeconds), 2, 60);
        return s;
    }

    /** 用独立的 cross-server.yml 覆盖跨服段（优先于 config.yml 内嵌配置）。 */
    public static void applyCrossServer(MainSettings s, FileConfiguration c) {
        if (c == null) {
            return;
        }
        s.crossServerEnabled = c.getBoolean("enabled", s.crossServerEnabled);
        s.redisHost = c.getString("redis.host", s.redisHost);
        s.redisPort = clamp(c.getInt("redis.port", s.redisPort), 1, 65535);
        s.redisPassword = c.getString("redis.password", s.redisPassword);
        s.redisDatabase = Math.max(0, c.getInt("redis.database", s.redisDatabase));
        s.redisPoolSize = clamp(c.getInt("redis.pool-size", s.redisPoolSize), 1, 64);
        s.redisTimeoutMs = Math.max(200, c.getInt("redis.timeout-ms", s.redisTimeoutMs));
        s.redisChannel = sanitize(c.getString("redis.channel", s.redisChannel), "liuguild");
        s.transferEnabled = c.getBoolean("transfer.enabled", s.transferEnabled);
        s.heartbeatSeconds = clamp(c.getInt("heartbeat-seconds", s.heartbeatSeconds), 2, 60);
        s.localFallback = c.getBoolean("local-fallback", s.localFallback);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static String sanitize(String v, String fallback) {
        if (v == null) {
            return fallback;
        }
        String out = v.trim().replace(":", "").replace(" ", "");
        return out.isEmpty() ? fallback : out;
    }
}
