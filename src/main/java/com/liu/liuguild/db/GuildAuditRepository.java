package com.liu.liuguild.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 工会审计日志读写；只做简单插入/查询，不参与业务事务。 */
public final class GuildAuditRepository {

    private final Database db;

    public GuildAuditRepository(Database db) {
        this.db = db;
    }

    public void insert(AuditEntry e) throws SQLException {
        try (Connection c = db.source().getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT INTO `" + db.t("guild_audit_log") + "`"
                     + " (`id`,`guild_id`,`actor_uuid`,`actor_name`,`action`,`target`,`amount`,`detail`,`created_at`)"
                     + " VALUES (?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, e.id());
            ps.setString(2, e.guildId());
            ps.setString(3, e.actorUuid() == null ? null : e.actorUuid().toString());
            ps.setString(4, e.actorName());
            ps.setString(5, e.action());
            ps.setString(6, e.target());
            ps.setLong(7, e.amount());
            ps.setString(8, e.detail());
            ps.setLong(9, e.createdAt());
            ps.executeUpdate();
        }
    }

    public List<AuditEntry> recent(String guildId, int limit) throws SQLException {
        return query("SELECT * FROM `" + db.t("guild_audit_log") + "` WHERE `guild_id`=?"
                + " ORDER BY `created_at` DESC LIMIT ?", guildId, limit);
    }

    public List<AuditEntry> recentAll(int limit) throws SQLException {
        return query("SELECT * FROM `" + db.t("guild_audit_log") + "`"
                + " ORDER BY `created_at` DESC LIMIT ?", null, limit);
    }

    private List<AuditEntry> query(String sql, String guildId, int limit) throws SQLException {
        List<AuditEntry> out = new ArrayList<>();
        try (Connection c = db.source().getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            int i = 1;
            if (guildId != null) {
                ps.setString(i++, guildId);
            }
            ps.setInt(i, Math.max(1, Math.min(200, limit)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String actor = rs.getString("actor_uuid");
                    out.add(new AuditEntry(
                            rs.getString("id"),
                            rs.getString("guild_id"),
                            actor == null ? null : UUID.fromString(actor),
                            rs.getString("actor_name"),
                            rs.getString("action"),
                            rs.getString("target"),
                            rs.getLong("amount"),
                            rs.getString("detail"),
                            rs.getLong("created_at")));
                }
            }
        }
        return out;
    }

    public int purgeBefore(long before) throws SQLException {
        try (Connection c = db.source().getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM `" + db.t("guild_audit_log")
                     + "` WHERE `created_at`>0 AND `created_at`<?")) {
            ps.setLong(1, before);
            return ps.executeUpdate();
        }
    }
}
