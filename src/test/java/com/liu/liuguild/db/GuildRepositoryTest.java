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
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void spacePurchaseAndArchiveAreAtomicAndPreserveRetainedItems() throws Exception {
        Guild g = guild(UUID.randomUUID().toString(), "Space", "SPC");
        GuildMember leader = new GuildMember(g.leaderUuid(), "Leader", g.id(), GuildRank.LEADER,
                System.currentTimeMillis(), 0, 0, 0);
        repo.insertGuildWithLeader(g, leader);
        repo.addPoints(g.id(), 1000);
        var space = new com.liu.liuguild.config.WarehouseSettings();
        space.requireRent = false;
        var price = new com.liu.liuguild.config.WarehouseSettings.Price(
                com.liu.liuguild.config.WarehouseSettings.Currency.FUNDS, 100);
        space.levels.put(1, new com.liu.liuguild.config.WarehouseSettings.Level(1, 90, price, 1));
        var warehouse = new com.liu.liuguild.warehouse.WarehouseRepository(db);
        assertFalse(warehouse.purchase(g.id(), UUID.randomUUID(), false, space, true, 0, 0, 0, price));
        assertTrue(warehouse.purchase(g.id(), leader.uuid(), false, space, true, 0, 0, 0, price));
        assertFalse(warehouse.purchase(g.id(), leader.uuid(), false, space, true, 0, 0, 0, price));
        assertEquals(900, repo.loadGuild(g.id()).points());
        assertTrue(repo.loadGuild(g.id()).warehouseLevelExpiresAt() > System.currentTimeMillis());
        var snapshot = warehouse.loadSnapshot(g.id());
        assertTrue(warehouse.save(g.id(), snapshot.version(), java.util.Map.of(0,
                new com.liu.liuguild.warehouse.WarehouseRepository.Stored("opaque-item", 100))));
        long version = warehouse.loadSnapshot(g.id()).version();
        repo.deleteGuild(g.id(), -1);
        assertFalse(warehouse.save(g.id(), version, java.util.Map.of()));
        warehouse.cleanExpired(space, System.currentTimeMillis() + 86400000L);
        assertEquals(100, warehouse.loadSnapshot(g.id()).items().get(0).amount());
    }

    @Test
    void expiryCleanupIsIdempotentAndPermanentAccessSurvives() throws Exception {
        Guild g = guild(UUID.randomUUID().toString(), "Expiry", "EXP");
        repo.insertGuild(g);
        var warehouse = new com.liu.liuguild.warehouse.WarehouseRepository(db);
        var snapshot = warehouse.loadSnapshot(g.id());
        assertTrue(warehouse.save(g.id(), snapshot.version(), java.util.Map.of(0,
                new com.liu.liuguild.warehouse.WarehouseRepository.Stored("opaque-item", 5))));
        var space = new com.liu.liuguild.config.WarehouseSettings();
        space.expiredRetentionDays = 0;
        long now = System.currentTimeMillis();
        try (var c = db.source().getConnection(); var ps = c.prepareStatement("UPDATE `" + db.t("guilds") + "` SET warehouse_expires_at=? WHERE guild_id=?")) {
            ps.setLong(1, now - 1); ps.setString(2, g.id()); ps.executeUpdate();
        }
        warehouse.cleanExpired(space, now);
        var cleared = warehouse.loadSnapshot(g.id());
        assertTrue(cleared.items().isEmpty());
        warehouse.cleanExpired(space, now + 1);
        assertEquals(cleared.version(), warehouse.loadSnapshot(g.id()).version());
        assertEquals(0, repo.loadGuild(g.id()).warehouseExpiresAt());
        try (var c = db.source().getConnection(); var ps = c.prepareStatement("UPDATE `" + db.t("guilds") + "` SET warehouse_expires_at=? WHERE guild_id=?")) {
            ps.setLong(1, Long.MAX_VALUE); ps.setString(2, g.id()); ps.executeUpdate();
        }
        warehouse.cleanExpired(space, now + 2);
        assertEquals(Long.MAX_VALUE, repo.loadGuild(g.id()).warehouseExpiresAt());
    }

    @Test
    void disbandImmediateAndDelayedRetentionAreEnforced() throws Exception {
        var warehouse = new com.liu.liuguild.warehouse.WarehouseRepository(db);
        var settings = new com.liu.liuguild.config.WarehouseSettings();
        long now = System.currentTimeMillis();
        for (int days : new int[]{0, 1}) {
            Guild g = guild(UUID.randomUUID().toString(), "Delete" + days, "D" + days);
            repo.insertGuild(g);
            var snapshot = warehouse.loadSnapshot(g.id());
            assertTrue(warehouse.save(g.id(), snapshot.version(), java.util.Map.of(0,
                    new com.liu.liuguild.warehouse.WarehouseRepository.Stored("opaque", 1))));
            repo.deleteGuild(g.id(), days);
            assertEquals(days == 0, warehouse.loadSnapshot(g.id()).items().isEmpty());
            warehouse.cleanExpired(settings, now + 2 * 86400000L);
            var cleared = warehouse.loadSnapshot(g.id());
            assertTrue(cleared.items().isEmpty());
            warehouse.cleanExpired(settings, now + 3 * 86400000L);
            assertEquals(cleared.version(), warehouse.loadSnapshot(g.id()).version());
        }
    }

    @Test
    void disabledSpaceDoesNotDeleteExpiredContents() throws Exception {
        Guild g = guild(UUID.randomUUID().toString(), "Disabled", "DIS");
        repo.insertGuild(g);
        var warehouse = new com.liu.liuguild.warehouse.WarehouseRepository(db);
        var snapshot = warehouse.loadSnapshot(g.id());
        assertTrue(warehouse.save(g.id(), snapshot.version(), java.util.Map.of(0,
                new com.liu.liuguild.warehouse.WarehouseRepository.Stored("opaque", 1))));
        var settings = new com.liu.liuguild.config.WarehouseSettings();
        settings.enabled = false;
        settings.expiredRetentionDays = 0;
        try (var c = db.source().getConnection(); var ps = c.prepareStatement("UPDATE `" + db.t("guilds") + "` SET warehouse_expires_at=1 WHERE guild_id=?")) {
            ps.setString(1, g.id()); ps.executeUpdate();
        }
        warehouse.cleanExpired(settings, System.currentTimeMillis());
        assertEquals(1, warehouse.loadSnapshot(g.id()).items().size());
    }

    @Test
    void memberPvpOverrideRoundTripsAndNullMeansInheritDefault() throws Exception {
        Guild g = guild(UUID.randomUUID().toString(), "Pvp", "PVP");
        repo.insertGuild(g);
        assertNull(repo.loadGuild(g.id()).memberPvp(), "未设置时应保持 null，回退 guild.yml");

        g.memberPvp(true);
        repo.updateGuild(g);
        assertEquals(Boolean.TRUE, repo.loadGuild(g.id()).memberPvp());

        g.memberPvp(false);
        repo.updateGuild(g);
        assertEquals(Boolean.FALSE, repo.loadGuild(g.id()).memberPvp());
    }

    @Test
    void forceImportReplacesMappedDataAndRosterButPreservesIdentity() throws Exception {
        Guild original = guild(UUID.randomUUID().toString(), "Import", "IMP");
        var oldLeader = new GuildMember(original.leaderUuid(), "Old", original.id(), GuildRank.LEADER, 1, 0, 0, 0);
        repo.insertGuildWithLeader(original, oldLeader);
        var warehouse = new com.liu.liuguild.warehouse.WarehouseRepository(db);
        var snapshot = warehouse.loadSnapshot(original.id());
        assertTrue(warehouse.save(original.id(), snapshot.version(), java.util.Map.of(0,
                new com.liu.liuguild.warehouse.WarehouseRepository.Stored("preserved", 12))));
        Guild incoming = guild(UUID.randomUUID().toString(), "import", "NEW");
        incoming.points(4321);
        incoming.bank(987);
        var leader = new GuildMember(incoming.leaderUuid(), "New", incoming.id(), GuildRank.LEADER, 2, 3, 4, 5);
        assertEquals(-1, repo.importGuild(incoming, java.util.List.of(leader), false));
        assertEquals(1, repo.importGuild(incoming, java.util.List.of(leader), true));
        Guild saved = repo.loadGuild(original.id());
        assertEquals("IMP", saved.tag());
        assertEquals(4321, saved.points());
        assertEquals(987, saved.bank());
        assertEquals(incoming.leaderUuid(), saved.leaderUuid());
        org.junit.jupiter.api.Assertions.assertNull(repo.loadGuild(incoming.id()));
        assertEquals(leader.uuid(), repo.loadMembers(original.id()).get(0).uuid());
        assertEquals(1, repo.importGuild(incoming, java.util.List.of(leader), true));
        assertEquals(1, repo.loadMembers(original.id()).size());
        assertEquals(12, warehouse.loadSnapshot(original.id()).items().get(0).amount());
    }

    @Test
    void forceImportRejectsLeaderInAnotherGuildWithoutChangingExistingData() throws Exception {
        Guild original = guild(UUID.randomUUID().toString(), "Target", "TAR");
        var oldLeader = new GuildMember(original.leaderUuid(), "Old", original.id(), GuildRank.LEADER, 1, 0, 0, 0);
        repo.insertGuildWithLeader(original, oldLeader);
        Guild other = guild(UUID.randomUUID().toString(), "OtherImport", "OTH");
        repo.insertGuildWithLeader(other, new GuildMember(other.leaderUuid(), "Other", other.id(), GuildRank.LEADER, 1, 0, 0, 0));
        Guild incoming = guild(UUID.randomUUID().toString(), "Target", "NEW");
        var conflicting = new GuildMember(other.leaderUuid(), "Conflict", incoming.id(), GuildRank.LEADER, 1, 0, 0, 0);
        incoming.leaderUuid(other.leaderUuid());
        assertThrows(java.sql.SQLException.class, () -> repo.importGuild(incoming, java.util.List.of(conflicting), true));
        assertEquals(original.leaderUuid(), repo.loadGuild(original.id()).leaderUuid());
        assertEquals(oldLeader.uuid(), repo.loadMembers(original.id()).get(0).uuid());
        assertEquals(1, repo.loadMembers(other.id()).size());
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

    @Test
    void personalFundsAreSeparateAndDonationIsAtomic() throws Exception {
        Guild g = guild(UUID.randomUUID().toString(), "Funds", "FUN");
        GuildMember leader = new GuildMember(g.leaderUuid(), "Leader", g.id(), GuildRank.LEADER,
                System.currentTimeMillis(), 0, 0, 0);
        repo.insertGuildWithLeader(g, leader);
        PersonalFundsRepository funds = new PersonalFundsRepository(db);
        assertTrue(funds.grantCheckin(leader.uuid(), 100, 80));
        assertFalse(funds.grantCheckin(leader.uuid(), 100, 80));
        assertEquals(80, funds.balance(leader.uuid()));
        assertFalse(funds.donate(leader.uuid(), g.id(), 81));
        assertFalse(funds.donate(leader.uuid(), "missing", 10));
        assertFalse(funds.donate(leader.uuid(), g.id(), -1));
        assertEquals(80, funds.balance(leader.uuid()));
        assertTrue(funds.donate(leader.uuid(), g.id(), 30));
        assertEquals(50, funds.balance(leader.uuid()));
        assertEquals(30, repo.loadGuild(g.id()).points());
        assertEquals(0, repo.loadGuild(g.id()).bank());
    }

    @Test
    void purchaseSpendsPersonalFundsAndCreditsGuildPoints() throws Exception {
        Guild g = guild("seller", "Seller", "SEL");
        GuildMember leader = new GuildMember(g.leaderUuid(), "Leader", g.id(), GuildRank.LEADER,
                System.currentTimeMillis(), 0, 0, 0);
        repo.insertGuildWithLeader(g, leader);
        PersonalFundsRepository funds = new PersonalFundsRepository(db);
        assertTrue(funds.grantCheckin(leader.uuid(), 100, 100));
        try (var c = db.source().getConnection(); var st = c.createStatement()) {
            st.executeUpdate("INSERT INTO guild_shop (listing_id,guild_id,item_data,stock,price,buy_price,created_at)"
                    + " VALUES ('sale','seller','',10,5,0,0)");
        }
        var shop = new com.liu.liuguild.shop.ShopRepository(db);
        // 报价被并发修改（4 != 5）→ 整笔拒绝，不动资金/库存/点数
        assertFalse(shop.purchase("sale", "seller", leader.uuid(), 2, 4, 8, 7));
        assertEquals(100, funds.balance(leader.uuid()));
        assertEquals(0, repo.loadGuild("seller").points());
        // 正常购买：扣 10 个人资金，工会 +9 点数，库存 10→8
        assertTrue(shop.purchase("sale", "seller", leader.uuid(), 2, 5, 10, 9));
        assertEquals(90, funds.balance(leader.uuid()));
        assertEquals(9, repo.loadGuild("seller").points());
        assertEquals(0, repo.loadGuild("seller").bank());
        // 个人资金不足 → 回滚，库存与点数不变
        assertFalse(shop.purchase("sale", "seller", leader.uuid(), 2, 5, 500, 475));
        assertEquals(90, funds.balance(leader.uuid()));
        assertEquals(9, repo.loadGuild("seller").points());
        // 非本会成员 → 拒绝
        assertFalse(shop.purchase("sale", "seller", UUID.randomUUID(), 1, 5, 5, 5));
        assertEquals(8, shop.removeAndTakeStock("sale"));
        assertFalse(shop.purchase("sale", "seller", leader.uuid(), 1, 5, 5, 5));
        assertEquals(9, repo.loadGuild("seller").points());
    }

    @Test
    void shopRemovalReturnsDatabaseStockOnlyOnce() throws Exception {
        try (var c = db.source().getConnection(); var st = c.createStatement()) {
            st.executeUpdate("INSERT INTO guild_shop (listing_id,guild_id,item_data,stock,price,buy_price,created_at)"
                    + " VALUES ('listing','guild','',12,5,999,0)");
        }
        var shop = new com.liu.liuguild.shop.ShopRepository(db);
        assertTrue(shop.tryDecrement("listing", 5));
        assertEquals(7, shop.removeAndTakeStock("listing"));
        assertEquals(0, shop.removeAndTakeStock("listing"));
        assertFalse(shop.tryDecrement("listing", 1));
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
        // 点数不再随整行保存写入（只走原子 UPDATE），这里单独原子设置以验证其独立持久化
        repo.setPoints(id, 9999);
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

    // =====================================================================
    // 工会点数（工会资金）与仓库购买 / 升级
    // =====================================================================

    private static final long DAY = 86_400_000L;

    private String newGuild(String name, String tag) throws Exception {
        String id = UUID.randomUUID().toString();
        Guild g = guild(id, name, tag);
        repo.insertGuildWithLeader(g, new GuildMember(g.leaderUuid(), "Leader", id,
                GuildRank.LEADER, System.currentTimeMillis(), 0, 0, 0));
        return id;
    }

    @Test
    void addPointsIsAtomicAndBounded() throws Exception {
        String id = newGuild("Points", "PT");
        assertTrue(repo.addPoints(id, 500));
        assertTrue(repo.addPoints(id, 250));
        assertEquals(750, repo.loadGuild(id).points());

        // 不能让点数变成负数
        assertFalse(repo.addPoints(id, -1000));
        assertEquals(750, repo.loadGuild(id).points());

        assertTrue(repo.addPoints(id, -750));
        assertEquals(0, repo.loadGuild(id).points());

        assertTrue(repo.addPoints(id, Long.MAX_VALUE - 1));
        assertFalse(repo.addPoints(id, Long.MAX_VALUE));
        assertEquals(Long.MAX_VALUE - 1, repo.loadGuild(id).points());
    }

    @Test
    void rentWarehouseDebitsPointsAndExtendsExpiry() throws Exception {
        String id = newGuild("Rent", "RT");
        repo.addPoints(id, 1000);
        long now = 1_000_000L;

        assertTrue(repo.rentWarehouse(id, 200, now, 7 * DAY));
        Guild after = repo.loadGuild(id);
        assertEquals(800, after.points());
        assertEquals(now + 7 * DAY, after.warehouseExpiresAt());

        // 在有效期内续期：从「当前到期时间」而非 now 往后顺延
        long later = now + 1_000L;
        assertTrue(repo.rentWarehouse(id, 100, later, DAY));
        Guild renewed = repo.loadGuild(id);
        assertEquals(700, renewed.points());
        assertEquals(now + 8 * DAY, renewed.warehouseExpiresAt());
    }

    @Test
    void rentWarehouseStartsFromNowWhenExpired() throws Exception {
        String id = newGuild("Expired", "EX");
        repo.addPoints(id, 1000);
        long now = 100_000L;
        // 先过期
        assertTrue(repo.rentWarehouse(id, 100, now, DAY));
        long muchLater = now + 10 * DAY;
        assertTrue(repo.rentWarehouse(id, 100, muchLater, DAY));
        // 已过期：从 now 起算，而不是叠加到早已过期的旧时间
        assertEquals(muchLater + DAY, repo.loadGuild(id).warehouseExpiresAt());
    }

    @Test
    void rentWarehouseFailsWhenPointsInsufficient() throws Exception {
        String id = newGuild("PoorRent", "PR");
        repo.addPoints(id, 50);
        assertFalse(repo.rentWarehouse(id, 200, 1_000L, DAY));
        Guild g = repo.loadGuild(id);
        assertEquals(50, g.points());
        assertEquals(0, g.warehouseExpiresAt());
    }

    @Test
    void upgradeWarehouseDebitsPointsAndIncrementsLevel() throws Exception {
        String id = newGuild("Upgrade", "UP");
        repo.addPoints(id, 1000);
        assertTrue(repo.upgradeWarehouse(id, 1000, 0));
        Guild g = repo.loadGuild(id);
        assertEquals(0, g.points());
        assertEquals(1, g.warehouseLevel());

        repo.addPoints(id, 2000);
        assertTrue(repo.upgradeWarehouse(id, 2000, 1));
        assertEquals(2, repo.loadGuild(id).warehouseLevel());
    }

    @Test
    void upgradeWarehouseRejectsStaleOrInsufficientRequests() throws Exception {
        String id = newGuild("UpgradeGuard", "UG");
        repo.addPoints(id, 1000);
        // 点数不足
        assertFalse(repo.upgradeWarehouse(id, 2000, 0));
        assertEquals(0, repo.loadGuild(id).warehouseLevel());
        // 等级不符合期望（乐观锁）：别人已升过级
        assertFalse(repo.upgradeWarehouse(id, 1000, 5));
        assertEquals(1000, repo.loadGuild(id).points());
        // 正确请求成功
        assertTrue(repo.upgradeWarehouse(id, 1000, 0));
        assertEquals(1, repo.loadGuild(id).warehouseLevel());
        // 同一等级不能重复升级
        assertFalse(repo.upgradeWarehouse(id, 1000, 0));
    }

    @Test
    void warehouseStateSurvivesReload() throws Exception {
        String id = newGuild("Persist", "PS");
        repo.addPoints(id, 5000);
        long now = System.currentTimeMillis();
        assertTrue(repo.upgradeWarehouse(id, 1000, 0));
        assertTrue(repo.rentWarehouse(id, 500, now, 30 * DAY));

        Guild loaded = repo.loadGuild(id);
        assertEquals(1, loaded.warehouseLevel());
        assertEquals(now + 30 * DAY, loaded.warehouseExpiresAt());
        assertEquals(3500, loaded.points());
    }

    /** 等级原子提升只增不减，防止并发下旧值把等级写回退。 */
    @Test
    void raiseLevelIsMonotonic() throws Exception {
        String id = newGuild("Level", "LV");
        assertEquals(1, repo.loadGuild(id).level());
        assertTrue(repo.raiseLevel(id, 3));
        assertEquals(3, repo.loadGuild(id).level());
        // 更低或相同等级都不应改写
        assertFalse(repo.raiseLevel(id, 2));
        assertFalse(repo.raiseLevel(id, 3));
        assertEquals(3, repo.loadGuild(id).level());
        assertTrue(repo.raiseLevel(id, 4));
        assertEquals(4, repo.loadGuild(id).level());
    }

    // =====================================================================
    // 每日上限发放（工会战 / 在线活跃）
    // =====================================================================

    @Test
    void dailyCappedGrantStopsAtCap() throws Exception {
        String id = newGuild("Daily", "DL");
        assertEquals(300, repo.grantDailyCapped(id, 300, 100, 500, true));
        assertEquals(200, repo.grantDailyCapped(id, 300, 100, 500, true), "第二次只能补到上限");
        assertEquals(0, repo.grantDailyCapped(id, 300, 100, 500, true), "已达上限不再发");
        assertEquals(500, repo.loadGuild(id).points());
        long[] d = repo.loadDailyFunds(id);
        assertEquals(100, d[0]);
        assertEquals(500, d[1]);
        // 活跃计数不受影响
        assertEquals(0, d[2]);
        assertEquals(0, d[3]);
    }

    @Test
    void dailyCappedGrantResetsOnNewDay() throws Exception {
        String id = newGuild("Reset", "RS");
        assertEquals(100, repo.grantDailyCapped(id, 100, 5, 100, true));
        assertEquals(0, repo.grantDailyCapped(id, 100, 5, 100, true));
        assertEquals(100, repo.grantDailyCapped(id, 100, 6, 100, true), "新自然日上限重置");
        assertEquals(200, repo.loadGuild(id).points());
    }

    @Test
    void dailyCappedGrantUnlimitedWhenCapIsZero() throws Exception {
        String id = newGuild("Unlimited", "UL");
        assertEquals(5000, repo.grantDailyCapped(id, 5000, 7, 0, false));
        assertEquals(5000, repo.grantDailyCapped(id, 5000, 7, 0, false));
        assertEquals(10000, repo.loadGuild(id).points());
    }

    @Test
    void warAndActivityDailyCountersAreIndependent() throws Exception {
        String id = newGuild("Independent", "IN");
        assertEquals(80, repo.grantDailyCapped(id, 80, 9, 100, true));
        assertEquals(100, repo.grantDailyCapped(id, 100, 9, 100, false));
        long[] d = repo.loadDailyFunds(id);
        assertEquals(9, d[0]);
        assertEquals(80, d[1]);
        assertEquals(9, d[2]);
        assertEquals(100, d[3]);
    }
    @Test
    void memberWritesAreGuildScopedAndRespectDatabaseLimit() throws Exception {
        Guild g = guild(UUID.randomUUID().toString(), "Scoped", "SCP");
        GuildMember leader = new GuildMember(g.leaderUuid(), "Leader", g.id(), GuildRank.LEADER,
                System.currentTimeMillis(), 0, 0, 0);
        repo.insertGuildWithLeader(g, leader);

        assertFalse(repo.insertMemberAndIncrement(new GuildMember(UUID.randomUUID(), "Extra", g.id(),
                GuildRank.MEMBER, System.currentTimeMillis(), 0, 0, 0), 1), "数据库层必须拒绝超员");

        UUID member = UUID.randomUUID();
        GuildMember m = new GuildMember(member, "Member", g.id(), GuildRank.MEMBER,
                System.currentTimeMillis(), 0, 0, 0);
        assertTrue(repo.insertMemberAndIncrement(m, 2));
        assertEquals(g.id(), repo.guildIdOf(member));

        // 旧退会任务带着旧 guild_id 不能误删玩家后来加入的新成员关系
        repo.deleteMember(member, "other-guild");
        assertEquals(g.id(), repo.guildIdOf(member));
        repo.deleteMember(member, g.id());
        assertNull(repo.guildIdOf(member));
    }

    @Test
    void staleGuildSnapshotCannotOverwriteNewerUpdatedAt() throws Exception {
        Guild g = guild(UUID.randomUUID().toString(), "Optimistic", "OPT");
        repo.insertGuild(g);
        long expected = g.updatedAt();

        Guild older = repo.loadGuild(g.id());
        older.name("First");
        repo.updateGuild(older, expected);

        Guild stale = guild(g.id(), "Stale", "STL");
        stale.updatedAt(expected);
        repo.updateGuild(stale, expected);

        assertEquals("First", repo.loadGuild(g.id()).name(), "旧快照不能覆盖新名称");
    }

    @Test
    void warRewardOutboxIsIdempotentAndMarkedDone() throws Exception {
        Guild g = guild(UUID.randomUUID().toString(), "Reward", "RWD");
        repo.insertGuild(g);
        UUID player = UUID.randomUUID();
        var row = new GuildRepository.WarReward("war-1", player, g.id(), true, "match", 100, 5, 0);
        repo.saveWarRewards(java.util.List.of(row));
        repo.saveWarRewards(java.util.List.of(row));
        assertEquals(1, repo.loadPendingWarRewards(10).size(), "重复写入不能生成重复奖励");
        repo.markWarRewardDone("war-1", player);
        assertTrue(repo.loadPendingWarRewards(10).isEmpty());
    }

}
