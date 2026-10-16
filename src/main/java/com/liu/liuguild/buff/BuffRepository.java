package com.liu.liuguild.buff;

import com.liu.liuguild.config.BuffSettings;
import com.liu.liuguild.db.Database;
import com.liu.liuguild.model.GuildRank;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 以工会行更新串行化跨服购买，扣款和有效期在同一个事务提交。 */
public final class BuffRepository {
    private final Database db;
    public BuffRepository(Database db) { this.db = db; }
    public record Purchase(String error, long expiresAt) {}

    public Purchase purchase(String guild, UUID actor, BuffSettings settings, String id, long now) throws SQLException {
        var buff = settings.buffs.get(id);
        if (!settings.enabled || buff == null) return new Purchase("buff-unavailable", 0);
        try (var c = db.source().getConnection()) {
            c.setAutoCommit(false);
            try {
                // 条件扣款同时锁住工会行，后续槽位/时长判断失败则回滚。
                String sql = "UPDATE `" + db.t("guilds") + "` SET points=points-? WHERE guild_id=? AND points>=?";
                try (var ps = c.prepareStatement(sql)) {
                    ps.setLong(1, buff.cost()); ps.setString(2, guild); ps.setLong(3, buff.cost());
                    if (ps.executeUpdate() != 1) { c.rollback(); return new Purchase("buff-insufficient", 0); }
                }
                int level;
                try (var ps = c.prepareStatement("SELECT level FROM `" + db.t("guilds") + "` WHERE guild_id=?")) {
                    ps.setString(1, guild);
                    try (var rs = ps.executeQuery()) { rs.next(); level = rs.getInt(1); }
                }
                boolean allowed = false;
                try (var ps = c.prepareStatement("SELECT rank FROM `" + db.t("guild_members") + "` WHERE guild_id=? AND player_uuid=?")) {
                    ps.setString(1, guild); ps.setString(2, actor.toString());
                    try (var rs = ps.executeQuery()) { if (rs.next()) allowed = GuildRank.parse(rs.getString(1)).atLeast(settings.purchaseRank); }
                }
                if (!allowed) { c.rollback(); return new Purchase("buff-no-permission", 0); }
                int active = 0; long previous = 0;
                try (var ps = c.prepareStatement("SELECT buff_id,expires_at FROM `" + db.t("guild_buffs") + "` WHERE guild_id=?")) {
                    ps.setString(1, guild);
                    try (var rs = ps.executeQuery()) {
                        while (rs.next()) {
                            String key = rs.getString(1); long expiry = rs.getLong(2);
                            if (expiry > now && settings.buffs.containsKey(key)) active++;
                            if (key.equals(id)) previous = expiry;
                        }
                    }
                }
                if (previous <= now && active >= settings.slots(level)) { c.rollback(); return new Purchase("buff-slots-full", 0); }
                long expiry = Math.addExact(Math.max(now, previous), Math.multiplyExact(buff.seconds(), 1000L));
                if (expiry - now > settings.maxSeconds * 1000L) { c.rollback(); return new Purchase("buff-duration-limit", 0); }
                try (var ps = c.prepareStatement("DELETE FROM `" + db.t("guild_buffs") + "` WHERE guild_id=? AND buff_id=?")) {
                    ps.setString(1, guild); ps.setString(2, id); ps.executeUpdate();
                }
                try (var ps = c.prepareStatement("INSERT INTO `" + db.t("guild_buffs") + "` (guild_id,buff_id,expires_at) VALUES (?,?,?)")) {
                    ps.setString(1, guild); ps.setString(2, id); ps.setLong(3, expiry); ps.executeUpdate();
                }
                c.commit(); return new Purchase(null, expiry);
            } catch (Exception e) { c.rollback(); throw e; }
        }
    }

    public Map<String, Map<String, Long>> loadActive(long now) throws SQLException {
        Map<String, Map<String, Long>> result = new HashMap<>();
        String sql = "SELECT b.guild_id,b.buff_id,b.expires_at FROM `" + db.t("guild_buffs")
                + "` b JOIN `" + db.t("guilds") + "` g ON g.guild_id=b.guild_id WHERE b.expires_at>?";
        try (var c = db.source().getConnection(); var ps = c.prepareStatement(sql)) {
            ps.setLong(1, now);
            try (var rs = ps.executeQuery()) { while (rs.next()) result.computeIfAbsent(rs.getString(1), k -> new HashMap<>()).put(rs.getString(2), rs.getLong(3)); }
        }
        return result;
    }
}
