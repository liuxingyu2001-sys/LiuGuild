package com.liu.liuguild.crossserver;

import com.liu.liuguild.config.MainSettings;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import redis.clients.jedis.Jedis;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 跨服总线：Redis 连接 + Pub/Sub 分发 + 在线存在 + 工会战匹配队列。
 * <p>Redis 不可用时整体退化为「仅本服」，{@link #publish} 会直接本地分发，
 * 保证单服也能完整玩工会与工会战。</p>
 */
public final class CrossServerBus {

    /** 字段分隔（记录分隔 / 单元分隔），避免与玩家名、工会名冲突。 */
    private static final char REC = '\u001e';
    private static final char UNIT = '\u001f';

    public interface Listener {
        void on(String type, Map<String, String> data);
    }

    private final Plugin plugin;
    private final MainSettings conf;
    private final RedisBridge bridge;
    private final ServerTransfer transfer;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private volatile boolean ready;

    public CrossServerBus(Plugin plugin, MainSettings conf, RedisBridge bridge, ServerTransfer transfer) {
        this.plugin = plugin;
        this.conf = conf;
        this.bridge = bridge;
        this.transfer = transfer;
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public RedisBridge bridge() {
        return bridge;
    }

    public ServerTransfer transfer() {
        return transfer;
    }

    public MainSettings conf() {
        return conf;
    }

    /** 跨服是否真正可用（Redis 连上且订阅启动）。 */
    public boolean isReady() {
        return ready && bridge.isHealthy();
    }

    public String myServer() {
        return conf.serverId;
    }

    // =====================================================================
    // 初始化
    // =====================================================================

    public void startAsync() {
        if (!conf.crossServerEnabled) {
            plugin.getLogger().info("跨服功能：未启用（cross-server.enabled=false），仅本服模式。");
            return;
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            if (!bridge.connect()) {
                plugin.getLogger().warning("跨服不可用，已退回仅本服模式。原因: " + bridge.lastError()
                        + "（不影响单服工会功能；配好 cross-server.redis 后重启即可启用）");
                return;
            }
            bridge.subscribe(this::handleIncoming);
            ready = true;
            bridge.refreshHeartbeat();
            plugin.getLogger().info("跨服已就绪：server-id=" + conf.serverId
                    + "，Redis " + conf.redisHost + ":" + conf.redisPort
                    + "，频道 " + conf.redisChannel);
        });
    }

    private void handleIncoming(String type, String payload) {
        Map<String, String> data = parse(payload);
        dispatch(type, data);
    }

    private void dispatch(String type, Map<String, String> data) {
        for (Listener l : listeners) {
            try {
                l.on(type, data);
            } catch (Throwable t) {
                plugin.getLogger().warning("跨服消息处理异常(" + type + "): " + t.getMessage());
            }
        }
    }

    // =====================================================================
    // 发布
    // =====================================================================

    public void publish(String type, Map<String, String> data) {
        if (isReady()) {
            bridge.broadcast(type, encode(data));
        } else {
            dispatch(type, data);
        }
    }

    public void publishTo(String server, String type, Map<String, String> data) {
        if (isReady()) {
            bridge.publish(server, type, encode(data));
        } else {
            dispatch(type, data);
        }
    }

    /**
     * 全局一次性任务抢锁（多服共享配置时的“单写者”保证）。
     * <p>用 Redis {@code SET key NX EX ttl} 原子抢占，只有一台子服会得到 true；
     * Redis 不可用时退化为本地执行（返回 true），由调用方按单服语义处理。</p>
     *
     * @param key        业务键（内部自动加 prefix）
     * @param ttlSeconds 锁存活时间，需 ≥ 任务周期，防止崩溃后死锁
     * @return true = 本次由本服执行
     */
    public boolean acquireOnce(String key, long ttlSeconds) {
        if (!bridge.isHealthy()) {
            return true;
        }
        Boolean ok = bridge.withJedis(jedis -> "OK".equals(jedis.set(
                bridge.prefix() + "lock:" + key, conf.serverId,
                redis.clients.jedis.params.SetParams.setParams().nx().ex(Math.max(1, ttlSeconds)))));
        return Boolean.TRUE.equals(ok);
    }

    public void shutdown() {
        ready = false;
        bridge.removeHeartbeat();
        bridge.close();
    }

    // =====================================================================
    // 在线存在（TTL 心跳）
    // =====================================================================

    /** 主线程调用：登记在线玩家，令其他子服可按名字找到他们。 */
    public void tickPresence(Collection<? extends Player> online) {
        if (!bridge.isHealthy() && !bridge.isHealthyIgnoringDegrade()) {
            return;
        }
        List<Player> snapshot = new ArrayList<>(online);
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            if (!bridge.isHealthyIgnoringDegrade()) {
                return;
            }
            bridge.refreshHeartbeat();
            bridge.withJedis(jedis -> {
                int ttl = RedisBridge.HEARTBEAT_TTL_SECONDS;
                for (Player p : snapshot) {
                    jedis.setex(bridge.keyPresence(p.getUniqueId()), ttl, conf.serverId);
                    jedis.setex(bridge.keyName(p.getName().toLowerCase(java.util.Locale.ROOT)), ttl, p.getUniqueId().toString());
                }
                return null;
            });
        });
    }

    /** 单个玩家上线：登记在线存在（异步）。 */
    public void presence(UUID uuid, String name) {
        if (!bridge.isHealthyIgnoringDegrade()) {
            return;
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> bridge.withJedis(jedis -> {
            int ttl = RedisBridge.HEARTBEAT_TTL_SECONDS;
            jedis.setex(bridge.keyPresence(uuid), ttl, conf.serverId);
            if (name != null) {
                jedis.setex(bridge.keyName(name.toLowerCase(java.util.Locale.ROOT)), ttl, uuid.toString());
            }
            return null;
        }));
    }

    public void clearPresence(UUID uuid, String name) {
        if (!bridge.isHealthyIgnoringDegrade()) {
            return;
        }
        // Redis 操作为网络 IO，异步执行，避免阻塞主线程（如 PlayerQuitEvent）
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> bridge.withJedis(jedis -> {
            jedis.del(bridge.keyPresence(uuid));
            if (name != null) {
                jedis.del(bridge.keyName(name.toLowerCase(java.util.Locale.ROOT)));
            }
            return null;
        }));
    }

    /** 玩家当前所在服务器；未知返回 null。 */
    public String serverOf(UUID uuid) {
        return bridge.withJedis(jedis -> jedis.get(bridge.keyPresence(uuid)));
    }

    /** 按玩家名解析在线 UUID；未知返回 null。 */
    public UUID resolveOnlineUuid(String name) {
        String v = bridge.withJedis(jedis -> jedis.get(bridge.keyName(name.toLowerCase(java.util.Locale.ROOT))));
        if (v == null) {
            return null;
        }
        try {
            return UUID.fromString(v);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // =====================================================================
    // 工会战匹配队列
    // =====================================================================

    public void queueGuild(String guildId, Map<String, String> meta) {
        bridge.withJedis(jedis -> {
            jedis.zadd(bridge.keyWarQueue(), System.currentTimeMillis(), guildId);
            Map<String, String> m = new LinkedHashMap<>(meta);
            m.put("queuedAt", String.valueOf(System.currentTimeMillis()));
            m.put("server", conf.serverId);
            jedis.hset(bridge.keyWarQueueMeta(guildId), m);
            jedis.expire(bridge.keyWarQueueMeta(guildId), 1800);
            return null;
        });
    }

    public void dequeueGuild(String guildId) {
        bridge.withJedis(jedis -> {
            jedis.zrem(bridge.keyWarQueue(), guildId);
            jedis.del(bridge.keyWarQueueMeta(guildId));
            return null;
        });
    }

    public boolean isQueued(String guildId) {
        Boolean queued = bridge.withJedis(jedis -> {
            if (!jedis.exists(bridge.keyWarQueueMeta(guildId))) {
                // 元数据已过期（如所在子服崩溃）：清理残留在有序集合中的条目。
                jedis.zrem(bridge.keyWarQueue(), guildId);
                return false;
            }
            return jedis.zscore(bridge.keyWarQueue(), guildId) != null;
        });
        return Boolean.TRUE.equals(queued);
    }

    /** 队列中的工会数量（单次 ZCARD，避免拉取整份队列）。 */
    public int queueCount() {
        Long n = bridge.withJedis(jedis -> jedis.zcard(bridge.keyWarQueue()));
        return n == null ? 0 : n.intValue();
    }

    /** 某工会排队时选择的模式；未排队返回 null（单次 HGET）。 */
    public String queuedMode(String guildId) {
        return bridge.withJedis(jedis -> jedis.hget(bridge.keyWarQueueMeta(guildId), "mode"));
    }

    /** 队列快照（按入队时间升序）：List of [guildId, metaMap]。同时清理已过期的条目。 */
    public List<Map<String, String>> queueSnapshot() {
        List<Map<String, String>> out = new ArrayList<>();
        bridge.withJedis(jedis -> {
            List<String> ids = new ArrayList<>(jedis.zrange(bridge.keyWarQueue(), 0, -1));
            List<String> stale = new ArrayList<>();
            for (String id : ids) {
                Map<String, String> meta = jedis.hgetAll(bridge.keyWarQueueMeta(id));
                if (meta.isEmpty()) {
                    stale.add(id);
                    continue;
                }
                meta.put("guildId", id);
                out.add(meta);
            }
            if (!stale.isEmpty()) {
                jedis.zrem(bridge.keyWarQueue(), stale.toArray(new String[0]));
            }
            return null;
        });
        return out;
    }

    /** 尝试获取匹配锁（同一时刻只有一台服做匹配）。 */
    public boolean tryMatchLock() {
        if (!isReady()) {
            return true; // 单服模式：本机独占
        }
        String r = bridge.withJedis(jedis -> jedis.set(bridge.keyMatchLock(), conf.serverId, redis.clients.jedis.params.SetParams.setParams().nx().px(5000)));
        return "OK".equals(r);
    }

    /** 从队列中原子移除指定的两个工会；两个都移除成功才返回 true。 */
    public boolean claimPair(String guildA, String guildB) {
        Long removed = bridge.withJedis(jedis -> {
            long a = jedis.zrem(bridge.keyWarQueue(), guildA);
            long b = jedis.zrem(bridge.keyWarQueue(), guildB);
            if (a > 0 && b > 0) {
                jedis.del(bridge.keyWarQueueMeta(guildA));
                jedis.del(bridge.keyWarQueueMeta(guildB));
                return a + b;
            }
            // 回滚：把已移除的加回去（用当前时间可能改变顺序，但概不常见）
            if (a > 0) {
                jedis.zadd(bridge.keyWarQueue(), System.currentTimeMillis(), guildA);
            }
            if (b > 0) {
                jedis.zadd(bridge.keyWarQueue(), System.currentTimeMillis(), guildB);
            }
            return 0L;
        });
        return removed != null && removed > 0;
    }

    // =====================================================================
    // 跨服工会战实时状态
    // =====================================================================

    public void publishWarState(String warId, String state, int scoreA, int scoreB) {
        Map<String, String> m = new HashMap<>();
        m.put("war", warId);
        m.put("state", state);
        m.put("sa", String.valueOf(scoreA));
        m.put("sb", String.valueOf(scoreB));
        publish("WAR_STATE", m);
    }

    public void setPendingWar(UUID uuid, String warId) {
        bridge.withJedis(jedis -> {
            jedis.setex(bridge.keyWarPending(uuid), 180, warId);
            return null;
        });
    }

    public String consumePendingWar(UUID uuid) {
        return bridge.withJedis(jedis -> {
            String v = jedis.get(bridge.keyWarPending(uuid));
            if (v != null) {
                jedis.del(bridge.keyWarPending(uuid));
            }
            return v;
        });
    }

    // =====================================================================
    // 编码
    // =====================================================================

    public static String encode(Map<String, String> data) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> e : data.entrySet()) {
            if (!first) {
                sb.append(REC);
            }
            first = false;
            sb.append(e.getKey() == null ? "" : e.getKey().replace(UNIT, ' ').replace(REC, ' '));
            sb.append(UNIT);
            sb.append(e.getValue() == null ? "" : e.getValue().replace(UNIT, ' ').replace(REC, ' '));
        }
        return sb.toString();
    }

    public static Map<String, String> parse(String payload) {
        Map<String, String> out = new HashMap<>();
        if (payload == null || payload.isEmpty()) {
            return out;
        }
        for (String part : payload.split(String.valueOf(REC))) {
            int i = part.indexOf(UNIT);
            if (i < 0) {
                continue;
            }
            out.put(part.substring(0, i), part.substring(i + 1));
        }
        return out;
    }

    public static Map<String, String> map(Object... kv) {
        Map<String, String> out = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            out.put(String.valueOf(kv[i]), kv[i + 1] == null ? "" : String.valueOf(kv[i + 1]));
        }
        return out;
    }

    /** 便捷：本服在线玩家。 */
    public Collection<? extends Player> online() {
        return Bukkit.getOnlinePlayers();
    }
}
