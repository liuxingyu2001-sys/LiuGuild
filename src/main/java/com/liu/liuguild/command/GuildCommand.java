package com.liu.liuguild.command;

import com.liu.liuguild.LiuGuild;
import com.liu.liuguild.model.Guild;
import com.liu.liuguild.model.GuildMember;
import com.liu.liuguild.model.GuildRank;
import com.liu.liuguild.model.GuildTitle;
import com.liu.liuguild.model.GuildWar;
import com.liu.liuguild.title.TitleService;
import com.liu.liuguild.util.NumberUtil;
import com.liu.liuguild.util.Text;
import org.bukkit.inventory.ItemStack;
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
            // 主命令
            "help", "menu", "gui", "create", "disband", "join", "leave", "invite", "accept", "deny",
            "kick", "promote", "demote", "transfer", "members", "seticon", "icon", "rename", "set", "bank",
            "home", "hq", "info", "list", "top", "war", "chat", "c", "checkin", "sign", "qiandao",
            "residence", "res", "lingdi", "warehouse", "wh", "cangku", "title", "titles", "chenghao",
            "shop", "shangdian", "store");

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
            case "members" -> {
                Guild g = plugin.guilds().guildOf(player.getUniqueId());
                if (g == null) {
                    plugin.messages().send(player, "not-in-guild");
                } else {
                    plugin.gui().openMembers(player, g, 0);
                }
            }
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
            case "checkin", "sign", "qiandao" -> plugin.guilds().checkin(player);
            case "res", "residence", "lingdi" -> residence(player, args);
            case "warehouse", "wh", "cangku" -> {
                Guild g = plugin.guilds().guildOf(player.getUniqueId());
                if (g == null) {
                    plugin.messages().send(player, "not-in-guild");
                    break;
                }
                if (!player.hasPermission("liuguild.warehouse")) {
                    plugin.messages().send(player, "no-permission");
                    break;
                }
                if (plugin.warehouse() == null) {
                    plugin.messages().send(player, "wh-disabled");
                    break;
                }
                String whSub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
                switch (whSub) {
                    case "rent", "buy", "xufei", "续费", "购买" -> plugin.warehouse().rent(player, g,
                            args.length > 2 ? com.liu.liuguild.util.NumberUtil.toInt(args[2], -1) : -1);
                    case "upgrade", "up", "shengji", "升级" -> plugin.warehouse().upgrade(player, g);
                    case "info", "menu", "manage", "管理" -> plugin.warehouse().openMenu(player, g);
                    default -> plugin.warehouse().open(player, g);
                }
            }
            case "title", "titles", "chenghao" -> title(player, args);
            case "shop", "shangdian", "store" -> shop(player, args);
            case "rename" -> rename(player, args);
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
        helpLine(player, "基础", "/guild accept [工会]", "接受收到的入会邀请");
        helpLine(player, "基础", "/guild deny [工会]", "拒绝收到的入会邀请");
        helpLine(player, "基础", "/guild invite <玩家>", "邀请玩家加入工会");
        helpLine(player, "基础", "/guild leave", "退出当前工会");
        helpLine(player, "基础", "/guild disband", "解散工会（仅会长，需确认）");
        helpLine(player, "聊天", "/guild chat [消息]", "切换工会频道或发送单条消息");
        helpLine(player, "成员", "/guild members", "查看成员列表");
        helpLine(player, "成员", "/guild promote <玩家>", "提升成员职位");
        helpLine(player, "成员", "/guild demote <玩家>", "降低成员职位");
        helpLine(player, "成员", "/guild kick <玩家>", "踢出成员");
        helpLine(player, "成员", "/guild transfer <玩家>", "转让会长");
        helpLine(player, "设置", "/guild seticon <图标>", "更换工会图标");
        helpLine(player, "设置", "/guild rename <新名称> [新标签]", "给工会改名字");
        helpLine(player, "设置", "/guild set notice <内容>", "修改工会公告");
        helpLine(player, "设置", "/guild set desc <内容>", "修改工会简介");
        helpLine(player, "设置", "/guild set open <true|false>", "切换开放加入 / 邀请制");
        helpLine(player, "银行", "/guild bank deposit <金额>", "存入工会银行");
        helpLine(player, "银行", "/guild bank withdraw <金额>", "从工会银行取出");
        helpLine(player, "驻地", "/guild home [槽位]", "传送到工会驻地（需 HuskHomes）");
        helpLine(player, "驻地", "/guild home set [槽位]", "把当前位置设为工会驻地");
        helpLine(player, "驻地", "/guild home del [槽位]", "删除工会驻地");
        helpLine(player, "日常", "/guild checkin", "每日签到领取工会资金");
        helpLine(player, "领地", "/guild residence list", "列出自己的领地（需 Residence）");
        helpLine(player, "领地", "/guild residence share <领地>", "把自己的领地在工会内共享");
        helpLine(player, "领地", "/guild residence unshare <领地>", "取消领地共享");
        helpLine(player, "仓库", "/guild warehouse", "打开工会共享仓库（同一时间仅一人）");
        helpLine(player, "仓库", "/guild warehouse rent <天数>", "按空间配置购买时长；显式输入0购买永久使用权");
        helpLine(player, "仓库", "/guild warehouse upgrade", "用工会资金升级仓库容量");
        helpLine(player, "头衔", "/guild title", "打开头衔面板 / 装备自定义头衔");
        helpLine(player, "商店", "/guild shop", "出售库存商品，收入进入工会金币账户");
        helpLine(player, "信息", "/guild info", "查看工会信息");
        helpLine(player, "信息", "/guild list", "浏览全服工会");
        helpLine(player, "信息", "/guild top points", "查看排行榜（points/level/wins/losses/members）");
        helpLine(player, "工会战", "/guild war queue", "参与匹配");
        helpLine(player, "工会战", "/guild war status", "查看当前战况");
        helpLine(player, "工会战", "/guild war spectate", "旁观进行中的工会战");
        helpLine(player, "工会战", "/guild war history", "查看历史战绩");
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

    /** /guild rename <新名称> [新标签] —— 仅会长，受开关/冷却/费用限制。 */
    private void rename(Player player, String[] args) {
        Guild g = plugin.guilds().guildOf(player.getUniqueId());
        if (g == null) {
            plugin.messages().send(player, "not-in-guild");
            return;
        }
        if (args.length < 2) {
            plugin.messages().send(player, "rename-usage");
            return;
        }
        String newName = args[1];
        String newTag = args.length > 2 ? args[2] : null;
        plugin.guilds().rename(player, g, newName, newTag);
    }

    private void bank(Player player, String[] args) {        Guild g = plugin.guilds().guildOf(player.getUniqueId());
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
            case "losses" -> "losses";
            case "members" -> "members";
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
                case "losses" -> String.valueOf(g.losses());
                case "members" -> String.valueOf(g.memberCount());
                default -> String.valueOf(g.points());
            };
            plugin.messages().sendRaw(player, "top-line", "rank", rank++, "guild", g.name(), "value", value);
        }
        player.sendMessage(Text.mm("<gray>可查看：<click:suggest_command:'/guild top points'><white>points</white></click> "
                + "<click:suggest_command:'/guild top bank'><white>bank</white></click> "
                + "<click:suggest_command:'/guild top level'><white>level</white></click> "
                + "<click:suggest_command:'/guild top members'><white>members</white></click> "
                + "<click:suggest_command:'/guild top wins'><white>wins</white></click> "
                + "<click:suggest_command:'/guild top losses'><white>losses</white></click>"));
    }

    // =====================================================================
    // 领地共享（Residence 软依赖）
    // =====================================================================

    /**
     * /guild residence list              列出自己拥有的领地
     * /guild residence share <领地名>    把自己拥有的领地在工会内共享
     * /guild residence unshare <领地名>  取消共享
     */
    /**
     * 工会头衔：查看 / 装备 / 创建 / 删除。
     *
     * <p>不带子命令时直接开 GUI —— 文字指令在手机上/命令方块里太难用。</p>
     */
    private void title(Player player, String[] args) {
        if (plugin.titles() == null || !plugin.settings().guild.titlesEnabled) {
            plugin.messages().send(player, "title-disabled");
            return;
        }
        Guild g = plugin.guilds().guildOf(player.getUniqueId());
        if (g == null) {
            plugin.messages().send(player, "not-in-guild");
            return;
        }
        String action = args.length >= 2 ? args[1].toLowerCase(java.util.Locale.ROOT) : "gui";
        switch (action) {
            case "gui", "menu" -> plugin.gui().openTitles(player, g);
            case "list" -> listTitles(player, g);
            case "set", "equip" -> {
                if (args.length < 3) {
                    plugin.messages().send(player, "title-usage");
                    return;
                }
                GuildMember m = g.member(player.getUniqueId());
                if (m == null) {
                    plugin.messages().send(player, "not-in-guild");
                    return;
                }
                String err = plugin.titles().equip(g, m, args[2].toLowerCase(java.util.Locale.ROOT));
                if (err != null) {
                    plugin.messages().send(player, err, "id", args[2]);
                    return;
                }
                GuildTitle t = plugin.titles().equipped(player.getUniqueId());
                plugin.messages().send(player, "title-equipped",
                        "display", t == null ? args[2] : t.display());
                announce(g, "title-broadcast-equip", player, "display",
                        t == null ? args[2] : t.display());
            }
            case "clear", "unequip", "off" -> {
                if (plugin.titles().equippedId(player.getUniqueId()) == null) {
                    plugin.messages().send(player, "title-none");
                    return;
                }
                plugin.titles().unequip(g, player.getUniqueId());
                plugin.messages().send(player, "title-unequipped");
            }
            case "create", "add", "new" -> createTitle(player, g, args);
            case "delete", "del" -> {
                if (!canManageTitles(player, g)) {
                    plugin.messages().send(player, "title-no-permission");
                    return;
                }
                if (args.length < 3) {
                    plugin.messages().send(player, "title-usage");
                    return;
                }
                String id = args[2].toLowerCase(java.util.Locale.ROOT);
                if (!plugin.titles().delete(g.id(), id)) {
                    plugin.messages().send(player, "title-not-found");
                    return;
                }
                plugin.messages().send(player, "title-deleted", "id", id);
                announce(g, "title-broadcast-delete", player, "id", id);
            }
            default -> plugin.messages().send(player, "title-usage");
        }
    }

    /** 列出工会头衔，标出自己够格/已装备的。 */
    private void listTitles(Player player, Guild g) {
        GuildMember me = g.member(player.getUniqueId());
        GuildRank rank = me == null ? GuildRank.MEMBER : me.rank();
        String equipped = plugin.titles().equippedId(player.getUniqueId());
        java.util.List<GuildTitle> all = plugin.titles().titlesOf(g.id());
        if (all.isEmpty()) {
            plugin.messages().send(player, "title-list-empty");
            if (canManageTitles(player, g)) {
                plugin.messages().send(player, "title-create-hint");
            }
            return;
        }
        plugin.messages().sendRaw(player, "title-list-header",
                "count", String.valueOf(all.size()),
                "max", String.valueOf(plugin.settings().guild.titlesMax));
        for (GuildTitle t : all) {
            if (t.id().equals(equipped)) {
                plugin.messages().sendRaw(player, "title-list-line-equipped",
                        "display", t.display(), "id", t.id());
            } else if (t.usableBy(rank)) {
                plugin.messages().sendRaw(player, "title-list-line",
                        "display", t.display(), "id", t.id());
            } else {
                plugin.messages().sendRaw(player, "title-list-line-req",
                        "display", t.display(), "id", t.id(), "rank", t.minRank().display());
            }
        }
        plugin.messages().send(player, "title-list-hint");
    }

    /** `/guild title create <标识> <显示…> [最低职位]`。 */
    private void createTitle(Player player, Guild g, String[] args) {
        if (!canManageTitles(player, g)) {
            plugin.messages().send(player, "title-no-permission");
            return;
        }
        if (args.length < 4) {
            plugin.messages().send(player, "title-usage");
            return;
        }
        int max = plugin.settings().guild.titlesMax;
        String id = args[2].toLowerCase(java.util.Locale.ROOT);
        boolean updating = plugin.titles().title(g.id(), id) != null;
        if (!updating && plugin.titles().titlesOf(g.id()).size() >= max) {
            plugin.messages().send(player, "title-limit", "max", String.valueOf(max));
            return;
        }
        // 末尾若是职位名，则作为最低职位，其余全部作为显示文本
        int end = args.length;
        GuildRank minRank = GuildRank.MEMBER;
        if (end > 4) {
            GuildRank maybe = rankOf(args[end - 1]);
            if (maybe != null) {
                minRank = maybe;
                end--;
            }
        }
        String display = String.join(" ", java.util.Arrays.copyOfRange(args, 3, end));
        String err = plugin.titles().create(g.id(), id, display, minRank);
        if (err != null) {
            plugin.messages().send(player, err, "max", String.valueOf(TitleService.maxDisplayLength()));
            return;
        }
        GuildTitle t = plugin.titles().title(g.id(), id);
        String shown = t == null ? display : t.display();
        if (updating) {
            plugin.messages().send(player, "title-exists", "id", id);
        } else {
            plugin.messages().send(player, "title-created", "id", id, "display", shown);
            announce(g, "title-broadcast-create", player, "display", shown);
        }
    }

    /** 能否管理头衔：有权限节点，或职位达到配置要求。 */
    private boolean canManageTitles(Player player, Guild g) {
        if (player.hasPermission("liuguild.title.manage")) {
            return true;
        }
        GuildMember m = g.member(player.getUniqueId());
        return m != null && m.rank().atLeast(plugin.settings().guild.titlesManageRank);
    }

    private static GuildRank rankOf(String s) {
        if (s == null) {
            return null;
        }
        return switch (s.toLowerCase(java.util.Locale.ROOT)) {
            case "member", "chengyuan", "成员" -> GuildRank.MEMBER;
            case "officer", "fuhuizhang", "副会长", "官员" -> GuildRank.OFFICER;
            case "leader", "huizhang", "会长" -> GuildRank.LEADER;
            default -> null;
        };
    }

    /** 向工会内广播（仅在线成员，不跨服）。 */
    private void announce(Guild g, String key, Player actor, Object... kv) {
        for (GuildMember m : g.memberMap().values()) {
            Player p = plugin.player(m.uuid());
            if (p != null && !p.getUniqueId().equals(actor.getUniqueId())) {
                plugin.messages().send(p, key, kv);
            }
        }
    }

    /**
     * 工会商店。
     *
     * <p>不带子命令直接开 GUI；文字子命令给脚本/命令方块用。</p>
     */
    private void shop(Player player, String[] args) {
        var shop = plugin.shop();
        if (shop == null || !plugin.settings().guild.shopEnabled) {
            plugin.messages().send(player, "shop-disabled");
            return;
        }
        String action = args.length >= 2 ? args[1].toLowerCase(java.util.Locale.ROOT) : "gui";
        switch (action) {
            case "gui", "menu" -> plugin.gui().openShop(player, plugin.guilds().guildOf(player.getUniqueId()));
            case "list" -> shopList(player);
            case "add", "sell", "shangjia" -> shopAdd(player, args);
            case "remove", "del", "xiajia" -> shopRemove(player, args);
            default -> plugin.messages().send(player, "shop-usage");
        }
    }

    /** 列出本会商店。 */
    private void shopList(Player player) {
        Guild g = plugin.guilds().guildOf(player.getUniqueId());
        if (g == null) {
            plugin.messages().send(player, "not-in-guild");
            return;
        }
        java.util.List<com.liu.liuguild.model.ShopListing> list = plugin.shop().listingsOf(g.id());
        if (list.isEmpty()) {
            plugin.messages().send(player, "shop-empty");
            return;
        }
        plugin.messages().sendRaw(player, "shop-title", "guild", g.name());
        for (com.liu.liuguild.model.ShopListing l : list) {
            plugin.messages().sendRaw(player, "shop-list-line",
                    "id", l.id().substring(0, 8),
                    "item", plugin.shop().itemName(l.preview()),
                    "price", String.valueOf(l.price()),
                    "stock", String.valueOf(l.stock()),
                    "buy_price", String.valueOf(l.buyPrice()));
        }
        plugin.messages().send(player, "shop-usage");
    }

    /** `/guild shop add <售价>`：上架手持物品，不支持收购。 */
    private void shopAdd(Player player, String[] args) {
        Guild g = plugin.guilds().guildOf(player.getUniqueId());
        if (g == null) {
            plugin.messages().send(player, "not-in-guild");
            return;
        }
        if (!plugin.shop().canManage(g, player)) {
            plugin.messages().send(player, "shop-no-permission");
            return;
        }
        if (args.length != 3) {
            plugin.messages().send(player, "shop-usage");
            return;
        }
        if (!NumberUtil.isDigits(args[2])) {
            plugin.messages().send(player, "shop-invalid-number");
            return;
        }
        long price = NumberUtil.toLong(args[2], -1);
        if (price < 0) {
            plugin.messages().send(player, "shop-invalid-number");
            return;
        }
        long buyPrice = 0; // 收购永久关闭
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand == null || hand.getType().isAir()) {
            plugin.messages().send(player, "shop-hand-empty");
            return;
        }
        String err = plugin.shop().addListing(g, player, hand, hand.getAmount(), price, buyPrice);
        if (err != null) {
            plugin.messages().send(player, err,
                    "max", String.valueOf(plugin.settings().guild.shopMaxListings));
            return;
        }
        plugin.messages().send(player, "shop-listed",
                "item", plugin.shop().itemName(hand),
                "amount", String.valueOf(hand.getAmount()),
                "price", String.valueOf(price));
        announce(g, "shop-broadcast-list", player,
                "item", plugin.shop().itemName(hand),
                "amount", String.valueOf(hand.getAmount()));
    }

    /** `/guild shop remove <编号前8位>`：下架并退回剩余物品。 */
    private void shopRemove(Player player, String[] args) {
        Guild g = plugin.guilds().guildOf(player.getUniqueId());
        if (g == null) {
            plugin.messages().send(player, "not-in-guild");
            return;
        }
        if (!plugin.shop().canManage(g, player)) {
            plugin.messages().send(player, "shop-no-permission");
            return;
        }
        if (args.length < 3) {
            plugin.messages().send(player, "shop-usage");
            return;
        }
        String prefix = args[2].toLowerCase(java.util.Locale.ROOT);
        com.liu.liuguild.model.ShopListing target = null;
        for (com.liu.liuguild.model.ShopListing l : plugin.shop().listingsOf(g.id())) {
            if (l.id().startsWith(prefix)) {
                target = l;
                break;
            }
        }
        if (target == null) {
            plugin.messages().send(player, "shop-not-found");
            return;
        }
        int had = target.stock();
        String err = plugin.shop().removeListing(g, player, target);
        if (err != null) {
            plugin.messages().send(player, err, "item", "", "amount", "");
        }
    }

    private void residence(Player player, String[] args) {
        com.liu.liuguild.hook.ResidenceHook res = plugin.residence();
        if (res == null || !res.available()) {
            plugin.messages().send(player, "res-unavailable");
            return;
        }
        if (!plugin.settings().guild.residenceEnabled) {
            plugin.messages().send(player, "res-disabled");
            return;
        }
        Guild g = plugin.guilds().guildOf(player.getUniqueId());
        if (g == null) {
            plugin.messages().send(player, "not-in-guild");
            return;
        }
        String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "list";
        switch (action) {
            case "list" -> {
                java.util.List<String> owned = res.residencesOf(player);
                if (owned.isEmpty()) {
                    plugin.messages().send(player, "res-none");
                    return;
                }
                plugin.messages().sendRaw(player, "res-list-header", "guild", g.name());
                for (String name : owned) {
                    plugin.messages().sendRaw(player, "res-list-line", "name", name);
                }
            }
            case "share" -> {
                if (args.length < 3) {
                    plugin.messages().send(player, "res-usage");
                    return;
                }
                String target = args[2];
                Object claim = res.residence(target);
                if (claim == null) {
                    plugin.messages().send(player, "res-not-found", "name", target);
                    return;
                }
                if (!res.isOwner(claim, player)) {
                    plugin.messages().send(player, "res-not-owner", "name", target);
                    return;
                }
                int n = res.share(claim, g);
                if (n <= 0) {
                    plugin.messages().send(player, "res-failed");
                    return;
                }
                plugin.messages().send(player, "res-shared", "name", target,
                        "count", n, "guild", g.name());
            }
            case "unshare" -> {
                if (args.length < 3) {
                    plugin.messages().send(player, "res-usage");
                    return;
                }
                String target = args[2];
                Object claim = res.residence(target);
                if (claim == null) {
                    plugin.messages().send(player, "res-not-found", "name", target);
                    return;
                }
                if (!res.isOwner(claim, player)) {
                    plugin.messages().send(player, "res-not-owner", "name", target);
                    return;
                }
                int n = res.revoke(claim, g);
                plugin.messages().send(player, "res-unshared", "name", target, "count", n);
            }
            default -> plugin.messages().send(player, "res-usage");
        }
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
                if (g == null) {
                    plugin.messages().send(player, "not-in-guild");
                    break;
                }
                // 不复用跨服队列里的旧模式（那是阻塞 Redis 调用，且下面 queue() 会拒绝重复入队）。
                String modeKey = args.length >= 3 ? args[2] : plugin.settings().war.defaultMode;
                plugin.war().queue(player, g, modeKey);
            }
            case "leave" -> {
                if (g == null) {
                    plugin.messages().send(player, "not-in-guild");
                    break;
                }
                plugin.war().leaveQueue(player, g);
            }
            case "join" -> plugin.war().join(player);
            case "spectate" -> plugin.war().spectate(player);
            case "history" -> {
                if (g == null) {
                    plugin.messages().send(player, "not-in-guild");
                    break;
                }
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
            if (sub.equals("residence") || sub.equals("res") || sub.equals("lingdi")) {
                return filter(List.of("list", "share", "unshare"), args[1]);
            }
            if (sub.equals("set")) {
                return filter(List.of("open", "notice", "desc"), args[1]);
            }
            if (sub.equals("bank")) {
                return filter(List.of("deposit", "withdraw"), args[1]);
            }
            if (sub.equals("warehouse") || sub.equals("wh") || sub.equals("cangku")) {
                return filter(List.of("info", "rent", "upgrade"), args[1]);
            }
            if (sub.equals("home") || sub.equals("hq")) {
                return filter(List.of("set", "del", "list"), args[1]);
            }
            if (sub.equals("top")) {
                return filter(List.of("points", "bank", "level", "wins", "losses", "members"), args[1]);
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
