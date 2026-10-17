package com.liu.liuguild.crossserver;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerTransferRefreshTest {

    @Test
    void requestsWhenListUnknownOrStaleButRespectsCooldown() {
        long now = 1_000_000L;
        assertTrue(ServerTransfer.shouldRequestServers(true, 0L, 0L, now));
        assertFalse(ServerTransfer.shouldRequestServers(false, now - 1000L, now - 1000L, now),
                "列表较新时不刷新");
        assertTrue(ServerTransfer.shouldRequestServers(false, now - 10L * 60L * 1000L, 0L, now),
                "列表过期时刷新");
        assertFalse(ServerTransfer.shouldRequestServers(true, 0L, now - 1000L, now),
                "刚请求过时等待冷却");
    }
}
