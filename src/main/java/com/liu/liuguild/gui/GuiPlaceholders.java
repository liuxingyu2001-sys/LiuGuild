package com.liu.liuguild.gui;

import com.liu.liuguild.LiuGuild;
import com.liu.liuguild.model.Guild;
import com.liu.liuguild.model.GuildMember;
import com.liu.liuguild.util.Text;
import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.Map;

/** GUI 占位符构建：基础/详情/主菜单三套，避免 GuiManager 继续膨胀。 */
final class GuiPlaceholders {

    private final LiuGuild plugin;

    GuiPlaceholders(LiuGuild plugin) {
        this.plugin = plugin;
    }

    Map<String, String> placeholders(Guild g) {
        Map<String, String> ph = new LinkedHashMap<>();
        if (g == null) {
            return ph;
        }
        ph.put("guild", g.name());
        ph.put("tag", g.tag());
        ph.put("leader", g.leaderName());
        ph.put("level", String.valueOf(g.level()));
        ph.put("max_level", String.valueOf(plugin.settings().guild.maxLevel));
        ph.put("points", String.valueOf(g.points()));
        ph.put("members", String.valueOf(g.memberCount()));
        ph.put("member_limit", String.valueOf(plugin.settings().guild.memberLimit(g.level())));
        ph.put("wins", String.valueOf(g.wins()));
        ph.put("losses", String.valueOf(g.losses()));
        ph.put("bank", plugin.economy().format(g.bank()));
        ph.put("home_slots", String.valueOf(plugin.settings().guild.homeSlots(g.level())));
        ph.put("notice", g.notice().isBlank() ? "无" : Text.sanitizeToMini(g.notice()));
        ph.put("description", g.description().isBlank() ? "无" : Text.sanitizeToMini(g.description()));
        ph.put("icon", g.icon());
        ph.put("open", g.open() ? "开放" : "邀请");
        ph.put("pvp", plugin.guilds().allowsMemberPvp(g) ? "开启" : "关闭");
        return ph;
    }

    /**
     * 工会详情面板的扩展占位符（胜率 / 在线 / 升级进度 / 建会时间等）。
     * 仅详情面板使用，避免主菜单每帧都算这些。
     */
    Map<String, String> detailPlaceholders(Guild g) {
        Map<String, String> ph = placeholders(g);
        if (g == null) {
            ph.put("total_wars", "0");
            ph.put("win_rate", "0%");
            ph.put("online", "0");
            ph.put("next_level_points", "-");
            ph.put("level_progress", "-");
            ph.put("created", "-");
            return ph;
        }
        int total = g.wins() + g.losses();
        ph.put("total_wars", String.valueOf(total));
        ph.put("win_rate", total == 0 ? "暂无战绩" : (Math.round(g.wins() * 1000.0 / total) / 10.0) + "%");
        ph.put("online", String.valueOf(plugin.guilds().onlineMemberCount(g)));
        long need = plugin.settings().guild.requiredPoints(g.level());
        boolean maxed = g.level() >= plugin.settings().guild.maxLevel;
        ph.put("next_level_points", maxed ? "已满级" : String.valueOf(need));
        ph.put("level_progress", maxed ? "已满级"
                : g.points() + " / " + need + "（还需 " + Math.max(0, need - g.points()) + "）");
        ph.put("created", com.liu.liuguild.util.TimeUtil.formatDate(g.createdAt()));
        return ph;
    }

    /** 主菜单占位符（含与具体玩家相关的签到状态）；内置 GUI 与 Sparrow UI 共用。 */
    Map<String, String> mainPlaceholders(Player player, Guild g) {
        Map<String, String> ph = placeholders(g);
        GuildMember self = (g == null || player == null) ? null : g.member(player.getUniqueId());
        ph.put("checkin_streak", String.valueOf(self == null ? 0 : self.checkinStreak()));
        ph.put("checkin_state", !plugin.settings().guild.checkinEnabled ? "未开启"
                : (self != null && com.liu.liuguild.config.GuildSettings.alreadyCheckedIn(
                        com.liu.liuguild.util.TimeUtil.todayEpochDay(), self.lastCheckin()) ? "已签到" : "未签到"));
        double checkinCost = plugin.settings().guild.checkinCost;
        long checkinGuildCost = plugin.settings().guild.checkinGuildCost;
        StringBuilder cost = new StringBuilder();
        if (checkinCost > 0) {
            cost.append(plugin.economy().format(checkinCost));
        }
        if (checkinGuildCost > 0) {
            if (cost.length() > 0) {
                cost.append(" + ");
            }
            cost.append(checkinGuildCost).append(" 工会资金");
        }
        ph.put("checkin_cost", cost.length() == 0 ? "免费" : cost.toString());
        ph.put("chat_state", plugin.guildChat() == null || !plugin.guildChat().available() ? "不可用"
                : (plugin.guildChat().inGuildChannel(player) ? "已开启" : "已关闭"));
        return ph;
    }

    static Object[] flatten(Map<String, String> ph) {
        Object[] arr = new Object[ph.size() * 2];
        int i = 0;
        for (Map.Entry<String, String> e : ph.entrySet()) {
            arr[i++] = e.getKey();
            arr[i++] = e.getValue();
        }
        return arr;
    }
}
