package com.liu.liuguild.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
    /** 当前阶段结束时间（跨服镜像用于显示倒计时）。 */
    private volatile long phaseUntil;
    private volatile String winner = "";
    /** 举办竞技场的服务器 id。 */
    private volatile String hostServer = "";
    private volatile String arenaWorld = "";
    /** 工会战模式 key（match / season / 自定义）。 */
    private volatile String mode = "match";

    /** 出战名单（内存，仅举办服有效）。 */
    private final Set<UUID> rosterA = new LinkedHashSet<>();
    private final Set<UUID> rosterB = new LinkedHashSet<>();

    /** 2～N 公会阵营。A/B 始终对应 guildA/guildB，额外公会使用 C、D… 键。 */
    private final Map<String, Party> parties = new LinkedHashMap<>();

    public GuildWar(String id, String guildA, String guildB) {
        this.id = id;
        this.guildA = guildA;
        this.guildB = guildB;
        this.parties.put("A", new Party("A", guildA, "", "", rosterA));
        this.parties.put("B", new Party("B", guildB, "", "", rosterB));
    }

    /** 一场比赛的公会阵营；2 个公会时就是 A/B。 */
    public static final class Party {
        private final String side;
        private final String guildId;
        private String name;
        private String tag;
        private final Set<UUID> roster;
        private int score;
        private int deaths;
        private UUID initiator;

        Party(String side, String guildId, String name, String tag, Set<UUID> roster) {
            this.side = side;
            this.guildId = guildId;
            this.name = name == null ? "" : name;
            this.tag = tag == null ? "" : tag;
            this.roster = roster;
        }

        public String side() { return side; }
        public String guildId() { return guildId; }
        public String name() { return name; }
        public String tag() { return tag; }
        public Set<UUID> roster() { return roster; }
        public int score() { return score; }
        public int deaths() { return deaths; }
        public UUID initiator() { return initiator; }
        void initiator(UUID v) { this.initiator = v; }
        void name(String v) { this.name = v == null ? "" : v; }
        void tag(String v) { this.tag = v == null ? "" : v; }
    }

    /** 追加第三个及之后的公会。 */
    public synchronized Party addParty(String guildId, String name, String tag) {
        if (guildId == null || guildId.isBlank()) {
            throw new IllegalArgumentException("guildId");
        }
        for (Party p : parties.values()) {
            if (p.guildId.equals(guildId)) {
                p.name(name);
                p.tag(tag);
                return p;
            }
        }
        String side = nextSide();
        Party p = new Party(side, guildId, name, tag, new LinkedHashSet<>());
        parties.put(side, p);
        return p;
    }

    private String nextSide() {
        for (char c = 'A'; c <= 'Z'; c++) {
            String key = String.valueOf(c);
            if (!parties.containsKey(key)) {
                return key;
            }
        }
        throw new IllegalStateException("过多公会阵营");
    }

    /** 阵营按加入顺序；A/B 永远在最前。 */
    public synchronized List<Party> parties() {
        return List.copyOf(parties.values());
    }

    public synchronized void initiator(String guildId, UUID uuid) {
        Party p = partyOfGuild(guildId);
        if (p != null) {
            p.initiator = uuid;
        }
    }

    public synchronized Party party(String side) {
        return side == null ? null : parties.get(side);
    }

    public synchronized Party partyOfGuild(String guildId) {
        for (Party p : parties.values()) {
            if (p.guildId.equals(guildId)) {
                return p;
            }
        }
        return null;
    }

    public synchronized String sideOfGuild(String guildId) {
        Party p = partyOfGuild(guildId);
        return p == null ? null : p.side;
    }

    /** 按击杀分降序、死亡数升序、公会 ID 稳定排序。 */
    public synchronized List<Party> ranking() {
        List<Party> list = new ArrayList<>();
        for (Party p : parties.values()) {
            if (!p.roster.isEmpty()) {
                list.add(p);
            }
        }
        list.sort(Comparator.comparingInt(Party::score).reversed()
                .thenComparingInt(Party::deaths)
                .thenComparing(Party::guildId));
        return list;
    }

    /** 第一名公会；没有有效分数时返回空串。 */
    public synchronized String firstPlace() {
        List<Party> rank = ranking();
        return rank.isEmpty() ? "" : rank.get(0).guildId;
    }

    public synchronized java.util.Set<UUID> allPlayers() {
        java.util.LinkedHashSet<UUID> all = new java.util.LinkedHashSet<>();
        for (Party p : parties.values()) {
            all.addAll(p.roster);
        }
        return all;
    }

    public synchronized boolean removePlayer(UUID uuid) {
        boolean removed = false;
        for (Party p : parties.values()) {
            removed |= p.roster.remove(uuid);
        }
        return removed;
    }

    public synchronized boolean containsPlayer(UUID uuid) {
        for (Party p : parties.values()) {
            if (p.roster.contains(uuid)) {
                return true;
            }
        }
        return false;
    }

    public synchronized boolean hasEnoughParties(int minimum) {
        int count = 0;
        for (Party p : parties.values()) {
            if (!p.roster.isEmpty()) {
                count++;
            }
        }
        return count >= minimum;
    }

    public synchronized int partyIndex(String side) {
        int i = 0;
        for (String key : parties.keySet()) {
            if (key.equals(side)) {
                return i;
            }
            i++;
        }
        return -1;
    }

    public synchronized String guildOfSide(String side) {
        Party p = parties.get(side);
        return p == null ? null : p.guildId;
    }

    public synchronized String nameOfSide(String side) {
        Party p = parties.get(side);
        return p == null ? "" : p.name;
    }

    public synchronized String tagOfSide(String side) {
        Party p = parties.get(side);
        return p == null ? "" : p.tag;
    }

    public synchronized int scoreOfSide(String side) {
        Party p = parties.get(side);
        return p == null ? 0 : p.score;
    }

    public synchronized void addScore(String side, int delta) {
        Party p = parties.get(side);
        if (p == null) {
            return;
        }
        p.score = Math.addExact(p.score, delta);
        if ("A".equals(side)) {
            scoreA = p.score;
        } else if ("B".equals(side)) {
            scoreB = p.score;
        }
    }

    public synchronized void scoreOfSide(String side, int value) {
        Party p = parties.get(side);
        if (p == null) {
            return;
        }
        p.score = value;
        if ("A".equals(side)) {
            scoreA = value;
        } else if ("B".equals(side)) {
            scoreB = value;
        }
    }

    public synchronized void addDeath(String side) {
        Party p = parties.get(side);
        if (p != null) {
            p.deaths++;
        }
    }

    public synchronized String nameOf(String guildId) {
        Party p = partyOfGuild(guildId);
        return p == null ? "" : p.name;
    }

    public synchronized String tagOf(String guildId) {
        Party p = partyOfGuild(guildId);
        return p == null ? "" : p.tag;
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
        Party p = parties.get("A");
        if (p != null) p.name = this.nameA;
    }

    public String nameB() {
        return nameB;
    }

    public void nameB(String v) {
        this.nameB = v == null ? "" : v;
        Party p = parties.get("B");
        if (p != null) p.name = this.nameB;
    }

    public String tagA() {
        return tagA;
    }

    public void tagA(String v) {
        this.tagA = v == null ? "" : v;
        Party p = parties.get("A");
        if (p != null) p.tag = this.tagA;
    }

    public String tagB() {
        return tagB;
    }

    public void tagB(String v) {
        this.tagB = v == null ? "" : v;
        Party p = parties.get("B");
        if (p != null) p.tag = this.tagB;
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
        Party party = parties.get("A");
        if (party != null) {
            party.score = v;
        }
    }

    public int scoreB() {
        return scoreB;
    }

    public void scoreB(int v) {
        this.scoreB = v;
        Party party = parties.get("B");
        if (party != null) {
            party.score = v;
        }
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

    public long phaseUntil() {
        return phaseUntil;
    }

    public void phaseUntil(long v) {
        this.phaseUntil = v;
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
        Party p = partyOfGuild(guildId);
        return p == null ? Set.of() : p.roster;
    }

    public Set<UUID> rosterOfSide(String side) {
        Party p = parties.get(side);
        return p == null ? Set.of() : p.roster;
    }

    /** 该玩家所属阵营：A / B / C… / null。 */
    public String sideOf(UUID uuid) {
        for (Party p : parties.values()) {
            if (p.roster.contains(uuid)) {
                return p.side;
            }
        }
        return null;
    }

    public boolean involves(String guildId) {
        return partyOfGuild(guildId) != null;
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
        List<Party> rank = ranking();
        if (rank.isEmpty()) {
            return "";
        }
        if (rank.size() == 2) {
            Party a = rank.get(0);
            Party b = rank.get(1);
            return (winner() == null || winner().isBlank() ? "" : nameOf(winner()) + " 胜（")
                    + a.score + " : " + b.score + (winner() == null || winner().isBlank() ? "" : "）");
        }
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (Party p : rank) {
            if (i++ > 0) {
                sb.append(" · ");
            }
            sb.append(p.name).append(" ").append(p.score);
        }
        return sb.toString();
    }
}
