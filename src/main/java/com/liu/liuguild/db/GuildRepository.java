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
        String guildSql = "INSERT INTO `" + db.t("guilds") + "` (`guild_id`,`name`,`tag`,`leader_uuid`,`leader_name`,`description`,`notice`,`icon`,`icon_data`,`open`,`created_at`,`updated_at`,`points`,`level`,`wins`,`losses`,`bank`,`home_server`,`member_count`) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        String memberSql = "INSERT INTO `" + db.t("guild_members") + "` (`player_uuid`,`player_name`,`guild_id`,`rank`,`joined_at`,`contributed`,`kills`,`deaths`) VALUES (?,?,?,?,?,?,?,?)";
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
    }

    private void bindMember(PreparedStatement ps, GuildMember m) throws SQLException {
        ps.setString(1, m.uuid().toString()); ps.setString(2, m.name()); ps.setString(3, m.guildId()); ps.setString(4, m.rank().name());
        ps.setLong(5, m.joinedAt()); ps.setLong(6, m.contributed()); ps.setInt(7, m.kills()); ps.setInt(8, m.deaths());
    }
    public boolean insertGuild(Guild g) throws SQLException {
        String sql = "INSERT INTO `" + db.t("guilds") + "` (`guild_id`,`name`,`tag`,`leader_uuid`,`leader_name`,`description`,`notice`,`icon`,`icon_data`,`open`,`created_at`,`updated_at`,`points`,`level`,`wins`,`losses`,`bank`,`home_server`,`member_count`) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (Connection c = db.source().getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            bindGuild(ps, g);
            return ps.executeUpdate() > 0;
        }
    }

    public void updateGuild(Guild g) throws SQLException {
        String sql = "UPDATE `" + db.t("guilds") + "` SET `name`=?,`tag`=?,`leader_uuid`=?,`leader_name`=?,"
                + "`description`=?,`notice`=?,`icon`=?,`icon_data`=?,`open`=?,`updated_at`=?,`points`=?,`level`=?,`wins`=?,`losses`=?,"
                + "`home_server`=?,`member_count`=? WHERE `guild_id`=?";
        try (Connection c = db.source().getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
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
            ps.setLong(11, g.points());
            ps.setInt(12, g.level());
            ps.setInt(13, g.wins());
            ps.setInt(14, g.losses());
            ps.setString(15, g.homeServer());
            ps.setInt(16, g.memberCount());
            ps.setString(17, g.id());
            ps.executeUpdate();
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

    public void deleteGuild(String id) throws SQLException {
        try (Connection c = db.source().getConnection()) {
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
                out.add(mapMember(rs));
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
                    out.add(mapMember(rs));
                }
            }
        }
        return out;
    }

    /** Insert a member and update the denormalized member count in one transaction. */
    public boolean insertMemberAndIncrement(GuildMember member) throws SQLException {
        String insert = "INSERT INTO `" + db.t("guild_members") + "` (`player_uuid`,`player_name`,`guild_id`,`rank`,`joined_at`,`contributed`,`kills`,`deaths`) VALUES (?,?,?,?,?,?,?,?)";
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
        String sql = "INSERT INTO `" + db.t("guild_members") + "` (`player_uuid`,`player_name`,`guild_id`,`rank`,`joined_at`,`contributed`,`kills`,`deaths`) VALUES (?,?,?,?,?,?,?,?)";
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
                + "`contributed`=?,`kills`=?,`deaths`=? WHERE `player_uuid`=?";
        try (Connection c = db.source().getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, m.name());
            ps.setString(2, m.guildId());
            ps.setString(3, m.rank().name());
            ps.setLong(4, m.contributed());
            ps.setInt(5, m.kills());
            ps.setInt(6, m.deaths());
            ps.setString(7, m.uuid().toString());
            ps.executeUpdate();
        }
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
    // 映射
    // =====================================================================

    private Guild mapGuild(ResultSet rs) throws SQLException {
        String leader = rs.getString("leader_uuid");
        Guild g = new Guild(
                rs.getString("guild_id"),
                rs.getString("name"),
                rs.getString("tag"),
                leader == null || leader.isBlank() ? null : UUID.fromString(leader),
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
        return g;
    }

    private GuildMember mapMember(ResultSet rs) throws SQLException {
        return new GuildMember(
                UUID.fromString(rs.getString("player_uuid")),
                rs.getString("player_name"),
                rs.getString("guild_id"),
                GuildRank.parse(rs.getString("rank")),
                rs.getLong("joined_at"),
                rs.getLong("contributed"),
                rs.getInt("kills"),
                rs.getInt("deaths"));
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
