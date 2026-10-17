package com.liu.liuguild.title;

import com.liu.liuguild.db.Database;
import com.liu.liuguild.model.GuildRank;
import com.liu.liuguild.model.GuildTitle;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 工会头衔持久化。所有方法都在异步线程调用。 */
public final class TitleRepository {

    private final Database db;

    public TitleRepository(Database db) {
        this.db = db;
    }

        /** 读取全部已装备的头衔（playerUuid → titleId）。 */
    public Map<UUID, String> loadAllEquipped() throws SQLException {
        Map<UUID, String> out = new LinkedHashMap<>();
        String sql = "SELECT `player_uuid`,`title_id` FROM `" + db.t("guild_member_titles") + "`";
        try (Connection c = db.source().getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                try {
                    out.put(UUID.fromString(rs.getString("player_uuid")), rs.getString("title_id"));
                } catch (IllegalArgumentException ignored) {
                    // 脏 UUID 跳过
                }
            }
        }
        return out;
    }

                /** 读取一个工会所有成员已装备的头衔（playerUuid → titleId）。 */
    public Map<UUID, String> loadEquipped(String guildId) throws SQLException {
        Map<UUID, String> out = new LinkedHashMap<>();
        String sql = "SELECT `player_uuid`,`title_id` FROM `" + db.t("guild_member_titles")
                + "` WHERE `guild_id`=?";
        try (Connection c = db.source().getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, guildId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try {
                        out.put(UUID.fromString(rs.getString("player_uuid")), rs.getString("title_id"));
                    } catch (IllegalArgumentException ignored) {
                        // 脏 UUID 跳过
                    }
                }
            }
        }
        return out;
    }

    /** 设置某成员装备的头衔（titleId 为 null 表示卸下）。 */
    public void setEquipped(UUID player, String guildId, String titleId) throws SQLException {
        if (titleId == null || titleId.isBlank()) {
            try (Connection c = db.source().getConnection();
                 PreparedStatement ps = c.prepareStatement(
                         "DELETE FROM `" + db.t("guild_member_titles") + "` WHERE `player_uuid`=?")) {
                ps.setString(1, player.toString());
                ps.executeUpdate();
            }
            return;
        }
        String update = "UPDATE `" + db.t("guild_member_titles")
                + "` SET `guild_id`=?,`title_id`=? WHERE `player_uuid`=?";
        try (Connection c = db.source().getConnection();
             PreparedStatement ps = c.prepareStatement(update)) {
            ps.setString(1, guildId);
            ps.setString(2, titleId);
            ps.setString(3, player.toString());
            if (ps.executeUpdate() > 0) {
                return;
            }
        }
        String insert = "INSERT INTO `" + db.t("guild_member_titles")
                + "` (`player_uuid`,`guild_id`,`title_id`) VALUES (?,?,?)";
        try (Connection c = db.source().getConnection();
             PreparedStatement ps = c.prepareStatement(insert)) {
            ps.setString(1, player.toString());
            ps.setString(2, guildId);
            ps.setString(3, titleId);
            ps.executeUpdate();
        }
    }

    /** 解散工会时清空头衔定义与装备记录。 */
    public void deleteAll(String guildId) throws SQLException {
        try (Connection c = db.source().getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM `" + db.t("guild_titles") + "` WHERE `guild_id`=?")) {
                ps.setString(1, guildId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM `" + db.t("guild_member_titles") + "` WHERE `guild_id`=?")) {
                ps.setString(1, guildId);
                ps.executeUpdate();
            }
        }
    }
}
