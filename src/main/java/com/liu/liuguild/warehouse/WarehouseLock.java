package com.liu.liuguild.warehouse;

import com.liu.liuguild.LiuGuild;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工会仓库的<b>跨服独占锁</b>：同一时刻只允许一个玩家打开某个工会的共享仓库。
 *
 * <p>为什么需要锁：仓库数据在共享数据库里，多台子服、多个玩家可能同时打开同一个工会仓库，
 * 各自把整份内容读进内存再写回，会出现「后写的覆盖先写的」→ 物品凭空丢失/复制。</p>
 *
 * <h3>实现</h3>
 * <ul>
 *   <li><b>跨服（Redis 可用）</b>：{@code SET key token NX EX ttl} 原子抢占；
 *       token 为 {@code serverId:playerUuid:random}，释放/续期时比对 token，
 *       绝不误删别人的锁。TTL + 心跳续期保证持有方崩溃后自动解锁。</li>
 *   <li><b>单服（Redis 不可用）</b>：回退到本地 {@link ConcurrentHashMap}，语义等价（只保证本服独占）。</li>
 * </ul>
 *
 * <p>另外记录了持有者名字，便于给其他玩家提示“谁正在打开”。</p>
 */
public final class WarehouseLock {

    /** 锁 TTL（秒）。超过这个时间没有心跳就自动释放，避免死锁。 */
    private static final long TTL_SECONDS = 60;
    /** 心跳间隔（秒）。必须显著小于 TTL，留出网络抖动余量。 */
    private static final long HEARTBEAT_SECONDS = 20;

    /** 本地会话（同时也用于记录持有者名字）。 */
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    /** 按工会分段加锁：避免一个工会的 Redis 抢占阻塞其它工会的仓库打开。 */
    private final Map<String, Object> keyLocks = new ConcurrentHashMap<>();

    private final LiuGuild plugin;

    public WarehouseLock(LiuGuild plugin) {
        this.plugin = plugin;
    }

    /** 测试用：只启用本地独占语义（不连 Redis）。 */
    public static WarehouseLock localOnly() {
        return new WarehouseLock(null);
    }

    /** 是否走跨服（Redis）路径。 */
    private boolean crossServer() {
        return plugin != null && plugin.bus() != null && plugin.bus().isReady();
    }

    private String serverId() {
        return plugin == null ? "local" : plugin.serverId();
    }

    /** 一次“谁在打开”的会话。 */
    public static final class Session {
        public final UUID player;
        public final String playerName;
        public final String token;
        public final long acquiredAt;
        /** 连续续期失败次数；Redis 短暂抖动不立刻踢人。 */
        volatile int refreshFailures;

        Session(UUID player, String playerName, String token) {
            this.player = player;
            this.playerName = playerName;
            this.token = token;
            this.acquiredAt = System.currentTimeMillis();
        }

        public String token() {
            return token;
        }
    }

    /**
     * 尝试为某玩家抢占工会仓库。
     *
     * @return 抢到返回 {@link Session}；已被别人占用返回 {@code null}
     */
    public Session acquire(String guildId, UUID player, String playerName) {
        if (guildId == null || player == null) {
            return null;
        }
        synchronized (keyLocks.computeIfAbsent(guildId, k -> new Object())) {
            return acquireLocked(guildId, player, playerName);
        }
    }

    private Session acquireLocked(String guildId, UUID player, String playerName) {
        // 同一个人重复打开（GUI 重开）直接复用，避免自我死锁
        Session existing = sessions.get(guildId);
        if (existing != null && existing.player.equals(player)) {
            return existing;
        }
        if (plugin != null && plugin.settings().main.crossServerEnabled && !crossServer()) return null;
        String token = serverId() + ":" + player + ":" + UUID.randomUUID().toString().substring(0, 8);
        boolean ok;
        if (crossServer()) {
            ok = plugin.bus().tryLock(lockKey(guildId), token, TTL_SECONDS);
        } else {
            // 单服：本地锁
            ok = sessions.get(guildId) == null;
        }
        if (!ok) {
            return null;
        }
        Session session = new Session(player, playerName, token);
        sessions.put(guildId, session);
        return session;
    }

    /**
     * 释放工会仓库锁（只释放自己持有的那把）。
     */
    public void release(String guildId, UUID player) {
        release(guildId, player, null);
    }

    /**
     * 释放仓库锁。token 非空时必须与当前会话 token 一致，避免旧异步任务释放玩家刚重新打开的会话。
     */
    public void release(String guildId, UUID player, String token) {
        if (guildId == null) {
            return;
        }
        Session session = sessions.get(guildId);
        if (session == null) {
            return;
        }
        if (player != null && !session.player.equals(player)) {
            return; // 不是持有者，忽略
        }
        if (token != null && !token.equals(session.token)) {
            return; // 旧会话，不能释放新会话
        }
        sessions.remove(guildId, session);
        if (crossServer()) {
            plugin.bus().releaseLock(lockKey(guildId), session.token);
        }
    }

    /** 强制释放（关服 / 解散工会 / 超时清理）。 */
    public void releaseAll() {
        for (Map.Entry<String, Session> e : sessions.entrySet()) {
            if (crossServer()) {
                plugin.bus().releaseLock(lockKey(e.getKey()), e.getValue().token);
            }
        }
        sessions.clear();
    }

    /** 当前是否被（别人）占用。 */
    public boolean isLockedByOther(String guildId, UUID player) {
        if (guildId == null) {
            return false;
        }
        Session s = sessions.get(guildId);
        if (s != null && (player == null || !s.player.equals(player))) {
            return true;
        }
        return false;
    }

    /** 当前服务器的持有者名字；没人持有返回 null。 */
    public String localHolderName(String guildId) {
        if (guildId == null) {
            return null;
        }
        Session s = sessions.get(guildId);
        return s == null ? null : s.playerName;
    }

    /** 本服是否持有该工会的仓库锁。 */
    public boolean isHeldLocallyBy(String guildId, UUID player) {
        if (guildId == null || player == null) {
            return false;
        }
        Session s = sessions.get(guildId);
        return s != null && s.player.equals(player);
    }

    /**
     * 心跳：定期为所有本服持有的锁续期。
     * <p>续期失败（锁已被抢占/过期）则丢弃本地会话，下次打开会重新抢占。</p>
     */
    public void heartbeat() {
        if (sessions.isEmpty()) {
            return;
        }
        boolean remote = crossServer();
        for (Map.Entry<String, Session> e : sessions.entrySet()) {
            Session s = e.getValue();
            if (!remote) {
                // 多服模式但 Redis 暂时不可用：不要一次抖动就把玩家踢出仓库。
                // 此时所有子服的 acquire 都会失败（不会再开新会话），本服已有会话
                // 继续本地独占是安全的；连续 3 个心跳（60s）都不可用才降级关闭。
                if (plugin != null && plugin.settings().main.crossServerEnabled
                        && ++s.refreshFailures >= 3) {
                    dropSession(e.getKey(), s);
                }
                continue;
            }
            com.liu.liuguild.crossserver.CrossServerBus.LockState state =
                    plugin.bus().refreshLockState(lockKey(e.getKey()), s.token, TTL_SECONDS);
            if (state == com.liu.liuguild.crossserver.CrossServerBus.LockState.UNAVAILABLE) {
                // Redis 抖动：锁并未被证明丢失，给 3 次续期机会再关闭。
                if (++s.refreshFailures < 3) {
                    continue;
                }
            } else if (state == com.liu.liuguild.crossserver.CrossServerBus.LockState.OK) {
                s.refreshFailures = 0;
                continue;
            }
            // 锁确实已失效（被抢占/过期），必须立即停止写入。
            dropSession(e.getKey(), s);
        }
    }

    private void dropSession(String guildId, Session session) {
        sessions.remove(guildId, session);
        if (plugin == null) {
            return;
        }
        UUID holder = session.player;
        plugin.sync(() -> {
            if (plugin.warehouse() != null) plugin.warehouse().kick(holder);
            Player p = plugin.player(holder);
            if (p != null) plugin.messages().send(p, "wh-lock-lost");
        });
    }

    /** 仅测试用：会话数。 */
    public int sessionCount() {
        return sessions.size();
    }

    public long heartbeatSeconds() {
        return HEARTBEAT_SECONDS;
    }

    private static String lockKey(String guildId) {
        return "warehouse:" + guildId;
    }
}
