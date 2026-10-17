package com.liu.liuguild.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 工会入会申请读写。 */
public final class GuildApplicationRepository {

    private final Database db;

    public GuildApplicationRepository(Database db) {
        this.db = db;
    }

    public void insert(GuildApplication a) throws SQLException {
        try (Connection c = db.source().getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT INTO `" + db.t("guild_applications") + "`"
                     + " (`id`,`guild_id`,`player_uuid`,`player_name`,`message`,`state`,`created_at`,`updated_at`)"
                     + " VALUES (?,?,?,?,?,?,?,?)")) {
            ps.setString(1, a.id());
            ps.setString(2, a.guildId());
            ps.setString(3, a.player().toString());
            ps.setString(4, a.playerName());
            ps.setString(5, a.message());
            ps.setString(6, a.state());
            ps.setLong(7, a.createdAt());
            ps.setLong(8, a.updatedAt());
            ps.executeUpdate();
        }
    }

    public List<GuildApplication> pendingForGuild(String guildId, int limit) throws SQLException {
        return query("SELECT * FROM `" + db.t("guild_applications")
                + "` WHERE `guild_id`=? AND `state`='PENDING' ORDER BY `created_at` ASC LIMIT ?", guildId, limit);
    }

    public List<GuildApplication> pendingForPlayer(UUID player) throws SQLException {
        return query("SELECT * FROM `" + db.t("guild_applications")
                + "` WHERE `player_uuid`=? AND `state`='PENDING' ORDER BY `created_at` ASC LIMIT ?", player.toString(), 50);
    }

    public int countPending(UUID player) throws SQLException {
        try (Connection c = db.source().getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM `" + db.t("guild_applications")
                     + "` WHERE `player_uuid`=? AND `state`='PENDING'")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public GuildApplication pendingById(String id) throws SQLException {
        List<GuildApplication> list = query("SELECT * FROM `" + db.t("guild_applications")
                + "` WHERE `id`=? AND `state`='PENDING' LIMIT ?", id, 1);
        return list.isEmpty() ? null : list.get(0);
    }

    public boolean decide(String id, String state) throws SQLException {
        try (Connection c = db.source().getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE `" + db.t("guild_applications")
                     + "` SET `state`=?, `updated_at`=? WHERE `id`=? AND `state`='PENDING'")) {
            ps.setString(1, state);
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, id);
            return ps.executeUpdate() > 0;
        }
    }

    /** 审批后加入失败时把已批准申请恢复为待审批。 */
    public boolean reopen(String id) throws SQLException {
        try (Connection c = db.source().getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE `" + db.t("guild_applications")
                     + "` SET `state`='PENDING', `updated_at`=? WHERE `id`=? AND `state`='APPROVED'")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, id);
            return ps.executeUpdate() > 0;
        }
    }

    public boolean cancel(String id, UUID player) throws SQLException {
        try (Connection c = db.source().getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE `" + db.t("guild_applications")
                     + "` SET `state`='CANCELLED', `updated_at`=? WHERE `id`=? AND `player_uuid`=? AND `state`='PENDING'")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, id);
            ps.setString(3, player.toString());
            return ps.executeUpdate() > 0;
        }
    }

    public int cancelPendingForPlayer(UUID player) throws SQLException {
        try (Connection c = db.source().getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE `" + db.t("guild_applications")
                     + "` SET `state`='CANCELLED', `updated_at`=? WHERE `player_uuid`=? AND `state`='PENDING'")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, player.toString());
            return ps.executeUpdate();
        }
    }

    public int cancelPendingForGuild(String guildId) throws SQLException {
        try (Connection c = db.source().getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE `" + db.t("guild_applications")
                     + "` SET `state`='CANCELLED', `updated_at`=? WHERE `guild_id`=? AND `state`='PENDING'")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, guildId);
            return ps.executeUpdate();
        }
    }

    public int purgeBefore(long before) throws SQLException {
        try (Connection c = db.source().getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM `" + db.t("guild_applications")
                     + "` WHERE `state`<>'PENDING' AND `updated_at`>0 AND `updated_at`<?")) {
            ps.setLong(1, before);
            return ps.executeUpdate();
        }
    }

    private List<GuildApplication> query(String sql, String param, int limit) throws SQLException {
        List<GuildApplication> out = new ArrayList<>();
        try (Connection c = db.source().getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, param);
            ps.setInt(2, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new GuildApplication(
                            rs.getString("id"),
                            rs.getString("guild_id"),
                            UUID.fromString(rs.getString("player_uuid")),
                            rs.getString("player_name"),
                            rs.getString("message"),
                            rs.getString("state"),
                            rs.getLong("created_at"),
                            rs.getLong("updated_at")));
                }
            }
        }
        return out;
    }
}
