package com.liu.liuguild.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 工会邮件读写。 */
public final class MailRepository {

    private final Database db;

    public MailRepository(Database db) {
        this.db = db;
    }

    public void insertMany(List<MailEntry> entries) throws SQLException {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        try (Connection c = db.source().getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT INTO `" + db.t("guild_mail") + "`"
                     + " (`id`,`guild_id`,`recipient_uuid`,`type`,`title`,`body`,`created_at`,`read_at`,`expires_at`)"
                     + " VALUES (?,?,?,?,?,?,?,?,?)")) {
            for (MailEntry e : entries) {
                ps.setString(1, e.id());
                ps.setString(2, e.guildId());
                ps.setString(3, e.recipient().toString());
                ps.setString(4, e.type());
                ps.setString(5, e.title());
                ps.setString(6, e.body());
                ps.setLong(7, e.createdAt());
                ps.setLong(8, e.readAt());
                ps.setLong(9, e.expiresAt());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    public List<MailEntry> inbox(UUID recipient, int limit) throws SQLException {
        List<MailEntry> out = new ArrayList<>();
        try (Connection c = db.source().getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM `" + db.t("guild_mail")
                     + "` WHERE `recipient_uuid`=? AND (`expires_at`=0 OR `expires_at`>?)"
                     + " ORDER BY `created_at` DESC LIMIT ?")) {
            ps.setString(1, recipient.toString());
            ps.setLong(2, System.currentTimeMillis());
            ps.setInt(3, Math.max(1, Math.min(50, limit)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new MailEntry(
                            rs.getString("id"),
                            rs.getString("guild_id"),
                            UUID.fromString(rs.getString("recipient_uuid")),
                            rs.getString("type"),
                            rs.getString("title"),
                            rs.getString("body"),
                            rs.getLong("created_at"),
                            rs.getLong("read_at"),
                            rs.getLong("expires_at")));
                }
            }
        }
        return out;
    }

    public int unreadCount(UUID recipient) throws SQLException {
        try (Connection c = db.source().getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM `" + db.t("guild_mail")
                     + "` WHERE `recipient_uuid`=? AND `read_at`=0 AND (`expires_at`=0 OR `expires_at`>?)")) {
            ps.setString(1, recipient.toString());
            ps.setLong(2, System.currentTimeMillis());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public void markRead(UUID recipient, String id) throws SQLException {
        try (Connection c = db.source().getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE `" + db.t("guild_mail")
                     + "` SET `read_at`=? WHERE `recipient_uuid`=? AND `id`=?")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, recipient.toString());
            ps.setString(3, id);
            ps.executeUpdate();
        }
    }

    public void markAllRead(UUID recipient) throws SQLException {
        try (Connection c = db.source().getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE `" + db.t("guild_mail")
                     + "` SET `read_at`=? WHERE `recipient_uuid`=? AND `read_at`=0")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, recipient.toString());
            ps.executeUpdate();
        }
    }

    /** 清理过期邮件与已读超过 7 天的邮件。 */
    public int purge(long now) throws SQLException {
        try (Connection c = db.source().getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM `" + db.t("guild_mail")
                     + "` WHERE (`expires_at`>0 AND `expires_at`<?)"
                     + " OR (`read_at`>0 AND `read_at`<?)")) {
            ps.setLong(1, now);
            ps.setLong(2, now - 7L * 24L * 3600L * 1000L);
            return ps.executeUpdate();
        }
    }
}
