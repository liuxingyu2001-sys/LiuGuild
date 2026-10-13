package com.liu.liuguild.api;

import com.liu.liuguild.LiuGuild;
import com.liu.liuguild.model.Guild;
import com.liu.liuguild.model.GuildMember;
import com.liu.liuguild.model.GuildRank;
import com.liu.liuguild.model.GuildWar;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * LiuGuild 对外 API 门面（静态，线程安全）。
 * <p>查询方法只读内存缓存、零 IO，可在主线程调用；写入方法异步执行、不阻塞调用线程。
 * 其它插件应把 LiuGuild 声明为 {@code softdepend}，并在 {@link #isReady()} 为 true 时调用。</p>
 *
 * <pre>{@code
 * if (GuildAPI.isReady()) {
 *     GuildAPI.GuildView g = GuildAPI.guildOf(player.getUniqueId());
 *     int level = GuildAPI.level(g.id());
 * }
 * }</pre>
 */
public final class GuildAPI {

    private static volatile LiuGuild plugin;

    private GuildAPI() {
    }

    public static void init(LiuGuild instance) {
        plugin = instance;
    }

    public static boolean isReady() {
        return plugin != null && plugin.isEnabled() && plugin.guilds() != null;
    }

    // =====================================================================
    // 工会查询
    // =====================================================================

    /** 不依赖 Bukkit 的只读视图。 */
    public record GuildView(String id, String name, String tag, String leaderName, String icon,
                            int level, long points, int members, int memberLimit,
                            int wins, int losses, long bank, boolean open, String notice) {
    }

    public static GuildView guildById(String guildId) {
        Guild g = raw(guildId);
        return g == null ? null : view(g);
    }

    public static GuildView guildByName(String name) {
        return !isReady() ? null : view(plugin.guilds().guildByName(name));
    }

    public static GuildView guildOf(UUID player) {
        return !isReady() || player == null ? null : view(plugin.guilds().guildOf(player));
    }

    public static boolean isInGuild(UUID player) {
        return isReady() && player != null && plugin.guilds().isInGuild(player);
    }

    public static boolean sameGuild(UUID a, UUID b) {
        if (!isReady() || a == null || b == null) {
            return false;
        }
        Guild ga = plugin.guilds().guildOf(a);
        return ga != null && plugin.guilds().guildOf(b) == ga;
    }

    public static int guildCount() {
        return isReady() ? plugin.guilds().guildCount() : 0;
    }

    public static List<GuildView> top(String metric, int limit) {
        if (!isReady()) {
            return List.of();
        }
        List<GuildView> out = new ArrayList<>();
        for (Guild g : plugin.guilds().top(metric, limit)) {
            out.add(view(g));
        }
        return out;
    }

    // =====================================================================
    // 成员查询
    // =====================================================================

    public record MemberView(UUID uuid, String name, GuildRank rank, long contributed, int kills, int deaths, long joinedAt) {
    }

    public static List<MemberView> members(String guildId) {
        Guild g = raw(guildId);
        if (g == null) {
            return List.of();
        }
        List<MemberView> out = new ArrayList<>();
        for (GuildMember m : g.members()) {
            out.add(new MemberView(m.uuid(), m.name(), m.rank(), m.contributed(), m.kills(), m.deaths(), m.joinedAt()));
        }
        return out;
    }

    public static GuildRank rankOf(UUID player) {
        if (!isReady() || player == null) {
            return null;
        }
        Guild g = plugin.guilds().guildOf(player);
        GuildMember m = g == null ? null : g.member(player);
        return m == null ? null : m.rank();
    }

    public static int onlineMembers(String guildId) {
        Guild g = raw(guildId);
        return g == null || !isReady() ? 0 : plugin.guilds().onlineMembers(g).size();
    }

    // =====================================================================
    // 写入（异步）
    // =====================================================================

    public static void addPoints(String guildId, long delta) {
        Guild g = raw(guildId);
        if (g != null) {
            plugin.guilds().addPoints(g, delta);
        }
    }

    public static void addBank(String guildId, long delta) {
        Guild g = raw(guildId);
        if (g == null) {
            return;
        }
        plugin.async(() -> {
            try {
                if (plugin.repo().addBank(guildId, delta)) {
                    plugin.guilds().reloadGuild(guildId);
                }
            } catch (Exception ignored) {
            }
        });
    }

    public static void sendGuildMessage(String guildId, Component message) {
        if (!isReady() || message == null) {
            return;
        }
        plugin.guilds().notifyGuild(guildId, message, null);
        plugin.guilds().guildBroadcast(guildId, message, null);
    }

    // =====================================================================
    // 工会战
    // =====================================================================

    public record WarView(String id, String guildA, String guildB, String nameA, String nameB,
                          String state, int scoreA, int scoreB, String winner, String hostServer) {
    }

    public static WarView warOf(String guildId) {
        if (!isReady() || plugin.war() == null) {
            return null;
        }
        GuildWar w = plugin.war().warOf(guildId);
        return w == null ? null : new WarView(w.id(), w.guildA(), w.guildB(), w.nameA(), w.nameB(),
                w.state().name(), w.scoreA(), w.scoreB(), w.winner(), w.hostServer());
    }

    public static boolean isInWar(UUID player) {
        return isReady() && plugin.war() != null && plugin.war().isInWar(player);
    }

    // =====================================================================
    // 内部
    // =====================================================================

    private static Guild raw(String guildId) {
        if (!isReady() || guildId == null) {
            return null;
        }
        return plugin.guilds().guild(guildId);
    }

    private static GuildView view(Guild g) {
        if (g == null) {
            return null;
        }
        int limit = plugin.settings().guild.memberLimit(g.level());
        return new GuildView(g.id(), g.name(), g.tag(), g.leaderName(), g.icon(),
                g.level(), g.points(), g.memberCount(), limit, g.wins(), g.losses(), g.bank(), g.open(), g.notice());
    }

    /** 主类实例（高级用法）。 */
    public static LiuGuild plugin() {
        return plugin;
    }

    /** 在线玩家列表（BungeeCord 代理名）。 */
    public static Collection<? extends Player> onlinePlayers() {
        return isReady() ? plugin.getServer().getOnlinePlayers() : List.of();
    }
}
