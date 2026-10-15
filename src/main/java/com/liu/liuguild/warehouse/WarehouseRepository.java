package com.liu.liuguild.warehouse;

import com.liu.liuguild.db.Database;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/** 数量与 NBT 分离，版本条件写入防止过期会话覆盖新仓库。所有调用走异步路径。 */
public final class WarehouseRepository {
    public record Stored(String itemData, long amount) { }
    public record Snapshot(long version, Map<Integer, Stored> items) { }
    private final Database db;
    public WarehouseRepository(Database db) { this.db = db; }

    private void ensure(Connection c, String guildId) throws SQLException {
        String insert = db.isSqlite() ? "INSERT OR IGNORE" : "INSERT IGNORE";
        try (var ps = c.prepareStatement(insert + " INTO `" + db.t("guild_warehouse_state")
                + "` (`guild_id`) VALUES (?)")) {
            ps.setString(1, guildId);
            ps.executeUpdate();
        }
    }

    public Snapshot loadSnapshot(String guildId) throws SQLException {
        try (Connection c = db.source().getConnection()) {
            ensure(c, guildId);
            c.setAutoCommit(false);
            try {
                if (!db.isSqlite()) {
                    try (var ps = c.prepareStatement("SELECT `version` FROM `" + db.t("guild_warehouse_state") + "` WHERE `guild_id`=? FOR UPDATE")) {
                        ps.setString(1, guildId);
                        try (var rs = ps.executeQuery()) { if (!rs.next()) throw new SQLException("Missing warehouse state"); }
                    }
                }
                long version;
                try (var ps = c.prepareStatement("SELECT `version` FROM `" + db.t("guild_warehouse_state") + "` WHERE `guild_id`=?")) {
                    ps.setString(1, guildId);
                    try (var rs = ps.executeQuery()) { if (!rs.next()) throw new SQLException("Missing warehouse state"); version = rs.getLong(1); }
                }
                Map<Integer, Stored> out = new LinkedHashMap<>();
                try (var ps = c.prepareStatement("SELECT `slot`,`item_data`,`amount` FROM `" + db.t("guild_warehouse")
                        + "` WHERE `guild_id`=? ORDER BY `slot`")) {
                    ps.setString(1, guildId);
                    try (var rs = ps.executeQuery()) {
                        while (rs.next()) out.put(rs.getInt(1), new Stored(rs.getString(2), rs.getLong(3)));
                    }
                }
                c.commit();
                return new Snapshot(version, out);
            } catch (SQLException e) { c.rollback(); throw e; }
        }
    }

    /** 每次存取都持久化，只有版本一致的会话能提交。失败完整回滚，包括版本。 */
    public boolean save(String guildId, long version, Map<Integer, Stored> items) throws SQLException {
        try (Connection c = db.source().getConnection()) {
            c.setAutoCommit(false);
            try {
                if (!guard(c, guildId)) { c.rollback(); return false; }
                try (var ps = c.prepareStatement("UPDATE `" + db.t("guild_warehouse_state")
                        + "` SET `version`=`version`+1 WHERE `guild_id`=? AND `version`=? AND `deleted_at`=0 AND EXISTS (SELECT 1 FROM `" + db.t("guilds") + "` g WHERE g.`guild_id`=? )")) {
                    ps.setString(1, guildId);
                    ps.setLong(2, version);
                    ps.setString(3, guildId);
                    if (ps.executeUpdate() != 1) { c.rollback(); return false; }
                }
                try (var ps = c.prepareStatement("DELETE FROM `" + db.t("guild_warehouse") + "` WHERE `guild_id`=?")) {
                    ps.setString(1, guildId);
                    ps.executeUpdate();
                }
                try (var ps = c.prepareStatement("INSERT INTO `" + db.t("guild_warehouse")
                        + "` (`guild_id`,`slot`,`item_data`,`amount`,`updated_at`) VALUES (?,?,?,?,?)")) {
                    for (var e : items.entrySet()) {
                        if (e.getKey() < 0 || e.getValue().itemData() == null || e.getValue().itemData().isBlank()
                                || e.getValue().amount() <= 0 || e.getValue().amount() > Integer.MAX_VALUE)
                            throw new SQLException("Invalid warehouse entry");
                        ps.setString(1, guildId);
                        ps.setInt(2, e.getKey());
                        ps.setString(3, e.getValue().itemData());
                        ps.setLong(4, e.getValue().amount());
                        ps.setLong(5, System.currentTimeMillis());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                c.commit();
                return true;
            } catch (SQLException e) { c.rollback(); throw e; }
        }
    }

    /** 与删除工会处于同一事务；墓碑阻止旧会话重新写入。 */
    public void archive(Connection c, String id, int days) throws SQLException {
        ensure(c, id);
        long now = System.currentTimeMillis();
        try (var ps = c.prepareStatement("UPDATE `" + db.t("guild_warehouse_state")
                + "` SET `version`=`version`+1,`deleted_at`=?,`purge_at`=? WHERE `guild_id`=?")) {
            ps.setLong(1, now);
            ps.setLong(2, days < 0 ? -1 : now + days * 86400000L);
            ps.setString(3, id); ps.executeUpdate();
        }
        if (days == 0) clear(c, id);
    }

    private void clear(Connection c, String id) throws SQLException {
        try (var ps = c.prepareStatement("DELETE FROM `" + db.t("guild_warehouse") + "` WHERE `guild_id`=?")) {
            ps.setString(1, id); ps.executeUpdate();
        }
        try (var ps = c.prepareStatement("UPDATE `" + db.t("guild_warehouse_state")
                + "` SET `version`=`version`+1 WHERE `guild_id`=?")) {
            ps.setString(1, id); ps.executeUpdate();
        }
    }

    /** 获取工会行写锁，序列化购买、到期清理和解散。 */
    private boolean guard(Connection c, String id) throws SQLException {
        if (!db.isSqlite()) {
            try (var ps = c.prepareStatement("SELECT `guild_id` FROM `" + db.t("guilds") + "` WHERE `guild_id`=? FOR UPDATE")) {
                ps.setString(1, id);
                try (var rs = ps.executeQuery()) { return rs.next(); }
            }
        }
        try (var ps = c.prepareStatement("UPDATE `" + db.t("guilds") + "` SET `warehouse_level`=`warehouse_level` WHERE `guild_id`=?")) {
            ps.setString(1, id); return ps.executeUpdate() == 1;
        }
    }

    public void cleanExpired(com.liu.liuguild.config.WarehouseSettings settings, long now) throws SQLException {
        if (settings == null || !settings.enabled) return;
        try (var c = db.source().getConnection()) {
            java.util.List<String> ids = new java.util.ArrayList<>();
            try (var ps = c.prepareStatement("SELECT `guild_id` FROM `" + db.t("guilds")
                    + "` WHERE `warehouse_expires_at`>0 AND `warehouse_expires_at`<?")) {
                ps.setLong(1, settings.requireRent && settings.expiredRetentionDays >= 0
                        ? now - settings.expiredRetentionDays * 86400000L + 1 : 0);
                try (var rs = ps.executeQuery()) { while (rs.next()) ids.add(rs.getString(1)); }
            }
            for (String id : ids) {
                c.setAutoCommit(false);
                try {
                    if (guard(c, id)) expire(c, id, settings, now);
                    c.commit();
                } catch (SQLException e) { c.rollback(); throw e; }
                finally { c.setAutoCommit(true); }
            }
            try (var ps = c.prepareStatement("SELECT `guild_id` FROM `" + db.t("guild_warehouse_state")
                    + "` WHERE `deleted_at`>0 AND `purge_at`>=0 AND `purge_at`<=?")) {
                ps.setLong(1, now);
                ids.clear();
                try (var rs = ps.executeQuery()) { while (rs.next()) ids.add(rs.getString(1)); }
            }
            for (String id : ids) {
                c.setAutoCommit(false);
                try {
                    clear(c, id);
                    try (var ps = c.prepareStatement("UPDATE `" + db.t("guild_warehouse_state") + "` SET `purge_at`=-2 WHERE `guild_id`=?")) {
                        ps.setString(1, id); ps.executeUpdate();
                    }
                    c.commit();
                }
                catch (SQLException e) { c.rollback(); throw e; }
                finally { c.setAutoCommit(true); }
            }
        }
    }

    private void expire(Connection c, String id, com.liu.liuguild.config.WarehouseSettings s, long now) throws SQLException {
        if (!s.requireRent || s.expiredRetentionDays < 0) return;
        try (var ps = c.prepareStatement("SELECT `warehouse_expires_at` FROM `" + db.t("guilds") + "` WHERE `guild_id`=?")) {
            ps.setString(1, id);
            try (var rs = ps.executeQuery()) {
                if (rs.next()) {
                    long expiry = rs.getLong(1);
                    if (expiry > 0 && expiry != Long.MAX_VALUE && now >= expiry
                            && now - expiry >= s.expiredRetentionDays * 86400000L) {
                        clear(c, id);
                        try (var reset = c.prepareStatement("UPDATE `" + db.t("guilds")
                                + "` SET `warehouse_expires_at`=0 WHERE `guild_id`=?")) {
                            reset.setString(1, id); reset.executeUpdate();
                        }
                    }
                }
            }
        }
    }

    /** 公共余额扣费和权益更新同一事务。外部货币由调用方扣款及失败补偿。 */
    public boolean purchase(String id, java.util.UUID actor, boolean admin,
                            com.liu.liuguild.config.WarehouseSettings s, boolean upgrade, int days,
                            int expectedLevel, long expectedExpiry,
                            com.liu.liuguild.config.WarehouseSettings.Price price) throws SQLException {
        try (var c = db.source().getConnection()) {
            c.setAutoCommit(false);
            try {
                if (!guard(c, id)) { c.rollback(); return false; }
                if (!admin) {
                    try (var ps = c.prepareStatement("SELECT `rank` FROM `" + db.t("guild_members") + "` WHERE `guild_id`=? AND `player_uuid`=?")) {
                        ps.setString(1, id); ps.setString(2, actor.toString());
                        try (var rs = ps.executeQuery()) {
                            if (!rs.next() || !com.liu.liuguild.model.GuildRank.parse(rs.getString(1)).atLeast(s.manageRank)) {
                                c.rollback(); return false;
                            }
                        }
                    }
                }
                long now = System.currentTimeMillis();
                int level, permanent; long levelExpiry, expiry;
                try (var ps = c.prepareStatement("SELECT `warehouse_level`,`warehouse_permanent_level`,`warehouse_level_expires_at`,`warehouse_expires_at` FROM `" + db.t("guilds") + "` WHERE `guild_id`=?")) {
                    ps.setString(1, id);
                    try (var rs = ps.executeQuery()) {
                        if (!rs.next()) { c.rollback(); return false; }
                        level = rs.getInt(1); permanent = rs.getInt(2); levelExpiry = rs.getLong(3); expiry = rs.getLong(4);
                    }
                }
                if (levelExpiry == 0) permanent = Math.max(permanent, level);
                if (levelExpiry > 0 && levelExpiry <= now) level = permanent;
                if (level != expectedLevel || expiry != expectedExpiry) { c.rollback(); return false; }
                expire(c, id, s, now);
                if (s.requireRent && s.expiredRetentionDays >= 0 && expiry > 0 && expiry != Long.MAX_VALUE
                        && now >= expiry && now - expiry >= s.expiredRetentionDays * 86400000L) expiry = 0;
                if (upgrade) {
                    var next = s.levels.get(level + 1);
                    if (next == null || !next.price().equals(price)) { c.rollback(); return false; }
                    level++;
                    levelExpiry = next.permanent() ? 0 : now + next.days() * 86400000L;
                    if (next.permanent()) permanent = level;
                } else {
                    if (!s.requireRent || expiry == Long.MAX_VALUE || days < 0) { c.rollback(); return false; }
                    var expectedPrice = days == 0 ? s.permanentPrice : new com.liu.liuguild.config.WarehouseSettings.Price(
                            s.rentPrice.currency(), Math.multiplyExact(s.rentPrice.amount(), days));
                    if (!price.equals(expectedPrice)) { c.rollback(); return false; }
                    expiry = days == 0 ? Long.MAX_VALUE : Math.addExact(Math.max(now, expiry), days * 86400000L);
                    if (days != 0 && s.maxDays > 0 && expiry - now > s.maxDays * 86400000L) { c.rollback(); return false; }
                }
                String balance = switch (price.currency()) { case FUNDS -> "points"; case BANK -> "bank"; default -> null; };
                if (balance != null) {
                    try (var ps = c.prepareStatement("UPDATE `" + db.t("guilds") + "` SET `" + balance + "`=`" + balance + "`-? WHERE `guild_id`=? AND `" + balance + "`>=?")) {
                        ps.setLong(1, price.amount()); ps.setString(2, id); ps.setLong(3, price.amount());
                        if (ps.executeUpdate() != 1) { c.rollback(); return false; }
                    }
                }
                try (var ps = c.prepareStatement("UPDATE `" + db.t("guilds") + "` SET `warehouse_level`=?,`warehouse_permanent_level`=?,`warehouse_level_expires_at`=?,`warehouse_expires_at`=? WHERE `guild_id`=?")) {
                    ps.setInt(1, level); ps.setInt(2, permanent); ps.setLong(3, levelExpiry); ps.setLong(4, expiry); ps.setString(5, id); ps.executeUpdate();
                }
                c.commit(); return true;
            } catch (Exception e) { c.rollback(); throw new SQLException("空间购买失败", e); }
        }
    }

    /** 解散时同时递增版本，所有旧会话提交立即失效。 */
    public void deleteAll(String guildId) throws SQLException {
        Snapshot snapshot = loadSnapshot(guildId);
        if (!save(guildId, snapshot.version(), Map.of())) throw new SQLException("Warehouse changed during purge");
    }
}
