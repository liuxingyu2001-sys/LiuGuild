package com.liu.liuguild.shop;

import com.liu.liuguild.db.Database;
import com.liu.liuguild.model.ShopListing;
import com.liu.liuguild.util.ItemCodec;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 工会商店持久化。
 *
 * <h3>库存增减必须是数据库层的原子操作</h3>
 * <p>同一件商品可能被不同子服的玩家同时抢购。如果实现成「先 SELECT 出库存 → 内存 -1 → UPDATE 写回」，
 * 两个并发请求都会读到同一份旧值，结果是<b>库存只减 1，商品发出去 2 份</b>（刷物品）。</p>
 * <p>所以扣库存用带条件的原子 UPDATE：
 * {@code UPDATE ... SET stock = stock - ? WHERE listing_id = ? AND stock >= ?}。
 * 影响行数为 0 就说明库存不够 —— 这是唯一的权威判断。</p>
 */
public final class ShopRepository {

    private final Database db;

    public ShopRepository(Database db) {
        this.db = db;
    }

    private static final String SELECT =
            "SELECT `listing_id`,`guild_id`,`item_data`,`stock`,`price`,`buy_price`,"
                    + "`seller_uuid`,`seller_name`,`created_at` FROM ";

    /** 读取全部上架记录（启动时全量加载进内存）。 */
    public List<ShopListing> loadAll() throws SQLException {
        List<ShopListing> out = new ArrayList<>();
        String sql = SELECT + "`" + db.t("guild_shop") + "` ORDER BY `created_at` ASC";
        try (Connection c = db.source().getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(map(rs));
            }
        }
        return out;
    }

    /** 读取某工会的上架记录。 */
    public List<ShopListing> loadGuild(String guildId) throws SQLException {
        List<ShopListing> out = new ArrayList<>();
        String sql = SELECT + "`" + db.t("guild_shop") + "` WHERE `guild_id`=? ORDER BY `created_at` ASC";
        try (Connection c = db.source().getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, guildId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(map(rs));
                }
            }
        }
        return out;
    }

    private ShopListing map(ResultSet rs) throws SQLException {
        String data = rs.getString("item_data");
        UUID seller = null;
        String su = rs.getString("seller_uuid");
        if (su != null) {
            try {
                seller = UUID.fromString(su);
            } catch (IllegalArgumentException ignored) {
            }
        }
        return new ShopListing(
                rs.getString("listing_id"),
                rs.getString("guild_id"),
                data,
                ItemCodec.decodeClamped(data),
                rs.getInt("stock"),
                rs.getLong("price"),
                0, // 历史收购价不再生效
                seller,
                rs.getString("seller_name"),
                rs.getLong("created_at"));
    }

    public void insert(ShopListing l) throws SQLException {
        String sql = "INSERT INTO `" + db.t("guild_shop")
                + "` (`listing_id`,`guild_id`,`item_data`,`stock`,`price`,`buy_price`,"
                + "`seller_uuid`,`seller_name`,`created_at`) VALUES (?,?,?,?,?,?,?,?,?)";
        try (Connection c = db.source().getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, l.id());
            ps.setString(2, l.guildId());
            ps.setString(3, l.itemData());
            ps.setInt(4, l.stock());
            ps.setLong(5, l.price());
            ps.setLong(6, l.buyPrice());
            ps.setString(7, l.seller() == null ? null : l.seller().toString());
            ps.setString(8, l.sellerName());
            ps.setLong(9, l.createdAt());
            ps.executeUpdate();
        }
    }

    public void updatePrices(String listingId, long price, long buyPrice) throws SQLException {
        String sql = "UPDATE `" + db.t("guild_shop") + "` SET `price`=?,`buy_price`=? WHERE `listing_id`=?";
        try (Connection c = db.source().getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, Math.max(0, price));
            ps.setLong(2, Math.max(0, buyPrice));
            ps.setString(3, listingId);
            ps.executeUpdate();
        }
    }

    /**
     * 购买：在同一事务里扣买家的<b>个人工会资金</b>、原子扣库存、把税后货款记入工会<b>点数</b>。
     *
     * <p>三个动作必须同生共死：任何一步失败都整体回滚，避免“扣了点数没拿到货”或
     * “发货了却没给工会入账”。库存更新用带条件的原子 UPDATE，报价被改或库存不足都会失败。</p>
     *
     * @param total 买家应付总额（个人工会资金）
     * @param net   税后真正进入工会点数的金额（0 <= net <= total）
     * @return true = 交易成功并已提交
     */
    public boolean purchase(String id, String guildId, UUID buyer, int amount,
                            long unitPrice, long total, long net) throws SQLException {
        if (amount <= 0 || unitPrice < 0 || total < 0 || net < 0 || net > total
                || buyer == null || guildId == null) {
            return false;
        }
        try (Connection c = db.source().getConnection()) {
            c.setAutoCommit(false);
            try {
                // 1) 扣买家个人工会资金：余额不足或不是该会成员都拒绝。
                if (total > 0) {
                    try (var ps = c.prepareStatement("UPDATE `" + db.t("personal_funds")
                            + "` SET `balance`=`balance`-? WHERE `player_uuid`=? AND `balance`>=?"
                            + " AND EXISTS (SELECT 1 FROM `" + db.t("guild_members")
                            + "` WHERE `player_uuid`=? AND `guild_id`=?)")) {
                        ps.setLong(1, total);
                        ps.setString(2, buyer.toString());
                        ps.setLong(3, total);
                        ps.setString(4, buyer.toString());
                        ps.setString(5, guildId);
                        if (ps.executeUpdate() != 1) { c.rollback(); return false; }
                    }
                }
                // 2) 原子扣库存，并校验报价未被并发修改。
                try (var ps = c.prepareStatement("UPDATE `" + db.t("guild_shop")
                        + "` SET `stock`=`stock`-? WHERE `listing_id`=? AND `guild_id`=? AND `stock`>=? AND `price`=?")) {
                    ps.setInt(1, amount);
                    ps.setString(2, id);
                    ps.setString(3, guildId);
                    ps.setInt(4, amount);
                    ps.setLong(5, unitPrice);
                    if (ps.executeUpdate() != 1) { c.rollback(); return false; }
                }
                // 3) 税后货款进入工会点数（公共工会资金）。
                if (net > 0) {
                    try (var ps = c.prepareStatement("UPDATE `" + db.t("guilds")
                            + "` SET `points`=`points`+? WHERE `guild_id`=? AND `points`<=?")) {
                        ps.setLong(1, net);
                        ps.setString(2, guildId);
                        ps.setLong(3, Long.MAX_VALUE - net);
                        if (ps.executeUpdate() != 1) { c.rollback(); return false; }
                    }
                }
                c.commit();
                return true;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        }
    }

    /** 在同一事务里锁定条目、读取真实库存并删除；重复下架不会重复退货。 */
    public int removeAndTakeStock(String listingId) throws SQLException {
        try (Connection c = db.source().getConnection()) {
            c.setAutoCommit(false);
            try {
                try (var lock = c.prepareStatement("UPDATE `" + db.t("guild_shop")
                        + "` SET `stock`=`stock` WHERE `listing_id`=?")) {
                    lock.setString(1, listingId);
                    lock.executeUpdate();
                }
                int stock = 0;
                try (var ps = c.prepareStatement("SELECT `stock` FROM `" + db.t("guild_shop") + "` WHERE `listing_id`=?")) {
                    ps.setString(1, listingId);
                    try (var rs = ps.executeQuery()) { if (rs.next()) stock = rs.getInt(1); }
                }
                try (var ps = c.prepareStatement("DELETE FROM `" + db.t("guild_shop") + "` WHERE `listing_id`=?")) {
                    ps.setString(1, listingId);
                    ps.executeUpdate();
                }
                c.commit();
                return stock;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        }
    }

    public void delete(String listingId) throws SQLException {
        String sql = "DELETE FROM `" + db.t("guild_shop") + "` WHERE `listing_id`=?";
        try (Connection c = db.source().getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, listingId);
            ps.executeUpdate();
        }
    }

    public void deleteGuild(String guildId) throws SQLException {
        String sql = "DELETE FROM `" + db.t("guild_shop") + "` WHERE `guild_id`=?";
        try (Connection c = db.source().getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, guildId);
            ps.executeUpdate();
        }
    }

    /**
     * 原子扣库存。
     *
     * @return true = 扣成功；false = 库存不足（并发下说明被别人抢走了）
     */
    public boolean tryDecrement(String listingId, int amount) throws SQLException {
        if (amount <= 0) {
            return false;
        }
        String sql = "UPDATE `" + db.t("guild_shop")
                + "` SET `stock`=`stock`-? WHERE `listing_id`=? AND `stock`>=?";
        try (Connection c = db.source().getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, amount);
            ps.setString(2, listingId);
            ps.setInt(3, amount);
            return ps.executeUpdate() > 0;
        }
    }

    /** 原子加库存（成员供货 / 下架退回）。 */
    public void increment(String listingId, int amount) throws SQLException {
        if (amount <= 0) {
            return;
        }
        String sql = "UPDATE `" + db.t("guild_shop") + "` SET `stock`=`stock`+? WHERE `listing_id`=?";
        try (Connection c = db.source().getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, amount);
            ps.setString(2, listingId);
            ps.executeUpdate();
        }
    }
}
