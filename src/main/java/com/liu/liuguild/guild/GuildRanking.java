package com.liu.liuguild.guild;

import com.liu.liuguild.model.Guild;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工会排行榜：按列缓存全量排序结果（PAPI/GUI 高频读取，避免每次都重排全部工会）。
 */
final class GuildRanking {

    private static final long TOP_CACHE_MS = 3000L;

    private final GuildManager guilds;
    private final Map<String, Object[]> topCache = new ConcurrentHashMap<>();

    GuildRanking(GuildManager guilds) {
        this.guilds = guilds;
    }

    List<Guild> top(String column, int limit) {
        List<Guild> list = topSorted(column);
        return list.subList(0, Math.min(Math.max(0, limit), list.size()));
    }

    private List<Guild> topSorted(String column) {
        String key = column == null ? "points" : column;
        long now = System.currentTimeMillis();
        Object[] cached = topCache.get(key);
        if (cached != null && now - (Long) cached[1] < TOP_CACHE_MS) {
            @SuppressWarnings("unchecked")
            List<Guild> hit = (List<Guild>) cached[0];
            return hit;
        }
        List<Guild> list = new ArrayList<>(guilds.all());
        switch (key) {
            case "level" -> list.sort((a, b) -> Integer.compare(b.level(), a.level()));
            case "wins" -> list.sort((a, b) -> Integer.compare(b.wins(), a.wins()));
            case "losses" -> list.sort((a, b) -> Integer.compare(b.losses(), a.losses()));
            case "bank" -> list.sort((a, b) -> Long.compare(b.bank(), a.bank()));
            case "members" -> list.sort((a, b) -> Integer.compare(b.memberCount(), a.memberCount()));
            default -> list.sort((a, b) -> Long.compare(b.points(), a.points()));
        }
        topCache.put(key, new Object[]{list, now});
        return list;
    }
}
