package com.liu.liuguild.model;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** 一场工会战。 */
public final class GuildWar {

    private final String id;
    private volatile String guildA;
    private volatile String guildB;
    private volatile String nameA = "";
    private volatile String nameB = "";
    private volatile String tagA = "";
    private volatile String tagB = "";
    private volatile WarState state = WarState.MATCHED;
    private volatile int scoreA;
    private volatile int scoreB;
    private volatile long createdAt = System.currentTimeMillis();
    private volatile long startedAt;
    private volatile long endedAt;
    private volatile String winner = "";
    /** 举办竞技场的服务器 id。 */
    private volatile String hostServer = "";
    private volatile String arenaWorld = "";
    /** 工会战模式 key（match / season / 自定义）。 */
    private volatile String mode = "match";

    /** 出战名单（内存，仅举办服有效）。 */
    private final Set<UUID> rosterA = new LinkedHashSet<>();
    private final Set<UUID> rosterB = new LinkedHashSet<>();

    public GuildWar(String id, String guildA, String guildB) {
        this.id = id;
        this.guildA = guildA;
        this.guildB = guildB;
    }

    public String id() {
        return id;
    }

    public String mode() {
        return mode;
    }

    public void mode(String mode) {
        this.mode = mode == null || mode.isBlank() ? "match" : mode;
    }

    public String guildA() {
        return guildA;
    }

    public void guildA(String guildA) {
        this.guildA = guildA;
    }

    public String guildB() {
        return guildB;
    }

    public void guildB(String guildB) {
        this.guildB = guildB;
    }

    public String nameA() {
        return nameA;
    }

    public void nameA(String v) {
        this.nameA = v == null ? "" : v;
    }

    public String nameB() {
        return nameB;
    }

    public void nameB(String v) {
        this.nameB = v == null ? "" : v;
    }

    public String tagA() {
        return tagA;
    }

    public void tagA(String v) {
        this.tagA = v == null ? "" : v;
    }

    public String tagB() {
        return tagB;
    }

    public void tagB(String v) {
        this.tagB = v == null ? "" : v;
    }

    public WarState state() {
        return state;
    }

    public void state(WarState state) {
        this.state = state;
    }

    public int scoreA() {
        return scoreA;
    }

    public void scoreA(int v) {
        this.scoreA = v;
    }

    public int scoreB() {
        return scoreB;
    }

    public void scoreB(int v) {
        this.scoreB = v;
    }

    public long createdAt() {
        return createdAt;
    }

    public void createdAt(long v) {
        this.createdAt = v;
    }

    public long startedAt() {
        return startedAt;
    }

    public void startedAt(long v) {
        this.startedAt = v;
    }

    public long endedAt() {
        return endedAt;
    }

    public void endedAt(long v) {
        this.endedAt = v;
    }

    public String winner() {
        return winner == null ? "" : winner;
    }

    public void winner(String winner) {
        this.winner = winner == null ? "" : winner;
    }

    public String hostServer() {
        return hostServer == null ? "" : hostServer;
    }

    public void hostServer(String v) {
        this.hostServer = v == null ? "" : v;
    }

    public String arenaWorld() {
        return arenaWorld == null ? "" : arenaWorld;
    }

    public void arenaWorld(String v) {
        this.arenaWorld = v == null ? "" : v;
    }

    public Set<UUID> rosterA() {
        return rosterA;
    }

    public Set<UUID> rosterB() {
        return rosterB;
    }

    public Set<UUID> rosterOf(String guildId) {
        if (guildId.equals(guildA)) {
            return rosterA;
        }
        if (guildId.equals(guildB)) {
            return rosterB;
        }
        return Set.of();
    }

    /** 该玩家所属阵营：A / B / null。 */
    public String sideOf(UUID uuid) {
        if (rosterA.contains(uuid)) {
            return "A";
        }
        if (rosterB.contains(uuid)) {
            return "B";
        }
        return null;
    }

    public boolean involves(String guildId) {
        return guildId != null && (guildId.equals(guildA) || guildId.equals(guildB));
    }

    public String enemyOf(String guildId) {
        if (guildId == null) {
            return "";
        }
        if (guildId.equals(guildA)) {
            return guildB;
        }
        if (guildId.equals(guildB)) {
            return guildA;
        }
        return "";
    }

    public String resultSummary() {
        if (winner() == null || winner().isBlank()) {
            return scoreA + " : " + scoreB;
        }
        return (winner().equals(guildA) ? nameA : nameB) + " 胜（" + scoreA + " : " + scoreB + "）";
    }
}
