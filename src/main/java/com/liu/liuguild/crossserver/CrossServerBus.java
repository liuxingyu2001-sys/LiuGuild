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
    public static final char UNIT = '\u001f';

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
            // ready 只在订阅真正建立后置位：否则发起服 publish 成功却收不到自己的回声，
            // WAR_MATCHED / SEASON_OPEN 这类靠回声生效的消息会丢。
            bridge.subscribe(this::handleIncoming, () -> ready = true);
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
            if (bridge.broadcast(type, encode(data)) > 0) return;
            plugin.getLogger().warning("跨服广播失败，降级为本服分发: " + type);
        }
        dispatch(type, data);
    }

    public void publishTo(String server, String type, Map<String, String> data) {
        if (isReady()) {
            if (bridge.publish(server, type, encode(data))) return;
            plugin.getLogger().warning("跨服定向发送失败，目标=" + server + " 类型=" + type);
            if (server != null && server.equals(conf.serverId)) dispatch(type, data);
            return;
        }
        dispatch(type, data);
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

    // =====================================================================
    // 分布式命名锁（带持有者令牌，可续期、可安全释放）
    // =====================================================================

    /** 锁键前缀。 */
    private static final String LOCK_PREFIX = "lock:";

    /**
     * 尝试获取一个带持有者令牌的锁。
     *
     * <p>用 {@code SET key token NX EX ttl} 原子抢占；成功仅当**当前无锁或旧锁已过期**。
     * 持有者令牌在释放/续期时做比对，避免“误删别人的锁”。</p>
     *
     * @param key   业务键（如 {@code warehouse:<guildId>}）
     * @param token 持有者令牌（全局唯一，建议 serverId:uuid）
     * @param ttl   过期秒数；防止持有方崩溃后死锁
     * @return true = 本次抢到锁
     */
    public boolean tryLock(String key, String token, long ttlSeconds) {
        if (token == null || token.isBlank()) {
            return false;
        }
        if (!bridge.isHealthy()) {
            return false; // 调用方应回退到本地锁
        }
        Boolean ok = bridge.withJedis(jedis -> "OK".equals(jedis.set(
                bridge.prefix() + LOCK_PREFIX + key, token,
                redis.clients.jedis.params.SetParams.setParams().nx().ex(Math.max(1, ttlSeconds)))));
        return Boolean.TRUE.equals(ok);
    }

    /**
     * 续期（心跳）：仅当锁仍属于该 token 时才刷新 TTL。
     *
     * <p>用 Lua 保证「比对 + 续期」原子。若拆成 GET 然后 EXPIRE，中间可能恰好发生
     * 锁过期并被别人抢走 —— 那就会把别人的锁续期。</p>
     *
     * @return true = 续期成功（仍持有锁）
     */
    public boolean refreshLock(String key, String token, long ttlSeconds) {
        if (token == null || token.isBlank() || !bridge.isHealthy()) {
            return false;
        }
        Boolean ok = bridge.withJedis(jedis -> {
            Object r = jedis.eval(COMPARE_EXPIRE_SCRIPT,
                    List.of(bridge.prefix() + LOCK_PREFIX + key),
                    List.of(token, String.valueOf(Math.max(1, ttlSeconds))));
            return toLong(r) > 0;
        });
        return Boolean.TRUE.equals(ok);
    }

    /** 续期结果：区分「锁已不属于我」和「Redis 暂时不可用」，避免一次抖动就丢锁。 */
    public enum LockState { OK, LOST, UNAVAILABLE }

    /** 续期（三态）：Redis 不可用时返回 UNAVAILABLE，调用方不应丢弃本地会话。 */
    public LockState refreshLockState(String key, String token, long ttlSeconds) {
        if (token == null || token.isBlank()) {
            return LockState.LOST;
        }
        if (!bridge.isHealthy()) {
            return LockState.UNAVAILABLE;
        }
        Boolean ok = bridge.withJedis(jedis -> {
            Object r = jedis.eval(COMPARE_EXPIRE_SCRIPT,
                    List.of(bridge.prefix() + LOCK_PREFIX + key),
                    List.of(token, String.valueOf(Math.max(1, ttlSeconds))));
            return toLong(r) > 0;
        });
        if (ok == null) {
            return LockState.UNAVAILABLE;
        }
        return ok ? LockState.OK : LockState.LOST;
    }

    /**
     * 释放锁：仅当锁仍属于该 token 时才删除，避免误删他人刚抢到的锁。
     *
     * <p>同样用 Lua 保证「比对 + 删除」原子。</p>
     *
     * @return true = 本次确实释放了锁
     */
    public boolean releaseLock(String key, String token) {
        if (token == null || token.isBlank() || !bridge.isHealthy()) {
            return false;
        }
        Boolean ok = bridge.withJedis(jedis -> {
            Object r = jedis.eval(COMPARE_DEL_SCRIPT,
                    List.of(bridge.prefix() + LOCK_PREFIX + key),
                    List.of(token));
            return toLong(r) > 0;
        });
        return Boolean.TRUE.equals(ok);
    }

    /** 在线状态比对删除：presence 属于本服、name 属于该 UUID 时才删（防止跨服传送竞态）。 */
    private static final String COMPARE_DELETE_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then redis.call('del', KEYS[1]) end "
                    + "if KEYS[2] ~= '' and redis.call('get', KEYS[2]) == ARGV[2] then redis.call('del', KEYS[2]) end "
                    + "return 1";

    /** 比对并删除（原子）。 */
    private static final String COMPARE_DEL_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";
    /** 比对并续期（原子）。 */
    private static final String COMPARE_EXPIRE_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('expire', KEYS[1], ARGV[2]) else return 0 end";

    private static long toLong(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(o));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 查询锁当前持有者令牌（null = 无锁）。 */
    public String lockOwner(String key) {
        if (!bridge.isHealthy()) {
            return null;
        }
        return bridge.withJedis(jedis -> jedis.get(bridge.prefix() + LOCK_PREFIX + key));
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
                int ttl = bridge.heartbeatTtlSeconds();
                // 单次 pipeline：2×在线人数 条 SETEX 只付 1 个 RTT，而不是每条一个往返。
                redis.clients.jedis.Pipeline pipe = jedis.pipelined();
                for (Player p : snapshot) {
                    pipe.setex(bridge.keyPresence(p.getUniqueId()), ttl, conf.serverId);
                    pipe.setex(bridge.keyName(p.getName().toLowerCase(java.util.Locale.ROOT)), ttl, p.getUniqueId().toString());
                }
                pipe.sync();
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
            int ttl = bridge.heartbeatTtlSeconds();
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
        // Redis 操作为网络 IO，异步执行，避免阻塞主线程（如 PlayerQuitEvent）。
        // 用 compare-and-delete：玩家从 A 服传到 B 服时，A 的 quit 不能把 B 刚登记的
        // presence/name 删掉（否则一个心跳周期内跨服邀请/待办解析都会失败）。
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> bridge.withJedis(jedis -> {
            String nameKey = name == null ? "" : bridge.keyName(name.toLowerCase(java.util.Locale.ROOT));
            jedis.eval(COMPARE_DELETE_SCRIPT,
                    java.util.List.of(bridge.keyPresence(uuid), nameKey),
                    java.util.List.of(conf.serverId, uuid.toString()));
            return null;
        }));
    }

    /**
     * 批量查询这些 UUID 中在线（任意子服）的数量：单次 MGET，避免逐人 GET 的 N 次往返。
     *
     * @return 在线数；Redis 不可用时返回 -1
     */
    public int onlineCount(java.util.Collection<UUID> uuids) {
        if (uuids == null || uuids.isEmpty()) {
            return 0;
        }
        String[] keys = new String[uuids.size()];
        int i = 0;
        for (UUID u : uuids) {
            keys[i++] = bridge.keyPresence(u);
        }
        Long n = bridge.withJedis(jedis -> {
            java.util.List<String> vals = jedis.mget(keys);
            long online = 0;
            for (String v : vals) {
                if (v != null) {
                    online++;
                }
            }
            return online;
        });
        return n == null ? -1 : n.intValue();
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

    /** @return true = 已写入 Redis；false = 跨服不可用/写失败（调用方不能提示“已排队”）。 */
    public boolean queueGuild(String guildId, Map<String, String> meta) {
        Boolean ok = bridge.withJedis(jedis -> {
            jedis.zadd(bridge.keyWarQueue(), System.currentTimeMillis(), guildId);
            Map<String, String> m = new LinkedHashMap<>(meta);
            m.put("queuedAt", String.valueOf(System.currentTimeMillis()));
            m.put("server", conf.serverId);
            jedis.hset(bridge.keyWarQueueMeta(guildId), m);
            jedis.expire(bridge.keyWarQueueMeta(guildId), 1800);
            return Boolean.TRUE;
        });
        return Boolean.TRUE.equals(ok);
    }

    public boolean dequeueGuild(String guildId) {
        Boolean ok = bridge.withJedis(jedis -> {
            jedis.zrem(bridge.keyWarQueue(), guildId);
            jedis.del(bridge.keyWarQueueMeta(guildId));
            return Boolean.TRUE;
        });
        return Boolean.TRUE.equals(ok);
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
        // 锁只覆盖一次匹配扫描；拿到后跑完即释放，TTL 仅作为进程崩溃的兜底。
        String r = bridge.withJedis(jedis -> jedis.set(bridge.keyMatchLock(), conf.serverId, redis.clients.jedis.params.SetParams.setParams().nx().px(2000)));
        return "OK".equals(r);
    }

    /** 主动释放匹配锁（只有持有者能删），让下一台子服可以立即匹配。 */
    public void releaseMatchLock() {
        if (!isReady() || !bridge.isHealthy()) {
            return;
        }
        bridge.withJedis(jedis -> {
            // keyMatchLock() 已是完整键，不能再走 releaseLock() 的 LOCK_PREFIX 拼接。
            jedis.eval(COMPARE_DEL_SCRIPT, List.of(bridge.keyMatchLock()), List.of(conf.serverId));
            return Boolean.TRUE;
        });
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

    public void publishWarState(String warId, String state, int scoreA, int scoreB, long leftSeconds) {
        Map<String, String> m = new HashMap<>();
        m.put("war", warId);
        m.put("state", state);
        m.put("sa", String.valueOf(scoreA));
        m.put("sb", String.valueOf(scoreB));
        m.put("left", String.valueOf(Math.max(0, leftSeconds)));
        publish("WAR_STATE", m);
    }

    public boolean setPendingWar(UUID uuid, String warId) {
        return setPendingWar(uuid, warId, null, null, 0, 0, 0, 0, 0);
    }

    /** 编码待入场标记；批量传送与单发共用，避免格式漂移。 */
    public static String pendingWarValue(String warId, String sourceServer, String world,
                                         double x, double y, double z, float yaw, float pitch) {
        return warId
                + (sourceServer == null ? "" : UNIT + sourceServer)
                + (sourceServer == null ? "" : UNIT + (world == null ? "" : world))
                + (sourceServer == null ? "" : UNIT + x + UNIT + y + UNIT + z + UNIT + yaw + UNIT + pitch);
    }

    /**
     * 写「待入场」标记，并带上出发服与坐标（供赛后跨服返程）。
     * 值格式：{@code warId UNIT sourceServer UNIT world UNIT x UNIT y UNIT z UNIT yaw UNIT pitch}。
     */
    public boolean setPendingWar(UUID uuid, String warId, String sourceServer, String world,
                                 double x, double y, double z, float yaw, float pitch) {
        String value = pendingWarValue(warId, sourceServer, world, x, y, z, yaw, pitch);
        Boolean ok = bridge.withJedis(jedis -> {
            jedis.setex(bridge.keyWarPending(uuid), 180, value);
            return Boolean.TRUE;
        });
        return Boolean.TRUE.equals(ok);
    }

    /**
     * 批量写入待入场标记：一次 pipeline 只付 1 个 RTT。
     * 自动入场会一次转移整会成员，逐个 SETEX 会把延迟乘以人数。
     */
    /** 写跨服旁观标记（与待入场格式相同，但接入后按旁观处理）。 */
    public boolean setPendingSpectate(UUID uuid, String warId, String sourceServer, String world,
                                      double x, double y, double z, float yaw, float pitch) {
        String value = pendingWarValue(warId, sourceServer, world, x, y, z, yaw, pitch);
        Boolean ok = bridge.withJedis(jedis -> {
            jedis.setex(bridge.keyWarSpectate(uuid), 180, value);
            return Boolean.TRUE;
        });
        return Boolean.TRUE.equals(ok);
    }

    public boolean setPendingWars(Map<UUID, String> pendingByPlayer) {
        if (pendingByPlayer == null || pendingByPlayer.isEmpty()) {
            return true;
        }
        Boolean ok = bridge.withJedis(jedis -> {
            redis.clients.jedis.Pipeline pipe = jedis.pipelined();
            for (Map.Entry<UUID, String> e : pendingByPlayer.entrySet()) {
                pipe.setex(bridge.keyWarPending(e.getKey()), 180, e.getValue());
            }
            pipe.sync();
            return Boolean.TRUE;
        });
        return Boolean.TRUE.equals(ok);
    }

    /**
     * 一次 pipeline 消费玩家接入时的全部待处理标记：待入场、赛后返程、竞技场传送。
     *
     * @return 长度为 3 的数组，依次为 war / return / arena，未命中为 null
     */
    public String[] consumePendingMarkers(UUID uuid) {
        return bridge.withJedis(jedis -> {
            redis.clients.jedis.Pipeline pipe = jedis.pipelined();
            redis.clients.jedis.Response<String> war = pipe.get(bridge.keyWarPending(uuid));
            pipe.del(bridge.keyWarPending(uuid));
            redis.clients.jedis.Response<String> ret = pipe.get(bridge.keyWarReturn(uuid));
            pipe.del(bridge.keyWarReturn(uuid));
            redis.clients.jedis.Response<String> arena = pipe.get(bridge.keyArenaPending(uuid));
            pipe.del(bridge.keyArenaPending(uuid));
            redis.clients.jedis.Response<String> spectate = pipe.get(bridge.keyWarSpectate(uuid));
            pipe.del(bridge.keyWarSpectate(uuid));
            pipe.sync();
            return new String[]{war.get(), ret.get(), arena.get(), spectate.get()};
        });
    }

    /** 写赛后返程目的地标记（世界 + 坐标），由目的服在玩家接入时消费。 */
    public boolean setPendingReturn(UUID uuid, String world, double x, double y, double z, float yaw, float pitch) {
        String value = (world == null ? "" : world) + UNIT + x + UNIT + y + UNIT + z + UNIT + yaw + UNIT + pitch;
        Boolean ok = bridge.withJedis(jedis -> {
            jedis.setex(bridge.keyWarReturn(uuid), 300, value);
            return Boolean.TRUE;
        });
        return Boolean.TRUE.equals(ok);
    }

    public String consumePendingReturn(UUID uuid) {
        return bridge.withJedis(jedis -> {
            String v = jedis.get(bridge.keyWarReturn(uuid));
            if (v != null) {
                jedis.del(bridge.keyWarReturn(uuid));
            }
            return v;
        });
    }

    /** 承办服是否正在承办比赛/赛事（由承办服续期）。 */
    public boolean isHostBusy(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        Boolean exists = bridge.withJedis(jedis -> jedis.exists(bridge.keyWarHost(host)));
        return Boolean.TRUE.equals(exists);
    }

    public void markHostBusy(String host, long ttlSeconds) {
        if (host == null || host.isBlank()) {
            return;
        }
        bridge.withJedis(jedis -> {
            jedis.setex(bridge.keyWarHost(host), Math.max(10, ttlSeconds), conf.serverId);
            return Boolean.TRUE;
        });
    }

    public void clearHostBusy(String host) {
        if (host == null || host.isBlank()) {
            return;
        }
        bridge.withJedis(jedis -> {
            jedis.del(bridge.keyWarHost(host));
            return Boolean.TRUE;
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

    /** 跨服竞技场传送：出发服写标记，承办服入职后消费并传送到场地。 */
    public boolean setPendingArena(UUID uuid, String arenaId) {
        Boolean ok = bridge.withJedis(jedis -> {
            jedis.setex(bridge.keyArenaPending(uuid), 180, arenaId);
            return Boolean.TRUE;
        });
        return Boolean.TRUE.equals(ok);
    }

    public String consumePendingArena(UUID uuid) {
        return bridge.withJedis(jedis -> {
            String v = jedis.get(bridge.keyArenaPending(uuid));
            if (v != null) {
                jedis.del(bridge.keyArenaPending(uuid));
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
