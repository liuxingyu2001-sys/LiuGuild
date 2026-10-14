package com.liu.liuguild.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 数据基础设施：HikariCP 连接池 + 自动建库建表（参考 multiserver-db-guide.md）。
 * SQLite（单服）/ MySQL（多服共享）双模式，DDL 做双方言适配。
 */
public final class Database {

    private final DbConfig cfg;
    private final Logger log;
    private HikariDataSource ds;

    public Database(DbConfig cfg, Logger log) {
        this.cfg = cfg;
        this.log = log;
    }

    public DbConfig config() {
        return cfg;
    }

    /** 供测试使用：判断索引是否存在（与建表时同一套判断逻辑）。 */
    public boolean indexExistsForTests(String indexName, String table) throws SQLException {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            return indexExists(st, indexName, table);
        }
    }

    public boolean isSqlite() {
        return cfg.isSqlite();
    }

    /** 带前缀的表名；SQLite 模式前缀为空。 */
    public String t(String name) {
        return cfg.isSqlite() ? name : cfg.tablePrefix + "_" + name;
    }

    public HikariDataSource source() {
        return ds;
    }

    // =====================================================================
    // 初始化 / 关闭
    // =====================================================================

    public void init() throws Exception {
        if (!cfg.isSqlite()) {
            Class.forName("com.mysql.cj.jdbc.Driver");
            if (cfg.autoCreateDatabase) {
                try (Connection c = DriverManager.getConnection(cfg.baseJdbcUrl(), cfg.user, cfg.password);
                     Statement st = c.createStatement()) {
                    st.executeUpdate("CREATE DATABASE IF NOT EXISTS `" + cfg.database
                            + "` DEFAULT CHARACTER SET utf8mb4");
                } catch (Exception e) {
                    log.log(Level.WARNING, "自动建库失败（需要 CREATE DATABASE 权限），请手动建库: " + e.getMessage());
                }
            }
        } else {
            Class.forName("org.sqlite.JDBC");
            File f = new File(cfg.sqliteFile);
            File parent = f.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
        }

        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(cfg.jdbcUrl());
        hc.setUsername(cfg.user);
        hc.setPassword(cfg.password);
        hc.setMaximumPoolSize(cfg.poolSize);
        hc.setConnectionTimeout(cfg.connectionTimeoutMs);
        hc.setPoolName("LiuGuild-Pool");
        hc.setMinimumIdle(1);
        if (cfg.isSqlite()) {
            hc.addDataSourceProperty("busy_timeout", "10000");
            hc.setConnectionInitSql("PRAGMA busy_timeout=10000");
        }
        ds = new HikariDataSource(hc);

        if (cfg.isSqlite()) {
            try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
                st.executeUpdate("PRAGMA journal_mode=WAL");
                st.executeUpdate("PRAGMA busy_timeout=10000");
            }
        }

        if (cfg.autoCreateTables) {
            createTables();
            migrate();
        }
    }

    public void shutdown() {
        HikariDataSource s = ds;
        ds = null;
        if (s != null) {
            s.close();
        }
    }

    public boolean healthy() {
        if (ds == null) {
            return false;
        }
        try (Connection c = ds.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT 1")) {
            return rs.next();
        } catch (Exception e) {
            return false;
        }
    }

    // =====================================================================
    // 建表
    // =====================================================================

    private void createTables() throws SQLException {
        String engine = cfg.isSqlite() ? "" : " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
        // 名称大小写不敏感：SQLite 用 COLLATE NOCASE，MySQL 默认排序规则已不敏感。
        String nameCol = cfg.isSqlite()
                ? "`name` VARCHAR(64) NOT NULL COLLATE NOCASE,"
                : "`name` VARCHAR(64) NOT NULL,";
        String tagCol = cfg.isSqlite()
                ? "`tag` VARCHAR(32) NOT NULL COLLATE NOCASE,"
                : "`tag` VARCHAR(32) NOT NULL,";

        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS `" + t("guilds") + "` ("
                    + "`guild_id` VARCHAR(36) NOT NULL,"
                    + nameCol
                    + tagCol
                    + "`leader_uuid` VARCHAR(36),"
                    + "`leader_name` VARCHAR(64),"
                    + "`description` VARCHAR(255),"
                    + "`notice` VARCHAR(255),"
                    + "`icon` VARCHAR(64),"
                    + "`icon_data` TEXT,"
                    + "`open` INT NOT NULL DEFAULT 0,"
                    + "`created_at` BIGINT NOT NULL DEFAULT 0,"
                    + "`updated_at` BIGINT NOT NULL DEFAULT 0,"
                    + "`points` BIGINT NOT NULL DEFAULT 0,"
                    + "`level` INT NOT NULL DEFAULT 1,"
                    + "`wins` INT NOT NULL DEFAULT 0,"
                    + "`losses` INT NOT NULL DEFAULT 0,"
                    + "`bank` BIGINT NOT NULL DEFAULT 0,"
                    + "`warehouse_level` INT NOT NULL DEFAULT 0,"
                    + "`warehouse_expires_at` BIGINT NOT NULL DEFAULT 0,"
                    + "`last_active_at` BIGINT NOT NULL DEFAULT 0,"
                    + "`home_server` VARCHAR(64),"
                    + "`member_count` INT NOT NULL DEFAULT 0,"
                    + "PRIMARY KEY (`guild_id`)"
                    + ")" + engine);

            st.executeUpdate("CREATE TABLE IF NOT EXISTS `" + t("guild_members") + "` ("
                    + "`player_uuid` VARCHAR(36) NOT NULL,"
                    + "`player_name` VARCHAR(64),"
                    + "`guild_id` VARCHAR(36) NOT NULL,"
                    + "`rank` VARCHAR(16) NOT NULL DEFAULT 'MEMBER',"
                    + "`joined_at` BIGINT NOT NULL DEFAULT 0,"
                    + "`contributed` BIGINT NOT NULL DEFAULT 0,"
                    + "`kills` INT NOT NULL DEFAULT 0,"
                    + "`deaths` INT NOT NULL DEFAULT 0,"
                    + "`last_checkin` BIGINT NOT NULL DEFAULT 0,"
                    + "`checkin_streak` INT NOT NULL DEFAULT 0,"
                    + "PRIMARY KEY (`player_uuid`)"
                    + ")" + engine);

            st.executeUpdate("CREATE TABLE IF NOT EXISTS `" + t("guild_wars") + "` ("
                    + "`war_id` VARCHAR(36) NOT NULL,"
                    + "`guild_a` VARCHAR(36),"
                    + "`guild_b` VARCHAR(36),"
                    + "`name_a` VARCHAR(64),"
                    + "`name_b` VARCHAR(64),"
                    + "`tag_a` VARCHAR(32),"
                    + "`tag_b` VARCHAR(32),"
                    + "`state` VARCHAR(16) NOT NULL DEFAULT 'MATCHED',"
                    + "`score_a` INT NOT NULL DEFAULT 0,"
                    + "`score_b` INT NOT NULL DEFAULT 0,"
                    + "`winner` VARCHAR(36),"
                    + "`host_server` VARCHAR(64),"
                    + "`arena_world` VARCHAR(128),"
                    + "`mode` VARCHAR(32) NOT NULL DEFAULT 'match',"
                    + "`created_at` BIGINT NOT NULL DEFAULT 0,"
                    + "`started_at` BIGINT NOT NULL DEFAULT 0,"
                    + "`ended_at` BIGINT NOT NULL DEFAULT 0,"
                    + "PRIMARY KEY (`war_id`)"
                    + ")" + engine);

            st.executeUpdate("CREATE TABLE IF NOT EXISTS `" + t("guild_homes") + "` ("
                    + "`guild_id` VARCHAR(36) NOT NULL,"
                    + "`slot` INT NOT NULL,"
                    + "`location_json` TEXT NOT NULL,"
                    + "PRIMARY KEY (`guild_id`,`slot`)"
                    + ")" + engine);

            // 工会仓库：物品以 ItemStack.serializeAsBytes 的 Base64 存储，可完整保留 NBT/组件
            st.executeUpdate("CREATE TABLE IF NOT EXISTS `" + t("guild_warehouse") + "` ("
                    + "`guild_id` VARCHAR(36) NOT NULL,"
                    + "`slot` INT NOT NULL,"
                    + "`item_data` TEXT NOT NULL,"
                    + "`updated_at` BIGINT NOT NULL DEFAULT 0,"
                    + "PRIMARY KEY (`guild_id`,`slot`)"
                    + ")" + engine);

            // 工会商店：上架物品（同样完整序列化），价格以工会资金计价
            st.executeUpdate("CREATE TABLE IF NOT EXISTS `" + t("guild_shop") + "` ("
                    + "`listing_id` VARCHAR(36) NOT NULL,"
                    + "`guild_id` VARCHAR(36) NOT NULL,"
                    + "`item_data` TEXT NOT NULL,"           // Base64(serializeAsBytes)，固定为单件（amount=1）
                    + "`stock` INT NOT NULL DEFAULT 1,"        // 可售数量（原子增减）
                    + "`price` BIGINT NOT NULL DEFAULT 0,"      // 玩家从工会买：单价
                    + "`buy_price` BIGINT NOT NULL DEFAULT 0,"  // 工会向成员收：单价（0=不收购）
                    + "`seller_uuid` VARCHAR(36),"
                    + "`seller_name` VARCHAR(64),"
                    + "`created_at` BIGINT NOT NULL DEFAULT 0,"
                    + "PRIMARY KEY (`listing_id`)"
                    + ")" + engine);
            index(st, t("guild_shop") + "_guild", t("guild_shop"), "`guild_id`");

            // 工会头衔定义
            st.executeUpdate("CREATE TABLE IF NOT EXISTS `" + t("guild_titles") + "` ("
                    + "`guild_id` VARCHAR(36) NOT NULL,"
                    + "`title_id` VARCHAR(32) NOT NULL,"
                    + "`display` VARCHAR(64) NOT NULL,"
                    + "`min_rank` VARCHAR(16) NOT NULL DEFAULT 'MEMBER',"
                    + "`created_at` BIGINT NOT NULL DEFAULT 0,"
                    + "PRIMARY KEY (`guild_id`,`title_id`)"
                    + ")" + engine);

            // 成员已装备的头衔（每人一个；单独成表，避免改 guild_members 的插入/更新语句）
            st.executeUpdate("CREATE TABLE IF NOT EXISTS `" + t("guild_member_titles") + "` ("
                    + "`player_uuid` VARCHAR(36) NOT NULL,"
                    + "`guild_id` VARCHAR(36) NOT NULL,"
                    + "`title_id` VARCHAR(32) NOT NULL,"
                    + "PRIMARY KEY (`player_uuid`)"
                    + ")" + engine);

            // 工会战装备托管快照（持久化落库：防止战中崩溃/重启或提前退服导致玩家原有装备被竞技场统一套装覆盖丢失）
            st.executeUpdate("CREATE TABLE IF NOT EXISTS `" + t("guild_war_escrow") + "` ("
                    + "`player_uuid` VARCHAR(36) NOT NULL,"
                    + "`server_id` VARCHAR(64) NOT NULL,"
                    + "`war_id` VARCHAR(36) NOT NULL,"
                    + "`sparrow_snapshot_id` VARCHAR(36),"
                    + "`items_data` " + (cfg.isSqlite() ? "TEXT," : "MEDIUMTEXT,")
                    + "`world_name` VARCHAR(128),"
                    + "`x` DOUBLE NOT NULL DEFAULT 0,"
                    + "`y` DOUBLE NOT NULL DEFAULT 0,"
                    + "`z` DOUBLE NOT NULL DEFAULT 0,"
                    + "`yaw` FLOAT NOT NULL DEFAULT 0,"
                    + "`pitch` FLOAT NOT NULL DEFAULT 0,"
                    + "`game_mode` VARCHAR(16),"
                    + "`health` DOUBLE NOT NULL DEFAULT 20,"
                    + "`food` INT NOT NULL DEFAULT 20,"
                    + "`exp_level` INT NOT NULL DEFAULT 0,"
                    + "`exp_progress` FLOAT NOT NULL DEFAULT 0,"
                    + "`created_at` BIGINT NOT NULL DEFAULT 0,"
                    + "PRIMARY KEY (`player_uuid`)"
                    + ")" + engine);

            uniqueIndex(st, t("guilds") + "_name", t("guilds"), "`name`");
            uniqueIndex(st, t("guilds") + "_tag", t("guilds"), "`tag`");
            index(st, t("guild_members") + "_guild", t("guild_members"), "`guild_id`");
            index(st, t("guild_wars") + "_state", t("guild_wars"), "`state`");
            index(st, t("guild_wars") + "_a", t("guild_wars"), "`guild_a`");
            index(st, t("guild_wars") + "_b", t("guild_wars"), "`guild_b`");
        }
    }

    private void index(Statement st, String indexName, String table, String columns) {
        if (indexExists(st, indexName, table)) {
            return;
        }
        String ddl = (cfg.isSqlite() ? "CREATE INDEX IF NOT EXISTS " : "CREATE INDEX ") + "`"
                + indexName + "` ON `" + table + "` (" + columns + ")";
        try {
            st.executeUpdate(ddl);
        } catch (SQLException ignored) {
            // 并发创建等情况：已存在即忽略
        }
    }

    /**
     * 唯一索引：从数据库层保证工会名称/标签的大小写不敏感唯一性
     * （SQLite 列为 {@code COLLATE NOCASE}，MySQL 默认排序规则不敏感），
     * 兜底应用层的并发竞争。
     *
     * <p>MySQL 不支持 {@code IF NOT EXISTS}，所以先查目录判断是否已存在，
     * 避免把「索引已存在」误报成「已有重复数据」。</p>
     */
    private void uniqueIndex(Statement st, String indexName, String table, String columns) {
        if (indexExists(st, indexName, table)) {
            return;
        }
        String ddl = (cfg.isSqlite() ? "CREATE UNIQUE INDEX IF NOT EXISTS " : "CREATE UNIQUE INDEX ") + "`"
                + indexName + "` ON `" + table + "` (" + columns + ")";
        try {
            st.executeUpdate(ddl);
        } catch (SQLException e) {
            // 已存在（并发创建）时不算错；真的是重复数据才值得警告
            if (isIndexAlreadyExists(e)) {
                return;
            }
            log.log(Level.WARNING, "创建唯一索引 " + indexName + " 失败（可能已有重复数据）: " + e.getMessage());
        }
    }

    /** 查询数据库目录，判断索引是否已存在（MySQL / SQLite 均可）。 */
    private boolean indexExists(Statement st, String indexName, String table) {
        String sql = cfg.isSqlite()
                ? "SELECT 1 FROM sqlite_master WHERE type='index' AND name=?"
                : "SELECT 1 FROM information_schema.statistics WHERE table_schema = DATABASE()"
                        + " AND table_name = ? AND index_name = ?";
        try (PreparedStatement ps = st.getConnection().prepareStatement(sql)) {
            if (cfg.isSqlite()) {
                ps.setString(1, indexName);
            } else {
                ps.setString(1, table);
                ps.setString(2, indexName);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            return false; // 判断失败时交给 CREATE 语句与异常处理兜底
        }
    }

    /** MySQL「索引已存在」错误码 1061 / SQLite 同类提示。 */
    private static boolean isIndexAlreadyExists(SQLException e) {
        return e.getErrorCode() == 1061
                || (e.getMessage() != null && e.getMessage().toLowerCase(java.util.Locale.ROOT).contains("already exist"));
    }

    /**
     * 轻量级结构升级：为老版本数据库补齐新列。
     * MySQL 不支持 {@code ADD COLUMN IF NOT EXISTS}，因此逐列尝试并忽略「列已存在」错误。
     */
    private void migrate() {
        List<String[]> columns = new ArrayList<>();
        // {table, column, 定义}
        columns.add(new String[]{"guilds", "icon", "`icon` VARCHAR(64)"});
        columns.add(new String[]{"guilds", "icon_data", "`icon_data` TEXT"});
        columns.add(new String[]{"guilds", "notice", "`notice` VARCHAR(255)"});
        columns.add(new String[]{"guilds", "description", "`description` VARCHAR(255)"});
        columns.add(new String[]{"guilds", "home_server", "`home_server` VARCHAR(64)"});
        columns.add(new String[]{"guilds", "member_count", "`member_count` INT NOT NULL DEFAULT 0"});
        columns.add(new String[]{"guilds", "updated_at", "`updated_at` BIGINT NOT NULL DEFAULT 0"});
        columns.add(new String[]{"guilds", "bank", "`bank` BIGINT NOT NULL DEFAULT 0"});
        columns.add(new String[]{"guilds", "warehouse_level", "`warehouse_level` INT NOT NULL DEFAULT 0"});
        columns.add(new String[]{"guilds", "warehouse_expires_at", "`warehouse_expires_at` BIGINT NOT NULL DEFAULT 0"});
        columns.add(new String[]{"guilds", "last_active_at", "`last_active_at` BIGINT NOT NULL DEFAULT 0"});
        columns.add(new String[]{"guild_members", "contributed", "`contributed` BIGINT NOT NULL DEFAULT 0"});
        columns.add(new String[]{"guild_members", "kills", "`kills` INT NOT NULL DEFAULT 0"});
        columns.add(new String[]{"guild_members", "deaths", "`deaths` INT NOT NULL DEFAULT 0"});
        columns.add(new String[]{"guild_members", "last_checkin", "`last_checkin` BIGINT NOT NULL DEFAULT 0"});
        columns.add(new String[]{"guild_members", "checkin_streak", "`checkin_streak` INT NOT NULL DEFAULT 0"});
        columns.add(new String[]{"guild_wars", "mode", "`mode` VARCHAR(32) NOT NULL DEFAULT 'match'"});
        columns.add(new String[]{"guild_wars", "arena_world", "`arena_world` VARCHAR(128)"});
        columns.add(new String[]{"guild_wars", "tag_a", "`tag_a` VARCHAR(32)"});
        columns.add(new String[]{"guild_wars", "tag_b", "`tag_b` VARCHAR(32)"});
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            for (String[] col : columns) {
                String ddl = "ALTER TABLE `" + t(col[0]) + "` ADD COLUMN " + col[2];
                try {
                    st.executeUpdate(ddl);
                    log.info("数据库升级：已添加 " + t(col[0]) + "." + col[1]);
                } catch (SQLException e) {
                    // 列已存在 / 表结构差异：忽略
                }
            }
        } catch (SQLException e) {
            log.log(Level.WARNING, "数据库结构升级检查失败: " + e.getMessage());
        }
    }
}
