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
        // SQLite 单写者：多连接只会把「快照升级写」冲突变成随机的 SQLITE_BUSY，
        // busy_timeout 对快照失效不生效，因此固定单连接串行化。
        hc.setMaximumPoolSize(cfg.isSqlite() ? 1 : cfg.poolSize);
        hc.setConnectionTimeout(cfg.connectionTimeoutMs);
        hc.setPoolName("LiuGuild-Pool");
        hc.setMinimumIdle(1);
        if (cfg.isSqlite()) {
            hc.addDataSourceProperty("busy_timeout", "10000");
            hc.addDataSourceProperty("journal_mode", "WAL");
            hc.addDataSourceProperty("synchronous", "NORMAL");
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
            st.executeUpdate("CREATE TABLE IF NOT EXISTS `" + t("personal_funds") + "` ("
                    + "`player_uuid` VARCHAR(36) NOT NULL PRIMARY KEY,"
                    + "`balance` BIGINT NOT NULL DEFAULT 0,"
                    + "`checkin_day` BIGINT NOT NULL DEFAULT -1)" + engine);
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
                    + "`member_pvp` INT NULL DEFAULT NULL,"
                    + "`created_at` BIGINT NOT NULL DEFAULT 0,"
                    + "`updated_at` BIGINT NOT NULL DEFAULT 0,"
                    + "`points` BIGINT NOT NULL DEFAULT 0,"
                    + "`level` INT NOT NULL DEFAULT 1,"
                    + "`wins` INT NOT NULL DEFAULT 0,"
                    + "`losses` INT NOT NULL DEFAULT 0,"
                    + "`bank` BIGINT NOT NULL DEFAULT 0,"
                    + "`warehouse_level` INT NOT NULL DEFAULT 0,"
                    + "`warehouse_expires_at` BIGINT NOT NULL DEFAULT 0,"
                    + "`warehouse_level_expires_at` BIGINT NOT NULL DEFAULT 0,"
                    + "`warehouse_permanent_level` INT NOT NULL DEFAULT 0,"
                    + "`war_funds_day` BIGINT NOT NULL DEFAULT 0,"
                    + "`war_funds_earned` BIGINT NOT NULL DEFAULT 0,"
                    + "`activity_day` BIGINT NOT NULL DEFAULT 0,"
                    + "`activity_earned` BIGINT NOT NULL DEFAULT 0,"
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

            st.executeUpdate("CREATE TABLE IF NOT EXISTS `" + t("guild_war_parties") + "` ("
                    + "`war_id` VARCHAR(36) NOT NULL,"
                    + "`guild_id` VARCHAR(36) NOT NULL,"
                    + "`name` VARCHAR(64),"
                    + "`tag` VARCHAR(32),"
                    + "`score` INT NOT NULL DEFAULT 0,"
                    + "`deaths` INT NOT NULL DEFAULT 0,"
                    + "`winner` INT NOT NULL DEFAULT 0,"
                    + "PRIMARY KEY (`war_id`,`guild_id`)"
                    + ")" + engine);

            st.executeUpdate("CREATE TABLE IF NOT EXISTS `" + t("guild_homes") + "` ("
                    + "`guild_id` VARCHAR(36) NOT NULL,"
                    + "`slot` INT NOT NULL,"
                    + "`location_json` TEXT NOT NULL,"
                    + "PRIMARY KEY (`guild_id`,`slot`)"
                    + ")" + engine);

            // 工会仓库：物品以 ItemStack.serializeAsBytes 的 Base64 存储，可完整保留 NBT/组件
            st.executeUpdate("CREATE TABLE IF NOT EXISTS `" + t("guild_warehouse_state") + "` ("
                    + "`guild_id` VARCHAR(36) NOT NULL PRIMARY KEY, `version` BIGINT NOT NULL DEFAULT 0,"
                    + "`deleted_at` BIGINT NOT NULL DEFAULT 0, `purge_at` BIGINT NOT NULL DEFAULT 0)" + engine);
            st.executeUpdate("CREATE TABLE IF NOT EXISTS `" + t("guild_warehouse") + "` ("
                    + "`guild_id` VARCHAR(36) NOT NULL,"
                    + "`slot` INT NOT NULL,"
                    + "`item_data` TEXT NOT NULL,"
                    + "`amount` BIGINT NOT NULL DEFAULT 0,"
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

            // 仓库/商店跨“数据库提交”和“玩家背包变更”的持久化交接记录。
            // 崩溃恢复时据此判断数据库版本是否已提交，再决定补发或丢弃。
            st.executeUpdate("CREATE TABLE IF NOT EXISTS `" + t("guild_warehouse_pending") + "` ("
                    + "`player_uuid` VARCHAR(36) NOT NULL,"
                    + "`guild_id` VARCHAR(36) NOT NULL,"
                    + "`session_token` VARCHAR(96) NOT NULL,"
                    + "`expected_version` BIGINT NOT NULL,"
                    + "`before_items` " + (cfg.isSqlite() ? "TEXT" : "MEDIUMTEXT") + ","
                    + "`after_items` " + (cfg.isSqlite() ? "TEXT" : "MEDIUMTEXT") + ","
                    + "`created_at` BIGINT NOT NULL DEFAULT 0,"
                    + "PRIMARY KEY (`player_uuid`)"
                    + ")" + engine);
            st.executeUpdate("CREATE TABLE IF NOT EXISTS `" + t("guild_shop_pending") + "` ("
                    + "`op_id` VARCHAR(36) NOT NULL,"
                    + "`player_uuid` VARCHAR(36) NOT NULL,"
                    + "`guild_id` VARCHAR(36) NOT NULL,"
                    + "`action` VARCHAR(16) NOT NULL,"
                    + "`listing_id` VARCHAR(36),"
                    + "`item_data` " + (cfg.isSqlite() ? "TEXT," : "MEDIUMTEXT,")
                    + "`before_items` " + (cfg.isSqlite() ? "TEXT" : "MEDIUMTEXT") + ","
                    + "`after_items` " + (cfg.isSqlite() ? "TEXT" : "MEDIUMTEXT") + ","
                    + "`amount` INT NOT NULL DEFAULT 0,"
                    + "`state` VARCHAR(16) NOT NULL,"
                    + "`created_at` BIGINT NOT NULL DEFAULT 0,"
                    + "PRIMARY KEY (`op_id`)"
                    + ")" + engine);
            index(st, t("guild_shop_pending") + "_player", t("guild_shop_pending"), "`player_uuid`");

            // 工会战玩家奖励 outbox：崩溃/重启后继续发放未完成的玩家奖励。
            st.executeUpdate("CREATE TABLE IF NOT EXISTS `" + t("guild_war_rewards") + "` ("
                    + "`war_id` VARCHAR(36) NOT NULL,"
                    + "`player_uuid` VARCHAR(36) NOT NULL,"
                    + "`guild_id` VARCHAR(36) NOT NULL,"
                    + "`win` INT NOT NULL DEFAULT 0,"
                    + "`mode` VARCHAR(32) NOT NULL,"
                    + "`money` DOUBLE NOT NULL DEFAULT 0,"
                    + "`pass_xp` INT NOT NULL DEFAULT 0,"
                    + "`title_id` INT NOT NULL DEFAULT 0,"
                    + "`status` VARCHAR(16) NOT NULL DEFAULT 'PENDING',"
                    + "`attempts` INT NOT NULL DEFAULT 0,"
                    + "`claimed_at` BIGINT NOT NULL DEFAULT 0,"
                    + "`created_at` BIGINT NOT NULL DEFAULT 0,"
                    + "PRIMARY KEY (`war_id`,`player_uuid`)"
                    + ")" + engine);
            index(st, t("guild_war_rewards") + "_status", t("guild_war_rewards"), "`status`,`created_at`");

            // 工会入会申请。
            st.executeUpdate("CREATE TABLE IF NOT EXISTS `" + t("guild_applications") + "` ("
                    + "`id` VARCHAR(36) NOT NULL PRIMARY KEY,"
                    + "`guild_id` VARCHAR(36) NOT NULL,"
                    + "`player_uuid` VARCHAR(36) NOT NULL,"
                    + "`player_name` VARCHAR(32) NULL,"
                    + "`message` VARCHAR(255) NULL,"
                    + "`state` VARCHAR(16) NOT NULL,"
                    + "`created_at` BIGINT NOT NULL DEFAULT 0,"
                    + "`updated_at` BIGINT NOT NULL DEFAULT 0"
                    + ")" + engine);
            index(st, t("guild_applications") + "_guild", t("guild_applications"), "`guild_id`, `state`");
            index(st, t("guild_applications") + "_player", t("guild_applications"), "`player_uuid`, `state`");

            // 工会系统邮件（离线通知/战报/申请结果）。
            st.executeUpdate("CREATE TABLE IF NOT EXISTS `" + t("guild_mail") + "` ("
                    + "`id` VARCHAR(36) NOT NULL PRIMARY KEY,"
                    + "`guild_id` VARCHAR(36) NULL,"
                    + "`recipient_uuid` VARCHAR(36) NOT NULL,"
                    + "`type` VARCHAR(32) NOT NULL,"
                    + "`title` VARCHAR(128) NOT NULL,"
                    + "`body` VARCHAR(512) NOT NULL,"
                    + "`created_at` BIGINT NOT NULL DEFAULT 0,"
                    + "`read_at` BIGINT NOT NULL DEFAULT 0,"
                    + "`expires_at` BIGINT NOT NULL DEFAULT 0"
                    + ")" + engine);
            index(st, t("guild_mail") + "_recipient", t("guild_mail"), "`recipient_uuid`, `created_at`");
            index(st, t("guild_mail") + "_expires", t("guild_mail"), "`expires_at`");

            // 工会战玩家战绩（击杀/死亡/存活/MVP）。
            st.executeUpdate("CREATE TABLE IF NOT EXISTS `" + t("guild_war_player_stats") + "` ("
                    + "`war_id` VARCHAR(36) NOT NULL,"
                    + "`player_uuid` VARCHAR(36) NOT NULL,"
                    + "`player_name` VARCHAR(32) NULL,"
                    + "`guild_id` VARCHAR(36) NOT NULL,"
                    + "`side` VARCHAR(1) NOT NULL,"
                    + "`kills` INT NOT NULL DEFAULT 0,"
                    + "`deaths` INT NOT NULL DEFAULT 0,"
                    + "`survived_seconds` BIGINT NOT NULL DEFAULT 0,"
                    + "`mvp` INT NOT NULL DEFAULT 0,"
                    + "`created_at` BIGINT NOT NULL DEFAULT 0,"
                    + "PRIMARY KEY (`war_id`, `player_uuid`)"
                    + ")" + engine);
            index(st, t("guild_war_player_stats") + "_war", t("guild_war_player_stats"), "`war_id`");
            index(st, t("guild_war_player_stats") + "_time", t("guild_war_player_stats"), "`created_at`");

            // 工会审计日志：银行/商店/仓库/管理员操作。
            st.executeUpdate("CREATE TABLE IF NOT EXISTS `" + t("guild_audit_log") + "` ("
                    + "`id` VARCHAR(36) NOT NULL PRIMARY KEY,"
                    + "`guild_id` VARCHAR(36) NOT NULL,"
                    + "`actor_uuid` VARCHAR(36) NULL,"
                    + "`actor_name` VARCHAR(32) NULL,"
                    + "`action` VARCHAR(32) NOT NULL,"
                    + "`target` VARCHAR(128) NULL,"
                    + "`amount` BIGINT NOT NULL DEFAULT 0,"
                    + "`detail` VARCHAR(255) NULL,"
                    + "`created_at` BIGINT NOT NULL DEFAULT 0"
                    + ")" + engine);
            index(st, t("guild_audit_log") + "_guild_time", t("guild_audit_log"),
                    "`guild_id`, `created_at`");
            index(st, t("guild_audit_log") + "_time", t("guild_audit_log"), "`created_at`");

            // 跨服参赛者出发地：赛后/崩溃恢复时用于跨服返程。
            st.executeUpdate("CREATE TABLE IF NOT EXISTS `" + t("guild_war_origins") + "` ("
                    + "`war_id` VARCHAR(36) NOT NULL,"
                    + "`player_uuid` VARCHAR(36) NOT NULL,"
                    + "`source_server` VARCHAR(64) NOT NULL,"
                    + "`world` VARCHAR(128) NOT NULL,"
                    + "`x` DOUBLE NOT NULL DEFAULT 0,"
                    + "`y` DOUBLE NOT NULL DEFAULT 0,"
                    + "`z` DOUBLE NOT NULL DEFAULT 0,"
                    + "`yaw` FLOAT NOT NULL DEFAULT 0,"
                    + "`pitch` FLOAT NOT NULL DEFAULT 0,"
                    + "`created_at` BIGINT NOT NULL DEFAULT 0,"
                    + "PRIMARY KEY (`player_uuid`)"
                    + ")" + engine);
            index(st, t("guild_war_origins") + "_war", t("guild_war_origins"), "`war_id`");
            index(st, t("guild_war_origins") + "_created", t("guild_war_origins"), "`created_at`");

            // 工会头衔定义
            st.executeUpdate("CREATE TABLE IF NOT EXISTS `" + t("guild_buffs") + "` ("
                    + "guild_id VARCHAR(64) NOT NULL, buff_id VARCHAR(32) NOT NULL, expires_at BIGINT NOT NULL,"
                    + "PRIMARY KEY (guild_id,buff_id))" + engine);
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
            // 历史查询按 (guild, state, ended_at) 走索引，避免 ENDED 全量排序。
            index(st, t("guild_wars") + "_a_hist", t("guild_wars"), "`guild_a`,`state`,`ended_at`");
            index(st, t("guild_wars") + "_b_hist", t("guild_wars"), "`guild_b`,`state`,`ended_at`");
            // 仓库/商店过期清理与奖励补扫的过滤列。
            index(st, t("guilds") + "_wh_expires", t("guilds"), "`warehouse_expires_at`");
            index(st, t("guild_warehouse_state") + "_purge", t("guild_warehouse_state"), "`purge_at`");
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
    private static boolean isColumnAlreadyExists(SQLException e) {
        String m = e.getMessage() == null ? "" : e.getMessage().toLowerCase(java.util.Locale.ROOT);
        return e.getErrorCode() == 1060 || m.contains("duplicate column") || m.contains("already exists");
    }

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
        columns.add(new String[]{"guild_warehouse", "amount", "`amount` BIGINT NOT NULL DEFAULT 0"});
        columns.add(new String[]{"guilds", "icon", "`icon` VARCHAR(64)"});
        columns.add(new String[]{"guilds", "icon_data", "`icon_data` TEXT"});
        columns.add(new String[]{"guilds", "notice", "`notice` VARCHAR(255)"});
        columns.add(new String[]{"guilds", "description", "`description` VARCHAR(255)"});
        columns.add(new String[]{"guilds", "home_server", "`home_server` VARCHAR(64)"});
        columns.add(new String[]{"guilds", "member_count", "`member_count` INT NOT NULL DEFAULT 0"});
        columns.add(new String[]{"guilds", "member_pvp", "`member_pvp` INT NULL DEFAULT NULL"});
        columns.add(new String[]{"guilds", "updated_at", "`updated_at` BIGINT NOT NULL DEFAULT 0"});
        columns.add(new String[]{"guilds", "bank", "`bank` BIGINT NOT NULL DEFAULT 0"});
        columns.add(new String[]{"guilds", "warehouse_level_expires_at", "`warehouse_level_expires_at` BIGINT NOT NULL DEFAULT 0"});
        columns.add(new String[]{"guilds", "warehouse_permanent_level", "`warehouse_permanent_level` INT NOT NULL DEFAULT 0"});
        columns.add(new String[]{"guild_warehouse_state", "deleted_at", "`deleted_at` BIGINT NOT NULL DEFAULT 0"});
        columns.add(new String[]{"guild_warehouse_state", "purge_at", "`purge_at` BIGINT NOT NULL DEFAULT 0"});
        columns.add(new String[]{"guilds", "warehouse_level", "`warehouse_level` INT NOT NULL DEFAULT 0"});
        columns.add(new String[]{"guilds", "warehouse_expires_at", "`warehouse_expires_at` BIGINT NOT NULL DEFAULT 0"});
        columns.add(new String[]{"guilds", "war_funds_day", "`war_funds_day` BIGINT NOT NULL DEFAULT 0"});
        columns.add(new String[]{"guilds", "war_funds_earned", "`war_funds_earned` BIGINT NOT NULL DEFAULT 0"});
        columns.add(new String[]{"guilds", "activity_day", "`activity_day` BIGINT NOT NULL DEFAULT 0"});
        columns.add(new String[]{"guilds", "activity_earned", "`activity_earned` BIGINT NOT NULL DEFAULT 0"});
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
        // 奖励 outbox 的领取状态（防重复发放）与商店上架交接的背包快照。
        columns.add(new String[]{"guild_war_rewards", "attempts", "`attempts` INT NOT NULL DEFAULT 0"});
        columns.add(new String[]{"guild_war_rewards", "claimed_at", "`claimed_at` BIGINT NOT NULL DEFAULT 0"});
        columns.add(new String[]{"guild_shop_pending", "before_items", "`before_items` " + (cfg.isSqlite() ? "TEXT" : "MEDIUMTEXT")});
        columns.add(new String[]{"guild_shop_pending", "after_items", "`after_items` " + (cfg.isSqlite() ? "TEXT" : "MEDIUMTEXT")});
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            for (String[] col : columns) {
                String table = t(col[0]);
                // 先查元数据再决定是否 ALTER：否则每次启动都要执行 30+ 次注定失败的 DDL。
                if (columnExists(st, table, col[1])) {
                    continue;
                }
                String ddl = "ALTER TABLE `" + table + "` ADD COLUMN " + col[2];
                try {
                    st.executeUpdate(ddl);
                    log.info("数据库升级：已添加 " + table + "." + col[1]);
                } catch (SQLException e) {
                    if (!isColumnAlreadyExists(e)) {
                        log.log(Level.WARNING, "数据库升级添加列失败 " + table + "." + col[1] + ": " + e.getMessage());
                    }
                }
            }
            // 老库新增 member_count 时默认 0，必须按真实成员关系回填；
            // 否则跨服入会人数上限会按 0 计算而被绕过。
            try (PreparedStatement ps = c.prepareStatement("UPDATE `" + t("guilds")
                    + "` SET `member_count`=(SELECT COUNT(*) FROM `" + t("guild_members")
                    + "` m WHERE m.`guild_id`=`" + t("guilds") + "`.`guild_id`)")) {
                ps.executeUpdate();
            } catch (SQLException e) {
                log.log(Level.WARNING, "回填 member_count 失败: " + e.getMessage());
            }
        } catch (SQLException e) {
            log.log(Level.WARNING, "数据库结构升级检查失败: " + e.getMessage());
        }
    }

    /** 查询列是否存在（MySQL 查 information_schema，SQLite 用 PRAGMA table_info）。 */
    private boolean columnExists(Statement st, String table, String column) {
        try {
            if (cfg.isSqlite()) {
                try (ResultSet rs = st.executeQuery("PRAGMA table_info(`" + table + "`)")) {
                    while (rs.next()) {
                        if (column.equalsIgnoreCase(rs.getString("name"))) {
                            return true;
                        }
                    }
                }
                return false;
            }
            try (PreparedStatement ps = st.getConnection().prepareStatement(
                    "SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()"
                            + " AND table_name=? AND column_name=?")) {
                ps.setString(1, table);
                ps.setString(2, column);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        } catch (SQLException e) {
            return false; // 判断失败时交给 ALTER + 错误处理兜底
        }
    }
}
