package com.liu.liuguild.db;

import com.liu.liuguild.model.Guild;
import com.liu.liuguild.model.GuildMember;
import com.liu.liuguild.model.GuildRank;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Round-1 regression tests: transactional create/join and atomic bank updates. */
class GuildRepositoryTest {

    @TempDir
    Path tempDir;

    private Database db;
    private GuildRepository repo;

    @BeforeEach
    void setUp() throws Exception {
        DbConfig cfg = new DbConfig();
        cfg.type = "sqlite";
        cfg.sqliteFile = tempDir.resolve("test.db").toString();
        db = new Database(cfg, Logger.getLogger("LiuGuildTest"));
        db.init();
        repo = new GuildRepository(db);
    }

    @AfterEach
    void tearDown() {
        if (db != null) {
            db.shutdown();
        }
    }

    /** 重复调用 init()（相当于重启）不应因索引已存在而报错/告警。 */
    @Test
    void reInitIsIdempotentAndDoesNotFailOnExistingIndexes() throws Exception {
        Database second = new Database(db.config(), Logger.getLogger("LiuGuildTest"));
        second.init(); // 索引已存在：必须静默跳过，而不是当成“重复数据”告警
        second.shutdown();
    }

    /** 索引目录查询（MySQL/SQLite 共用的前置判断）必须真的能看到已建好的索引。 */
    @Test
    void detectsAlreadyExistingUniqueIndexes() throws Exception {
        assertTrue(db.indexExistsForTests(db.t("guilds") + "_name", db.t("guilds")),
                "唯一索引 " + db.t("guilds") + "_name 应已被检测到");
        assertTrue(db.indexExistsForTests(db.t("guilds") + "_tag", db.t("guilds")),
                "唯一索引 " + db.t("guilds") + "_tag 应已被检测到");
        assertFalse(db.indexExistsForTests("no_such_index_at_all", db.t("guilds")),
                "不存在的索引不应被误判为已存在");
    }

    private Guild guild(String id, String name, String tag) {
        long now = System.currentTimeMillis();
        return new Guild(id, name, tag, UUID.randomUUID(), "Leader", "", "", false,
                now, now, 0, 1, 0, 0, 0, "test", 1, "default");
    }

    @Test
    void createPersistsGuildAndLeaderAtomically() throws Exception {
        String id = UUID.randomUUID().toString();
        Guild g = guild(id, "Alpha", "AL");
        GuildMember leader = new GuildMember(g.leaderUuid(), "Leader", id, GuildRank.LEADER,
                System.currentTimeMillis(), 0, 0, 0);

        repo.insertGuildWithLeader(g, leader);

        assertEquals("Alpha", repo.loadGuild(id).name());
        assertEquals(1, repo.loadMembers(id).size());
    }

    @Test
    void duplicateNameOrTagIsRejectedByUniqueIndex() throws Exception {
        String idA = UUID.randomUUID().toString();
        Guild a = guild(idA, "Unique", "UQ");
        repo.insertGuildWithLeader(a, new GuildMember(a.leaderUuid(), "Leader", idA,
                GuildRank.LEADER, System.currentTimeMillis(), 0, 0, 0));

        // 同名（大小写不敏感）必须被数据库唯一索引拒绝
        String idB = UUID.randomUUID().toString();
        Guild b = guild(idB, "unique", "ZZ");
        GuildMember lb = new GuildMember(b.leaderUuid(), "Leader", idB,
                GuildRank.LEADER, System.currentTimeMillis(), 0, 0, 0);
        Exception nameEx = assertThrows(Exception.class, () -> repo.insertGuildWithLeader(b, lb));
        assertTrue(GuildRepository.isDuplicateKey(nameEx));

        // 同缩写（大小写不敏感）同样被拒绝
        String idC = UUID.randomUUID().toString();
        Guild c = guild(idC, "Other", "uq");
        GuildMember lc = new GuildMember(c.leaderUuid(), "Leader", idC,
                GuildRank.LEADER, System.currentTimeMillis(), 0, 0, 0);
        Exception tagEx = assertThrows(Exception.class, () -> repo.insertGuildWithLeader(c, lc));
        assertTrue(GuildRepository.isDuplicateKey(tagEx));
    }

    @Test
    void duplicateMemberIsRejectedWithoutCorruptingCount() throws Exception {
        String id = UUID.randomUUID().toString();
        Guild g = guild(id, "Beta", "BE");
        UUID player = UUID.randomUUID();
        GuildMember leader = new GuildMember(g.leaderUuid(), "Leader", id, GuildRank.LEADER,
                System.currentTimeMillis(), 0, 0, 0);
        repo.insertGuildWithLeader(g, leader);

        GuildMember m = new GuildMember(player, "Bob", id, GuildRank.MEMBER,
                System.currentTimeMillis(), 0, 0, 0);
        assertTrue(repo.insertMemberAndIncrement(m));
        assertEquals(1, repo.loadMembers(id).stream().filter(x -> x.uuid().equals(player)).count());
        assertEquals(2, repo.loadGuild(id).memberCount());

        // Same UUID again: only one row may exist and the count must not grow.
        GuildMember duplicate = new GuildMember(player, "Bob", id, GuildRank.MEMBER,
                System.currentTimeMillis(), 0, 0, 0);
        assertFalse(repo.insertMemberAndIncrement(duplicate));
        assertEquals(2, repo.loadGuild(id).memberCount());
    }

    @Test
    void bankUpdatesAreBoundedAndAtomic() throws Exception {
        String id = UUID.randomUUID().toString();
        Guild g = guild(id, "Gamma", "GA");
        GuildMember leader = new GuildMember(g.leaderUuid(), "Leader", id, GuildRank.LEADER,
                System.currentTimeMillis(), 0, 0, 0);
        repo.insertGuildWithLeader(g, leader);

        assertTrue(repo.addBank(id, 500));
        assertTrue(repo.addBank(id, 250));
        assertEquals(750, repo.loadGuild(id).bank());

        assertFalse(repo.addBank(id, -1000));
        assertEquals(750, repo.loadGuild(id).bank());

        assertTrue(repo.addBank(id, -750));
        assertEquals(0, repo.loadGuild(id).bank());

        assertTrue(repo.addBank(id, Long.MAX_VALUE - 1));
        assertFalse(repo.addBank(id, Long.MAX_VALUE));
        assertEquals(Long.MAX_VALUE - 1, repo.loadGuild(id).bank());
    }

    @Test
    void malformedMemberRowIsSkippedInsteadOfBreakingLoad() throws Exception {
        String id = UUID.randomUUID().toString();
        Guild g = guild(id, "Epsilon", "EP");
        GuildMember leader = new GuildMember(g.leaderUuid(), "Leader", id, GuildRank.LEADER,
                System.currentTimeMillis(), 0, 0, 0);
        repo.insertGuildWithLeader(g, leader);

        try (var c = db.source().getConnection();
             var ps = c.prepareStatement("INSERT INTO `guild_members` (`player_uuid`,`player_name`,`guild_id`,`rank`,`joined_at`,`contributed`,`kills`,`deaths`) VALUES (?,?,?,?,?,?,?,?)")) {
            ps.setString(1, "not-a-uuid");
            ps.setString(2, "Corrupt");
            ps.setString(3, id);
            ps.setString(4, "MEMBER");
            ps.setLong(5, System.currentTimeMillis());
            ps.setLong(6, 0);
            ps.setInt(7, 0);
            ps.setInt(8, 0);
            ps.executeUpdate();
        }

        // The corrupt row must not throw nor be returned.
        assertEquals(1, repo.loadMembers(id).size());
        assertEquals(1, repo.loadMembers().size());
    }

    @Test
    void flushAllPersistsGuildAndMemberChanges() throws Exception {
        String id = UUID.randomUUID().toString();
        Guild g = guild(id, "Flush", "FL");
        GuildMember leader = new GuildMember(g.leaderUuid(), "Leader", id, GuildRank.LEADER,
                System.currentTimeMillis(), 0, 0, 0);
        repo.insertGuildWithLeader(g, leader);

        g.points(9999);
        g.level(7);
        leader.addKill();
        leader.addDeath();
        repo.flushAll(java.util.List.of(g), java.util.List.of(leader));

        assertEquals(9999, repo.loadGuild(id).points());
        assertEquals(7, repo.loadGuild(id).level());
        GuildMember loaded = repo.loadMembers(id).get(0);
        assertEquals(1, loaded.kills());
        assertEquals(1, loaded.deaths());
    }

    @Test
    void checkinFieldsPersistThroughUpdateAndFlush() throws Exception {
        String id = UUID.randomUUID().toString();
        Guild g = guild(id, "Checkin", "CK");
        GuildMember leader = new GuildMember(g.leaderUuid(), "Leader", id, GuildRank.LEADER,
                System.currentTimeMillis(), 0, 0, 0);
        repo.insertGuildWithLeader(g, leader);

        leader.lastCheckin(20000L);
        leader.checkinStreak(7);
        repo.updateMember(leader);

        GuildMember loaded = repo.loadMembers(id).get(0);
        assertEquals(20000L, loaded.lastCheckin());
        assertEquals(7, loaded.checkinStreak());

        leader.lastCheckin(20001L);
        leader.checkinStreak(8);
        repo.flushAll(java.util.List.of(g), java.util.List.of(leader));
        GuildMember afterFlush = repo.loadMembers(id).get(0);
        assertEquals(20001L, afterFlush.lastCheckin());
        assertEquals(8, afterFlush.checkinStreak());
    }

    @Test
    void homesPersistAndDeletePerSlot() throws Exception {
        String id = UUID.randomUUID().toString();
        Guild g = guild(id, "Delta", "DE");
        GuildMember leader = new GuildMember(g.leaderUuid(), "Leader", id, GuildRank.LEADER,
                System.currentTimeMillis(), 0, 0, 0);
        repo.insertGuildWithLeader(g, leader);

        repo.saveHome(id, 1, "{\"world\":\"world\"}");
        repo.saveHome(id, 2, "{\"world\":\"nether\"}");
        assertEquals(2, repo.loadHomes(id).size());
        assertEquals("{\"world\":\"world\"}", repo.loadHomes(id).get(1));

        // Overwrite slot 1 (upsert must not create a duplicate row)
        repo.saveHome(id, 1, "{\"world\":\"overworld\"}");
        assertEquals(2, repo.loadHomes(id).size());
        assertEquals("{\"world\":\"overworld\"}", repo.loadHomes(id).get(1));

        repo.deleteHome(id, 1);
        assertEquals(1, repo.loadHomes(id).size());
        assertFalse(repo.loadHomes(id).containsKey(1));

        // Disband cleans up homes too
        repo.deleteGuild(id);
        assertTrue(repo.loadHomes(id).isEmpty());
    }
}
