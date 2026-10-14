package com.liu.liuguild.hook;

import com.liu.liuguild.LiuGuild;
import com.liu.liuguild.api.GuildAPI;
import com.liu.liuguild.model.Guild;
import com.liu.liuguild.model.GuildMember;
import com.liu.liuguild.model.GuildWar;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * PlaceholderAPI 扩展（标识符 {@code liuguild}）。
 *
 * <h3>玩家自身工会</h3>
 * <pre>
 *   %liuguild_name% %liuguild_tag% %liuguild_level% %liuguild_points%
 *   %liuguild_members% %liuguild_member_limit% %liuguild_wins% %liuguild_losses%
 *   %liuguild_bank% %liuguild_rank% %liuguild_leader% %liuguild_notice%
 *   %liuguild_icon% %liuguild_open% %liuguild_created% %liuguild_online%
 *   %liuguild_kills% %liuguild_deaths% %liuguild_contributed%
 * </pre>
 *
 * <h3>指定工会</h3>
 * <pre>
 *   %liuguild_g_name_&lt;工会名&gt;% %liuguild_g_level_&lt;工会名&gt;% %liuguild_g_points_&lt;工会名&gt;%
 *   %liuguild_g_members_&lt;工会名&gt;% %liuguild_g_wins_&lt;工会名&gt;% %liuguild_g_tag_&lt;工会名&gt;%
 * </pre>
 *
 * <h3>排行榜与服务器</h3>
 * <pre>
 *   %liuguild_top_&lt;n&gt;_name|tag|level|points|wins|members|bank%
 *   %liuguild_server_id% %liuguild_guild_count% %liuguild_total_players%
 * </pre>
 *
 * <h3>工会战</h3>
 * <pre>
 *   %liuguild_war_state% %liuguild_war_enemy% %liuguild_war_score% %liuguild_war_enemy_score%
 *   %liuguild_war_time_left% %liuguild_war_winner% %liuguild_war_host%
 * </pre>
 */
public final class GuildPlaceholders extends PlaceholderExpansion {

    private final LiuGuild plugin;

    public GuildPlaceholders(LiuGuild plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "liuguild";
    }

    @Override
    public @NotNull String getAuthor() {
        return "liuxingyu2001";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        String p = params.toLowerCase(java.util.Locale.ROOT);
        Guild guild = player == null ? null : plugin.guilds().guildOf(player.getUniqueId());
        GuildMember member = guild == null || player == null ? null : guild.member(player.getUniqueId());

        // 服务端级
        switch (p) {
            case "server_id" -> {
                return plugin.serverId();
            }
            case "guild_count" -> {
                return String.valueOf(GuildAPI.guildCount());
            }
            case "total_players" -> {
                return String.valueOf(plugin.getServer().getOnlinePlayers().size());
            }
            default -> {
            }
        }

        // 排行榜
        if (p.startsWith("top_")) {
            return handleTop(p);
        }

        // 指定工会
        if (p.startsWith("g_")) {
            return handleNamed(p.substring(2));
        }

        // 工会战
        if (p.equals("war_lives")) {
            return player == null || plugin.war() == null ? "0"
                    : String.valueOf(plugin.war().lives(player.getUniqueId()));
        }
        if (p.equals("war_arena")) {
            return plugin.war() == null ? "" : plugin.war().currentArena().id;
        }
        if (p.startsWith("war_")) {
            return handleWar(guild, p);
        }

        // 玩家自身
        if (guild == null) {
            return "";
        }
        return switch (p) {
            case "name" -> guild.name();
            case "tag" -> guild.tag();
            case "level" -> String.valueOf(guild.level());
            case "points", "funds" -> String.valueOf(guild.points());
            case "home_slots" -> String.valueOf(plugin.settings().guild.homeSlots(guild.level()));
            case "members" -> String.valueOf(guild.memberCount());
            case "member_limit" -> String.valueOf(plugin.settings().guild.memberLimit(guild.level()));
            case "wins" -> String.valueOf(guild.wins());
            case "losses" -> String.valueOf(guild.losses());
            case "bank" -> plugin.economy().format(guild.bank());
            case "bank_raw" -> String.valueOf(guild.bank());
            case "rank" -> member == null ? "" : member.rank().name();
            case "rank_display" -> member == null ? "" : member.rank().display();
            case "leader" -> guild.leaderName();
            case "notice" -> guild.notice();
            case "description" -> guild.description();
            case "icon" -> guild.icon();
            case "open" -> guild.open() ? "open" : "closed";
            case "created" -> new SimpleDateFormat("yyyy-MM-dd").format(new Date(guild.createdAt()));
            case "online" -> String.valueOf(plugin.guilds().onlineMembers(guild).size());
            case "kills" -> member == null ? "0" : String.valueOf(member.kills());
            case "deaths" -> member == null ? "0" : String.valueOf(member.deaths());
            case "contributed" -> member == null ? "0" : plugin.economy().format(member.contributed());
            default -> "";
        };
    }

    private String handleNamed(String rest) {
        int idx = rest.indexOf('_');
        if (idx <= 0) {
            return "";
        }
        String field = rest.substring(0, idx);
        String name = rest.substring(idx + 1);
        GuildAPI.GuildView g = GuildAPI.guildByName(name);
        if (g == null) {
            return "";
        }
        return switch (field) {
            case "name" -> g.name();
            case "tag" -> g.tag();
            case "level" -> String.valueOf(g.level());
            case "points", "funds" -> String.valueOf(g.points());
            case "members" -> String.valueOf(g.members());
            case "wins" -> String.valueOf(g.wins());
            case "losses" -> String.valueOf(g.losses());
            case "bank" -> plugin.economy().format(g.bank());
            case "leader" -> g.leaderName();
            case "icon" -> g.icon();
            default -> "";
        };
    }

    private String handleTop(String p) {
        // top_<n>_<field>
        String[] parts = p.split("_", 3);
        if (parts.length < 3) {
            return "";
        }
        int n;
        try {
            n = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return "";
        }
        if (n < 1 || n > 1000) return "";
        String field = parts[2];
        String metric = switch (field) {
            case "bank", "level", "wins", "members", "losses" -> field;
            default -> "points";
        };
        List<GuildAPI.GuildView> top = GuildAPI.top(metric, Math.max(1, n));
        if (top.size() < n) {
            return "";
        }
        GuildAPI.GuildView g = top.get(n - 1);
        return switch (field) {
            case "name" -> g.name();
            case "tag" -> g.tag();
            case "level" -> String.valueOf(g.level());
            case "points", "funds" -> String.valueOf(g.points());
            case "wins" -> String.valueOf(g.wins());
            case "losses" -> String.valueOf(g.losses());
            case "members" -> String.valueOf(g.members());
            case "bank" -> plugin.economy().format(g.bank());
            case "leader" -> g.leaderName();
            case "icon" -> g.icon();
            default -> "";
        };
    }

    private String handleWar(Guild guild, String p) {
        if (plugin.war() == null || guild == null) {
            return "";
        }
        GuildWar war = plugin.war().warOf(guild.id());
        boolean home = war != null && war.guildA().equals(guild.id());
        return switch (p) {
            case "state" -> war == null ? "" : war.state().display();
            case "enemy" -> war == null ? "" : (home ? war.nameB() : war.nameA());
            case "score" -> war == null ? "0" : String.valueOf(home ? war.scoreA() : war.scoreB());
            case "enemy_score" -> war == null ? "0" : String.valueOf(home ? war.scoreB() : war.scoreA());
            case "time_left" -> war == null ? "0" : String.valueOf(plugin.war().phaseSecondsLeft());
            case "winner" -> war == null ? "" : war.winner();
            case "host" -> war == null ? "" : war.hostServer();
            case "mode" -> war == null ? "" : war.mode();
            case "mode_display" -> war == null ? "" : plugin.settings().mode(war.mode()).display;
            case "queued" -> plugin.war().isQueued(guild.id()) ? "true" : "false";
            case "queued_mode" -> {
                String q = plugin.war().queuedMode(guild.id());
                yield q == null ? "" : plugin.settings().mode(q).display;
            }
            default -> "";
        };
    }
}
