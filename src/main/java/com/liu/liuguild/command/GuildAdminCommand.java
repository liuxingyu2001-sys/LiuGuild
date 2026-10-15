package com.liu.liuguild.command;

import com.liu.liuguild.LiuGuild;
import com.liu.liuguild.config.ArenaDef;
import com.liu.liuguild.config.WarMode;
import com.liu.liuguild.model.Guild;
import com.liu.liuguild.model.GuildWar;
import com.liu.liuguild.util.NumberUtil;
import com.liu.liuguild.util.Text;
import com.liu.liuguild.util.WorldResolver;
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
            Map.entry("verify", "liuguild.admin.verify"),
            Map.entry("doctor", "liuguild.admin.status"),
            Map.entry("overrides", "liuguild.admin.reload"),
            Map.entry("guilds", "liuguild.admin.guilds"),
            Map.entry("player", "liuguild.admin.player"),
            Map.entry("broadcast", "liuguild.admin.broadcast"),
            Map.entry("gui", "liuguild.admin.gui"));

    public GuildAdminCommand(LiuGuild plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            plugin.messages().send(sender, "admin-usage");
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        String required = SUB_PERMISSIONS.getOrDefault(sub, "liuguild.admin");
        // 允许「总权限」或「对应子权限」任一；help 对所有人可见。
        boolean allowed = sender.hasPermission("liuguild.admin")
                || sender.hasPermission(required)
                || sub.equals("help");
        if (!allowed) {
            plugin.messages().send(sender, "no-permission");
            return true;
        }
        switch (sub) {
            case "help" -> help(sender);
            case "reload" -> {
                plugin.reloadAll();
                plugin.messages().send(sender, "reload-done");
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
            case "verify" -> verify(sender, args);
            case "doctor" -> doctor(sender);
            case "overrides" -> overrides(sender, args);
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
        adminLine(sender, "import <sql文件> [force]", "导入 PlayerGuild 数据，force 覆盖同名工会");
        adminLine(sender, "arena setspawn <id> <点名>", "设出生点（点名任意：a/b/c/d/spec…）");
        adminLine(sender, "arena setteampoint <id> <a|b> <点名>", "指定 A/B 队用哪个出生点");
        adminLine(sender, "arena setworld|setserver|settemplate", "设置世界/承办服/模板");
        adminLine(sender, "arena list|info <id>|create <id>", "竞技场管理");
        adminLine(sender, "arena setendspawn|setradius|settempworld|setgamerule|setafter|tp|remove <id>", "竞技场进阶设置");
        adminLine(sender, "kit save <key>", "保存当前身上装备为套装");
        adminLine(sender, "kit list|info <key>|delete <key>", "装备套装管理");
        adminLine(sender, "mode list|info <模式>|start <模式>", "工会战模式 / 开启赛季");
        adminLine(sender, "war list|queue|start|stop|tp|score", "工会战管理（查看/队列/强开/停止/传送/改分）");
        adminLine(sender, "disband|setlevel|setpoints|setbank|info <工会>", "工会数据管理");
        adminLine(sender, "rename <工会> <新名称> [新标签]", "给任意工会改名（绕开冷却与费用）");
        adminLine(sender, "guilds [页码]", "分页列出全部工会");
        adminLine(sender, "player <玩家>", "查看玩家所属工会");
        adminLine(sender, "broadcast <文本>", "全服广播消息");
        adminLine(sender, "cleanup [dry|confirm]", "清理不活跃（僵尸）工会");
        adminLine(sender, "verify [fix]", "校验并修复内存数据一致性");
        adminLine(sender, "doctor", "部署自检：跨服/经济/场地/模板/套装/世界");
        adminLine(sender, "overrides [list|clear <文件|all>]", "查看 / 清除游戏内编辑覆盖层");
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
            case "listworlds", "worlds" -> {
                java.util.List<String> names = WorldResolver.knownNames();
                sender.sendMessage(cn("&6识别到的世界（&f" + names.size() + "&6）："));
                for (String name : names) {
                    org.bukkit.World w = WorldResolver.resolve(name);
                    sender.sendMessage(cn("&7- &f" + name
                            + (w != null ? " &a[已加载]" : " &e[仅在磁盘，未加载]")));
                }
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
                sender.sendMessage(cn("&7A点: &f" + fmt(a.spawnA) + " &8(队伍A→&f" + a.teamASpawn + "&8)"));
                sender.sendMessage(cn("&7B点: &f" + fmt(a.spawnB) + " &8(队伍B→&f" + a.teamBSpawn + "&8)"));
                sender.sendMessage(cn("&7旁观点: &f" + fmt(a.spawnSpectator)));
                for (String name : a.spawnPointNames()) {
                    if (name.equals("a") || name.equals("b")) {
                        continue;
                    }
                    sender.sendMessage(cn("&7出生点 &f" + name + "&7: &f" + fmt(a.spawn(name))));
                }
            }
            case "setspawn" -> setSpawn(sender, args);
            case "setteampoint" -> setTeamPoint(sender, args);
            case "setworld" -> {
                if (require(sender, args, 4)) {
                    plugin.configs().set("arena.yml", "arenas." + args[2] + ".world", args[3]);
                    plugin.reloadSettings();
                    plugin.messages().sendRaw(sender, "arena-set", "arena", args[2], "value", args[3]);
                }
            }
            case "setserver" -> {
                if (require(sender, args, 4)) {
                    plugin.configs().set("arena.yml", "arenas." + args[2] + ".server", args[3]);
                    plugin.reloadSettings();
                    plugin.messages().sendRaw(sender, "arena-set", "arena", args[2], "value", args[3]);
                }
            }
            case "settemplate" -> {
                if (require(sender, args, 4)) {
                    plugin.configs().set("arena.yml", "arenas." + args[2] + ".template", args[3]);
                    plugin.reloadSettings();
                    plugin.messages().sendRaw(sender, "arena-set", "arena", args[2], "value", args[3]);
                }
            }
            case "create" -> {
                if (require(sender, args, 3)) {
                    plugin.configs().set("arena.yml", "arenas." + args[2] + ".template", "");
                    plugin.configs().set("arena.yml", "arenas." + args[2] + ".world", "");
                    plugin.reloadSettings();
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
                    plugin.reloadSettings();
                    sender.sendMessage(cn("&a已删除竞技场 &f" + args[2]));
                }
            }
            case "setradius" -> {
                if (require(sender, args, 4)) {
                    plugin.configs().set("arena.yml", "arenas." + args[2] + ".radius", NumberUtil.toInt(args[3], 48));
                    plugin.reloadSettings();
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
                    plugin.reloadSettings();
                    plugin.messages().sendRaw(sender, "arena-set", "arena", args[2], "value", args[3] + "=" + args[4]);
                }
            }
            case "setgamerule" -> {
                if (require(sender, args, 5)) {
                    boolean value = Boolean.parseBoolean(args[4]);
                    plugin.configs().set("arena.yml",
                            "arenas." + args[2] + ".temporary-world.gamerules." + args[3], value);
                    plugin.reloadSettings();
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
                    plugin.reloadSettings();
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
                plugin.reloadSettings();
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
                // 承办服在别的子服时：先代理传送到承办服，抵达后再由承办服送到场地。
                String host = a.server != null && !a.server.isBlank()
                        ? a.server : plugin.settings().war.dedicatedServer;
                boolean remote = host != null && !host.isBlank() && !plugin.war().isHostServer(host);
                if (remote) {
                    if (!plugin.bus().isReady() || !plugin.bus().transfer().available()) {
                        sender.sendMessage(cn("&c跨服传送不可用，无法传送到承办服 &f" + host + "&c。"));
                        return;
                    }
                    sender.sendMessage(cn("&a正在把你传送到承办服 &f" + host + " &a的竞技场 &f" + a.id + "&a…"));
                    if (!plugin.war().transferToArena(p, host, a.id)) {
                        sender.sendMessage(cn("&c传送失败，请稍后再试。"));
                    }
                    return;
                }
                org.bukkit.World world = a.world != null && !a.world.isBlank() ? WorldResolver.resolve(a.world)
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
        if (!point.matches("[a-z0-9_-]{1,16}")) {
            plugin.messages().sendRaw(sender, "arena-usage");
            return;
        }
        String name = (point.equals("spec") || point.equals("spectator")) ? "spectator" : point;
        Location loc = player.getLocation();
        String base = "arenas." + arenaId + ".spawn-" + name + ".";
        plugin.configs().set("arena.yml", base + "x", round(loc.getX()));
        plugin.configs().set("arena.yml", base + "y", round(loc.getY()));
        plugin.configs().set("arena.yml", base + "z", round(loc.getZ()));
        plugin.configs().set("arena.yml", base + "yaw", round(loc.getYaw()));
        plugin.configs().set("arena.yml", base + "pitch", round(loc.getPitch()));
        plugin.reloadSettings();
        plugin.messages().sendRaw(sender, "arena-spawn-set", "arena", arenaId,
                "point", name.toUpperCase(Locale.ROOT), "world", loc.getWorld().getName());
    }

    /** 指定 A/B 队使用哪个命名出生点。 */
    private void setTeamPoint(CommandSender sender, String[] args) {
        if (!require(sender, args, 5)) {
            return;
        }
        String arenaId = args[2];
        ArenaDef arena = plugin.settings().arena(arenaId);
        if (arena == null) {
            sender.sendMessage(cn("&c竞技场不存在：" + arenaId));
            return;
        }
        String side = args[3].toLowerCase(Locale.ROOT);
        if (!side.equals("a") && !side.equals("b")) {
            sender.sendMessage(cn("&c队伍只能是 a 或 b"));
            return;
        }
        String point = args[4].toLowerCase(Locale.ROOT);
        if (arena.spawn(point) == null) {
            sender.sendMessage(cn("&c出生点不存在：" + point + "（先用 arena setspawn " + arenaId + " " + point + " 设置）"));
            return;
        }
        plugin.configs().set("arena.yml", "arenas." + arenaId + ".team-" + side + "-spawn", point);
        plugin.reloadSettings();
        plugin.messages().sendRaw(sender, "arena-set", "arena", arenaId, "value", "队伍" + side.toUpperCase(Locale.ROOT) + "→" + point);
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
                plugin.reloadSettings();
                plugin.messages().sendRaw(sender, "kit-saved", "key", key);
            }
            case "delete" -> {
                if (require(sender, args, 3)) {
                    plugin.configs().set("kit.yml", "kits." + args[2], null);
                    plugin.reloadSettings();
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
                    sender.sendMessage(cn("&7计分: &f击杀" + m.scoreKill + " 死亡" + m.scoreDeath
                            + " &7（目标分 objective 为预留，当前未启用）"));
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
                    if (plugin.war().forceSeason(args[2])) {
                        plugin.messages().sendRaw(sender, "season-started", "mode", args[2]);
                    } else {
                        plugin.messages().sendRaw(sender, "war-mode-unknown", "mode", args[2]);
                    }
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
                boolean ended = plugin.war().forceEnd(w.id());
                if (ended) {
                    plugin.messages().send(sender, "admin-forceend", "war", w.id());
                } else {
                    plugin.messages().send(sender, "admin-war-not-hosted", "host", w.hostServer());
                }
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
        if (args.length < 2 || args.length > 3 || (args.length == 3
                && !args[2].equalsIgnoreCase("force") && !args[2].equalsIgnoreCase("--force"))) {
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
                var result = plugin.importer().importFile(target.toPath(), args.length == 3);
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

    /** /guildadmin rename <工会> <新名称> [新标签] —— 绕开冷却与费用。 */
    private void rename(CommandSender sender, String[] args) {
        if (!require(sender, args, 3)) {
            return;
        }
        Guild g = find(args[1]);
        if (g == null) {
            plugin.messages().send(sender, "guild-not-found", "name", args[1]);
            return;
        }
        String newName = args[2];
        String newTag = args.length > 3 ? args[3] : null;
        if (sender instanceof Player player) {
            plugin.guilds().adminRename(player, g, newName, newTag);
        } else {
            // 控制台没有 Player：直接改内存并落库，同名校验仍生效
            plugin.guilds().adminRename(sender, g, newName, newTag);
        }
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
                java.util.UUID uuid = resolveUuid(args[1]);
                guildId = uuid == null ? null : plugin.repo().guildIdOf(uuid);
            } catch (Exception e) {
                guildId = null;
            }
            Guild g = guildId == null ? null : plugin.guilds().guild(guildId);
            String result = g != null ? g.name() : (guildId == null ? "无" : guildId);
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

    private void overrides(CommandSender sender, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("clear")) {
            String file = args.length >= 3 ? args[2] : "all";
            int n = plugin.configs().clearOverrides(file);
            plugin.reloadSettings();
            plugin.messages().sendRaw(sender, "admin-overrides-cleared", "count", n);
            return;
        }
        java.util.List<String> files = plugin.configs().overrideFiles();
        sender.sendMessage(cn("&6===== 游戏内编辑覆盖层 ====="));
        if (files.isEmpty()) {
            sender.sendMessage(cn("&7无（全部使用基础配置文件）"));
        } else {
            for (String f : files) {
                sender.sendMessage(cn("&7- &foverrides/" + f));
            }
        }
        sender.sendMessage(cn("&7清除：&f/guildadmin overrides clear <文件|all>"));
    }

    private void doctor(CommandSender sender) {
        var s = plugin.settings();
        java.util.List<String> ok = new java.util.ArrayList<>();
        java.util.List<String> bad = new java.util.ArrayList<>();

        if (plugin.serverId() == null || plugin.serverId().isBlank()) {
            bad.add("server-id 为空（server.yml）");
        } else {
            ok.add("server-id = " + plugin.serverId());
        }
        if (!plugin.db().healthy()) {
            bad.add("数据库连接不健康（database.yml）");
        } else {
            ok.add("数据库连接正常（" + (plugin.db().isSqlite() ? "SQLite" : "MySQL") + "）");
        }
        if (s.main.crossServerEnabled) {
            if (plugin.bus().isReady()) {
                ok.add("跨服 Redis 已连接");
                if (!plugin.bus().transfer().available()) {
                    bad.add("跨服传送不可用（检查代理 / server-id 是否为代理中已登记名）");
                }
            } else {
                bad.add("跨服已启用但 Redis 未连接（将退回单服）");
            }
        } else {
            ok.add("跨服未启用（单服模式）");
        }
        if (s.guild.createCost > 0) {
            if (plugin.economy().available()) {
                ok.add("经济插件可用（Vault）");
            } else {
                bad.add("guild.create-cost > 0 但无经济插件，费用将被忽略");
            }
        }
        if (s.guild.homeEnabled && !(plugin.guildHome() != null && plugin.guildHome().available())) {
            bad.add("guild.home.enabled=true 但 HuskHomes 不可用");
        }
        if (s.main.mcmmoEnabled && !(plugin.mcmmo() != null && plugin.mcmmo().available())) {
            bad.add("hooks.mcmmo.enabled=true 但未检测到 mcMMO");
        }
        if (s.war.enabled) {
            if (s.war.modes.isEmpty()) {
                bad.add("工会战已启用但无任何模式");
            }
            for (com.liu.liuguild.config.WarMode m : s.war.modes.values()) {
                com.liu.liuguild.config.ArenaDef a = s.arenas.arenas.get(
                        m.arena == null ? "" : m.arena.toLowerCase(Locale.ROOT));
                if (a == null) {
                    bad.add("模式 " + m.key + " 引用不存在的场地: " + m.arena);
                    continue;
                }
                if (a.hasTemplate()) {
                    boolean liuOk = plugin.dungeon() != null && plugin.dungeon().available();
                    if (!liuOk) {
                        bad.add("模式 " + m.key + " 使用模板但 liudungeon 不可用");
                    } else if (!plugin.dungeon().hasTemplate(a.template)) {
                        bad.add("场地 " + a.id + " 模板不存在: " + a.template);
                    } else {
                        ok.add("模式 " + m.key + " 模板可用: " + a.template);
                    }
                } else if (!a.tempWorld.builtin) {
                    org.bukkit.World w = a.world.isBlank()
                            ? (Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0))
                            : WorldResolver.resolve(a.world);
                    if (w == null) {
                        bad.add("场地 " + a.id + " 世界不存在: " + a.world);
                    } else {
                        ok.add("模式 " + m.key + " 世界可用: " + w.getName());
                    }
                } else {
                    ok.add("模式 " + m.key + " 使用内置临时世界（无需 liudungeon）");
                }
                var kp = s.kit.profile(m.kit);
                if (kp == null || (!kp.hasSnapshot() && kp.items.isEmpty() && kp.armor.isEmpty())) {
                    bad.add("模式 " + m.key + " 套装为空: " + m.kit);
                } else {
                    ok.add("模式 " + m.key + " 套装已配置: " + m.kit);
                }
            }
        }

        sender.sendMessage(cn("&6===== 部署自检（doctor）====="));
        for (String o : ok) {
            sender.sendMessage(cn("&a[✓] &7" + o));
        }
        for (String b : bad) {
            sender.sendMessage(cn("&c[✗] &7" + b));
        }
        sender.sendMessage(cn(bad.isEmpty() ? "&a全部通过，可用于生产。" : "&e共 &f" + bad.size() + " &e项需处理。"));
    }

    private void verify(CommandSender sender, String[] args) {
        boolean fix = args.length >= 2 && args[1].equalsIgnoreCase("fix");
        java.util.List<String> issues = plugin.guilds().verifyData(fix);
        sender.sendMessage(cn("&6===== 数据一致性校验 ====="));
        sender.sendMessage(cn("&7工会数：&f" + plugin.guilds().guildCount()
                + " &7模式：" + (fix ? "&c已修复" : "&e仅检查")));
        if (issues.isEmpty()) {
            sender.sendMessage(cn("&a未发现一致性问题。"));
            return;
        }
        sender.sendMessage(cn("&e发现 &f" + issues.size() + " &e个问题："));
        int shown = 0;
        for (String s : issues) {
            if (shown++ >= 20) {
                sender.sendMessage(cn("&8…还有 " + (issues.size() - 20) + " 条"));
                break;
            }
            sender.sendMessage(cn("&7- &f" + s));
        }
        if (!fix) {
            sender.sendMessage(cn("&7执行 &f/guildadmin verify fix &7以修正。"));
        }
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
            return startsWith(permittedSubs(sender), args[0]);
        }
        if (args.length == 2) {
            return switch (args[0].toLowerCase(Locale.ROOT)) {
                case "arena" -> startsWith(List.of("list", "info", "setspawn", "setteampoint", "setworld", "setserver", "settemplate",
                        "create", "remove", "setradius", "settempworld", "setgamerule", "setafter", "setendspawn", "tp"), args[1]);
                case "kit" -> startsWith(List.of("list", "save", "delete", "info"), args[1]);
                case "mode" -> startsWith(List.of("list", "info", "start"), args[1]);
                case "war" -> startsWith(List.of("list", "queue", "start", "stop", "tp", "score"), args[1]);
                case "reload", "status", "guilds", "cleanup" -> List.of();
                case "verify" -> startsWith(List.of("fix"), args[1]);
                case "overrides" -> startsWith(List.of("list", "clear"), args[1]);
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
        if (args.length == 3 && args[0].equalsIgnoreCase("import")) {
            return startsWith(List.of("force", "--force"), args[2]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("overrides") && args[1].equalsIgnoreCase("clear")) {
            List<String> options = new ArrayList<>(List.of("all"));
            options.addAll(java.util.Arrays.asList(com.liu.liuguild.config.ConfigManager.MODULES));
            return startsWith(options, args[2]);
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

    private List<String> permittedSubs(CommandSender sender) {
        List<String> out = new ArrayList<>();
        boolean parent = sender.hasPermission("liuguild.admin");
        for (String s : SUBS) {
            String req = SUB_PERMISSIONS.getOrDefault(s, "liuguild.admin");
            if (parent || sender.hasPermission(req) || s.equals("help")) {
                out.add(s);
            }
        }
        return out;
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
