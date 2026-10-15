package com.liu.liuguild.db;

import com.liu.liuguild.model.Guild;
import com.liu.liuguild.model.GuildMember;
import com.liu.liuguild.model.GuildRank;
import com.liu.liuguild.model.GuildWar;
import com.liu.liuguild.model.WarState;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 工会 / 成员 / 工会战的持久化访问层。所有方法都在异步线程调用。 */
public final class GuildRepository {

    private final Database db;

    public GuildRepository(Database db) {
        this.db = db;
    }

    /** 判断异常链中是否为唯一键冲突（MySQL 1062 / SQLState 23xxx / SQLite UNIQUE）。 */
    public static boolean isDuplicateKey(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof java.sql.SQLIntegrityConstraintViolationException) {
                return true;
            }
            if (c instanceof SQLException se) {
                if (se.getErrorCode() == 1062) {
                    return true;
                }
                String state = se.getSQLState();
                if (state != null && state.startsWith("23")) {
                    return true;
                }
            }
            String msg = c.getMessage();
            if (msg != null) {
                String m = msg.toUpperCase(java.util.Locale.ROOT);
                if (m.contains("UNIQUE") || m.contains("DUPLICATE ENTRY")) {
                    return true;
                }
            }
        }
        return false;
    }

    // =====================================================================
    // 工会
    // =====================================================================

    public List<Guild> loadGuilds() throws SQLException {
        List<Guild> out = new ArrayList<>();
        String sql = "SELECT * FROM `" + db.t("guilds") + "`";
        try (Connection c = db.source().getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(mapGuild(rs));
            }
        }
        return out;
    }

    public Guild loadGuild(String id) throws SQLException {
        String sql = "SELECT * FROM `" + db.t("guilds") + "` WHERE `guild_id`=?";
        try (Connection c = db.source().getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapGuild(rs) : null;
            }
        }
    }

    public boolean nameOrTagTaken(String name, String tag, String exceptId) throws SQLException {
        String sql = "SELECT `guild_id` FROM `" + db.t("guilds")
                + "` WHERE (LOWER(`name`)=LOWER(?) OR LOWER(`tag`)=LOWER(?)) AND `guild_id`<>? LIMIT 1";
        try (Connection c = db.source().getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, name);
            ps.setString(2, tag);
            ps.setString(3, exceptId == null ? "" : exceptId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public boolean nameTaken(String name) throws SQLException {
        return exists("name", name);
    }

    public boolean tagTaken(String tag) throws SQLException {
        return exists("tag", tag);
    }

    private boolean exists(String column, String value) throws SQLException {
        String sql = "SELECT 1 FROM `" + db.t("guilds") + "` WHERE LOWER(`" + column + "`)=LOWER(?) LIMIT 1";
        try (Connection c = db.source().getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** Atomically inserts a guild and its leader. */
    public void insertGuildWithLeader(Guild guild, GuildMember leader) throws SQLException {
        String guildSql = "INSERT INTO `" + db.t("guilds") + "` (`guild_id`,`name`,`tag`,`leader_uuid`,`leader_name`,`description`,`notice`,`icon`,`icon_data`,`open`,`created_at`,`updated_at`,`points`,`level`,`wins`,`losses`,`bank`,`home_server`,`member_count`,`last_active_at`) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        String memberSql = "INSERT INTO `" + db.t("guild_members") + "` (`player_uuid`,`player_name`,`guild_id`,`rank`,`joined_at`,`contributed`,`kills`,`deaths`,`last_checkin`,`checkin_streak`) VALUES (?,?,?,?,?,?,?,?,?,?)";
        try (Connection c = db.source().getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement gs = c.prepareStatement(guildSql); PreparedStatement ms = c.prepareStatement(memberSql)) {
                bindGuild(gs, guild);
                bindMember(ms, leader);
                gs.executeUpdate();
                ms.executeUpdate();
                c.commit();
            } catch (SQLException e) {
                try { c.rollback(); } catch (SQLException ignored) { }
                throw e;
            } finally { c.setAutoCommit(true); }
        }
    }

    private void bindGuild(PreparedStatement ps, Guild g) throws SQLException {
        ps.setString(1, g.id()); ps.setString(2, g.name()); ps.setString(3, g.tag());
        ps.setString(4, g.leaderUuid() == null ? null : g.leaderUuid().toString()); ps.setString(5, g.leaderName());
        ps.setString(6, g.description()); ps.setString(7, g.notice()); ps.setString(8, g.icon()); ps.setString(9, g.iconData());
        ps.setInt(10, g.open() ? 1 : 0); ps.setLong(11, g.createdAt()); ps.setLong(12, g.updatedAt()); ps.setLong(13, g.points());
        ps.setInt(14, g.level()); ps.setInt(15, g.wins()); ps.setInt(16, g.losses()); ps.setLong(17, g.bank()); ps.setString(18, g.homeServer()); ps.setInt(19, g.memberCount());
        ps.setLong(20, g.lastActiveAt());
    }

    private void bindMember(PreparedStatement ps, GuildMember m) throws SQLException {
        ps.setString(1, m.uuid().toString()); ps.setString(2, m.name()); ps.setString(3, m.guildId()); ps.setString(4, m.rank().name());
        ps.setLong(5, m.joinedAt()); ps.setLong(6, m.contributed()); ps.setInt(7, m.kills()); ps.setInt(8, m.deaths());
        ps.setLong(9, m.lastCheckin()); ps.setInt(10, m.checkinStreak());
    }
    public boolean insertGuild(Guild g) throws SQLException {
        String sql = "INSERT INTO `" + db.t("guilds") + "` (`guild_id`,`name`,`tag`,`leader_uuid`,`leader_name`,`description`,`notice`,`icon`,`icon_data`,`open`,`created_at`,`updated_at`,`points`,`level`,`wins`,`losses`,`bank`,`home_server`,`member_count`,`last_active_at`) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (Connection c = db.source().getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            bindGuild(ps, g);
            return ps.executeUpdate() > 0;
        }
    }

    /** 导入一个完整工会；强制模式只覆盖同名工会的映射字段和成员，保留空间等附属数据。 */
    public int importGuild(Guild guild, List<GuildMember> members, boolean force) throws SQLException {
        try (Connection c = db.source().getConnection()) {
            c.setAutoCommit(false);
            try {
                String existingId = null;
                try (var ps = c.prepareStatement("SELECT `guild_id` FROM `" + db.t("guilds")
                        + "` WHERE LOWER(`name`)=LOWER(?)" + (db.isSqlite() ? "" : " FOR UPDATE"))) {
                    ps.setString(1, guild.name());
                    try (var rs = ps.executeQuery()) { if (rs.next()) existingId = rs.getString(1); }
                }
                if (existingId != null && !force) { c.rollback(); return -1; }
                String id = existingId == null ? guild.id() : existingId;
                List<GuildMember> accepted = new ArrayList<>();
                java.util.Set<UUID> seen = new java.util.HashSet<>();
                for (GuildMember member : members) {
                    if (!seen.add(member.uuid())) continue;
                    try (var ps = c.prepareStatement("SELECT `guild_id` FROM `" + db.t("guild_members") + "` WHERE `player_uuid`=?")) {
                        ps.setString(1, member.uuid().toString());
                        try (var rs = ps.executeQuery()) {
                            if (rs.next() && !id.equals(rs.getString(1))) continue;
                        }
                    }
                    accepted.add(member);
                }
                if (accepted.stream().noneMatch(m -> m.uuid().equals(guild.leaderUuid())))
                    throw new SQLException("导入会长已属于其他工会，拒绝覆盖");
                if (existingId == null) {
                    guild.memberCount(accepted.size());
                    try (var ps = c.prepareStatement("INSERT INTO `" + db.t("guilds") + "` (`guild_id`,`name`,`tag`,`leader_uuid`,`leader_name`,`description`,`notice`,`icon`,`icon_data`,`open`,`created_at`,`updated_at`,`points`,`level`,`wins`,`losses`,`bank`,`home_server`,`member_count`,`last_active_at`) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                        bindGuild(ps, guild); ps.executeUpdate();
                    }
                } else {
                    try (var ps = c.prepareStatement("UPDATE `" + db.t("guilds") + "` SET `leader_uuid`=?,`leader_name`=?,`description`=?,`icon_data`=?,`open`=?,`created_at`=?,`updated_at`=?,`points`=?,`level`=?,`bank`=?,`member_count`=? WHERE `guild_id`=?")) {
                        ps.setString(1, guild.leaderUuid().toString()); ps.setString(2, guild.leaderName());
                        ps.setString(3, guild.description()); ps.setString(4, guild.iconData()); ps.setInt(5, guild.open() ? 1 : 0);
                        ps.setLong(6, guild.createdAt()); ps.setLong(7, guild.updatedAt()); ps.setLong(8, guild.points());
                        ps.setInt(9, guild.level()); ps.setLong(10, guild.bank()); ps.setInt(11, accepted.size());
                        ps.setString(12, id); ps.executeUpdate();
                    }
                    try (var ps = c.prepareStatement("DELETE FROM `" + db.t("guild_members") + "` WHERE `guild_id`=?")) {
                        ps.setString(1, id); ps.executeUpdate();
                    }
                }
                try (var ps = c.prepareStatement("INSERT INTO `" + db.t("guild_members") + "` (`player_uuid`,`player_name`,`guild_id`,`rank`,`joined_at`,`contributed`,`kills`,`deaths`,`last_checkin`,`checkin_streak`) VALUES (?,?,?,?,?,?,?,?,?,?)")) {
                    for (GuildMember member : accepted) {
                        bindMember(ps, member); ps.setString(3, id);
                        ps.setString(4, member.uuid().equals(guild.leaderUuid()) ? GuildRank.LEADER.name()
                                : member.rank() == GuildRank.LEADER ? GuildRank.MEMBER.name() : member.rank().name());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                c.commit();
                return accepted.size();
            } catch (SQLException e) {
                c.rollback(); throw e;
            } finally { c.setAutoCommit(true); }
        }
    }

    public void updateGuild(Guild g) throws SQLException {
        try (Connection c = db.source().getConnection();
             PreparedStatement ps = c.prepareStatement(guildUpdateSql())) {
            bindGuildUpdate(ps, g);
            ps.executeUpdate();
        }
    }

    private String guildUpdateSql() {
        return "UPDATE `" + db.t("guilds") + "` SET `name`=?,`tag`=?,`leader_uuid`=?,`leader_name`=?,"
                + "`description`=?,`notice`=?,`icon`=?,`icon_data`=?,`open`=?,`updated_at`=?,"
                + "`level`=?,`wins`=?,`losses`=?,"
                + "`home_server`=?,`member_count`=?,`last_active_at`=? WHERE `guild_id`=?";
    }

    private void bindGuildUpdate(PreparedStatement ps, Guild g) throws SQLException {
        ps.setString(1, g.name());
        ps.setString(2, g.tag());
        ps.setString(3, g.leaderUuid() == null ? null : g.leaderUuid().toString());
        ps.setString(4, g.leaderName());
        ps.setString(5, g.description());
        ps.setString(6, g.notice());
        ps.setString(7, g.icon());
        ps.setString(8, g.iconData());
        ps.setInt(9, g.open() ? 1 : 0);
        ps.setLong(10, g.updatedAt());
        ps.setInt(11, g.level());
        ps.setInt(12, g.wins());
        ps.setInt(13, g.losses());
        ps.setString(14, g.homeServer());
        ps.setInt(15, g.memberCount());
        ps.setLong(16, g.lastActiveAt());
        ps.setString(17, g.id());
    }

    /**
     * 关服前批量保存全部工会与成员（单事务 + 批处理），消除异步写入的最后一小段丢失窗口。
     */
    public void flushAll(List<Guild> guilds, List<GuildMember> members) throws SQLException {
        if (guilds.isEmpty() && members.isEmpty()) {
            return;
        }
        String memberSql = "UPDATE `" + db.t("guild_members") + "` SET `player_name`=?,`guild_id`=?,`rank`=?,"
                + "`contributed`=?,`kills`=?,`deaths`=?,`last_checkin`=?,`checkin_streak`=? WHERE `player_uuid`=?";
        try (Connection c = db.source().getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement gs = c.prepareStatement(guildUpdateSql());
                 PreparedStatement ms = c.prepareStatement(memberSql)) {
                for (Guild g : guilds) {
                    bindGuildUpdate(gs, g);
                    gs.addBatch();
                }
                for (GuildMember m : members) {
                    bindMemberUpdate(ms, m);
                    ms.addBatch();
                }
                if (!guilds.isEmpty()) {
                    gs.executeBatch();
                }
                if (!members.isEmpty()) {
                    ms.executeBatch();
                }
                c.commit();
            } catch (SQLException e) {
                try { c.rollback(); } catch (SQLException ignored) { }
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    /** 原子增减银行余额；余额不足时返回 false。 */
    public boolean addBank(String guildId, long delta) throws SQLException {
        if (delta == Long.MIN_VALUE) return false;
        String sql = "UPDATE `" + db.t("guilds") + "` SET `bank`=`bank`+? WHERE `guild_id`=? AND `bank`>=? AND `bank`<=?";
        try (Connection c = db.source().getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, delta);
            ps.setString(2, guildId);
            ps.setLong(3, delta < 0 ? -delta : 0);
            ps.setLong(4, delta > 0 ? Long.MAX_VALUE - delta : Long.MAX_VALUE);
            return ps.executeUpdate() > 0;
        }
    }

    /**
     * 原子增减工会点数（工会资金）；当结果会溢出 {@code [0, Long.MAX_VALUE]} 时返回 false。
     */
    public boolean addPoints(String guildId, long delta) throws SQLException {
        if (delta == Long.MIN_VALUE) return false;
        String sql = "UPDATE `" + db.t("guilds") + "` SET `points`=`points`+? WHERE `guild_id`=? AND `points`>=? AND `points`<=?";
        try (Connection c = db.source().getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, delta);
            ps.setString(2, guildId);
            ps.setLong(3, delta < 0 ? -delta : 0);
            ps.setLong(4, delta > 0 ? Long.MAX_VALUE - delta : Long.MAX_VALUE);
            return ps.executeUpdate() > 0;
        }
    }

    /** 原子设置工会点数（管理员）。 */
    public boolean setPoints(String guildId, long points) throws SQLException {
        String sql = "UPDATE `" + db.t("guilds") + "` SET `points`=? WHERE `guild_id`=?";
        try (Connection c = db.source().getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, Math.max(0, points));
            ps.setString(2, guildId);
            return ps.executeUpdate() > 0;
        }
    }

    /** 轻量读取工会点数（用于原子写入后刷新缓存，避免整行重载）。 */
    public long loadPoints(String guildId) throws SQLException {
        String sql = "SELECT `points` FROM `" + db.t("guilds") + "` WHERE `guild_id`=?";
        try (Connection c = db.source().getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, guildId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    /**
     * 原子提升工会等级（只增不减）。
     *
     * <p>点数用原子 UPDATE 写入后，等级由各子服根据点数重算；若两台子服同时加点，
     * 较旧的一次重算可能后落库，用整行保存会把已升上去的等级写回退。此方法只在
     * 新等级严格大于当前等级时才更新，保证等级单调不减。</p>
     *
     * @return 真正提升了等级返回 true；当前等级已不低于 {@code level} 返回 false
     */
    public boolean raiseLevel(String guildId, int level) throws SQLException {
        String sql = "UPDATE `" + db.t("guilds") + "` SET `level`=? WHERE `guild_id`=? AND `level`<?";
        try (Connection c = db.source().getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, level);
            ps.setString(2, guildId);
            ps.setInt(3, level);
            return ps.executeUpdate() > 0;
        }
    }

    /**
     * 发放「带每日上限」的工会资金：扣减无，直接给 points 加值，并累加当日计数。
     *
     * <p>跨服并发用「读-算-乐观锁写」：读当前日与当日已发放额，算出本次实发额，
     * 再用 {@code WHERE day=? AND earned=?} 作乐观锁写入；影响 0 行则重试。
     * 这样多台子服同时发放也不会突破每日上限，且同一语句内完成 points 与计数更新。</p>
     *
     * @param war true = 工会战计数列；false = 在线活跃计数列
     * @return 实际发放的点数（0 = 已达上限或工会不存在）
     */
    public long grantDailyCapped(String guildId, long delta, long day, long cap, boolean war) throws SQLException {
        if (delta <= 0) {
            return 0;
        }
        String dayCol = war ? "war_funds_day" : "activity_day";
        String earnedCol = war ? "war_funds_earned" : "activity_earned";
        String select = "SELECT `" + dayCol + "`,`" + earnedCol + "` FROM `" + db.t("guilds") + "` WHERE `guild_id`=?";
        String update = "UPDATE `" + db.t("guilds") + "` SET `points`=`points`+?,`" + dayCol + "`=?,`" + earnedCol + "`=?"
                + " WHERE `guild_id`=? AND `" + dayCol + "`=? AND `" + earnedCol + "`=?";
        for (int attempt = 0; attempt < 8; attempt++) {
            long curDay;
            long earned;
            try (Connection c = db.source().getConnection();
                 PreparedStatement ps = c.prepareStatement(select)) {
                ps.setString(1, guildId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return 0;
                    }
                    curDay = rs.getLong(1);
                    earned = rs.getLong(2);
                }
            }
            long used = curDay == day ? earned : 0;
            long granted = cap <= 0 ? delta : Math.min(delta, Math.max(0, cap - used));
            if (granted <= 0) {
                return 0;
            }
            long newEarned = used + granted;
            try (Connection c = db.source().getConnection();
                 PreparedStatement ps = c.prepareStatement(update)) {
                ps.setLong(1, granted);
                ps.setLong(2, day);
                ps.setLong(3, newEarned);
                ps.setString(4, guildId);
                ps.setLong(5, curDay);
                ps.setLong(6, earned);
                if (ps.executeUpdate() > 0) {
                    return granted;
                }
            }
        }
        return 0;
    }

    /** 读取每日上限计数：{工会战日, 工会战已发, 活跃日, 活跃已发}。 */
    public long[] loadDailyFunds(String guildId) throws SQLException {
        String sql = "SELECT `war_funds_day`,`war_funds_earned`,`activity_day`,`activity_earned`"
                + " FROM `" + db.t("guilds") + "` WHERE `guild_id`=?";
        try (Connection c = db.source().getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, guildId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return new long[]{0, 0, 0, 0};
                }
                return new long[]{rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)};
            }
        }
    }

    /**
     * 原子购买工会仓库使用时间：扣点数并把到期时间延长 {@code durationMillis}。
     *
     * <p>用一条 UPDATE 同时完成扣费与续期，且续期以「当前到期时间与 now 的较大值」为基准，
     * 因此多台子服并发购买既不会丢时长，也不会重复扣费。</p>
     *
     * @return 点数足够并完成扣费/续期返回 true；余额不足返回 false
     */
    public boolean rentWarehouse(String guildId, long cost, long now, long durationMillis) throws SQLException {
        if (cost < 0 || durationMillis <= 0) return false;
        String sql = "UPDATE `" + db.t("guilds") + "` SET `points`=`points`-?,"
                + "`warehouse_expires_at`=(CASE WHEN `warehouse_expires_at`>? THEN `warehouse_expires_at` ELSE ? END)+?"
                + " WHERE `guild_id`=? AND `points`>=?";
        try (Connection c = db.source().getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, cost);
            ps.setLong(2, now);
            ps.setLong(3, now);
            ps.setLong(4, durationMillis);
            ps.setString(5, guildId);
            ps.setLong(6, cost);
            return ps.executeUpdate() > 0;
        }
    }

    /**
     * 原子升级工会仓库容量：扣点数并把仓库等级 +1。
     *
     * <p>{@code warehouse_level=?} 作为乐观锁：两台子服同时升级时只有一台成功，
     * 另一台会因为等级已被改动而影响 0 行，从而避免「付一次钱升两级」。</p>
     *
     * @return 点数足够且等级仍是期望值时返回 true
     */
    public boolean upgradeWarehouse(String guildId, long cost, int expectedLevel) throws SQLException {
        if (cost < 0 || expectedLevel < 0) return false;
        String sql = "UPDATE `" + db.t("guilds") + "` SET `points`=`points`-?,`warehouse_level`=`warehouse_level`+1"
                + " WHERE `guild_id`=? AND `points`>=? AND `warehouse_level`=?";
        try (Connection c = db.source().getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, cost);
            ps.setString(2, guildId);
            ps.setLong(3, cost);
            ps.setInt(4, expectedLevel);
            return ps.executeUpdate() > 0;
        }
    }

    public void deleteGuild(String id) throws SQLException {
        deleteGuild(id, 0);
    }

    public void deleteGuild(String id, int warehouseRetentionDays) throws SQLException {
        try (Connection c = db.source().getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement guard = c.prepareStatement("UPDATE `" + db.t("guilds")
                        + "` SET `warehouse_level`=`warehouse_level` WHERE `guild_id`=?")) {
                    guard.setString(1, id);
                    guard.executeUpdate();
                }
                new com.liu.liuguild.warehouse.WarehouseRepository(db).archive(c, id, warehouseRetentionDays);
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM `" + db.t("guild_members") + "` WHERE `guild_id`=?")) {
                    ps.setString(1, id);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM `" + db.t("guild_homes") + "` WHERE `guild_id`=?")) {
                    ps.setString(1, id);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM `" + db.t("guilds") + "` WHERE `guild_id`=?")) {
                    ps.setString(1, id);
                    ps.executeUpdate();
                }
                c.commit();
            } catch (SQLException e) {
                try { c.rollback(); } catch (SQLException ignored) { }
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    // =====================================================================
    // 工会驻地
    // =====================================================================

    /** 写入/覆盖某工会某槽位的驻地坐标。 */
    public void saveHome(String guildId, int slot, String locationJson) throws SQLException {
        String table = db.t("guild_homes");
        String sql = db.isSqlite()
                ? "INSERT INTO `" + table + "` (`guild_id`,`slot`,`location_json`) VALUES (?,?,?) "
                        + "ON CONFLICT(`guild_id`,`slot`) DO UPDATE SET `location_json`=excluded.`location_json`"
                : "INSERT INTO `" + table + "` (`guild_id`,`slot`,`location_json`) VALUES (?,?,?) "
                        + "ON DUPLICATE KEY UPDATE `location_json`=VALUES(`location_json`)";
        try (Connection c = db.source().getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, guildId);
            ps.setInt(2, slot);
            ps.setString(3, locationJson);
            ps.executeUpdate();
        }
    }

    public void deleteHome(String guildId, int slot) throws SQLException {
        try (Connection c = db.source().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM `" + db.t("guild_homes") + "` WHERE `guild_id`=? AND `slot`=?")) {
            ps.setString(1, guildId);
            ps.setInt(2, slot);
            ps.executeUpdate();
        }
    }

    /** 读取某工会全部驻地：槽位 -> 序列化坐标。 */
    public java.util.Map<Integer, String> loadHomes(String guildId) throws SQLException {
        java.util.Map<Integer, String> out = new java.util.HashMap<>();
        try (Connection c = db.source().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT `slot`,`location_json` FROM `" + db.t("guild_homes") + "` WHERE `guild_id`=? ORDER BY `slot`")) {
            ps.setString(1, guildId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getInt(1), rs.getString(2));
                }
            }
        }
        return out;
    }

    public List<Guild> topBy(String column, int limit) throws SQLException {
        // column 由调用方白名单控制，不来自用户输入
        String sql = "SELECT * FROM `" + db.t("guilds") + "` ORDER BY `" + column + "` DESC LIMIT ?";
        List<Guild> out = new ArrayList<>();
        try (Connection c = db.source().getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(mapGuild(rs));
                }
            }
        }
        return out;
    }

    // =====================================================================
    // 成员
    // =====================================================================

    public List<GuildMember> loadMembers() throws SQLException {
        List<GuildMember> out = new ArrayList<>();
        String sql = "SELECT * FROM `" + db.t("guild_members") + "`";
        try (Connection c = db.source().getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                GuildMember member = mapMember(rs);
                if (member != null) {
                    out.add(member);
                }
            }
        }
        return out;
    }

    public List<GuildMember> loadMembers(String guildId) throws SQLException {
        List<GuildMember> out = new ArrayList<>();
        String sql = "SELECT * FROM `" + db.t("guild_members") + "` WHERE `guild_id`=?";
        try (Connection c = db.source().getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, guildId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    GuildMember member = mapMember(rs);
                    if (member != null) {
                        out.add(member);
                    }
                }
            }
        }
        return out;
    }

    /** Insert a member and update the denormalized member count in one transaction. */
    public boolean insertMemberAndIncrement(GuildMember member) throws SQLException {
        String insert = "INSERT INTO `" + db.t("guild_members") + "` (`player_uuid`,`player_name`,`guild_id`,`rank`,`joined_at`,`contributed`,`kills`,`deaths`,`last_checkin`,`checkin_streak`) VALUES (?,?,?,?,?,?,?,?,?,?)";
        try (Connection c = db.source().getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(insert)) {
                bindMember(ps, member);
                ps.executeUpdate();
                try (PreparedStatement update = c.prepareStatement("UPDATE `" + db.t("guilds") + "` SET `member_count`=`member_count`+1 WHERE `guild_id`=?")) {
                    update.setString(1, member.guildId());
                    if (update.executeUpdate() != 1) throw new SQLException("Guild disappeared during member join");
                }
                c.commit();
                return true;
            } catch (SQLException e) {
                try { c.rollback(); } catch (SQLException ignored) { }
                // Duplicate player UUID: SQLite/MySQL report this differently, so verify directly.
                if (memberExists(c, member.uuid())) {
                    return false;
                }
                throw e;
            } finally { c.setAutoCommit(true); }
        }
    }

    private boolean memberExists(Connection c, UUID uuid) {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM `" + db.t("guild_members") + "` WHERE `player_uuid`=?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            return false;
        }
    }
    /** Legacy import member insert. */
    public boolean insertMember(GuildMember m) throws SQLException {
        String sql = "INSERT INTO `" + db.t("guild_members") + "` (`player_uuid`,`player_name`,`guild_id`,`rank`,`joined_at`,`contributed`,`kills`,`deaths`,`last_checkin`,`checkin_streak`) VALUES (?,?,?,?,?,?,?,?,?,?)";
        try (Connection c = db.source().getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            bindMember(ps, m);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            if (e.getSQLState() != null && e.getSQLState().startsWith("23")) return false;
            throw e;
        }
    }

    public void updateMember(GuildMember m) throws SQLException {
        String sql = "UPDATE `" + db.t("guild_members") + "` SET `player_name`=?,`guild_id`=?,`rank`=?,"
                + "`contributed`=?,`kills`=?,`deaths`=?,`last_checkin`=?,`checkin_streak`=? WHERE `player_uuid`=?";
        try (Connection c = db.source().getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            bindMemberUpdate(ps, m);
            ps.executeUpdate();
        }
    }

    private void bindMemberUpdate(PreparedStatement ps, GuildMember m) throws SQLException {
        ps.setString(1, m.name());
        ps.setString(2, m.guildId());
        ps.setString(3, m.rank().name());
        ps.setLong(4, m.contributed());
        ps.setInt(5, m.kills());
        ps.setInt(6, m.deaths());
        ps.setLong(7, m.lastCheckin());
        ps.setInt(8, m.checkinStreak());
        ps.setString(9, m.uuid().toString());
    }

    public void deleteMember(UUID uuid) throws SQLException {
        try (Connection c = db.source().getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM `" + db.t("guild_members") + "` WHERE `player_uuid`=?")) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        }
    }

    public String guildIdOf(UUID uuid) throws SQLException {
        try (Connection c = db.source().getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT `guild_id` FROM `" + db.t("guild_members") + "` WHERE `player_uuid`=?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    // =====================================================================
    // 工会战
    // =====================================================================

    public void insertWar(GuildWar w) throws SQLException {
        String sql = "INSERT INTO `" + db.t("guild_wars") + "` (`war_id`,`guild_a`,`guild_b`,`name_a`,`name_b`,"
                + "`tag_a`,`tag_b`,`state`,`score_a`,`score_b`,`winner`,`host_server`,`arena_world`,`mode`,"
                + "`created_at`,`started_at`,`ended_at`) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (Connection c = db.source().getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, w.id());
            ps.setString(2, w.guildA());
            ps.setString(3, w.guildB());
            ps.setString(4, w.nameA());
            ps.setString(5, w.nameB());
            ps.setString(6, w.tagA());
            ps.setString(7, w.tagB());
            ps.setString(8, w.state().name());
            ps.setInt(9, w.scoreA());
            ps.setInt(10, w.scoreB());
            ps.setString(11, w.winner());
            ps.setString(12, w.hostServer());
            ps.setString(13, w.arenaWorld());
            ps.setString(14, w.mode());
            ps.setLong(15, w.createdAt());
            ps.setLong(16, w.startedAt());
            ps.setLong(17, w.endedAt());
            ps.executeUpdate();
        }
    }

    /** 状态条件迁移：仅当数据库当前状态为 from 时更新为 to（防多服重复结算）。 */
    public boolean transition(GuildWar w, WarState from, WarState to) throws SQLException {
        String sql = "UPDATE `" + db.t("guild_wars") + "` SET `state`=?,`score_a`=?,`score_b`=?,`winner`=?,"
                + "`started_at`=?,`ended_at`=? WHERE `war_id`=? AND `state`=?";
        try (Connection c = db.source().getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, to.name());
            ps.setInt(2, w.scoreA());
            ps.setInt(3, w.scoreB());
            ps.setString(4, w.winner());
            ps.setLong(5, w.startedAt());
            ps.setLong(6, w.endedAt());
            ps.setString(7, w.id());
            ps.setString(8, from.name());
            return ps.executeUpdate() > 0;
        }
    }

    public void updateWar(GuildWar w) throws SQLException {
        String sql = "UPDATE `" + db.t("guild_wars") + "` SET `state`=?,`score_a`=?,`score_b`=?,`winner`=?,"
                + "`host_server`=?,`arena_world`=?,`mode`=?,`started_at`=?,`ended_at`=? WHERE `war_id`=?";
        try (Connection c = db.source().getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, w.state().name());
            ps.setInt(2, w.scoreA());
            ps.setInt(3, w.scoreB());
            ps.setString(4, w.winner());
            ps.setString(5, w.hostServer());
            ps.setString(6, w.arenaWorld());
            ps.setString(7, w.mode());
            ps.setLong(8, w.startedAt());
            ps.setLong(9, w.endedAt());
            ps.setString(10, w.id());
            ps.executeUpdate();
        }
    }

    public GuildWar loadWar(String id) throws SQLException {
        String sql = "SELECT * FROM `" + db.t("guild_wars") + "` WHERE `war_id`=?";
        try (Connection c = db.source().getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapWar(rs) : null;
            }
        }
    }

    public List<GuildWar> loadLiveWars() throws SQLException {
        List<GuildWar> out = new ArrayList<>();
        String sql = "SELECT * FROM `" + db.t("guild_wars")
                + "` WHERE `state` NOT IN ('ENDED','CANCELLED') ORDER BY `created_at` ASC";
        try (Connection c = db.source().getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(mapWar(rs));
            }
        }
        return out;
    }

    public List<GuildWar> warHistory(String guildId, int limit) throws SQLException {
        List<GuildWar> out = new ArrayList<>();
        String sql = "SELECT * FROM `" + db.t("guild_wars")
                + "` WHERE (`guild_a`=? OR `guild_b`=?) AND `state`='ENDED' ORDER BY `ended_at` DESC LIMIT ?";
        try (Connection c = db.source().getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, guildId);
            ps.setString(2, guildId);
            ps.setInt(3, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(mapWar(rs));
                }
            }
        }
        return out;
    }

    public void purgeWarsBefore(long before) throws SQLException {
        try (Connection c = db.source().getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM `" + db.t("guild_wars")
                     + "` WHERE `state`='ENDED' AND `ended_at`<? AND `ended_at`>0")) {
            ps.setLong(1, before);
            ps.executeUpdate();
        }
    }

    // =====================================================================
    // 工会战装备托管快照（guild_war_escrow）
    // =====================================================================

    public void saveWarEscrow(com.liu.liuguild.war.KitService.PersistedEscrow e) throws SQLException {
        String update = "UPDATE `" + db.t("guild_war_escrow") + "` SET "
                + "`server_id`=?,`war_id`=?,`sparrow_snapshot_id`=?,`items_data`=?,"
                + "`world_name`=?,`x`=?,`y`=?,`z`=?,`yaw`=?,`pitch`=?,"
                + "`game_mode`=?,`health`=?,`food`=?,`exp_level`=?,`exp_progress`=?,`created_at`=? "
                + "WHERE `player_uuid`=?";
        try (Connection c = db.source().getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(update)) {
                ps.setString(1, e.serverId());
                ps.setString(2, e.warId());
                ps.setString(3, e.sparrowSnapshotId() == null ? null : e.sparrowSnapshotId().toString());
                ps.setString(4, e.itemsData());
                ps.setString(5, e.worldName());
                ps.setDouble(6, e.x());
                ps.setDouble(7, e.y());
                ps.setDouble(8, e.z());
                ps.setFloat(9, e.yaw());
                ps.setFloat(10, e.pitch());
                ps.setString(11, e.gameMode());
                ps.setDouble(12, e.health());
                ps.setInt(13, e.food());
                ps.setInt(14, e.level());
                ps.setFloat(15, e.exp());
                ps.setLong(16, e.createdAt());
                ps.setString(17, e.playerUuid().toString());
                if (ps.executeUpdate() > 0) {
                    return;
                }
            }
            String insert = "INSERT INTO `" + db.t("guild_war_escrow") + "` ("
                    + "`player_uuid`,`server_id`,`war_id`,`sparrow_snapshot_id`,`items_data`,"
                    + "`world_name`,`x`,`y`,`z`,`yaw`,`pitch`,"
                    + "`game_mode`,`health`,`food`,`exp_level`,`exp_progress`,`created_at`"
                    + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
            try (PreparedStatement ps = c.prepareStatement(insert)) {
                ps.setString(1, e.playerUuid().toString());
                ps.setString(2, e.serverId());
                ps.setString(3, e.warId());
                ps.setString(4, e.sparrowSnapshotId() == null ? null : e.sparrowSnapshotId().toString());
                ps.setString(5, e.itemsData());
                ps.setString(6, e.worldName());
                ps.setDouble(7, e.x());
                ps.setDouble(8, e.y());
                ps.setDouble(9, e.z());
                ps.setFloat(10, e.yaw());
                ps.setFloat(11, e.pitch());
                ps.setString(12, e.gameMode());
                ps.setDouble(13, e.health());
                ps.setInt(14, e.food());
                ps.setInt(15, e.level());
                ps.setFloat(16, e.exp());
                ps.setLong(17, e.createdAt());
                ps.executeUpdate();
            }
        }
    }

    public void updateWarEscrowSparrowId(UUID playerUuid, UUID sparrowSnapshotId) throws SQLException {
        String sql = "UPDATE `" + db.t("guild_war_escrow")
                + "` SET `sparrow_snapshot_id`=? WHERE `player_uuid`=?";
        try (Connection c = db.source().getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, sparrowSnapshotId == null ? null : sparrowSnapshotId.toString());
            ps.setString(2, playerUuid.toString());
            ps.executeUpdate();
        }
    }

    public com.liu.liuguild.war.KitService.PersistedEscrow loadWarEscrow(UUID playerUuid) throws SQLException {
        String sql = "SELECT * FROM `" + db.t("guild_war_escrow") + "` WHERE `player_uuid`=?";
        try (Connection c = db.source().getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return mapWarEscrow(rs);
            }
        }
    }

    public void deleteWarEscrow(UUID playerUuid) throws SQLException {
        try (Connection c = db.source().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM `" + db.t("guild_war_escrow") + "` WHERE `player_uuid`=?")) {
            ps.setString(1, playerUuid.toString());
            ps.executeUpdate();
        }
    }

    private com.liu.liuguild.war.KitService.PersistedEscrow mapWarEscrow(ResultSet rs) throws SQLException {
        return new com.liu.liuguild.war.KitService.PersistedEscrow(
                parseUuid(rs.getString("player_uuid")),
                rs.getString("server_id"),
                rs.getString("war_id"),
                parseUuid(rs.getString("sparrow_snapshot_id")),
                rs.getString("items_data"),
                rs.getString("world_name"),
                rs.getDouble("x"),
                rs.getDouble("y"),
                rs.getDouble("z"),
                rs.getFloat("yaw"),
                rs.getFloat("pitch"),
                rs.getString("game_mode"),
                rs.getDouble("health"),
                rs.getInt("food"),
                rs.getInt("exp_level"),
                rs.getFloat("exp_progress"),
                rs.getLong("created_at"));
    }

    // =====================================================================
    // 映射
    // =====================================================================

    /** 容错解析 UUID；格式非法时返回 null 而不抛异常。 */
    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Guild mapGuild(ResultSet rs) throws SQLException {
        String leader = rs.getString("leader_uuid");
        Guild g = new Guild(
                rs.getString("guild_id"),
                rs.getString("name"),
                rs.getString("tag"),
                leader == null || leader.isBlank() ? null : parseUuid(leader),
                rs.getString("leader_name"),
                rs.getString("description"),
                rs.getString("notice"),
                rs.getInt("open") != 0,
                rs.getLong("created_at"),
                rs.getLong("updated_at"),
                rs.getLong("points"),
                rs.getInt("level"),
                rs.getInt("wins"),
                rs.getInt("losses"),
                rs.getLong("bank"),
                rs.getString("home_server"),
                rs.getInt("member_count"),
                rs.getString("icon"));
        g.iconData(rs.getString("icon_data"));
        try {
            g.warehouseLevel(rs.getInt("warehouse_level"));
            g.warehouseLevelExpiresAt(rs.getLong("warehouse_level_expires_at"));
            g.warehousePermanentLevel(rs.getInt("warehouse_permanent_level"));
            g.warehouseExpiresAt(rs.getLong("warehouse_expires_at"));
            g.warFundsDay(rs.getLong("war_funds_day"));
            g.warFundsEarned(rs.getLong("war_funds_earned"));
            g.activityDay(rs.getLong("activity_day"));
            g.activityEarned(rs.getLong("activity_earned"));
        } catch (SQLException ignored) {
            // 旧库尚未补列时忽略（migrate 会补齐）
        }
        long active = rs.getLong("last_active_at");
        g.lastActiveAt(active > 0 ? active : g.createdAt());
        return g;
    }

    private GuildMember mapMember(ResultSet rs) throws SQLException {
        UUID uuid = parseUuid(rs.getString("player_uuid"));
        if (uuid == null) {
            // 脏数据：无法解析 UUID 的成员行直接跳过（否则会拖垮整份缓存加载）
            return null;
        }
        GuildMember m = new GuildMember(
                uuid,
                rs.getString("player_name"),
                rs.getString("guild_id"),
                GuildRank.parse(rs.getString("rank")),
                rs.getLong("joined_at"),
                rs.getLong("contributed"),
                rs.getInt("kills"),
                rs.getInt("deaths"));
        try {
            m.lastCheckin(rs.getLong("last_checkin"));
            m.checkinStreak(rs.getInt("checkin_streak"));
        } catch (SQLException ignored) {
            // 旧库尚未补列时忽略（migrate 会补齐）
        }
        return m;
    }

    private GuildWar mapWar(ResultSet rs) throws SQLException {
        GuildWar w = new GuildWar(rs.getString("war_id"), rs.getString("guild_a"), rs.getString("guild_b"));
        w.nameA(rs.getString("name_a"));
        w.nameB(rs.getString("name_b"));
        w.tagA(rs.getString("tag_a"));
        w.tagB(rs.getString("tag_b"));
        w.state(WarState.parse(rs.getString("state")));
        w.scoreA(rs.getInt("score_a"));
        w.scoreB(rs.getInt("score_b"));
        w.winner(rs.getString("winner"));
        w.hostServer(rs.getString("host_server"));
        w.arenaWorld(rs.getString("arena_world"));
        try {
            w.mode(rs.getString("mode"));
        } catch (SQLException ignored) {
        }
        w.createdAt(rs.getLong("created_at"));
        w.startedAt(rs.getLong("started_at"));
        w.endedAt(rs.getLong("ended_at"));
        return w;
    }
}
