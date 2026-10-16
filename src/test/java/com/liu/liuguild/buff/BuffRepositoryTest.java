package com.liu.liuguild.buff;

import com.liu.liuguild.config.BuffSettings;
import com.liu.liuguild.db.*;
import com.liu.liuguild.model.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class BuffRepositoryTest {
    @TempDir Path temp;
    Database db; GuildRepository guilds; BuffRepository buffs; Guild guild; GuildMember leader; BuffSettings config;
    @BeforeEach void setup() throws Exception {
        DbConfig settings = new DbConfig(); settings.type = "sqlite"; settings.sqliteFile = temp.resolve("test.db").toString();
        db = new Database(settings, Logger.getLogger("BuffTest")); db.init();
        guilds = new GuildRepository(db); buffs = new BuffRepository(db);
        long now = System.currentTimeMillis();
        guild = new Guild(UUID.randomUUID().toString(), "BuffGuild", "BUF", UUID.randomUUID(), "Leader", "", "", false,
                now, now, 0, 1, 0, 0, 0, "test", 1, "default");
        leader = new GuildMember(guild.leaderUuid(), "Leader", guild.id(), GuildRank.LEADER, now, 0, 0, 0);
        guilds.insertGuildWithLeader(guild, leader); guilds.addPoints(guild.id(), 10000);
        config = new BuffSettings(); config.slots.put(1, 1); config.maxSeconds = 7200;
        config.buffs.put("speed", new BuffSettings.Buff("speed", "速度", "SPEED", 0, 1000, 3600, 0));
        config.buffs.put("haste", new BuffSettings.Buff("haste", "急迫", "HASTE", 0, 1000, 3600, 0));
    }
    @AfterEach void close() { db.shutdown(); }
    @Test void purchasesRenewAndCapWithoutChargingFailedRequests() throws Exception {
        long now = System.currentTimeMillis();
        assertNull(buffs.purchase(guild.id(), leader.uuid(), config, "speed", now).error());
        assertEquals(now + 3600000, buffs.loadActive(now).get(guild.id()).get("speed"));
        assertEquals("buff-slots-full", buffs.purchase(guild.id(), leader.uuid(), config, "haste", now).error());
        assertNull(buffs.purchase(guild.id(), leader.uuid(), config, "speed", now).error());
        assertEquals("buff-duration-limit", buffs.purchase(guild.id(), leader.uuid(), config, "speed", now).error());
        assertEquals(8000, guilds.loadGuild(guild.id()).points());
        assertEquals(now + 7200000, buffs.loadActive(now).get(guild.id()).get("speed"));
        assertTrue(buffs.loadActive(now + 7200000).isEmpty());
    }
    @Test void permissionsAndInsufficientFundsRollback() throws Exception {
        long now = System.currentTimeMillis();
        assertEquals("buff-no-permission", buffs.purchase(guild.id(), UUID.randomUUID(), config, "speed", now).error());
        assertEquals(10000, guilds.loadGuild(guild.id()).points());
        guilds.setPoints(guild.id(), 10);
        assertEquals("buff-insufficient", buffs.purchase(guild.id(), leader.uuid(), config, "speed", now).error());
        assertTrue(buffs.loadActive(now).isEmpty());
        assertEquals(10, guilds.loadGuild(guild.id()).points());
    }
    @Test void simultaneousRenewalsDoNotExceedCapOrDoubleSpend() throws Exception {
        long now = System.currentTimeMillis();
        config.maxSeconds = 3600;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var start = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<BuffRepository.Purchase> buy = () -> {
                start.await();
                return buffs.purchase(guild.id(), leader.uuid(), config, "speed", now);
            };
            var first = pool.submit(buy); var second = pool.submit(buy); start.countDown();
            int successes = (first.get().error() == null ? 1 : 0) + (second.get().error() == null ? 1 : 0);
            assertEquals(1, successes);
            assertEquals(9000, guilds.loadGuild(guild.id()).points());
        } finally { pool.shutdownNow(); }
    }

    @Test void expiredBuffFreesSlotAndDeletedGuildDoesNotLoad() throws Exception {
        long now = System.currentTimeMillis();
        assertNull(buffs.purchase(guild.id(), leader.uuid(), config, "speed", now).error());
        assertNull(buffs.purchase(guild.id(), leader.uuid(), config, "haste", now + 3600001).error());
        guilds.deleteGuild(guild.id(), -1);
        assertTrue(buffs.loadActive(now).isEmpty());
    }
}
