package com.liu.liuguild.warehouse;

import com.liu.liuguild.db.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工会仓库持久化。所有方法都在异步线程调用。
 *
 * <p>物品以 {@code ItemStack.serializeAsBytes} 的 Base64 存 {@code item_data} 列，
 * 槽位为主键一部分（{@code guild_id, slot}）。</p>
 */
public final class WarehouseRepository {

    private final Database db;

    public WarehouseRepository(Database db) {
        this.db = db;
    }

    /** 读取整仓（slot → 物品数据）。 */
    public Map<Integer, String> load(String guildId) throws SQLException {
        Map<Integer, String> out = new LinkedHashMap<>();
        String sql = "SELECT `slot`,`item_data` FROM `" + db.t("guild_warehouse") + "` WHERE `guild_id`=?";
        try (Connection c = db.source().getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, guildId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getInt("slot"), rs.getString("item_data"));
                }
            }
        }
        return out;
    }

    /**
     * 用给定内容<b>整体替换</b>某工会的仓库（单事务）。
     *
     * <p>这是仓库保存的核心：先删后插在同一事务里完成，避免“删了一半崩溃”导致丢物。
     * 调用方必须已持有该工会的独占锁。</p>
     */
    public void replaceAll(String guildId, Map<Integer, String> items) throws SQLException {
        String delete = "DELETE FROM `" + db.t("guild_warehouse") + "` WHERE `guild_id`=?";
        String insert = "INSERT INTO `" + db.t("guild_warehouse")
                + "` (`guild_id`,`slot`,`item_data`,`updated_at`) VALUES (?,?,?,?)";
        try (Connection c = db.source().getConnection()) {
            boolean oldAuto = c.getAutoCommit();
            c.setAutoCommit(false);
            try (PreparedStatement del = c.prepareStatement(delete);
                 PreparedStatement ins = c.prepareStatement(insert)) {
                del.setString(1, guildId);
                del.executeUpdate();
                long now = System.currentTimeMillis();
                for (Map.Entry<Integer, String> e : items.entrySet()) {
                    if (e.getValue() == null || e.getValue().isBlank()) {
                        continue;
                    }
                    ins.setString(1, guildId);
                    ins.setInt(2, e.getKey());
                    ins.setString(3, e.getValue());
                    ins.setLong(4, now);
                    ins.addBatch();
                }
                ins.executeBatch();
                c.commit();
            } catch (SQLException ex) {
                c.rollback();
                throw ex;
            } finally {
                c.setAutoCommit(oldAuto);
            }
        }
    }

    /** 清空某工会仓库（解散工会时调用）。 */
    public void deleteAll(String guildId) throws SQLException {
        try (Connection c = db.source().getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM `" + db.t("guild_warehouse") + "` WHERE `guild_id`=?")) {
            ps.setString(1, guildId);
            ps.executeUpdate();
        }
    }
}
