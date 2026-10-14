package com.liu.liuguild.warehouse;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工会仓库独占锁的语义。
 *
 * <p>用 {@link WarehouseLock#localOnly()} 走本地路径（不连 Redis），但独占、重入、
 * 释放、强制释放这些逻辑与跨服路径完全一致 —— 跨服只是把同一个判断交给 Redis 的
 * {@code SET NX} 原子执行。</p>
 *
 * <p>这是仓库不丢物/不复制的地基：一旦锁形同虚设，两个玩家同时打开就会互相覆盖。</p>
 */
class WarehouseLockTest {

    private static final String GUILD = "guild-1";
    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();

    @Test
    void firstPlayerAcquiresTheLock() {
        WarehouseLock lock = WarehouseLock.localOnly();
        WarehouseLock.Session s = lock.acquire(GUILD, ALICE, "Alice");
        assertNotNull(s, "第一个玩家应该抢到锁");
        assertEquals(ALICE, s.player);
        assertEquals("Alice", s.playerName);
        assertNotNull(s.token, "必须有持有者令牌，否则可能误删别人的锁");
    }

    @Test
    void secondPlayerIsRejectedWhileHeld() {
        WarehouseLock lock = WarehouseLock.localOnly();
        lock.acquire(GUILD, ALICE, "Alice");

        assertNull(lock.acquire(GUILD, BOB, "Bob"), "同一工会只允许一个玩家打开");
        assertTrue(lock.isLockedByOther(GUILD, BOB));
        assertFalse(lock.isLockedByOther(GUILD, ALICE), "对自己不算被占用");
    }

    @Test
    void samePlayerReentryReusesTheSameSession() {
        WarehouseLock lock = WarehouseLock.localOnly();
        WarehouseLock.Session first = lock.acquire(GUILD, ALICE, "Alice");
        WarehouseLock.Session again = lock.acquire(GUILD, ALICE, "Alice");

        // 重入必须复用同一个会话；否则释放时会把令牌换掉，旧的释放会误删新锁
        assertSame(first, again, "同一个人重复打开不应生成新令牌");
        assertEquals(1, lock.sessionCount());
    }

    @Test
    void releaseByNonHolderIsIgnored() {
        WarehouseLock lock = WarehouseLock.localOnly();
        lock.acquire(GUILD, ALICE, "Alice");

        lock.release(GUILD, BOB); // Bob 不是持有者
        assertTrue(lock.isLockedByOther(GUILD, BOB), "非持有者不能解锁");

        lock.release(GUILD, ALICE);
        assertFalse(lock.isLockedByOther(GUILD, BOB), "持有者能解锁");
        assertNotNull(lock.acquire(GUILD, BOB, "Bob"), "解锁后 Bob 应该能抢到");
    }

    @Test
    void releaseAfterReleaseIsHarmless() {
        WarehouseLock lock = WarehouseLock.localOnly();
        lock.acquire(GUILD, ALICE, "Alice");
        lock.release(GUILD, ALICE);
        lock.release(GUILD, ALICE); // 再来一次不能抛
        assertEquals(0, lock.sessionCount());
    }

    @Test
    void differentGuildsDoNotBlockEachOther() {
        WarehouseLock lock = WarehouseLock.localOnly();
        assertNotNull(lock.acquire("g1", ALICE, "Alice"));
        assertNotNull(lock.acquire("g2", ALICE, "Alice"));
        assertNull(lock.acquire("g1", BOB, "Bob"), "同一个工会才互斥");
        assertEquals(2, lock.sessionCount());
    }

    @Test
    void forceReleaseClearsEverything() {
        WarehouseLock lock = WarehouseLock.localOnly();
        lock.acquire("g1", ALICE, "Alice");
        lock.acquire("g2", BOB, "Bob");
        assertEquals(2, lock.sessionCount());

        lock.releaseAll(); // 关服 / 解散工会
        assertEquals(0, lock.sessionCount());
        assertNotNull(lock.acquire("g1", BOB, "Bob"), "强释放后应可重新抢占");
    }

    @Test
    void nullArgumentsAreRejectedInsteadOfThrowing() {
        WarehouseLock lock = WarehouseLock.localOnly();
        assertNull(lock.acquire(null, ALICE, "Alice"));
        assertNull(lock.acquire(GUILD, null, "Alice"));
        lock.release(null, ALICE);
        lock.release(GUILD, null); // 不能抛
        assertFalse(lock.isLockedByOther(null, ALICE));
    }

    @Test
    void holderNameIsTrackedForTheBusyMessage() {
        WarehouseLock lock = WarehouseLock.localOnly();
        assertNull(lock.localHolderName(GUILD));
        lock.acquire(GUILD, ALICE, "Alice");
        assertEquals("Alice", lock.localHolderName(GUILD));
        assertTrue(lock.isHeldLocallyBy(GUILD, ALICE));
        assertFalse(lock.isHeldLocallyBy(GUILD, BOB));
    }

    @Test
    void heartbeatIsSafeWithoutRedis() {
        WarehouseLock lock = WarehouseLock.localOnly();
        lock.acquire(GUILD, ALICE, "Alice");
        lock.heartbeat(); // 本地锁无 TTL，不应清掉会话或抛异常
        assertEquals(1, lock.sessionCount());
        assertTrue(lock.heartbeatSeconds() > 0);
    }

    @Test
    void tokensAreUniquePerAcquision() {
        WarehouseLock lock = WarehouseLock.localOnly();
        String t1 = lock.acquire("g1", ALICE, "Alice").token;
        String t2 = lock.acquire("g2", ALICE, "Alice").token;
        assertTrue(!t1.equals(t2), "不同工会的令牌必须不同（否则释放会互相误删）");
    }
}
