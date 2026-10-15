package com.liu.liuguild.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/** 独立于成员记录的个人建设资金；离会保留，永不兑换 Vault 金币。 */
public final class PersonalFundsRepository {
    private final Database db;

    public PersonalFundsRepository(Database db) {
        this.db = db;
    }

    private void ensure(Connection c, UUID player) throws SQLException {
        String prefix = db.isSqlite() ? "INSERT OR IGNORE" : "INSERT IGNORE";
        try (var ps = c.prepareStatement(prefix + " INTO `" + db.t("personal_funds")
                + "` (`player_uuid`) VALUES (?)")) {
            ps.setString(1, player.toString());
            ps.executeUpdate();
        }
    }

    public long balance(UUID player) throws SQLException {
        try (var c = db.source().getConnection();
             var ps = c.prepareStatement("SELECT `balance` FROM `" + db.t("personal_funds") + "` WHERE `player_uuid`=?")) {
            ps.setString(1, player.toString());
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    /** 同一玩家每天最多领取一次，即使退会重入或跨服并发。 */
    public boolean grantCheckin(UUID player, long day, long amount) throws SQLException {
        if (amount < 0) return false;
        try (var c = db.source().getConnection()) {
            ensure(c, player);
            try (var ps = c.prepareStatement("UPDATE `" + db.t("personal_funds")
                    + "` SET `balance`=`balance`+?,`checkin_day`=? WHERE `player_uuid`=?"
                    + " AND `checkin_day`<? AND `balance`<=?")) {
                ps.setLong(1, amount);
                ps.setLong(2, day);
                ps.setString(3, player.toString());
                ps.setLong(4, day);
                ps.setLong(5, Long.MAX_VALUE - amount);
                return ps.executeUpdate() == 1;
            }
        }
    }

    /** 个人扣款和公共资金入账为同一事务，余额/成员身份由数据库裁决。 */
    public boolean donate(UUID player, String guildId, long amount) throws SQLException {
        if (amount <= 0) return false;
        try (var c = db.source().getConnection()) {
            c.setAutoCommit(false);
            try {
                try (var ps = c.prepareStatement("UPDATE `" + db.t("personal_funds")
                        + "` SET `balance`=`balance`-? WHERE `player_uuid`=? AND `balance`>=?"
                        + " AND EXISTS (SELECT 1 FROM `" + db.t("guild_members")
                        + "` WHERE `player_uuid`=? AND `guild_id`=?)")) {
                    ps.setLong(1, amount);
                    ps.setString(2, player.toString());
                    ps.setLong(3, amount);
                    ps.setString(4, player.toString());
                    ps.setString(5, guildId);
                    if (ps.executeUpdate() != 1) { c.rollback(); return false; }
                }
                try (var ps = c.prepareStatement("UPDATE `" + db.t("guilds")
                        + "` SET `points`=`points`+? WHERE `guild_id`=? AND `points`<=?")) {
                    ps.setLong(1, amount);
                    ps.setString(2, guildId);
                    ps.setLong(3, Long.MAX_VALUE - amount);
                    if (ps.executeUpdate() != 1) { c.rollback(); return false; }
                }
                c.commit();
                return true;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        }
    }
}
