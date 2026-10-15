package com.liu.liuguild.importing;

import com.liu.liuguild.LiuGuild;
import com.liu.liuguild.model.Guild;
import com.liu.liuguild.model.GuildMember;
import com.liu.liuguild.model.GuildRank;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 旧版「PlayerGuild」数据导入器。
 * <p>解析其 MySQL dump（{@code guild_info} / {@code guild_player}），映射到 LiuGuild：
 * <ul>
 *   <li>{@code guild_info.guild_name} → 工会名；{@code logo_name}（序列化 ItemStack）→ 自定义图标数据；</li>
 *   <li>{@code money} → 工会银行；{@code prosperity_degree} → 工会点数；{@code level} → 等级；</li>
 *   <li>{@code join_mode=0} → 开放加入；{@code create_time} → 创建时间；</li>
 *   <li>{@code guild_player.role_id}：1=会长 / 2=副会长 / 其它=成员；{@code total_money} → 贡献；{@code kill/die} → 战绩。</li>
 * </ul>
 * 默认跳过同名工会；显式强制时覆盖映射字段及成员，跨工会玩家不迁移。</p>
 */
public final class PlayerguildImporter {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final LiuGuild plugin;

    public PlayerguildImporter(LiuGuild plugin) {
        this.plugin = plugin;
    }

    public record ImportResult(int guilds, int members, int skippedGuilds, int skippedMembers, List<String> warnings) {
        public String summary() {
            return "导入完成：工会 " + guilds + " 个、成员 " + members + " 人；"
                    + "跳过已存在工会 " + skippedGuilds + " 个、已入会玩家 " + skippedMembers + " 人。";
        }
    }

    private static final class SrcGuild {
        int id;
        String name;
        String logo;
        String description;
        int level = 1;
        long money;
        long prosperity;
        long createdAt = System.currentTimeMillis();
        boolean open;
        String creator;
    }

    private static final class SrcMember {
        int guildId;
        String name;
        UUID uuid;
        long contributed;
        long joinedAt = System.currentTimeMillis();
        int kills;
        int deaths;
        GuildRank rank = GuildRank.MEMBER;
    }

    public ImportResult importFile(Path file) throws Exception {
        return importFile(file, false);
    }

    public ImportResult importFile(Path file, boolean force) throws Exception {
        String sql = Files.readString(file, StandardCharsets.UTF_8);
        List<List<String>> guildRows = SqlDump.rows(sql, "guild_info");
        List<List<String>> memberRows = SqlDump.rows(sql, "guild_player");

        List<String> warnings = new ArrayList<>();
        List<SrcGuild> guilds = parseGuilds(guildRows, warnings);
        Map<Integer, List<SrcMember>> membersByGuild = parseMembers(memberRows, warnings);

        // 先按现有工会名去重
        Set<String> usedNames = new HashSet<>();
        Set<String> usedTags = new HashSet<>();
        int guildCount = 0;
        int memberCount = 0;
        int skippedGuilds = 0;
        int skippedMembers = 0;

        for (SrcGuild src : guilds) {
            String name = sanitizeName(src.name);
            if (name == null || usedNames.contains(name.toLowerCase(Locale.ROOT)) || (!force && plugin.repo().nameTaken(name))) {
                skippedGuilds++;
                continue;
            }
            List<SrcMember> members = membersByGuild.getOrDefault(src.id, List.of());
            if (members.isEmpty()) {
                warnings.add("工会「" + name + "」没有成员，已跳过");
                skippedGuilds++;
                continue;
            }
            String tag = uniqueTag(name, usedTags);
            UUID leaderUuid = null;
            String leaderName = src.creator;
            long earliest = Long.MAX_VALUE;
            for (SrcMember m : members) {
                if (m.rank == GuildRank.LEADER && m.joinedAt < earliest) {
                    earliest = m.joinedAt;
                    leaderUuid = m.uuid;
                    leaderName = m.name;
                }
            }
            if (leaderUuid == null) {
                SrcMember first = members.get(0);
                leaderUuid = first.uuid;
                leaderName = first.name;
                first.rank = GuildRank.LEADER;
            }
            Guild g = new Guild(UUID.randomUUID().toString(), name, tag, leaderUuid, leaderName,
                    src.description == null ? "" : src.description, "", src.open,
                    src.createdAt, System.currentTimeMillis(), src.prosperity, Math.max(1, src.level),
                    0, 0, src.money, plugin.serverId(), members.size(), plugin.settings().guild.defaultIcon);
            g.iconData(src.logo);
            List<GuildMember> importedMembers = new ArrayList<>();
            for (SrcMember m : members) {
                importedMembers.add(new GuildMember(m.uuid, m.name, g.id(), m.rank,
                        m.joinedAt, m.contributed, m.kills, m.deaths));
            }
            try {
                int count = plugin.repo().importGuild(g, importedMembers, force);
                if (count < 0) { skippedGuilds++; continue; }
                memberCount += count;
                skippedMembers += members.size() - count;
            } catch (Exception e) {
                warnings.add("工会「" + name + "」写入失败: " + e.getMessage());
                skippedGuilds++;
                continue;
            }
            usedNames.add(name.toLowerCase(Locale.ROOT));
            usedTags.add(tag.toLowerCase(Locale.ROOT));
            guildCount++;
        }

        plugin.guilds().reloadAll();
        for (Guild guild : plugin.repo().loadGuilds()) {
            if (usedNames.contains(guild.name().toLowerCase(Locale.ROOT))) plugin.broadcastGuildChange(guild.id());
        }
        return new ImportResult(guildCount, memberCount, skippedGuilds, skippedMembers, warnings);
    }

    private List<SrcGuild> parseGuilds(List<List<String>> rows, List<String> warnings) {
        List<SrcGuild> out = new ArrayList<>();
        for (List<String> r : rows) {
            if (r.size() < 19) {
                continue;
            }
            SrcGuild g = new SrcGuild();
            g.id = (int) parseLong(r.get(0), 0);
            g.name = r.get(1);
            g.logo = r.get(3);
            g.description = r.get(4);
            g.level = (int) parseLong(r.get(5), 1);
            g.money = parseLong(r.get(6), 0);
            g.prosperity = parseLong(r.get(7), 0);
            g.creator = r.get(15);
            g.createdAt = parseDate(r.get(16));
            // join_mode: 1=手动(邀请) / 0=自动(开放)
            g.open = parseLong(r.get(18), 1) == 0;
            out.add(g);
        }
        return out;
    }

    private Map<Integer, List<SrcMember>> parseMembers(List<List<String>> rows, List<String> warnings) {
        Map<Integer, List<SrcMember>> out = new HashMap<>();
        for (List<String> r : rows) {
            if (r.size() < 19) {
                continue;
            }
            SrcMember m = new SrcMember();
            m.guildId = (int) parseLong(r.get(1), -1);
            m.name = r.get(3);
            try {
                m.uuid = UUID.fromString(r.get(4));
            } catch (Exception e) {
                warnings.add("非法 UUID，已跳过玩家 " + m.name);
                continue;
            }
            m.contributed = parseLong(r.get(7), 0);
            long role = parseLong(r.get(9), 4);
            m.rank = role == 1 ? GuildRank.LEADER : (role == 2 ? GuildRank.OFFICER : GuildRank.MEMBER);
            m.joinedAt = parseDate(r.get(10));
            m.kills = (int) parseLong(r.get(17), 0);
            m.deaths = (int) parseLong(r.get(18), 0);
            out.computeIfAbsent(m.guildId, k -> new ArrayList<>()).add(m);
        }
        return out;
    }

    private String sanitizeName(String raw) {
        if (raw == null) {
            return null;
        }
        String name = raw.trim();
        int max = plugin.settings().guild.nameMax;
        if (name.length() > max) {
            name = name.substring(0, max);
        }
        return name.isEmpty() ? null : name;
    }

    private String uniqueTag(String name, Set<String> used) {
        int max = plugin.settings().guild.tagMax;
        int min = plugin.settings().guild.tagMin;
        String base = name.replaceAll("\\s+", "");
        if (base.isEmpty()) {
            base = "GUILD";
        }
        String tag = base.substring(0, Math.min(base.length(), max));
        while (tag.length() < min) {
            tag = tag + "0";
        }
        String candidate = tag;
        int n = 1;
        while (used.contains(candidate.toLowerCase(Locale.ROOT)) || tagTaken(candidate)) {
            String suffix = String.valueOf(n);
            candidate = tag.length() >= max
                    ? tag.substring(0, Math.max(1, max - suffix.length())) + suffix
                    : tag + suffix;
            n++;
            if (n > 9999) {
                break;
            }
        }
        return candidate;
    }

    private boolean tagTaken(String tag) {
        try {
            return plugin.repo().tagTaken(tag);
        } catch (Exception e) {
            return false;
        }
    }

    private static long parseDate(String s) {
        if (s == null || s.isBlank() || s.startsWith("0000")) {
            return System.currentTimeMillis();
        }
        try {
            return LocalDateTime.parse(s.trim(), DATE).atZone(ZONE).toInstant().toEpochMilli();
        } catch (Exception e) {
            return System.currentTimeMillis();
        }
    }

    private static long parseLong(String s, long def) {
        if (s == null) {
            return def;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return def;
        }
    }
}
