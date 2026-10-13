package com.liu.liuguild.db;

/**
 * 数据库连接配置 POJO（参考 multiserver-db-guide.md）。
 * jdbcUrl() 与 baseJdbcUrl() 分离：自动建库必须用不带库名的 URL 先连一次。
 */
public final class DbConfig {
    public String type = "sqlite";               // sqlite | mysql
    public String host = "127.0.0.1";
    public int port = 3306;
    public String database = "minecraft";
    public String user = "root";
    public String password = "";
    public String params = "useSSL=false&characterEncoding=utf8&serverTimezone=Asia/Shanghai&rewriteBatchedStatements=true";
    public String sqliteFile = "plugins/LiuGuild/data.db";
    public String tablePrefix = "liuguild";
    public int poolSize = 5;
    public int connectionTimeoutMs = 5000;
    public boolean autoCreateDatabase = true;
    public boolean autoCreateTables = true;

    public boolean isSqlite() {
        return "sqlite".equalsIgnoreCase(type);
    }

    public String jdbcUrl() {
        if (isSqlite()) {
            return "jdbc:sqlite:" + sqliteFile;
        }
        return "jdbc:mysql://" + host + ":" + port + "/" + database + "?" + params;
    }

    public String baseJdbcUrl() {
        return "jdbc:mysql://" + host + ":" + port + "/?" + params;
    }
}
