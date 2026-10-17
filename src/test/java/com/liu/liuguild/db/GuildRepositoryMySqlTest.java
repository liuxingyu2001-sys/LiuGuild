package com.liu.liuguild.db;

import com.liu.liuguild.model.Guild;
import com.liu.liuguild.model.GuildMember;
import com.liu.liuguild.model.GuildRank;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MySQL/MariaDB 方言与并发路径回归测试。
 *
 * <p>默认跳过：需要真实 MySQL 兼容服务。开启方式：</p>
 * <pre>
 * mvn -Dliuguild.test.mysql=true \
 *     -Dliuguild.test.mysql.host=127.0.0.1 -Dliuguild.test.mysql.port=3306 \
 *     -Dliuguild.test.mysql.user=root -Dliuguild.test.mysql.password=secret \
 *     -Dtest=GuildRepositoryMySqlTest test
 * </pre>
 *
 * <p>测试会创建一个名为 {@code liuguild_it_<随机>} 的独立库并在结束时删除，不会使用已有库。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GuildRepositoryMySqlTest {

    private Database db;
    private GuildRepository repo;

    @BeforeAll
    void setUp() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("liuguild.test.mysql"),
                "MySQL 集成测试默认跳过（-Dliuguild.test.mysql=true 开启）");
        DbConfig cfg = new DbConfig();
        cfg.type = "mysql";
        cfg.host = System.getProperty("liuguild.test.mysql.host", "127.0.0.1");
        cfg.port = Integer.getInteger("liuguild.test.mysql.port", 3306);
        cfg.user = System.getProperty("liuguild.test.mysql.user", "root");
        cfg.password = System.getProperty("liuguild.test.mysql.password", "");
        cfg.database = "liuguild_it_" + UUID.randomUUID().toString().substring(0, 8);
        cfg.tablePrefix = "liuguild";
        cfg.poolSize = 4;
        cfg.autoCreateDatabase = true;
        cfg.autoCreateTables = true;
        db = new Database(cfg, Logger.getLogger("LiuGuildMySqlTest"));
        db.init();
        repo = new GuildRepository(db);
    }

    @AfterAll
    void tearDown() throws Exception {
        if (db == null) {
            return;
        }
        DbConfig cfg = db.config();
        db.shutdown();
        try (Connection c = DriverManager.getConnection(cfg.baseJdbcUrl(), cfg.user, cfg.password);
             Statement st = c.createStatement()) {
            st.executeUpdate("DROP DATABASE IF EXISTS `" + cfg.database + "`");
        } catch (Exception ignored) {
            // 清理失败不影响测试结论（库名带随机后缀，不会污染其它库）
        }
    }

    @Test
    void createsSchemaAndUniqueIndexes() throws Exception {
        String id = UUID.randomUUID().toString();
        Guild g = guild(id, "MySql", "MS");
        GuildMember leader = leader(g, id);
        repo.insertGuildWithLeader(g, leader);

        assertEquals("MySql", repo.loadGuild(id).name());
        assertEquals(1, repo.loadMembers(id).size());
        assertTrue(db.indexExistsForTests("liuguild_guilds_name", "liuguild_guilds"), "唯一索引应已创建");
        assertTrue(db.indexExistsForTests("liuguild_guilds_tag", "liuguild_guilds"), "标签唯一索引应已创建");
    }

    @Test
    void flushUpsertsRowsThatWereNeverPersisted() throws Exception {
        String id = UUID.randomUUID().toString();
        Guild g = guild(id, "Fresh", "FR");
        GuildMember leader = leader(g, id);
        // since=0：模拟本进程内新建、还没异步 INSERT
        repo.flushAll(List.of(g), List.of(leader), 0L);

        assertNotNull(repo.loadGuild(id));
        assertEquals(1, repo.loadMembers(id).size());
    }

    @Test
    void flushDoesNotOverwriteAtomicLevelOrMemberCount() throws Exception {
        String id = UUID.randomUUID().toString();
        Guild g = guild(id, "Atomic", "AT");
        GuildMember leader = leader(g, id);
        repo.insertGuildWithLeader(g, leader);

        repo.setLevel(id, 9);
        try (Connection c = db.source().getConnection();
             var ps = c.prepareStatement("UPDATE `liuguild_guilds` SET member_count=3 WHERE guild_id=?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        }

        g.level(2);
        g.memberCount(1);
        repo.flushAll(List.of(g), List.of(leader), System.currentTimeMillis() + 1000L);

        Guild loaded = repo.loadGuild(id);
        assertEquals(9, loaded.level(), "整行保存不能回退等级");
        assertEquals(3, loaded.memberCount(), "整行保存不能覆盖 member_count");
    }

    @Test
    void duplicateMemberIsRejectedWithoutIncrementingCount() throws Exception {
        String id = UUID.randomUUID().toString();
        Guild g = guild(id, "Join", "JN");
        GuildMember leader = leader(g, id);
        repo.insertGuildWithLeader(g, leader);

        GuildMember second = new GuildMember(UUID.randomUUID(), "Second", id, GuildRank.MEMBER,
                System.currentTimeMillis(), 0, 0, 0);
        try (Connection c = db.source().getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("UPDATE `liuguild_guilds` SET member_count=1 WHERE guild_id='" + id + "'");
        }
        assertTrue(repo.insertMemberAndIncrement(second, 5));
        assertEquals(2, repo.loadGuild(id).memberCount());

        GuildMember duplicate = new GuildMember(second.uuid(), "Second", id, GuildRank.MEMBER,
                System.currentTimeMillis(), 0, 0, 0);
        assertFalse(repo.insertMemberAndIncrement(duplicate, 5), "重复成员必须被拒绝");
        assertEquals(2, repo.loadGuild(id).memberCount(), "拒绝后不能额外加计数");
    }

    /** 两台子服并发领取同一场奖励：只能有一台成功（MySQL 行锁/条件 UPDATE）。 */
    @Test
    void warRewardClaimIsExclusiveAcrossConnections() throws Exception {
        UUID player = UUID.randomUUID();
        String warId = UUID.randomUUID().toString();
        repo.saveWarRewards(List.of(new GuildRepository.WarReward(warId, player, "g1", true, "match", 100, 5, 0)));
        assertEquals(1, repo.loadPendingWarRewards(10).size(), "奖励行应先写入 PENDING");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            GuildRepository concurrent = new GuildRepository(db);
            futures.add(pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return concurrent.claimWarRewardsForWar(warId).size();
            }));
        }
        start.countDown();
        int claimed = 0;
        for (Future<Integer> f : futures) {
            claimed += f.get(10, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        assertEquals(1, claimed, "同一行奖励只能被领取一次");
    }

    private Guild guild(String id, String name, String tag) {
        long now = System.currentTimeMillis();
        return new Guild(id, name, tag, UUID.randomUUID(), "Leader", "", "", false,
                now, now, 0, 1, 0, 0, 0, "test", 1, "default");
    }

    private GuildMember leader(Guild g, String guildId) {
        return new GuildMember(g.leaderUuid(), "Leader", guildId, GuildRank.LEADER,
                System.currentTimeMillis(), 0, 0, 0);
    }

    @Test
    void warOriginUpsertWorksOnMySql() throws Exception {
        UUID player = UUID.randomUUID();
        repo.saveWarOrigin(new WarOrigin("war-origin-1", player, "zy", "world", 1.5, 64, -2.5, 90f, 45f));
        WarOrigin loaded = repo.loadWarOrigin(player);
        assertNotNull(loaded);
        assertEquals("zy", loaded.sourceServer());
        assertEquals(1.5, loaded.x(), 0.0001);
        assertEquals(90f, loaded.yaw(), 0.0001);

        repo.saveWarOrigin(new WarOrigin("war-origin-2", player, "fb", "world_nether", 0, 70, 0, 0f, 0f));
        assertEquals("war-origin-2", repo.loadWarOrigin(player).warId());
        repo.deleteWarOrigin(player);
        assertNull(repo.loadWarOrigin(player));
    }

    @Test
    void auditLogWorksOnMySql() throws Exception {
        GuildAuditRepository audit = new GuildAuditRepository(db);
        UUID actor = UUID.randomUUID();
        audit.insert(new AuditEntry("mysql-audit-1", "g-mysql", actor, "Tester", "bank.deposit",
                null, 100, "balance=100", System.currentTimeMillis()));
        var list = audit.recent("g-mysql", 10);
        assertEquals(1, list.size());
        assertEquals("bank.deposit", list.get(0).action());
        assertEquals(100, list.get(0).amount());
        assertTrue(audit.purgeBefore(System.currentTimeMillis() + 10_000L) >= 1);
    }

    @Test
    void warStatsWorkOnMySql() throws Exception {
        WarStatsRepository stats = new WarStatsRepository(db);
        long now = System.currentTimeMillis();
        UUID player = UUID.randomUUID();
        stats.save(List.of(new WarPlayerStat("mysql-war-stats", player, "Tester", "g-1", "A",
                7, 2, 420, true, now)));
        var loaded = stats.load("mysql-war-stats");
        assertEquals(1, loaded.size());
        assertEquals(7, loaded.get(0).kills());
        assertTrue(loaded.get(0).mvp());

        stats.save(List.of(new WarPlayerStat("mysql-war-stats", player, "Tester2", "g-1", "A",
                8, 2, 430, true, now)));
        assertEquals(8, stats.load("mysql-war-stats").get(0).kills());
        assertTrue(stats.purgeBefore(now + 10_000L) >= 1);
    }

    @Test
    void mailWorksOnMySql() throws Exception {
        MailRepository mail = new MailRepository(db);
        UUID player = UUID.randomUUID();
        long now = System.currentTimeMillis();
        mail.insertMany(List.of(new MailEntry("mysql-m-1", "g-1", player, "system",
                "测试", "正文", now, 0, now + 60_000)));
        assertEquals(1, mail.unreadCount(player));
        assertEquals(1, mail.inbox(player, 10).size());
        mail.markAllRead(player);
        assertEquals(0, mail.unreadCount(player));
        assertTrue(mail.purge(now + 120_000L) >= 1);
    }

    @Test
    void guildApplicationWorksOnMySql() throws Exception {
        GuildApplicationRepository apps = new GuildApplicationRepository(db);
        UUID player = UUID.randomUUID();
        long now = System.currentTimeMillis();
        apps.insert(new GuildApplication("mysql-app-1", "g-app", player, "Tester", "hello",
                "PENDING", now, now));
        assertEquals(1, apps.countPending(player));
        assertTrue(apps.decide("mysql-app-1", "REJECTED"));
        assertEquals(0, apps.countPending(player));
    }

    @Test
    void purgeResetsRunOnMySql() throws Exception {
        long now = System.currentTimeMillis();
        Guild g = new Guild("mysql-purge", "Purge", "PG", UUID.randomUUID(), "Leader", "", "", false,
                now, now, 0, 1, 0, 0, 0, "test", 1, "default");
        GuildMember leader = new GuildMember(g.leaderUuid(), "Leader", g.id(), GuildRank.LEADER,
                System.currentTimeMillis(), 9, 2, 1);
        repo.insertGuildWithLeader(g, leader);
        repo.resetWarehouse(g.id());
        repo.resetActivity(g.id(), 777L);
        repo.resetGuildStats(g.id());
        repo.resetMemberStats(g.id());
        assertEquals(777L, repo.loadGuild(g.id()).lastActiveAt());
        assertEquals(0, repo.loadMembers(g.id()).stream()
                .filter(x -> x.uuid().equals(leader.uuid())).findFirst().orElseThrow().kills());
    }
}
