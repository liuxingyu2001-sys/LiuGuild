package com.liu.liuguild.command;

import com.liu.liuguild.LiuGuild;
import com.liu.liuguild.config.ArenaDef;
import com.liu.liuguild.config.WarMode;
import com.liu.liuguild.model.Guild;
import com.liu.liuguild.model.GuildWar;
import com.liu.liuguild.util.NumberUtil;
import com.liu.liuguild.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** 管理指令 {@code /guildadmin}：重载、状态、场地、装备、模式、赛季、工会与工会战管理。 */
public final class GuildAdminCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = List.of(
            "help", "reload", "status", "import", "arena", "kit", "mode", "war",
            "disband", "setlevel", "setpoints", "setbank", "forceend", "info", "gui", "cleanup",
            "guilds", "player", "broadcast");

    private final LiuGuild plugin;

    /** 子命令 -> 需要的权限节点（未列出时仅需 liuguild.admin）。 */
    private static final Map<String, String> SUB_PERMISSIONS = Map.ofEntries(
            Map.entry("reload", "liuguild.admin.reload"),
            Map.entry("status", "liuguild.admin.status"),
            Map.entry("import", "liuguild.admin.import"),
            Map.entry("arena", "liuguild.admin.arena"),
            Map.entry("kit", "liuguild.admin.kit"),
            Map.entry("mode", "liuguild.admin.mode"),
            Map.entry("war", "liuguild.admin.war"),
            Map.entry("disband", "liuguild.admin.disband"),
            Map.entry("setlevel", "liuguild.admin.setlevel"),
            Map.entry("setpoints", "liuguild.admin.setpoints"),
            Map.entry("setbank", "liuguild.admin.setbank"),
            Map.entry("forceend", "liuguild.admin.forceend"),
            Map.entry("cleanup", "liuguild.admin.cleanup"),
            Map.entry("guilds", "liuguild.admin.guilds"),
            Map.entry("player", "liuguild.admin.player"),
            Map.entry("broadcast", "liuguild.admin.broadcast"),
            Map.entry("gui", "liuguild.admin.gui"));

    public GuildAdminCommand(LiuGuild plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("liuguild.admin")) {
            plugin.messages().send(sender, "no-permission");
            return true;
        }
        if (args.length == 0) {
            plugin.messages().send(sender, "admin-usage");
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        String required = SUB_PERMISSIONS.getOrDefault(sub, "liuguild.admin");
        if (!required.equals("liuguild.admin") && !sender.hasPermission(required)) {
            plugin.messages().send(sender, "no-permission");
            return true;
        }
        switch (sub) {
            case "help" -> help(sender);
            case "reload" -> {
                if (args.length >= 2) {
                    plugin.reloadAll();
                    plugin.messages().send(sender, "reload-done");
                } else {
                    plugin.reloadAll();
                    plugin.messages().send(sender, "reload-done");
                }
            }
            case "status" -> status(sender);
            case "import" -> importData(sender, args);
            case "arena" -> arena(sender, args);
            case "kit" -> kit(sender, args);
            case "mode" -> mode(sender, args);
            case "war" -> war(sender, args);
            case "disband" -> disband(sender, args);
            case "setlevel" -> setLevel(sender, args);
            case "setpoints" -> setPoints(sender, args);
            case "setbank" -> setBank(sender, args);
            case "forceend" -> forceEnd(sender, args);
            case "info" -> info(sender, args);
            case "cleanup" -> cleanup(sender, args);
            case "guilds" -> guilds(sender, args);
            case "player" -> player(sender, args);
            case "broadcast" -> broadcast(sender, args);
            case "gui" -> {
                if (sender instanceof Player p) {
                    plugin.gui().openAdmin(p);
                } else {
                    plugin.messages().send(sender, "players-only");
                }
            }
            default -> plugin.messages().send(sender, "admin-usage");
        }
        return true;
    }

    // =====================================================================
    // 帮助 / 状态
    // =====================================================================

    private void help(CommandSender sender) {
        sender.sendMessage(cn("&6===== LiuGuild 管理帮助 ====="));
        adminLine(sender, "reload", "重载全部配置与模块");
        adminLine(sender, "status", "查看详细运行状态");
        adminLine(sender, "gui", "打开管理面板");
        adminLine(sender, "import <sql文件>", "导入 PlayerGuild 数据");
        adminLine(sender, "arena setspawn <id> <a|b|spec>", "设置竞技场出生点");
        adminLine(sender, "arena setworld|setserver|settemplate", "设置世界/承办服/模板");
        adminLine(sender, "arena list|info <id>|create <id>", "竞技场管理");
        adminLine(sender, "arena setendspawn|setradius|settempworld|setgamerule|setafter|tp|remove <id>", "竞技场进阶设置");
        adminLine(sender, "kit save <key>", "保存当前身上装备为套装");
        adminLine(sender, "kit list|info <key>|delete <key>", "装备套装管理");
        adminLine(sender, "mode list|info <模式>|start <模式>", "工会战模式 / 开启赛季");
        adminLine(sender, "war list|queue|start|stop|tp|score", "工会战管理（查看/队列/强开/停止/传送/改分）");
        adminLine(sender, "disband|setlevel|setpoints|setbank|info <工会>", "工会数据管理");
        adminLine(sender, "guilds [页码]", "分页列出全部工会");
        adminLine(sender, "player <玩家>", "查看玩家所属工会");
        adminLine(sender, "broadcast <文本>", "全服广播消息");
        adminLine(sender, "cleanup [dry|confirm]", "清理不活跃（僵尸）工会");
    }

    private void adminLine(CommandSender sender, String cmd, String desc) {
        Component line = Text.mm("<dark_gray>▸ <white>/guildadmin " + cmd + " <dark_gray>- <gray>" + desc)
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.suggestCommand("/guildadmin " + cmd))
                .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(Text.mm("<gray>点击自动填入")));
        sender.sendMessage(line);
    }

    private void status(CommandSender sender) {
        for (String line : plugin.statusLines()) {
            sender.sendMessage(Text.mm(cn(line)));
        }
    }

    private static String cn(String s) {
        return s.replace('&', '\u00a7');
    }

    // =====================================================================
    // 场地
    // =====================================================================

    private void arena(CommandSender sender, String[] args) {
        if (args.length < 2) {
            plugin.messages().sendRaw(sender, "arena-usage");
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "list" -> {
                sender.sendMessage(cn("&6竞技场列表："));
                plugin.settings().arenas.arenas.forEach((id, a) -> sender.sendMessage(cn(
                        "&7- &f" + id + " &7承办=&f" + (a.server.isBlank() ? "-" : a.server)
                                + " &7世界=&f" + (a.world.isBlank() ? "主世界" : a.world)
                                + " &7模板=&f" + (a.hasTemplate() ? a.template : "固定坐标"))));
            }
            case "info" -> {
                if (args.length < 3) {
                    plugin.messages().sendRaw(sender, "arena-usage");
                    return;
                }
                ArenaDef a = plugin.settings().arena(args[2]);
                sender.sendMessage(cn("&6竞技场 &f" + a.id));
                sender.sendMessage(cn("&7承办服: &f" + (a.server.isBlank() ? "-" : a.server)));
                sender.sendMessage(cn("&7世界: &f" + (a.world.isBlank() ? "主世界" : a.world)));
                sender.sendMessage(cn("&7模板: &f" + (a.hasTemplate() ? a.template : "无（固定坐标）")));
                sender.sendMessage(cn("&7A点: &f" + fmt(a.spawnA)));
                sender.sendMessage(cn("&7B点: &f" + fmt(a.spawnB)));
                sender.sendMessage(cn("&7旁观点: &f" + fmt(a.spawnSpectator)));
            }
            case "setspawn" -> setSpawn(sender, args);
            case "setworld" -> {
                if (require(sender, args, 4)) {
                    plugin.configs().set("arena.yml", "arenas." + args[2] + ".world", args[3]);
                    plugin.reloadAll();
                    plugin.messages().sendRaw(sender, "arena-set", "arena", args[2], "value", args[3]);
                }
            }
            case "setserver" -> {
                if (require(sender, args, 4)) {
                    plugin.configs().set("arena.yml", "arenas." + args[2] + ".server", args[3]);
                    plugin.reloadAll();
                    plugin.messages().sendRaw(sender, "arena-set", "arena", args[2], "value", args[3]);
                }
            }
            case "settemplate" -> {
                if (require(sender, args, 4)) {
                    plugin.configs().set("arena.yml", "arenas." + args[2] + ".template", args[3]);
                    plugin.reloadAll();
                    plugin.messages().sendRaw(sender, "arena-set", "arena", args[2], "value", args[3]);
                }
            }
            case "create" -> {
                if (require(sender, args, 3)) {
                    plugin.configs().set("arena.yml", "arenas." + args[2] + ".template", "");
                    plugin.configs().set("arena.yml", "arenas." + args[2] + ".world", "");
                    plugin.reloadAll();
                    plugin.messages().sendRaw(sender, "arena-created", "arena", args[2]);
                }
            }
            case "remove", "delete" -> {
                if (require(sender, args, 3)) {
                    if (!plugin.settings().arenas.arenas.containsKey(args[2].toLowerCase(Locale.ROOT))) {
                        sender.sendMessage(cn("&c竞技场不存在：" + args[2]));
                        return;
                    }
                    plugin.configs().remove("arena.yml", "arenas." + args[2]);
                    plugin.reloadAll();
                    sender.sendMessage(cn("&a已删除竞技场 &f" + args[2]));
                }
            }
            case "setradius" -> {
                if (require(sender, args, 4)) {
                    plugin.configs().set("arena.yml", "arenas." + args[2] + ".radius", NumberUtil.toInt(args[3], 48));
                    plugin.reloadAll();
                    plugin.messages().sendRaw(sender, "arena-set", "arena", args[2], "value", "radius=" + args[3]);
                }
            }
            case "settempworld" -> {
                if (require(sender, args, 5)) {
                    Object value = args[4];
                    if (args[4].equalsIgnoreCase("true") || args[4].equalsIgnoreCase("false")) {
                        value = Boolean.parseBoolean(args[4]);
                    } else if (args[3].equalsIgnoreCase("time")) {
                        value = NumberUtil.toInt(args[4], -1);
                    }
                    plugin.configs().set("arena.yml", "arenas." + args[2] + ".temporary-world." + args[3], value);
                    plugin.reloadAll();
                    plugin.messages().sendRaw(sender, "arena-set", "arena", args[2], "value", args[3] + "=" + args[4]);
                }
            }
            case "setgamerule" -> {
                if (require(sender, args, 5)) {
                    boolean value = Boolean.parseBoolean(args[4]);
                    plugin.configs().set("arena.yml",
                            "arenas." + args[2] + ".temporary-world.gamerules." + args[3], value);
                    plugin.reloadAll();
                    plugin.messages().sendRaw(sender, "arena-set", "arena", args[2], "value", args[3] + "=" + value);
                }
            }
            case "setafter" -> {
                if (require(sender, args, 5)) {
                    Object value = args[4];
                    if (args[4].equalsIgnoreCase("true") || args[4].equalsIgnoreCase("false")) {
                        value = Boolean.parseBoolean(args[4]);
                    } else if (args[3].toLowerCase(Locale.ROOT).contains("delay")) {
                        value = NumberUtil.toInt(args[4], 0);
                    }
                    plugin.configs().set("arena.yml", "arenas." + args[2] + ".after-war." + args[3], value);
                    plugin.reloadAll();
                    plugin.messages().sendRaw(sender, "arena-set", "arena", args[2], "value", args[3] + "=" + args[4]);
                }
            }
            case "setendspawn" -> {
                if (!(sender instanceof Player p)) {
                    plugin.messages().send(sender, "players-only");
                    return;
                }
                if (!require(sender, args, 3)) {
                    return;
                }
                Location loc = p.getLocation();
                String base = "arenas." + args[2] + ".after-war.end-spawn.";
                plugin.configs().set("arena.yml", base + "enabled", true);
                plugin.configs().set("arena.yml", base + "world", loc.getWorld().getName());
                plugin.configs().set("arena.yml", base + "x", round(loc.getX()));
                plugin.configs().set("arena.yml", base + "y", round(loc.getY()));
                plugin.configs().set("arena.yml", base + "z", round(loc.getZ()));
                plugin.configs().set("arena.yml", base + "yaw", round(loc.getYaw()));
                plugin.configs().set("arena.yml", base + "pitch", round(loc.getPitch()));
                plugin.reloadAll();
                sender.sendMessage(cn("&a已将竞技场 &f" + args[2] + " &a的集合点设为当前位置（世界 &f" + loc.getWorld().getName() + "&a）。"));
            }
            case "tp", "teleport" -> {
                if (!(sender instanceof Player p)) {
                    plugin.messages().send(sender, "players-only");
                    return;
                }
                if (!require(sender, args, 3)) {
                    return;
                }
                ArenaDef a = plugin.settings().arena(args[2]);
                org.bukkit.World world = a.world != null && !a.world.isBlank() ? Bukkit.getWorld(a.world)
                        : (Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0));
                if (world == null) {
                    sender.sendMessage(cn("&c世界未加载，无法传送。"));
                    return;
                }
                p.teleport(a.spawnA.toLocation(world));
                sender.sendMessage(cn("&a已传送到竞技场 &f" + a.id + " &a的 A 点。"));
            }
            default -> plugin.messages().sendRaw(sender, "arena-usage");
        }
    }

    private void setSpawn(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "players-only");
            return;
        }
        if (args.length < 4) {
            plugin.messages().sendRaw(sender, "arena-usage");
            return;
        }
        String arenaId = args[2];
        String point = args[3].toLowerCase(Locale.ROOT);
        String path = switch (point) {
            case "a" -> "spawn-a";
            case "b" -> "spawn-b";
            case "spec", "spectator" -> "spawn-spectator";
            default -> null;
        };
        if (path == null) {
            plugin.messages().sendRaw(sender, "arena-usage");
            return;
        }
        Location loc = player.getLocation();
        String base = "arenas." + arenaId + "." + path + ".";
        plugin.configs().set("arena.yml", base + "x", round(loc.getX()));
        plugin.configs().set("arena.yml", base + "y", round(loc.getY()));
        plugin.configs().set("arena.yml", base + "z", round(loc.getZ()));
        plugin.configs().set("arena.yml", base + "yaw", round(loc.getYaw()));
        plugin.configs().set("arena.yml", base + "pitch", round(loc.getPitch()));
        plugin.reloadAll();
        plugin.messages().sendRaw(sender, "arena-spawn-set", "arena", arenaId,
                "point", point.toUpperCase(Locale.ROOT), "world", loc.getWorld().getName());
    }

    // =====================================================================
    // 装备
    // =====================================================================

    private void kit(CommandSender sender, String[] args) {
        if (args.length < 2) {
            plugin.messages().sendRaw(sender, "kit-usage");
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "list" -> {
                sender.sendMessage(cn("&6装备套装："));
                plugin.settings().kit.kits.forEach((key, k) -> sender.sendMessage(cn(
                        "&7- &f" + key + " &7快照=" + (k.hasSnapshot() ? "&a有" : "&8无")
                                + " &7物品=" + k.items.size() + " &7效果=" + k.effects.size())));
            }
            case "save" -> {
                if (!(sender instanceof Player player)) {
                    plugin.messages().send(sender, "players-only");
                    return;
                }
                if (args.length < 3) {
                    plugin.messages().sendRaw(sender, "kit-usage");
                    return;
                }
                String key = args[2].toLowerCase(Locale.ROOT);
                String snapshot = plugin.kits().capture(player);
                plugin.configs().set("kit.yml", "kits." + key + ".enabled", true);
                plugin.configs().set("kit.yml", "kits." + key + ".snapshot", snapshot);
                plugin.reloadAll();
                plugin.messages().sendRaw(sender, "kit-saved", "key", key);
            }
            case "delete" -> {
                if (require(sender, args, 3)) {
                    plugin.configs().set("kit.yml", "kits." + args[2], null);
                    plugin.reloadAll();
                    plugin.messages().sendRaw(sender, "kit-deleted", "key", args[2]);
                }
            }
            case "info" -> {
                if (require(sender, args, 3)) {
                    var k = plugin.settings().kit.profile(args[2]);
                    sender.sendMessage(cn("&6套装 &f" + k.key));
                    sender.sendMessage(cn("&7启用: &f" + k.enabled + " &7清空背包: &f" + k.clearInventory));
                    sender.sendMessage(cn("&7快照: " + (k.hasSnapshot() ? "&a已保存" : "&8无")));
                    sender.sendMessage(cn("&7护甲: &f" + k.armor));
                    sender.sendMessage(cn("&7物品: &f" + k.items));
                    sender.sendMessage(cn("&7效果: &f" + k.effects));
                }
            }
            default -> plugin.messages().sendRaw(sender, "kit-usage");
        }
    }

    // =====================================================================
    // 模式 / 赛季
    // =====================================================================

    private void mode(CommandSender sender, String[] args) {
        if (args.length < 2) {
            plugin.messages().sendRaw(sender, "mode-usage");
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "list" -> {
                sender.sendMessage(cn("&6工会战模式："));
                plugin.settings().war.modes.forEach((key, m) -> sender.sendMessage(cn(
                        "&7- &f" + key + " &7" + m.display + " &7启用=" + (m.enabled ? "&a是" : "&c否")
                                + " &7场地=&f" + m.arena + " &7公平装备=" + (m.fairEquipment ? "&a是" : "&8否")
                                + (m.scheduled() ? " &7时间表=&f" + m.schedule : ""))));
            }
            case "info" -> {
                if (require(sender, args, 3)) {
                    WarMode m = plugin.settings().mode(args[2]);
                    sender.sendMessage(cn("&6模式 &f" + m.key + " &7(" + m.display + ")"));
                    sender.sendMessage(cn("&7启用: &f" + m.enabled + " &7场地: &f" + m.arena
                            + " &7承办服: &f" + (m.dedicatedServer.isBlank() ? "-" : m.dedicatedServer)));
                    sender.sendMessage(cn("&7公平装备: &f" + m.fairEquipment + " &7套装: &f" + m.kit));
                    sender.sendMessage(cn("&7队伍上限: &f" + m.teamSize + " &7最低人数: &f" + m.minMembers
                            + " &7最低等级: &f" + m.minLevel));
                    sender.sendMessage(cn("&7准备/战斗/结算: &f" + m.prepareSeconds + "/" + m.durationSeconds + "/" + m.settleSeconds));
                    sender.sendMessage(cn("&7计分: &f击杀" + m.scoreKill + " 死亡" + m.scoreDeath + " 目标" + m.scoreObjective));
                    sender.sendMessage(cn("&7生命: &f每人 " + m.livesPerPlayer + " 次 &7人数均衡=" + m.balanceTeamLives
                            + " &7复活等待=" + m.respawnDelaySeconds + " 秒"));
                    sender.sendMessage(cn("&7奖励: &f胜个人金币 " + m.winPlayerMoney + " / 工会资金 " + m.winGuildFunds
                            + " / 通行证经验 " + m.winPassXp + " &7负个人金币 " + m.losePlayerMoney
                            + " / 工会资金 " + m.loseGuildFunds + " / 通行证经验 " + m.losePassXp
                            + " &7胜方称号ID " + m.winTitleId));
                    sender.sendMessage(cn("&7时间表: &f" + (m.schedule.isEmpty() ? "无" : m.schedule)));
                }
            }
            case "start" -> {
                if (require(sender, args, 3)) {
                    plugin.war().forceSeason(args[2]);
                    plugin.messages().sendRaw(sender, "season-started", "mode", args[2]);
                }
            }
            default -> plugin.messages().sendRaw(sender, "mode-usage");
        }
    }

    private void war(CommandSender sender, String[] args) {
        String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "list";
        switch (action) {
            case "list" -> {
                List<GuildWar> wars = plugin.war().liveWars();
                if (wars.isEmpty()) {
                    sender.sendMessage(cn("&7当前没有进行中的工会战。"));
                } else {
                    sender.sendMessage(cn("&6进行中的工会战："));
                    for (GuildWar w : wars) {
                        sender.sendMessage(cn("&7- &f" + w.id().substring(0, 8) + " &7[" + w.mode() + "] "
                                + w.nameA() + " &e" + w.scoreA() + " : " + w.scoreB() + " &f" + w.nameB()
                                + " &7状态=" + w.state() + " 承办=" + w.hostServer()));
                    }
                }
                sender.sendMessage(cn("&7匹配队列: &f" + plugin.war().queueSize() + " 个工会"));
            }
            case "queue" -> {
                List<Map<String, String>> queued = plugin.bus().isReady() ? plugin.bus().queueSnapshot() : List.of();
                if (queued.isEmpty()) {
                    sender.sendMessage(cn("&7匹配队列为空。"));
                    return;
                }
                sender.sendMessage(cn("&6匹配队列（&f" + queued.size() + "&6 个工会）："));
                for (Map<String, String> meta : queued) {
                    sender.sendMessage(cn("&7- &f" + meta.getOrDefault("name", meta.get("guildId"))
                            + " &7模式=&f" + meta.getOrDefault("mode", "-")
                            + " &7承办服=&f" + meta.getOrDefault("server", "-")
                            + " &7发起人=&f" + meta.getOrDefault("leader", "-")));
                }
            }
            case "start" -> {
                if (!require(sender, args, 5)) {
                    return;
                }
                Guild ga = find(args[3]);
                Guild gb = find(args[4]);
                String error = plugin.war().forceStart(ga, gb, args[2]);
                if (error != null) {
                    plugin.messages().send(sender, error);
                } else {
                    sender.sendMessage(cn("&a已强制开启工会战：&f" + ga.name() + " &7VS &f" + gb.name()
                            + " &7（模式 &f" + args[2] + "&7）"));
                }
            }
            case "stop", "end" -> {
                GuildWar w = args.length >= 3 ? plugin.war().warById(args[2]) : plugin.war().currentWar();
                if (w == null) {
                    plugin.messages().send(sender, "war-not-in-war");
                    return;
                }
                plugin.war().forceEnd(w.id());
                plugin.messages().send(sender, "admin-forceend", "war", w.id());
            }
            case "score" -> {
                if (!require(sender, args, 5)) {
                    return;
                }
                int value = NumberUtil.toInt(args[4], 0);
                if (plugin.war().setScore(args[2], args[3], value)) {
                    sender.sendMessage(cn("&a已将工会战 &f" + args[2].substring(0, Math.min(8, args[2].length()))
                            + " &a的 " + args[3].toUpperCase(Locale.ROOT) + " 方比分设为 &f" + value));
                } else {
                    plugin.messages().send(sender, "war-not-in-war");
                }
            }
            case "tp", "teleport" -> {
                if (!(sender instanceof Player p)) {
                    plugin.messages().send(sender, "players-only");
                    return;
                }
                if (plugin.war().teleportToArena(p)) {
                    sender.sendMessage(cn("&a已传送到竞技场。"));
                } else {
                    sender.sendMessage(cn("&c竞技场世界未加载，无法传送。"));
                }
            }
            default -> {
                GuildWar w = plugin.war().warById(args.length >= 2 ? args[1] : null);
                if (w == null) {
                    plugin.messages().send(sender, "war-not-in-war");
                    return;
                }
                sender.sendMessage(cn("&6工会战 &f" + w.id()));
                sender.sendMessage(cn("&7模式: &f" + w.mode() + " &7状态: &f" + w.state()));
                sender.sendMessage(cn("&7" + w.nameA() + " &e" + w.scoreA() + " : " + w.scoreB() + " &7" + w.nameB()));
                sender.sendMessage(cn("&7承办: &f" + w.hostServer() + " &7竞技场: &f" + w.arenaWorld()));
            }
        }
    }

    // =====================================================================
    // 工会
    // =====================================================================

    private void importData(CommandSender sender, String[] args) {
        if (args.length < 2) {
            plugin.messages().send(sender, "import-usage");
            return;
        }
        File file = new File(args[1]);
        if (!file.isAbsolute()) {
            file = new File(plugin.getDataFolder(), args[1]);
            if (!file.exists()) {
                file = new File(plugin.getDataFolder().getParentFile(), args[1]);
            }
        }
        if (!file.isFile()) {
            plugin.messages().send(sender, "import-file-missing", "file", file.getPath());
            return;
        }
        final File target = file;
        plugin.messages().send(sender, "import-started");
        plugin.async(() -> {
            try {
                var result = plugin.importer().importFile(target.toPath());
                plugin.sync(() -> plugin.messages().send(sender, "import-done", "summary", result.summary()));
                result.warnings().forEach(w -> plugin.getLogger().warning("[import] " + w));
            } catch (Exception e) {
                plugin.getLogger().severe("[import] 失败: " + e);
                plugin.sync(() -> plugin.messages().send(sender, "import-failed", "error", String.valueOf(e.getMessage())));
            }
        });
    }

    private void disband(CommandSender sender, String[] args) {
        if (!require(sender, args, 2)) {
            return;
        }
        Guild g = find(args[1]);
        if (g == null) {
            plugin.messages().send(sender, "guild-not-found", "name", args[1]);
            return;
        }
        plugin.guilds().disband(g, true, com.liu.liuguild.api.event.GuildDisbandEvent.Cause.ADMIN);
        plugin.messages().send(sender, "admin-disband", "guild", g.name());
    }

    private void setLevel(CommandSender sender, String[] args) {
        if (!require(sender, args, 3)) {
            return;
        }
        Guild g = find(args[1]);
        if (g == null) {
            plugin.messages().send(sender, "guild-not-found", "name", args[1]);
            return;
        }
        int level = NumberUtil.toInt(args[2], g.level());
        plugin.guilds().setLevel(g, level);
        plugin.messages().send(sender, "admin-setlevel", "guild", g.name(), "level", level);
    }

    private void setPoints(CommandSender sender, String[] args) {
        if (!require(sender, args, 3)) {
            return;
        }
        Guild g = find(args[1]);
        if (g == null) {
            plugin.messages().send(sender, "guild-not-found", "name", args[1]);
            return;
        }
        long points = NumberUtil.toLong(args[2], g.points());
        plugin.guilds().setPoints(g, points);
        plugin.messages().send(sender, "admin-setpoints", "guild", g.name(), "points", points);
    }

    private void setBank(CommandSender sender, String[] args) {
        if (!require(sender, args, 3)) {
            return;
        }
        Guild g = find(args[1]);
        if (g == null) {
            plugin.messages().send(sender, "guild-not-found", "name", args[1]);
            return;
        }
        long target = Math.max(0, NumberUtil.toLong(args[2], g.bank()));
        long delta = target - g.bank();
        plugin.guilds().changeBank(g.id(), delta).whenComplete((ok, error) -> plugin.sync(() -> {
            if (error != null || !Boolean.TRUE.equals(ok)) {
                sender.sendMessage(cn("&c设置银行余额失败（可能溢出或数据库错误）。"));
            } else {
                sender.sendMessage(cn("&a已将工会 &f" + g.name() + " &a的银行余额设为 &f" + target + "&a。"));
            }
        }));
    }

    private void guilds(CommandSender sender, String[] args) {
        int perPage = 15;
        int page = args.length >= 2 ? Math.max(1, NumberUtil.toInt(args[1], 1)) : 1;
        List<Guild> all = new ArrayList<>(plugin.guilds().all());
        all.sort((a, b) -> Long.compare(b.points(), a.points()));
        int maxPage = Math.max(1, (all.size() + perPage - 1) / perPage);
        page = Math.min(page, maxPage);
        sender.sendMessage(cn("&6工会列表 &7（第 " + page + "/" + maxPage + " 页，共 " + all.size() + " 个）"));
        int from = (page - 1) * perPage;
        for (int i = 0; i < perPage && from + i < all.size(); i++) {
            Guild g = all.get(from + i);
            sender.sendMessage(cn("&7- &f" + g.name() + " &8[" + g.tag() + "] &7等级=&f" + g.level()
                    + " &7成员=&f" + g.memberCount() + " &7资金=&f" + g.points() + " &7银行=&f" + g.bank()
                    + " &8id=" + g.id().substring(0, 8)));
        }
        sender.sendMessage(cn("&7下一页：&f/guildadmin guilds " + Math.min(maxPage, page + 1)));
    }

    private void player(CommandSender sender, String[] args) {
        if (!require(sender, args, 2)) {
            return;
        }
        Player target = plugin.playerExact(args[1]);
        if (target != null) {
            Guild g = plugin.guilds().guildOf(target.getUniqueId());
            sender.sendMessage(cn("&6玩家 &f" + target.getName() + " &7工会：&f"
                    + (g == null ? "无" : g.name() + " (" + (g.member(target.getUniqueId()) == null ? "?"
                    : g.member(target.getUniqueId()).rank().display()) + ")")));
            return;
        }
        // 离线：查数据库
        plugin.async(() -> {
            String guildId;
            try {
                guildId = plugin.repo().guildIdOf(resolveUuid(args[1]));
            } catch (Exception e) {
                guildId = null;
            }
            String result = guildId == null ? "无" : guildId;
            plugin.sync(() -> sender.sendMessage(cn("&6玩家 &f" + args[1] + " &7工会：&f" + result)));
        });
    }

    private UUID resolveUuid(String name) {
        Player online = plugin.playerExact(name);
        if (online != null) {
            return online.getUniqueId();
        }
        @SuppressWarnings("deprecation")
        var offline = Bukkit.getOfflinePlayer(name);
        return offline.getUniqueId();
    }

    private void broadcast(CommandSender sender, String[] args) {
        if (args.length < 2) {
            plugin.messages().sendRaw(sender, "admin-usage");
            return;
        }
        String message = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
        org.bukkit.Bukkit.broadcast(plugin.messages().prefixed("admin-broadcast", "message", message));
    }

    private void forceEnd(CommandSender sender, String[] args) {
        GuildWar war = args.length >= 2 ? plugin.war().warById(args[1]) : plugin.war().currentWar();
        if (war == null) {
            plugin.messages().send(sender, "war-not-in-war");
            return;
        }
        plugin.war().forceEnd(war.id());
        plugin.messages().send(sender, "admin-forceend", "war", war.id());
    }

    private void info(CommandSender sender, String[] args) {
        if (!require(sender, args, 2)) {
            return;
        }
        Guild g = find(args[1]);
        if (g == null) {
            plugin.messages().send(sender, "guild-not-found", "name", args[1]);
            return;
        }
        sender.sendMessage(cn("&e===== " + g.name() + " [" + g.tag() + "] ====="));
        sender.sendMessage(cn("&7ID: &f" + g.id()));
        sender.sendMessage(cn("&7会长: &f" + g.leaderName() + " &7等级: &f" + g.level()
                + " &7点数: &f" + g.points() + " &7银行: &f" + g.bank()));
        sender.sendMessage(cn("&7成员: &f" + g.memberCount() + " &7战绩: &f" + g.wins() + "胜 " + g.losses() + "负"));
        sender.sendMessage(cn("&7图标: &f" + g.icon() + " &7开放: &f" + g.open()
                + " &7创建: &f" + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm")
                        .format(new java.util.Date(g.createdAt()))));
    }

    private void cleanup(CommandSender sender, String[] args) {
        String mode = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "scan";
        var conf = plugin.settings().guild;
        boolean dry = switch (mode) {
            case "confirm", "apply", "run" -> false;
            default -> true;
        };
        var targets = plugin.guilds().purgeInactive(dry);
        sender.sendMessage(cn("&6===== 不活跃工会清理 ====="));
        sender.sendMessage(cn("&7判定：连续 &f" + conf.cleanupInactiveDays + " &7天无活动且无人在线"
                + (conf.cleanupMaxMembers > 0 ? "，成员数≤&f" + conf.cleanupMaxMembers : "")));
        sender.sendMessage(cn("&7模式：" + (dry ? "&e试运行（未删除）" : "&c已执行删除")
                + " &7命中：&f" + targets.size()));
        int shown = 0;
        for (Guild g : targets) {
            if (shown++ >= 15) {
                sender.sendMessage(cn("&8…还有 " + (targets.size() - 15) + " 个"));
                break;
            }
            long days = (System.currentTimeMillis() - g.lastActiveAt()) / 86_400_000L;
            sender.sendMessage(cn("&7- &f" + g.name() + " &8[" + g.tag() + "] &7成员=&f" + g.memberCount()
                    + " &7最后活跃=&f" + days + " 天前"));
        }
        if (dry && !targets.isEmpty()) {
            sender.sendMessage(cn("&7确认删除请执行：&f/guildadmin cleanup confirm"));
        }
    }

    private Guild find(String key) {
        Guild g = plugin.guilds().guild(key);
        return g != null ? g : plugin.guilds().guildByName(key);
    }

    private boolean require(CommandSender sender, String[] args, int min) {
        if (args.length < min) {
            plugin.messages().sendRaw(sender, "admin-usage");
            return false;
        }
        return true;
    }

    private static String fmt(ArenaDef.Spawn s) {
        return String.format(Locale.ROOT, "%.1f, %.1f, %.1f (yaw %.1f)", s.x(), s.y(), s.z(), s.yaw());
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    // =====================================================================
    // 补全
    // =====================================================================

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return startsWith(SUBS, args[0]);
        }
        if (args.length == 2) {
            return switch (args[0].toLowerCase(Locale.ROOT)) {
                case "arena" -> startsWith(List.of("list", "info", "setspawn", "setworld", "setserver", "settemplate",
                        "create", "remove", "setradius", "settempworld", "setgamerule", "setafter", "setendspawn", "tp"), args[1]);
                case "kit" -> startsWith(List.of("list", "save", "delete", "info"), args[1]);
                case "mode" -> startsWith(List.of("list", "info", "start"), args[1]);
                case "war" -> startsWith(List.of("list", "queue", "start", "stop", "tp", "score"), args[1]);
                case "reload", "status", "guilds", "cleanup" -> List.of();
                case "player", "broadcast" -> onlinePlayerNames(args[1]);
                default -> guildNames(args[1]);
            };
        }
        if (args.length == 3) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("arena")) {
                return startsWith(new ArrayList<>(plugin.settings().arenas.arenas.keySet()), args[2]);
            }
            if (sub.equals("kit")) {
                return startsWith(new ArrayList<>(plugin.settings().kit.kits.keySet()), args[2]);
            }
            if (sub.equals("mode")) {
                return startsWith(new ArrayList<>(plugin.settings().war.modes.keySet()), args[2]);
            }
            if (sub.equals("war") && args[1].equalsIgnoreCase("start")) {
                return startsWith(new ArrayList<>(plugin.settings().war.modes.keySet()), args[2]);
            }
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("arena") && args[1].equalsIgnoreCase("setspawn")) {
            return startsWith(List.of("a", "b", "spec"), args[3]);
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("arena") && args[1].equalsIgnoreCase("settempworld")) {
            return startsWith(List.of("environment", "keep-on-end", "builtin", "world-type", "name-format",
                    "time", "weather", "difficulty"), args[3]);
        }
        if (args.length == 5 && args[0].equalsIgnoreCase("arena") && args[1].equalsIgnoreCase("settempworld")) {
            if (args[3].equalsIgnoreCase("world-type")) {
                return startsWith(List.of("flat", "normal"), args[4]);
            }
            if (args[3].equalsIgnoreCase("environment")) {
                return startsWith(List.of("NORMAL", "NETHER", "THE_END"), args[4]);
            }
            if (args[3].equalsIgnoreCase("weather")) {
                return startsWith(List.of("clear", "rain", "thunder", ""), args[4]);
            }
            if (args[3].equalsIgnoreCase("difficulty")) {
                return startsWith(List.of("PEACEFUL", "EASY", "NORMAL", "HARD", ""), args[4]);
            }
            if (args[3].equalsIgnoreCase("builtin") || args[3].equalsIgnoreCase("keep-on-end")) {
                return startsWith(List.of("true", "false"), args[4]);
            }
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("arena") && args[1].equalsIgnoreCase("setafter")) {
            return startsWith(List.of("auto-return", "return-delay-seconds", "end-spawn.enabled"), args[3]);
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("war") && args[1].equalsIgnoreCase("start")) {
            return guildNames(args[3]);
        }
        if (args.length == 5 && args[0].equalsIgnoreCase("war")) {
            if (args[1].equalsIgnoreCase("start")) {
                return guildNames(args[4]);
            }
            if (args[1].equalsIgnoreCase("score")) {
                return startsWith(List.of("a", "b"), args[3]);
            }
        }
        if (args.length == 5 && args[0].equalsIgnoreCase("arena") && args[1].equalsIgnoreCase("setgamerule")) {
            return startsWith(List.of("true", "false"), args[4]);
        }
        return List.of();
    }

    private List<String> guildNames(String prefix) {
        List<String> out = new ArrayList<>();
        for (Guild g : plugin.guilds().all()) {
            out.add(g.name());
        }
        return startsWith(out, prefix);
    }

    private List<String> onlinePlayerNames(String prefix) {
        List<String> out = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            out.add(p.getName());
        }
        return startsWith(out, prefix);
    }

    private static List<String> startsWith(List<String> options, String prefix) {
        List<String> out = new ArrayList<>();
        String p = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        for (String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(p)) {
                out.add(o);
            }
        }
        return out;
    }
}
