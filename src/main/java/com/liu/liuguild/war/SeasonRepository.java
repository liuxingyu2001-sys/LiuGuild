package com.liu.liuguild.war;

import com.liu.liuguild.db.Database;
import java.sql.*;
import java.util.*;

/** 赛季数据库协议：数据库是真源；代理仅负责切服，轮询负责到达、返程与失联检测。 */
public final class SeasonRepository {
    public record Event(String id, String mode, String host, String state, long until, long heartbeat) {}
    public record Entry(String event, UUID player, String guild, String source, String world,
                        double x, double y, double z, float yaw, float pitch, String state) {}
    private final Database db;
    public SeasonRepository(Database db) { this.db = db; }
    private String t(String table) { return "`" + db.t("season_" + table) + "`"; }
    public void init() throws SQLException {
        try (var c = db.source().getConnection(); var s = c.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS " + t("events") + " (id VARCHAR(36) PRIMARY KEY, mode VARCHAR(64) NOT NULL, host VARCHAR(64) NOT NULL, state VARCHAR(16) NOT NULL, until_ms BIGINT NOT NULL, heartbeat BIGINT NOT NULL)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS " + t("hosts") + " (host VARCHAR(64) PRIMARY KEY, event_id VARCHAR(36) NOT NULL)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS " + t("entries") + " (event_id VARCHAR(36) NOT NULL, player VARCHAR(36) NOT NULL, guild_id VARCHAR(64) NOT NULL, source VARCHAR(64) NOT NULL, world VARCHAR(128) NOT NULL, x DOUBLE NOT NULL, y DOUBLE NOT NULL, z DOUBLE NOT NULL, yaw FLOAT NOT NULL, pitch FLOAT NOT NULL, state VARCHAR(16) NOT NULL, PRIMARY KEY(event_id,player))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS " + t("players") + " (player VARCHAR(36) PRIMARY KEY, event_id VARCHAR(36) NOT NULL)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS " + t("results") + " (event_id VARCHAR(36) NOT NULL, guild_id VARCHAR(64) NOT NULL, place_no INT NOT NULL, points BIGINT NOT NULL, deaths INT NOT NULL, reward BIGINT NOT NULL, PRIMARY KEY(event_id,guild_id))");
        }
    }
    public boolean create(Event event) throws SQLException {
        try (var c = db.source().getConnection()) {
            c.setAutoCommit(false);
            try {
                try (var p = c.prepareStatement("INSERT INTO " + t("hosts") + " VALUES (?,?)")) {
                    p.setString(1,event.host()); p.setString(2,event.id()); p.executeUpdate();
                }
                try (var p = c.prepareStatement("INSERT INTO " + t("events") + " VALUES (?,?,?,?,?,?)")) {
                    p.setString(1,event.id()); p.setString(2,event.mode()); p.setString(3,event.host()); p.setString(4,event.state()); p.setLong(5,event.until()); p.setLong(6,event.heartbeat()); p.executeUpdate();
                }
                c.commit(); return true;
            } catch (SQLException ex) { c.rollback(); throw ex; }
        }
    }
    public List<Event> events() throws SQLException {
        List<Event> result = new ArrayList<>();
        try (var c=db.source().getConnection(); var p=c.prepareStatement("SELECT * FROM " + t("events") + " WHERE state NOT IN ('FINISHED','CANCELLED')"); var r=p.executeQuery()) {
            while(r.next()) result.add(new Event(r.getString("id"),r.getString("mode"),r.getString("host"),r.getString("state"),r.getLong("until_ms"),r.getLong("heartbeat")));
        }
        return result;
    }
    public Event event(String id) throws SQLException {
        try (var c=db.source().getConnection(); var p=c.prepareStatement("SELECT * FROM " + t("events") + " WHERE id=?")) {
            p.setString(1,id);
            try(var r=p.executeQuery()) { return r.next() ? new Event(id,r.getString("mode"),r.getString("host"),r.getString("state"),r.getLong("until_ms"),r.getLong("heartbeat")) : null; }
        }
    }
    public void state(String id, String state, long until) throws SQLException {
        try(var c=db.source().getConnection(); var p=c.prepareStatement("UPDATE " + t("events") + " SET state=?,until_ms=?,heartbeat=? WHERE id=? AND state NOT IN ('FINISHED','CANCELLED')")) {
            p.setString(1,state); p.setLong(2,until); p.setLong(3,System.currentTimeMillis()); p.setString(4,id); p.executeUpdate();
        }
    }
    public void heartbeat(String id) throws SQLException {
        try(var c=db.source().getConnection(); var p=c.prepareStatement("UPDATE " + t("events") + " SET heartbeat=? WHERE id=?")) {
            p.setLong(1,System.currentTimeMillis()); p.setString(2,id); p.executeUpdate();
        }
    }
    public void enter(Entry e) throws SQLException {
        try(var c=db.source().getConnection()) {
            c.setAutoCommit(false);
            try {
                try(var p=c.prepareStatement("INSERT INTO " + t("players") + " VALUES (?,?)")) {
                    p.setString(1,e.player().toString()); p.setString(2,e.event()); p.executeUpdate();
                }
                try(var p=c.prepareStatement("INSERT INTO " + t("entries") + " VALUES (?,?,?,?,?,?,?,?,?,?,?)")) {
                    p.setString(1,e.event()); p.setString(2,e.player().toString()); p.setString(3,e.guild()); p.setString(4,e.source()); p.setString(5,e.world());
                    p.setDouble(6,e.x()); p.setDouble(7,e.y()); p.setDouble(8,e.z()); p.setFloat(9,e.yaw()); p.setFloat(10,e.pitch()); p.setString(11,e.state());
                    p.executeUpdate();
                }
                c.commit();
            } catch(Exception ex) { c.rollback(); throw ex; }
        }
    }
    public List<Entry> entries(String event) throws SQLException {
        List<Entry> result=new ArrayList<>();
        try(var c=db.source().getConnection(); var p=c.prepareStatement("SELECT * FROM " + t("entries") + " WHERE event_id=?")) {
            p.setString(1,event);
            try(var r=p.executeQuery()) { while(r.next()) result.add(entry(r)); }
        }
        return result;
    }
    private Entry entry(ResultSet r) throws SQLException {
        return new Entry(r.getString("event_id"),UUID.fromString(r.getString("player")),r.getString("guild_id"),r.getString("source"),r.getString("world"),r.getDouble("x"),r.getDouble("y"),r.getDouble("z"),r.getFloat("yaw"),r.getFloat("pitch"),r.getString("state"));
    }
    public List<Entry> pendingReturns() throws SQLException {
        List<Entry> result=new ArrayList<>();
        try(var c=db.source().getConnection(); var p=c.prepareStatement("SELECT e.* FROM " + t("entries") + " e JOIN " + t("events") + " s ON s.id=e.event_id WHERE s.state IN ('FINISHED','CANCELLED') AND e.state<>'RETURNED'"); var r=p.executeQuery()) {
            while(r.next()) result.add(entry(r));
        }
        return result;
    }
    public Entry entry(String event, UUID player) throws SQLException {
        try(var c=db.source().getConnection(); var p=c.prepareStatement("SELECT * FROM " + t("entries") + " WHERE event_id=? AND player=?")) {
            p.setString(1,event); p.setString(2,player.toString());
            try(var r=p.executeQuery()) { return r.next() ? entry(r) : null; }
        }
    }

    public void destination(String id, String server, String world, ArenaDestination pos) throws SQLException {
        try(var c=db.source().getConnection(); var p=c.prepareStatement("UPDATE " + t("entries") + " SET source=?,world=?,x=?,y=?,z=?,yaw=?,pitch=? WHERE event_id=? AND state<>'RETURNED'")) {
            p.setString(1,server); p.setString(2,world); p.setDouble(3,pos.x()); p.setDouble(4,pos.y()); p.setDouble(5,pos.z()); p.setFloat(6,pos.yaw()); p.setFloat(7,pos.pitch()); p.setString(8,id); p.executeUpdate();
        }
    }
    public record ArenaDestination(double x,double y,double z,float yaw,float pitch) {}

    public void entryState(String id, UUID player, String state) throws SQLException {
        try(var c=db.source().getConnection()) {
            c.setAutoCommit(false);
            try {
                try(var p=c.prepareStatement("UPDATE " + t("entries") + " SET state=? WHERE event_id=? AND player=?")) {
                    p.setString(1,state); p.setString(2,id); p.setString(3,player.toString()); p.executeUpdate();
                }
                if("RETURNED".equals(state)) try(var release=c.prepareStatement("DELETE FROM " + t("players") + " WHERE player=? AND event_id=?")) {
                    release.setString(1,player.toString()); release.setString(2,id); release.executeUpdate();
                }
                c.commit();
            } catch (SQLException e) { c.rollback(); throw e; }
            finally { c.setAutoCommit(true); }
        }
    }
    /** 排名、公共资金奖励、终态同事务提交，重试不会重复奖励。 */
    public void finish(String id, List<SeasonBattle.Standing> ranks, Map<Integer,Long> rewards, boolean cancelled) throws SQLException {
        try(var c=db.source().getConnection()) {
            c.setAutoCommit(false);
            try {
                try(var p=c.prepareStatement("UPDATE " + t("events") + " SET state=? WHERE id=? AND state NOT IN ('FINISHED','CANCELLED')")) {
                    p.setString(1,cancelled?"CANCELLED":"FINISHED"); p.setString(2,id);
                    if(p.executeUpdate()!=1) { c.rollback(); return; }
                }
                if(!cancelled) for(var rank:ranks) {
                    long reward=rewards.getOrDefault(rank.rank(),0L);
                    try(var p=c.prepareStatement("INSERT INTO " + t("results") + " VALUES (?,?,?,?,?,?)")) {
                        p.setString(1,id); p.setString(2,rank.guildId()); p.setInt(3,rank.rank()); p.setLong(4,rank.points()); p.setInt(5,rank.deaths()); p.setLong(6,reward); p.executeUpdate();
                    }
                    try(var p=c.prepareStatement("UPDATE `"+db.t("guilds")+"` SET points=points+? WHERE guild_id=?")) {
                        p.setLong(1,reward); p.setString(2,rank.guildId()); p.executeUpdate();
                    }
                }
                try(var p=c.prepareStatement("DELETE FROM " + t("hosts") + " WHERE event_id=?")) { p.setString(1,id); p.executeUpdate(); }
                c.commit();
            } catch(Exception e) { c.rollback(); throw e; }
        }
    }
}
