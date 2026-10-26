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

    /** 商店发货/退物的持久化交接记录。 */
    /**
     * 事务交接记录。
     * <p>{@code beforeItems}/{@code afterItems} 是玩家背包快照（Base64），
     * 用于崩溃恢复时精确判断“物品到底扣没扣”，不再靠背包件数推断。</p>
     */
    public record Pending(String opId, UUID player, String guildId, String action, String listingId,
                          String itemData, int amount, String state,
                          String beforeItems, String afterItems) { }

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
        insert(l, null);
    }

    /** 与上架记录同一事务把 pending 标记为 DONE；崩溃前未提交则恢复时退回物品。 */
    public void insert(ShopListing l, String pendingId) throws SQLException {
        insert(l, pendingId, Integer.MAX_VALUE);
    }

    /** 上架并在同一事务内检查工会上架数量上限，避免多服并发突破 max-listings。 */
    public void insert(ShopListing l, String pendingId, int maxListings) throws SQLException {
        String sql = "INSERT INTO `" + db.t("guild_shop")
                + "` (`listing_id`,`guild_id`,`item_data`,`stock`,`price`,`buy_price`,"
                + "`seller_uuid`,`seller_name`,`created_at`) VALUES (?,?,?,?,?,?,?,?,?)";
        try (Connection c = db.source().getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                if (maxListings > 0 && maxListings < Integer.MAX_VALUE) {
                    // MySQL 下先锁工会行，避免两台子服同时 COUNT 到 N-1 后各自插入突破上限
                    //（SQLite 单连接串行写，不需要 FOR UPDATE）。
                    if (!db.isSqlite()) {
                        try (PreparedStatement lock = c.prepareStatement("SELECT `guild_id` FROM `" + db.t("guilds")
                                + "` WHERE `guild_id`=? FOR UPDATE")) {
                            lock.setString(1, l.guildId());
                            try (ResultSet ignored = lock.executeQuery()) {
                                // 仅取行锁
                            }
                        }
                    }
                    try (PreparedStatement count = c.prepareStatement("SELECT COUNT(*) FROM `" + db.t("guild_shop")
                            + "` WHERE `guild_id`=?")) {
                        count.setString(1, l.guildId());
                        try (ResultSet rs = count.executeQuery()) {
                            if (rs.next() && rs.getInt(1) >= maxListings) {
                                c.rollback();
                                throw new SQLException("Shop listing limit reached");
                            }
                        }
                    }
                }
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
                if (pendingId != null && !markPending(c, pendingId, "DONE")) {
                    c.rollback();
                    throw new SQLException("Shop pending operation disappeared");
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally { c.setAutoCommit(true); }
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
        return purchase(id, guildId, buyer, amount, unitPrice, total, net, null);
    }

    /** 同 {@link #purchase}，并在同一事务把 pending 置为 READY，崩溃后可继续发货。 */
    public boolean purchase(String id, String guildId, UUID buyer, int amount,
                            long unitPrice, long total, long net, String pendingId) throws SQLException {
        if (amount <= 0 || unitPrice < 0 || total < 0 || net < 0 || net > total
                || buyer == null || guildId == null) {
            return false;
        }
        try (Connection c = db.source().getConnection()) {
            c.setAutoCommit(false);
            try {
                // 0) 成员校验独立于金额：0 价商品也必须只能由本会成员购买。
                try (var ps = c.prepareStatement("SELECT 1 FROM `" + db.t("guild_members")
                        + "` WHERE `player_uuid`=? AND `guild_id`=? LIMIT 1")) {
                    ps.setString(1, buyer.toString());
                    ps.setString(2, guildId);
                    try (var rs = ps.executeQuery()) {
                        if (!rs.next()) { c.rollback(); return false; }
                    }
                }
                // 1) 扣买家个人工会资金：余额不足拒绝。
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
                if (pendingId != null && !markPending(c, pendingId, "READY")) {
                    c.rollback();
                    return false;
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
        return removeAndTakeStock(listingId, null);
    }

    /** 同 {@link #removeAndTakeStock}，并在同一事务把 pending 置为 READY 并写入真实库存。 */
    public int removeAndTakeStock(String listingId, String pendingId) throws SQLException {
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
                if (pendingId != null) {
                    try (var ps = c.prepareStatement("UPDATE `" + db.t("guild_shop_pending")
                            + "` SET `state`='READY',`amount`=? WHERE `op_id`=? AND `state`='PREPARED'")) {
                        ps.setInt(1, stock);
                        ps.setString(2, pendingId);
                        if (ps.executeUpdate() != 1) { c.rollback(); throw new SQLException("Shop pending operation disappeared"); }
                    }
                }
                c.commit();
                return stock;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }


    public void savePending(Pending p) throws SQLException {
        String sql = "INSERT INTO `" + db.t("guild_shop_pending")
                + "` (`op_id`,`player_uuid`,`guild_id`,`action`,`listing_id`,`item_data`,`amount`,`state`,"
                + "`before_items`,`after_items`,`created_at`) VALUES (?,?,?,?,?,?,?,?,?,?,?)";
        try (Connection c = db.source().getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, p.opId());
            ps.setString(2, p.player().toString());
            ps.setString(3, p.guildId());
            ps.setString(4, p.action());
            ps.setString(5, p.listingId());
            ps.setString(6, p.itemData());
            ps.setInt(7, p.amount());
            ps.setString(8, p.state());
            ps.setString(9, p.beforeItems());
            ps.setString(10, p.afterItems());
            ps.setLong(11, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    public List<Pending> loadPending(UUID player) throws SQLException {
        List<Pending> out = new ArrayList<>();
        String sql = "SELECT `op_id`,`player_uuid`,`guild_id`,`action`,`listing_id`,`item_data`,`amount`,`state`,"
                + "`before_items`,`after_items` FROM `"
                + db.t("guild_shop_pending") + "` WHERE `player_uuid`=? ORDER BY `created_at`";
        try (Connection c = db.source().getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID uuid;
                    try { uuid = UUID.fromString(rs.getString(2)); } catch (IllegalArgumentException e) { continue; }
                    out.add(new Pending(rs.getString(1), uuid, rs.getString(3), rs.getString(4), rs.getString(5),
                            rs.getString(6), rs.getInt(7), rs.getString(8), rs.getString(9), rs.getString(10)));
                }
            }
        }
        return out;
    }

    public void deletePending(String opId) throws SQLException {
        try (Connection c = db.source().getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM `" + db.t("guild_shop_pending") + "` WHERE `op_id`=?")) {
            ps.setString(1, opId);
            ps.executeUpdate();
        }
    }

    /**
     * 发货前领取：READY → DELIVERING 只有一次能成功，
     * 避免“先给物品、后异步删记录”崩溃后重复发货。
     */
    public boolean claimDelivery(String opId) throws SQLException {
        try (Connection c = db.source().getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE `" + db.t("guild_shop_pending")
                     + "` SET `state`='DELIVERING' WHERE `op_id`=? AND `state`='READY'")) {
            ps.setString(1, opId);
            return ps.executeUpdate() == 1;
        }
    }

    /** 独立事务推进 pending 状态（上架从背包扣除后调用）。 */
    public boolean markPendingState(String opId, String state) throws SQLException {
        try (Connection c = db.source().getConnection()) {
            return markPending(c, opId, state);
        }
    }

    private boolean markPending(Connection c, String opId, String state) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE `" + db.t("guild_shop_pending") + "` SET `state`=? WHERE `op_id`=? AND `state`<>'DONE'")) {
            ps.setString(1, state);
            ps.setString(2, opId);
            return ps.executeUpdate() == 1;
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

    }
