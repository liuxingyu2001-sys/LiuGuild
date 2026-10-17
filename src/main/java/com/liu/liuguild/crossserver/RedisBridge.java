package com.liu.liuguild.crossserver;

import com.liu.liuguild.config.MainSettings;
import org.bukkit.plugin.Plugin;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Redis 连接与 Pub/Sub 基础设施（模式对齐 liudungeon 的 RedisBridge）：
 * <ul>
 *   <li>所有键以 {@code <channel>:} 为前缀，隔离共用同一 Redis 的多个群组；</li>
 *   <li>心跳带 TTL，崩溃的服务器自动从在线列表消失；</li>
 *   <li>连续失败熔断，避免 Redis 挂掉后每次操作都等超时；订阅线程自动重连。</li>
 * </ul>
 */
public final class RedisBridge {

    public static final int HEARTBEAT_TTL_SECONDS = 15;
    /** 在线/子服键的 TTL 由心跳周期推导，避免 heartbeat-seconds 配大后键必然过期。 */
    public int heartbeatTtlSeconds() {
        return Math.max(HEARTBEAT_TTL_SECONDS, Math.max(2, conf.heartbeatSeconds) * 3);
    }
    private static final int DEGRADE_THRESHOLD = 3;

    private final Plugin plugin;
    private final MainSettings conf;
    private final String prefix;

    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile boolean degraded;
    private volatile JedisPool pool;
    private volatile String lastError;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile JedisPubSub subscriber;

    public RedisBridge(Plugin plugin, MainSettings conf) {
        this.plugin = plugin;
        this.conf = conf;
        this.prefix = conf.redisChannel + ":";
    }

    // =====================================================================
    // 连接
    // =====================================================================

    public boolean connect() {
        try {
            JedisPoolConfig cfg = new JedisPoolConfig();
            cfg.setMaxTotal(conf.redisPoolSize);
            cfg.setMaxIdle(conf.redisPoolSize);
            cfg.setMinIdle(1);
            // 每次借连接都 PING 会给每条 Redis 命令叠加一次 RTT；改为空闲检测。
            cfg.setTestOnBorrow(false);
            cfg.setTestWhileIdle(true);
            cfg.setTimeBetweenEvictionRuns(java.time.Duration.ofSeconds(30));
            cfg.setMaxWait(java.time.Duration.ofMillis(conf.redisTimeoutMs));

            String password = conf.redisPassword == null || conf.redisPassword.isBlank()
                    ? null : conf.redisPassword;
            this.pool = new JedisPool(cfg, conf.redisHost, conf.redisPort,
                    conf.redisTimeoutMs, password, conf.redisDatabase);

            try (Jedis jedis = pool.getResource()) {
                String pong = jedis.ping();
                if (!"PONG".equalsIgnoreCase(pong)) {
                    this.lastError = "PING 返回 " + pong;
                    closePool();
                    return false;
                }
            }
            this.lastError = null;
            return true;
        } catch (Throwable t) {
            this.lastError = describe(t);
            closePool();
            return false;
        }
    }

    public boolean isHealthy() {
        return pool != null && !closed.get() && !degraded;
    }

    public boolean isHealthyIgnoringDegrade() {
        return pool != null && !closed.get();
    }

    public boolean isDegraded() {
        return degraded;
    }

    public String lastError() {
        return lastError;
    }

    public String prefix() {
        return prefix;
    }

    private void markSuccess() {
        if (consecutiveFailures.getAndSet(0) > 0 || degraded) {
            degraded = false;
            plugin.getLogger().info("跨服 Redis 连接已恢复。");
        }
    }

    private void markFailure(String error) {
        this.lastError = error;
        int n = consecutiveFailures.incrementAndGet();
        if (n >= DEGRADE_THRESHOLD && !degraded) {
            degraded = true;
            plugin.getLogger().warning("跨服 Redis 连续 " + n + " 次操作失败，暂时停用跨服功能（退回仅本服），"
                    + "后台会自动重试。原因: " + error);
        }
    }

    public <T> T withJedis(Function<Jedis, T> action) {
        return withJedis(action, false);
    }

    public <T> T withJedisProbe(Function<Jedis, T> action) {
        return withJedis(action, true);
    }

    private <T> T withJedis(Function<Jedis, T> action, boolean probe) {
        if (probe ? !isHealthyIgnoringDegrade() : !isHealthy()) {
            return null;
        }
        try (Jedis jedis = pool.getResource()) {
            T result = action.apply(jedis);
            markSuccess();
            return result;
        } catch (Throwable t) {
            markFailure(describe(t));
            return null;
        }
    }

    // =====================================================================
    // 键
    // =====================================================================

    public String keyServer(String serverId) {
        return prefix + "srv:" + serverId;
    }

    public String keyPresence(java.util.UUID player) {
        return prefix + "presence:" + player;
    }

    public String keyName(String normalizedName) {
        return prefix + "name:" + normalizedName;
    }

    public String keyGuild(String guildId) {
        return prefix + "guild:" + guildId;
    }

    public String keyWar(String warId) {
        return prefix + "war:" + warId;
    }

    public String keyWarQueue() {
        return prefix + "war:queue";
    }

    public String keyWarQueueMeta(String guildId) {
        return prefix + "war:queue:meta:" + guildId;
    }

    public String keyWarPending(java.util.UUID player) {
        return prefix + "war:pending:" + player;
    }

    /** 跨服旁观标记（warId + 出发地）。 */
    public String keyWarSpectate(java.util.UUID player) {
        return prefix + "war:spectate:" + player;
    }

    /** 赛后跨服返程目的地的临时标记（世界 + 坐标）。 */
    public String keyWarReturn(java.util.UUID player) {
        return prefix + "war:return:" + player;
    }

    /** 承办服忙碌/占用标记：战斗期间由承办服续期，匹配服据此跳过该承办服。 */
    public String keyWarHost(String host) {
        return prefix + "war:host:" + (host == null ? "" : host.toLowerCase(java.util.Locale.ROOT));
    }

    public String keyArenaPending(java.util.UUID player) {
        return prefix + "arena:pending:" + player;
    }

    public String keyMatchLock() {
        return prefix + "war:matchlock";
    }

    public String channelAll() {
        return prefix + "broadcast";
    }

    public String channelFor(String serverId) {
        return prefix + "msg:" + serverId;
    }

    public String myChannel() {
        return channelFor(conf.serverId);
    }

    // =====================================================================
    // 心跳 / 在线
    // =====================================================================

    public boolean refreshHeartbeat() {
        Boolean ok = withJedisProbe(jedis -> {
            jedis.setex(keyServer(conf.serverId), heartbeatTtlSeconds(), String.valueOf(System.currentTimeMillis()));
            return Boolean.TRUE;
        });
        return Boolean.TRUE.equals(ok);
    }

    public void removeHeartbeat() {
        withJedisProbe(jedis -> {
            jedis.del(keyServer(conf.serverId));
            return null;
        });
    }

    public Map<String, String> onlineServers() {
        Map<String, String> out = new LinkedHashMap<>();
        withJedis(jedis -> {
            // 使用 SCAN 而非 KEYS，避免在共享 Redis 上阻塞。
            redis.clients.jedis.params.ScanParams params = new redis.clients.jedis.params.ScanParams()
                    .match(prefix + "srv:*").count(100);
            String cursor = redis.clients.jedis.params.ScanParams.SCAN_POINTER_START;
            do {
                redis.clients.jedis.resps.ScanResult<String> res = jedis.scan(cursor, params);
                cursor = res.getCursor();
                for (String k : res.getResult()) {
                    out.put(k.substring((prefix + "srv:").length()), jedis.get(k));
                }
            } while (!redis.clients.jedis.params.ScanParams.SCAN_POINTER_START.equals(cursor));
            return null;
        });
        return out;
    }

    // =====================================================================
    // Pub/Sub
    // =====================================================================

    public void subscribe(java.util.function.BiConsumer<String, String> handler) {
        subscribe(handler, null);
    }

    /**
     * 订阅跨服消息。
     *
     * @param onSubscribed 真正 SUBSCRIBE 成功后的回调（用于把 ready 置 true，
     *                     避免“线程刚启动就宣布可用”导致自己发出的消息收不到回声）
     */
    public void subscribe(java.util.function.BiConsumer<String, String> handler, Runnable onSubscribed) {
        if (!isHealthyIgnoringDegrade() && pool == null) {
            return;
        }
        final JedisPubSub sub = new JedisPubSub() {
            @Override
            public void onSubscribe(String channel, int subscribedChannels) {
                if (onSubscribed != null) {
                    onSubscribed.run();
                }
            }

            @Override
            public void onMessage(String channel, String message) {
                try {
                    int bar = message.indexOf('|');
                    String type = bar < 0 ? message : message.substring(0, bar);
                    String payload = bar < 0 ? "" : message.substring(bar + 1);
                    handler.accept(type, payload);
                } catch (Throwable t) {
                    plugin.getLogger().warning("处理跨服消息失败: " + t.getMessage());
                }
            }
        };
        this.subscriber = sub;

        Thread t = new Thread(() -> {
            while (!closed.get() && isHealthyIgnoringDegrade()) {
                try (Jedis jedis = pool.getResource()) {
                    jedis.subscribe(sub, myChannel(), channelAll());
                } catch (Throwable ex) {
                    if (closed.get()) {
                        return;
                    }
                    plugin.getLogger().warning("跨服订阅中断，2 秒后重连: " + ex.getMessage());
                    try {
                        Thread.sleep(2000L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }, "liuguild-redis-sub");
        t.setDaemon(true);
        t.start();
    }

    public boolean publish(String targetServerId, String type, String payload) {
        Boolean ok = withJedis(jedis -> {
            jedis.publish(channelFor(targetServerId), type + "|" + payload);
            return Boolean.TRUE;
        });
        return Boolean.TRUE.equals(ok);
    }

    public int broadcast(String type, String payload) {
        Boolean ok = withJedis(jedis -> {
            jedis.publish(channelAll(), type + "|" + payload);
            return Boolean.TRUE;
        });
        return Boolean.TRUE.equals(ok) ? 1 : 0;
    }

    // =====================================================================
    // 关闭
    // =====================================================================

    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        JedisPubSub sub = subscriber;
        if (sub != null && sub.isSubscribed()) {
            try {
                sub.unsubscribe();
            } catch (Throwable ignored) {
            }
        }
        closePool();
    }

    private void closePool() {
        JedisPool p = pool;
        pool = null;
        if (p != null) {
            try {
                p.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static String describe(Throwable t) {
        String msg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        String lower = msg.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("auth") || lower.contains("noauth") || lower.contains("wrongpass")) {
            return "认证失败，请检查 cross-server.redis.password（" + msg + "）";
        }
        if (lower.contains("refused")) {
            return "连接被拒绝，Redis 似乎没运行或端口不对（" + msg + "）";
        }
        if (lower.contains("timeout") || lower.contains("timed out")) {
            return "连接超时，请检查网络与 cross-server.redis.timeout-ms（" + msg + "）";
        }
        if (lower.contains("unknownhost") || lower.contains("name or service")) {
            return "主机名解析失败，请检查 cross-server.redis.host（" + msg + "）";
        }
        return msg;
    }
}
