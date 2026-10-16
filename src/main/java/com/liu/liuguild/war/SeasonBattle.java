package com.liu.liuguild.war;

import java.util.*;

/** 多工会赛季的纯状态模型。由承办服主线程串行调用，不依赖 Bukkit 或双队比赛模型。 */
public final class SeasonBattle {
    public enum Phase { PREPARING, RUNNING, FINISHED }
    public record Standing(String guildId, int rank, long points, int deaths, int survivors) {}
    private static final class Team {
        long points;
        int deaths;
        final Map<UUID, Integer> lives = new LinkedHashMap<>();
    }

    private final Map<String, Team> teams = new LinkedHashMap<>();
    private final Map<UUID, String> membership = new HashMap<>();
    private final Set<UUID> awaitingRespawn = new HashSet<>();
    private Phase phase = Phase.PREPARING;
    private long deadline;

    public Phase phase() { return phase; }

    public void register(String guildId, Collection<UUID> players, int lives) {
        if (phase != Phase.PREPARING) throw new IllegalStateException("赛事已开始");
        if (guildId == null || guildId.isBlank() || teams.containsKey(guildId)
                || players == null || players.isEmpty() || lives < 1)
            throw new IllegalArgumentException("工会、参赛名单或生命次数无效");
        Set<UUID> unique = new HashSet<>(players);
        if (unique.size() != players.size() || unique.contains(null)
                || unique.stream().anyMatch(membership::containsKey))
            throw new IllegalArgumentException("参赛玩家重复或无效");
        Team team = new Team();
        players.stream().sorted().forEach(player -> team.lives.put(player, lives));
        teams.put(guildId, team);
        players.forEach(player -> membership.put(player, guildId));
    }

    public void start(long now, long durationMillis) {
        if (phase != Phase.PREPARING || teams.size() < 2 || durationMillis <= 0)
            throw new IllegalStateException("至少两个工会且时长有效才能开赛");
        deadline = Math.addExact(now, durationMillis);
        phase = Phase.RUNNING;
    }

    /** 同一次死亡在复活前只接受一次；环境死亡也计死亡数，但不扣分。 */
    public boolean death(UUID victim, UUID killer, int killPoints, long now) {
        if (killPoints < 0) throw new IllegalArgumentException("击杀分数不能为负");
        if (finishIfDue(now) || phase != Phase.RUNNING || !alive(victim)
                || awaitingRespawn.contains(victim)) return false;
        String victimGuild = membership.get(victim);
        Team victimTeam = teams.get(victimGuild);
        String killerGuild = membership.get(killer);
        if (killerGuild != null && !killerGuild.equals(victimGuild) && alive(killer)
                && !awaitingRespawn.contains(killer)) {
            Team killerTeam = teams.get(killerGuild);
            killerTeam.points = Math.addExact(killerTeam.points, killPoints);
        }
        victimTeam.deaths++;
        victimTeam.lives.computeIfPresent(victim, (id, lives) -> lives - 1);
        awaitingRespawn.add(victim);
        finishIfDue(now);
        return true;
    }

    public void eliminate(UUID player, long now) {
        Team team = teams.get(membership.get(player));
        if (phase != Phase.RUNNING || team == null) return;
        team.lives.put(player, 0);
        awaitingRespawn.remove(player);
        finishIfDue(now);
    }

    public boolean respawn(UUID player) {
        return phase == Phase.RUNNING && alive(player) && awaitingRespawn.remove(player);
    }

    /** 等待复活仍算存活；生命耗尽才淘汰。全部工会淘汰时同样结束。 */
    public boolean finishIfDue(long now) {
        if (phase == Phase.RUNNING && (now >= deadline || livingTeams() <= 1)) phase = Phase.FINISHED;
        return phase == Phase.FINISHED;
    }

    private boolean alive(UUID player) {
        Team team = teams.get(membership.get(player));
        return team != null && team.lives.getOrDefault(player, 0) > 0;
    }

    private int survivors(Team team) {
        return (int) team.lives.values().stream().filter(lives -> lives > 0).count();
    }

    private long livingTeams() {
        return teams.values().stream().filter(team -> survivors(team) > 0).count();
    }

    /** 所有工会按ID稳定分配点位，人数与死亡不改变本队点位；不足时循环复用。 */
    public int spawnIndex(String guildId, int pointCount) {
        if (!teams.containsKey(guildId) || pointCount < 1) throw new IllegalArgumentException("工会或点位无效");
        List<String> ids = new ArrayList<>(teams.keySet());
        Collections.sort(ids);
        return ids.indexOf(guildId) % pointCount;
    }

    /** 分数降序、死亡升序，同分同死亡并列（1、1、3）。ID只用于稳定展示顺序。 */
    public List<Standing> standings() {
        List<String> ids = new ArrayList<>(teams.keySet());
        ids.sort(Comparator.<String>comparingLong(id -> teams.get(id).points).reversed()
                .thenComparingInt(id -> teams.get(id).deaths).thenComparing(id -> id));
        List<Standing> result = new ArrayList<>();
        Team previous = null;
        int rank = 0;
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            Team team = teams.get(id);
            if (previous == null || team.points != previous.points || team.deaths != previous.deaths) rank = i + 1;
            result.add(new Standing(id, rank, team.points, team.deaths, survivors(team)));
            previous = team;
        }
        return List.copyOf(result);
    }
}
