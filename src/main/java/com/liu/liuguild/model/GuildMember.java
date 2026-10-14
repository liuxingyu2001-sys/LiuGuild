package com.liu.liuguild.model;

import java.util.UUID;

/** 工会成员。 */
public final class GuildMember {

    private final UUID uuid;
    private volatile String name;
    private volatile String guildId;
    private volatile GuildRank rank;
    private volatile long joinedAt;
    private volatile long contributed;
    private volatile int kills;
    private volatile int deaths;
    /** 上次签到所在日（epoch day，本地时区）；0 = 从未签到。 */
    private volatile long lastCheckin;
    /** 连续签到天数。 */
    private volatile int checkinStreak;

    public GuildMember(UUID uuid, String name, String guildId, GuildRank rank,
                       long joinedAt, long contributed, int kills, int deaths) {
        this.uuid = uuid;
        this.name = name == null ? "" : name;
        this.guildId = guildId;
        this.rank = rank == null ? GuildRank.MEMBER : rank;
        this.joinedAt = joinedAt;
        this.contributed = contributed;
        this.kills = kills;
        this.deaths = deaths;
    }

    public UUID uuid() {
        return uuid;
    }

    public String name() {
        return name;
    }

    public void name(String name) {
        this.name = name == null ? "" : name;
    }

    public String guildId() {
        return guildId;
    }

    public void guildId(String guildId) {
        this.guildId = guildId;
    }

    public GuildRank rank() {
        return rank;
    }

    public void rank(GuildRank rank) {
        this.rank = rank == null ? GuildRank.MEMBER : rank;
    }

    public long joinedAt() {
        return joinedAt;
    }

    public long contributed() {
        return contributed;
    }

    public void addContributed(long amount) {
        this.contributed += amount;
    }

    public int kills() {
        return kills;
    }

    public void addKill() {
        this.kills++;
    }

    public int deaths() {
        return deaths;
    }

    public void addDeath() {
        this.deaths++;
    }

    public long lastCheckin() {
        return lastCheckin;
    }

    public void lastCheckin(long lastCheckin) {
        this.lastCheckin = lastCheckin;
    }

    public int checkinStreak() {
        return checkinStreak;
    }

    public void checkinStreak(int checkinStreak) {
        this.checkinStreak = Math.max(0, checkinStreak);
    }
}
