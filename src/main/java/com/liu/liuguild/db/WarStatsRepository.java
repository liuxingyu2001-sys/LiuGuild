package com.liu.liuguild.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 工会战玩家战绩读写。 */
public final class WarStatsRepository {

    private final Database db;

    public WarStatsRepository(Database db) {
        this.db = db;
    }

    /** 批量 upsert 一场比赛的战绩；重复结算不会产生重复行。 */
    public void save(List<WarPlayerStat> stats) throws SQLException {
        if (stats == null || stats.isEmpty()) {
            return;
        }
        String table = db.t("guild_war_player_stats");
        String sql = db.isSqlite()
                ? "INSERT INTO `" + table + "` (`war_id`,`player_uuid`,`player_name`,`guild_id`,`side`,"
                + "`kills`,`deaths`,`survived_seconds`,`mvp`,`created_at`) VALUES (?,?,?,?,?,?,?,?,?,?)"
                + " ON CONFLICT(`war_id`,`player_uuid`) DO UPDATE SET `player_name`=excluded.`player_name`,"
                + "`guild_id`=excluded.`guild_id`,`side`=excluded.`side`,`kills`=excluded.`kills`,"
                + "`deaths`=excluded.`deaths`,`survived_seconds`=excluded.`survived_seconds`,"
                + "`mvp`=excluded.`mvp`,`created_at`=excluded.`created_at`"
                : "INSERT INTO `" + table + "` (`war_id`,`player_uuid`,`player_name`,`guild_id`,`side`,"
                + "`kills`,`deaths`,`survived_seconds`,`mvp`,`created_at`) VALUES (?,?,?,?,?,?,?,?,?,?)"
                + " ON DUPLICATE KEY UPDATE `player_name`=VALUES(`player_name`),`guild_id`=VALUES(`guild_id`),"
                + "`side`=VALUES(`side`),`kills`=VALUES(`kills`),`deaths`=VALUES(`deaths`),"
                + "`survived_seconds`=VALUES(`survived_seconds`),`mvp`=VALUES(`mvp`),"
                + "`created_at`=VALUES(`created_at`)";
        try (Connection c = db.source().getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (WarPlayerStat s : stats) {
                ps.setString(1, s.warId());
                ps.setString(2, s.player().toString());
                ps.setString(3, s.playerName());
                ps.setString(4, s.guildId());
                ps.setString(5, s.side());
                ps.setInt(6, s.kills());
                ps.setInt(7, s.deaths());
                ps.setLong(8, s.survivedSeconds());
                ps.setInt(9, s.mvp() ? 1 : 0);
                ps.setLong(10, s.createdAt());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    public List<WarPlayerStat> load(String warId) throws SQLException {
        List<WarPlayerStat> out = new ArrayList<>();
        if (warId == null) {
            return out;
        }
        try (Connection c = db.source().getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM `" + db.t("guild_war_player_stats")
                     + "` WHERE `war_id`=? ORDER BY `mvp` DESC, `kills` DESC, `survived_seconds` DESC")) {
            ps.setString(1, warId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new WarPlayerStat(
                            rs.getString("war_id"),
                            UUID.fromString(rs.getString("player_uuid")),
                            rs.getString("player_name"),
                            rs.getString("guild_id"),
                            rs.getString("side"),
                            rs.getInt("kills"),
                            rs.getInt("deaths"),
                            rs.getLong("survived_seconds"),
                            rs.getInt("mvp") != 0,
                            rs.getLong("created_at")));
                }
            }
        }
        return out;
    }

    public int purgeBefore(long before) throws SQLException {
        try (Connection c = db.source().getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM `" + db.t("guild_war_player_stats")
                     + "` WHERE `created_at`>0 AND `created_at`<?")) {
            ps.setLong(1, before);
            return ps.executeUpdate();
        }
    }
}
