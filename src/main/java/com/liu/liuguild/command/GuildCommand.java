package com.liu.liuguild.command;

import com.liu.liuguild.LiuGuild;
import com.liu.liuguild.model.Guild;
import com.liu.liuguild.model.GuildRank;
import com.liu.liuguild.model.GuildWar;
import com.liu.liuguild.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** 玩家指令 {@code /guild}。 */
public final class GuildCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = List.of(
            "help", "gui", "create", "disband", "join", "leave", "invite", "accept", "deny",
            "kick", "promote", "demote", "transfer", "seticon", "set", "bank", "home", "info", "list", "top", "war", "chat");

    private final LiuGuild plugin;

    public GuildCommand(LiuGuild plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "players-only");
            return true;
        }
        if (!player.hasPermission("liuguild.use")) {
            plugin.messages().send(player, "no-permission");
            return true;
        }
        if (args.length == 0) {
            plugin.gui().openMain(player);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "help" -> help(player);
            case "gui", "menu" -> plugin.gui().openMain(player);
            case "create" -> create(player, args);
            case "disband" -> disband(player, args);
            case "join" -> join(player, args);
            case "leave" -> plugin.guilds().leave(player);
            case "invite" -> invite(player, args);
            case "accept" -> accept(player, args);
            case "deny" -> deny(player, args);
            case "kick" -> kick(player, args);
            case "promote" -> promote(player, args, true);
            case "demote" -> promote(player, args, false);
            case "transfer" -> transfer(player, args);
            case "seticon", "icon" -> setIcon(player, args);
            case "set" -> set(player, args);
            case "bank" -> bank(player, args);
            case "home", "hq" -> {
                if (plugin.guildHome() == null) {
                    plugin.messages().send(player, "home-unavailable");
                } else {
                    plugin.guildHome().command(player, args);
                }
            }
            case "info" -> info(player, args);
            case "list" -> list(player, args);
            case "top" -> top(player, args);
            case "chat", "c" -> plugin.guildChat().chat(player, args.length > 1
                    ? String.join(" ", Arrays.copyOfRange(args, 1, args.length)) : "");
            case "war" -> war(player, args);
            default -> plugin.messages().send(player, "unknown-command");
        }
        return true;
    }

    // ------------------------------------------------------------------

    private void help(Player player) {
        plugin.messages().sendRaw(player, "help-header");
        helpLine(player, "基础", "/guild create <名称> [标签]", "创建一个新工会");
        helpLine(player, "基础", "/guild join <名称>", "加入一个开放加入的工会");
        helpLine(player, "基础", "/guild invite <玩家>", "邀请玩家加入工会");
        helpLine(player, "基础", "/guild leave", "退出当前工会");
        helpLine(player, "聊天", "/guild chat [消息]", "切换工会频道或发送单条消息");
        helpLine(player, "成员", "/guild promote <玩家>", "提升成员职位");
        helpLine(player, "成员", "/guild kick <玩家>", "踢出成员");
        helpLine(player, "成员", "/guild transfer <玩家>", "转让会长");
        helpLine(player, "设置", "/guild seticon <图标>", "更换工会图标");
        helpLine(player, "设置", "/guild set notice <内容>", "修改工会公告");
        helpLine(player, "银行", "/guild bank deposit <金额>", "存入工会银行");
        helpLine(player, "驻地", "/guild home [槽位]", "传送到工会驻地（需 HuskHomes）");
        helpLine(player, "驻地", "/guild home set [槽位]", "把当前位置设为工会驻地");
        helpLine(player, "驻地", "/guild home del [槽位]", "删除工会驻地");
        helpLine(player, "信息", "/guild info", "查看工会信息");
        helpLine(player, "信息", "/guild list", "浏览全服工会");
        helpLine(player, "信息", "/guild top points", "查看排行榜");
        helpLine(player, "工会战", "/guild war queue", "参与匹配");
        helpLine(player, "工会战", "/guild war status", "查看当前战况");
        helpLine(player, "菜单", "/guild gui", "打开工会图形菜单");
    }

    private void helpLine(Player player, String group, String cmd, String desc) {
        Component line = Text.mm("<dark_gray>▸ <yellow>[" + group + "] <white>" + cmd + " <dark_gray>- <gray>" + desc)
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.suggestCommand(cmd))
                .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(Text.mm("<gray>点击自动填入：<white>" + cmd)));
        player.sendMessage(line);
    }

    private void create(Player player, String[] args) {
        if (!player.hasPermission("liuguild.create")) {
            plugin.messages().send(player, "no-permission");
            return;
        }
        if (args.length < 2) {
            plugin.messages().send(player, "create-usage");
            return;
        }
        String name = args[1];
        String tag = args.length >= 3 ? args[2] : name;
        plugin.guilds().create(player, name, tag);
    }

    private void disband(Player player, String[] args) {
        Guild g = plugin.guilds().guildOf(player.getUniqueId());
        if (g == null) {
            plugin.messages().send(player, "not-in-guild");
            return;
        }
        if (g.leaderUuid() == null || !g.leaderUuid().equals(player.getUniqueId())) {
            plugin.messages().send(player, "no-permission-rank");
            return;
        }
        if (args.length < 2 || !args[1].equalsIgnoreCase("confirm")) {
            plugin.messages().send(player, "disband-confirm");
            return;
        }
        plugin.guilds().disband(g, true);
    }

    private void join(Player player, String[] args) {
        if (args.length < 2) {
            plugin.messages().send(player, "join-usage");
            return;
        }
        Guild g = plugin.guilds().guildByName(args[1]);
        if (g == null) {
            plugin.messages().send(player, "guild-not-found", "name", args[1]);
            return;
        }
        plugin.guilds().join(player, g, false);
    }

    private void invite(Player player, String[] args) {
        if (args.length < 2) {
            plugin.messages().send(player, "invite-usage");
            return;
        }
        Guild g = plugin.guilds().guildOf(player.getUniqueId());
        if (g == null) {
            plugin.messages().send(player, "not-in-guild");
            return;
        }
        plugin.guilds().invite(player, g, args[1]);
    }

    private void accept(Player player, String[] args) {
        if (args.length < 2) {
            plugin.messages().send(player, "invite-none");
            return;
        }
        plugin.guilds().accept(player, args[1]);
    }

    private void deny(Player player, String[] args) {
        if (args.length < 2) {
            plugin.messages().send(player, "invite-none");
            return;
        }
        plugin.guilds().deny(player, args[1]);
    }

    private void kick(Player player, String[] args) {
        Guild g = plugin.guilds().guildOf(player.getUniqueId());
        if (g == null) {
            plugin.messages().send(player, "not-in-guild");
            return;
        }
        if (args.length < 2) {
            plugin.messages().send(player, "kick-usage");
            return;
        }
        Player target = plugin.playerExact(args[1]);
        if (target == null) {
            java.util.UUID id = null;
            for (com.liu.liuguild.model.GuildMember m : g.members()) {
                if (m.name().equalsIgnoreCase(args[1])) {
                    id = m.uuid();
                    break;
                }
            }
            if (id == null) {
                plugin.messages().send(player, "player-not-found", "player", args[1]);
                return;
            }
            plugin.guilds().kick(player, g, id, args[1]);
            return;
        }
        plugin.guilds().kick(player, g, target.getUniqueId(), target.getName());
    }

    private void promote(Player player, String[] args, boolean up) {
        Guild g = plugin.guilds().guildOf(player.getUniqueId());
        if (g == null) {
            plugin.messages().send(player, "not-in-guild");
            return;
        }
        if (args.length < 2) {
            plugin.messages().send(player, "player-not-found", "player", "?");
            return;
        }
        Player target = plugin.playerExact(args[1]);
        if (target == null) {
            plugin.messages().send(player, "player-not-found", "player", args[1]);
            return;
        }
        if (up) {
            plugin.guilds().promote(player, g, target.getUniqueId(), target.getName());
        } else {
            plugin.guilds().demote(player, g, target.getUniqueId(), target.getName());
        }
    }

    private void transfer(Player player, String[] args) {
        Guild g = plugin.guilds().guildOf(player.getUniqueId());
        if (g == null) {
            plugin.messages().send(player, "not-in-guild");
            return;
        }
        if (args.length < 2) {
            plugin.messages().send(player, "player-not-found", "player", "?");
            return;
        }
        Player target = plugin.playerExact(args[1]);
        if (target == null) {
            plugin.messages().send(player, "player-not-found", "player", args[1]);
            return;
        }
        plugin.guilds().transfer(player, g, target.getUniqueId(), target.getName());
    }

    private void setIcon(Player player, String[] args) {
        Guild g = plugin.guilds().guildOf(player.getUniqueId());
        if (g == null) {
            plugin.messages().send(player, "not-in-guild");
            return;
        }
        if (args.length < 2) {
            plugin.messages().send(player, "set-usage");
            return;
        }
        plugin.guilds().setIcon(player, g, args[1]);
    }

    private void set(Player player, String[] args) {
        Guild g = plugin.guilds().guildOf(player.getUniqueId());
        if (g == null) {
            plugin.messages().send(player, "not-in-guild");
            return;
        }
        if (!plugin.guilds().canManageSettings(player, g) || args.length < 3) {
            plugin.messages().send(player, "set-usage");
            return;
        }
        String key = args[1].toLowerCase(Locale.ROOT);
        String value = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        switch (key) {
            case "open" -> {
                boolean open = value.equalsIgnoreCase("true") || value.equalsIgnoreCase("开放")
                        || value.equalsIgnoreCase("on") || value.equalsIgnoreCase("1");
                g.open(open);
                plugin.guilds().saveGuild(g);
                plugin.messages().send(player, "set-open", "value", open ? "开放" : "邀请");
            }
            case "notice" -> {
                plugin.guilds().setNotice(player, g, value);
            }
            case "desc", "description" -> {
                plugin.guilds().setDescription(player, g, value);
            }
            default -> plugin.messages().send(player, "set-usage");
        }
    }

    private void bank(Player player, String[] args) {
        Guild g = plugin.guilds().guildOf(player.getUniqueId());
        if (g == null) {
            plugin.messages().send(player, "not-in-guild");
            return;
        }
        if (args.length < 3) {
            plugin.messages().send(player, "bank-usage");
            return;
        }
        double amount = com.liu.liuguild.util.NumberUtil.parseAmount(args[2]);
        if (amount <= 0) {
            plugin.messages().send(player, "input-invalid-number");
            return;
        }
        if (args[1].equalsIgnoreCase("deposit")) {
            plugin.guilds().deposit(player, g, amount);
        } else if (args[1].equalsIgnoreCase("withdraw")) {
            plugin.guilds().withdraw(player, g, amount);
        } else {
            plugin.messages().send(player, "bank-usage");
        }
    }

    private void info(Player player, String[] args) {
        Guild g = args.length >= 2 ? plugin.guilds().guildByName(args[1]) : plugin.guilds().guildOf(player.getUniqueId());
        if (g == null) {
            plugin.messages().send(player, "not-in-guild");
            return;
        }
        plugin.messages().sendRaw(player, "info-header", "tag", g.tag(), "guild", g.name());
        plugin.messages().sendRaw(player, "info-body", "leader", g.leaderName(), "level", g.level(),
                "members", g.memberCount(), "max", plugin.settings().guild.memberLimit(g.level()));
        plugin.messages().sendRaw(player, "info-points", "points", g.points(), "wins", g.wins(),
                "losses", g.losses(), "bank", plugin.economy().format(g.bank()));
        plugin.messages().sendRawRich(player, "info-notice", "notice", g.notice().isBlank() ? "无" : Text.sanitizeToMini(g.notice()));
    }

    private void list(Player player, String[] args) {
        int page = args.length >= 2 ? Math.max(1, parse(args[1], 1)) : 1;
        List<Guild> guilds = new ArrayList<>(plugin.guilds().all());
        guilds.sort((a, b) -> Long.compare(b.points(), a.points()));
        int perPage = 10;
        int maxPage = Math.max(1, (guilds.size() + perPage - 1) / perPage);
        page = Math.min(page, maxPage);
        int from = (page - 1) * perPage;
        plugin.messages().sendRaw(player, "list-header", "count", guilds.size());
        for (int i = 0; i < perPage && from + i < guilds.size(); i++) {
            Guild g = guilds.get(from + i);
            plugin.messages().sendRaw(player, "list-line", "guild", g.name(), "tag", g.tag(),
                    "leader", g.leaderName(), "members", g.memberCount());
        }
        player.sendMessage(Text.mm("<gray>第 <white>" + page + "<gray>/<white>" + maxPage + " <gray>页  "
                + nav("上一页", page > 1, page - 1)
                + nav("下一页", page < maxPage, page + 1)));
    }

    private static String nav(String label, boolean enabled, int page) {
        if (!enabled) {
            return "<dark_gray>[" + label + "] ";
        }
        return "<click:run_command:'/guild list " + page + "'><yellow>[" + label + "]</yellow></click> <gray>";
    }

    private void top(Player player, String[] args) {
        String category = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "points";
        String column = switch (category) {
            case "bank" -> "bank";
            case "level" -> "level";
            case "wins" -> "wins";
            default -> "points";
        };
        List<Guild> list = plugin.guilds().top(column, 10);
        plugin.messages().sendRaw(player, "top-header", "category", category);
        int rank = 1;
        for (Guild g : list) {
            String value = switch (column) {
                case "bank" -> plugin.economy().format(g.bank());
                case "level" -> String.valueOf(g.level());
                case "wins" -> String.valueOf(g.wins());
                default -> String.valueOf(g.points());
            };
            plugin.messages().sendRaw(player, "top-line", "rank", rank++, "guild", g.name(), "value", value);
        }
        player.sendMessage(Text.mm("<gray>可查看：<click:suggest_command:'/guild top points'><white>points</white></click> "
                + "<click:suggest_command:'/guild top bank'><white>bank</white></click> "
                + "<click:suggest_command:'/guild top level'><white>level</white></click> "
                + "<click:suggest_command:'/guild top wins'><white>wins</white></click>"));
    }

    private void war(Player player, String[] args) {
        Guild g = plugin.guilds().guildOf(player.getUniqueId());
        String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "status";
        if (!player.hasPermission("liuguild.war")) {
            plugin.messages().send(player, "no-permission");
            return;
        }
        switch (action) {
            case "queue" -> {
                if (g != null) {
                    String modeKey = args.length >= 3 ? args[2]
                            : (plugin.war().queuedMode(g.id()) != null ? plugin.war().queuedMode(g.id()) : plugin.settings().war.defaultMode);
                    plugin.war().queue(player, g, modeKey);
                }
            }
            case "leave" -> {
                if (g != null) {
                    plugin.war().leaveQueue(player, g);
                }
            }
            case "join" -> plugin.war().join(player);
            case "spectate" -> plugin.war().spectate(player);
            case "history" -> {
                if (g != null) {
                    plugin.war().historyAsync(g.id(), 10, list -> {
                        if (list.isEmpty()) {
                            plugin.messages().send(player, "war-history-empty");
                            return;
                        }
                        plugin.messages().sendRaw(player, "war-history-header");
                        for (GuildWar w : list) {
                            boolean home = w.guildA().equals(g.id());
                            plugin.messages().sendRaw(player, "war-history-line",
                                    "enemy", home ? w.nameB() : w.nameA(),
                                    "self", home ? w.scoreA() : w.scoreB(),
                                    "opp", home ? w.scoreB() : w.scoreA(),
                                    "result", w.winner() == null || w.winner().isBlank() ? "平局"
                                            : (w.winner().equals(g.id()) ? "胜利" : "失败"));
                        }
                    });
                }
            }
            default -> {
                GuildWar w = g == null ? null : plugin.war().warOf(g.id());
                if (w == null) {
                    plugin.messages().send(player, "war-not-in-war");
                } else {
                    boolean home = w.guildA().equals(g.id());
                    plugin.messages().sendRaw(player, "war-score", "guild_a", w.nameA(), "guild_b", w.nameB(),
                            "score_a", w.scoreA(), "score_b", w.scoreB());
                    if (w.state() == com.liu.liuguild.model.WarState.ACTIVE && plugin.war().phaseSecondsLeft() >= 0) {
                        player.sendMessage(Text.mm("<gray>剩余 " + plugin.war().phaseSecondsLeft() + " 秒"));
                    }
                }
            }
        }
    }

    private static int parse(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return def;
        }
    }

    // ------------------------------------------------------------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(SUBS, args[0]);
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("join")) {
                List<String> names = new ArrayList<>();
                plugin.guilds().all().forEach(g -> names.add(g.name()));
                return filter(names, args[1]);
            }
            if (List.of("invite", "kick", "promote", "demote", "transfer").contains(sub)) {
                List<String> names = new ArrayList<>();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    names.add(p.getName());
                }
                return filter(names, args[1]);
            }
            if (sub.equals("seticon")) {
                return filter(new ArrayList<>(plugin.settings().icons.icons.keySet()), args[1]);
            }
            if (sub.equals("war")) {
                return filter(List.of("queue", "leave", "join", "spectate", "status", "history"), args[1]);
            }
            if (sub.equals("set")) {
                return filter(List.of("open", "notice", "desc"), args[1]);
            }
            if (sub.equals("bank")) {
                return filter(List.of("deposit", "withdraw"), args[1]);
            }
            if (sub.equals("home") || sub.equals("hq")) {
                return filter(List.of("set", "del", "list"), args[1]);
            }
            if (sub.equals("top")) {
                return filter(List.of("points", "bank", "level", "wins"), args[1]);
            }
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        List<String> out = new ArrayList<>();
        String p = prefix.toLowerCase(Locale.ROOT);
        for (String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(p)) {
                out.add(o);
            }
        }
        return out;
    }
}
