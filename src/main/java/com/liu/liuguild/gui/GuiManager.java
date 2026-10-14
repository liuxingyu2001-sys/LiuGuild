package com.liu.liuguild.gui;

import com.liu.liuguild.LiuGuild;
import com.liu.liuguild.config.ArenaDef;
import com.liu.liuguild.config.ButtonDef;
import com.liu.liuguild.config.GuiSettings;
import com.liu.liuguild.config.IconDef;
import com.liu.liuguild.config.MenuDef;
import com.liu.liuguild.model.Guild;
import com.liu.liuguild.model.GuildMember;
import com.liu.liuguild.model.GuildRank;
import com.liu.liuguild.model.GuildWar;
import com.liu.liuguild.module.GuildModule;
import com.liu.liuguild.util.NumberUtil;
import com.liu.liuguild.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** GUI 菜单模块：主菜单 / 成员 / 列表 / 工会战 / 设置 / 图标。 */
public final class GuiManager implements GuildModule, Listener {

    private final LiuGuild plugin;
    private final ChatInput chatInput;

    public GuiManager(LiuGuild plugin) {
        this.plugin = plugin;
        this.chatInput = new ChatInput(plugin);
    }

    public ChatInput chatInput() {
        return chatInput;
    }

    /**
     * 统一输入入口：优先用 Sparrow 铁砧界面，失败/关闭时回退到聊天栏输入。
     * 所有“请输出…”的交互都应走这里，以充分利用前置插件。
     */
    public void requestText(Player player, String messageKey, java.util.function.Consumer<String> callback, Object... kv) {
        net.kyori.adventure.text.Component prompt = plugin.messages().component(messageKey, kv);
        com.liu.liuguild.hook.SparrowUiHook sparrow = plugin.sparrowUi();
        if (sparrow != null && sparrow.openTextInput(player, prompt, prompt, callback)) {
            return;
        }
        chatInput.request(player, plugin.messages().prefixed(messageKey, kv), callback);
    }

    @Override
    public String id() {
        return "gui";
    }

    @Override
    public int order() {
        return 40;
    }

    @Override
    public void enable(LiuGuild plugin) {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getPluginManager().registerEvents(chatInput, plugin);
    }

    // =====================================================================
    // Holder
    // =====================================================================

    public static final class Holder implements InventoryHolder {
        Inventory inv;
        final String menu;
        final String guildId;
        final int page;
        final Map<Integer, String> actions = new LinkedHashMap<>();

        Holder(String menu, String guildId, int page) {
            this.menu = menu;
            this.guildId = guildId;
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    // =====================================================================
    // 打开菜单
    // =====================================================================

    public void openMain(Player player) {
        Guild g = plugin.guilds().guildOf(player.getUniqueId());
        if (g == null) {
            // 未入会：进入傻瓜式引导菜单，而不是直接丢一个空列表。
            openNoGuild(player);
            return;
        }
        // 优先使用可选的 Sparrow UI 引擎；失败则回退内置 GUI。
        com.liu.liuguild.hook.SparrowUiHook sparrow = plugin.sparrowUi();
        if (sparrow != null && sparrow.openMain(player, g)) {
            return;
        }
        MenuDef def = menu("main");
        if (def == null) {
            return;
        }
        Map<String, String> ph = mainPlaceholders(player, g);
        Holder holder = new Holder("main", g.id(), 0);
        Inventory inv = create(holder, def, ph, player);
        for (Map.Entry<String, ButtonDef> e : def.buttons.entrySet()) {
            ButtonDef b = e.getValue();
            ItemStack item = "info".equals(e.getKey())
                    ? GuiUtil.guildIcon(plugin, player, g, b.fallback())
                    : GuiUtil.fromButton(player, b, ph);
            if ("info".equals(e.getKey()) && item.getItemMeta() != null) {
                var meta = item.getItemMeta();
                meta.displayName(GuiUtil.render(player, b.name, ph));
                List<Component> lore = new ArrayList<>();
                for (String line : b.lore) {
                    lore.add(GuiUtil.render(player, line, ph));
                }
                meta.lore(lore);
                item.setItemMeta(meta);
            }
            inv.setItem(clamp(b.slot), item);
            holder.actions.put(clamp(b.slot), actionId(e.getKey()));
        }
        player.openInventory(inv);
    }

    public void openMembers(Player player, Guild g, int page) {
        if (g == null) {
            return;
        }
        MenuDef def = menu("members");
        if (def == null || def.entry == null) {
            return;
        }
        List<GuildMember> members = new ArrayList<>(g.members());
        members.sort(Comparator.comparingInt((GuildMember m) -> -m.rank().weight())
                .thenComparingLong(GuildMember::joinedAt));
        Map<String, String> ph = placeholders(g);
        Holder holder = new Holder("members", g.id(), page);
        Inventory inv = create(holder, def, ph, player);
        int perPage = def.rows * 9 - 9;
        int from = Math.max(0, page * perPage);
        for (int i = 0; i < perPage && from + i < members.size(); i++) {
            GuildMember m = members.get(from + i);
            Map<String, String> mph = new LinkedHashMap<>(ph);
            mph.put("player", m.name());
            mph.put("rank", m.rank().display());
            mph.put("contributed", plugin.economy().format(m.contributed()));
            mph.put("kills", String.valueOf(m.kills()));
            mph.put("deaths", String.valueOf(m.deaths()));
            mph.put("joined", new java.text.SimpleDateFormat("yyyy-MM-dd").format(new java.util.Date(m.joinedAt())));
            ItemStack item = GuiUtil.build(player, def.entry.material, def.entry.fallbackMaterial,
                    def.entry.name, def.entry.lore, mph, m.uuid());
            int slot = i;
            inv.setItem(slot, item);
            holder.actions.put(slot, "member:" + m.uuid());
        }
        addBack(inv, holder, def, ph, player, "back:main", page, "members", members.size(), perPage);
        player.openInventory(inv);
    }

    public void openNoGuild(Player player) {
        MenuDef def = menu("noguild");
        if (def == null || def.buttons.isEmpty()) {
            // 缺配置时退而求其次：至少展示工会列表（内含创建入口）。
            openList(player, 0);
            return;
        }
        Map<String, String> ph = placeholders(null);
        Holder holder = new Holder("noguild", "", 0);
        Inventory inv = create(holder, def, ph, player);
        for (Map.Entry<String, ButtonDef> e : def.buttons.entrySet()) {
            ButtonDef b = e.getValue();
            inv.setItem(clamp(b.slot), GuiUtil.fromButton(player, b, ph));
            holder.actions.put(clamp(b.slot), actionId(e.getKey()));
        }
        player.openInventory(inv);
    }

    public void openList(Player player, int page) {
        MenuDef def = menu("list");
        if (def == null || def.entry == null) {
            return;
        }
        List<Guild> guilds = new ArrayList<>(plugin.guilds().all());
        guilds.sort((a, b) -> Long.compare(b.points(), a.points()));
        Guild self = plugin.guilds().guildOf(player.getUniqueId());
        Map<String, String> ph = basePlaceholders();
        ph.put("count", String.valueOf(guilds.size()));
        Holder holder = new Holder("list", self == null ? "" : self.id(), page);
        Inventory inv = create(holder, def, ph, player);
        int perPage = def.rows * 9 - 9;
        int from = Math.max(0, page * perPage);
        for (int i = 0; i < perPage && from + i < guilds.size(); i++) {
            Guild g = guilds.get(from + i);
            Map<String, String> gph = placeholders(g);
            ItemStack item = GuiUtil.guildIcon(plugin, player, g, def.entry.fallback());
            if (item.getItemMeta() != null) {
                var meta = item.getItemMeta();
                meta.displayName(GuiUtil.render(player, def.entry.name, gph));
                List<Component> lore = new ArrayList<>();
                for (String line : def.entry.lore) {
                    lore.add(GuiUtil.render(player, line, gph));
                }
                meta.lore(lore);
                item.setItemMeta(meta);
            }
            inv.setItem(i, item);
            holder.actions.put(i, "list_entry:" + g.id());
        }
        for (Map.Entry<String, ButtonDef> e : def.buttons.entrySet()) {
            if ("create".equals(e.getKey()) && self != null) {
                continue;
            }
            ButtonDef b = e.getValue();
            inv.setItem(clamp(b.slot), GuiUtil.fromButton(player, b, ph));
            holder.actions.put(clamp(b.slot), actionId(e.getKey()));
        }
        addBack(inv, holder, def, ph, player, "back:main", page, "list", guilds.size(), perPage);
        player.openInventory(inv);
    }

    public void openWar(Player player, Guild g) {
        if (g == null) {
            return;
        }
        MenuDef def = menu("war");
        if (def == null) {
            return;
        }
        Map<String, String> ph = placeholders(g);
        GuildWar war = plugin.war() == null ? null : plugin.war().warOf(g.id());
        String queuedMode = plugin.war() == null ? null : plugin.war().queuedMode(g.id());
        ph.put("state", war == null ? (queuedMode == null ? "空闲" : "排队中") : war.state().display());
        ph.put("enemy", war == null ? "-" : (war.guildA().equals(g.id()) ? war.nameB() : war.nameA()));
        boolean home = war != null && war.guildA().equals(g.id());
        ph.put("score", war == null ? "0" : String.valueOf(home ? war.scoreA() : war.scoreB()));
        ph.put("enemy_score", war == null ? "0" : String.valueOf(home ? war.scoreB() : war.scoreA()));
        ph.put("lives", String.valueOf(plugin.war() == null ? 0 : plugin.war().lives(player.getUniqueId())));
        ph.put("arena", plugin.war() == null ? "-" : plugin.war().currentArena().id);
        ph.put("host", war == null ? "-" : war.hostServer());
        ph.put("history_count", String.valueOf(g.wins() + g.losses()));
        Holder holder = new Holder("war", g.id(), 0);
        Inventory inv = create(holder, def, ph, player);
        // 动态列出各工会战模式（点击参与匹配）
        int slot = 0;
        int entryLimit = def.rows * 9 - 9;
        for (com.liu.liuguild.config.WarMode m : plugin.settings().war.enabledModes()) {
            if (slot >= entryLimit) {
                break;
            }
            Map<String, String> mph = new LinkedHashMap<>(ph);
            mph.put("mode", m.display);
            mph.put("mode_key", m.key);
            mph.put("mode_state", m.key.equals(queuedMode) ? "排队中" : (war != null ? "战斗中" : "可参与"));
            mph.put("mode_min", String.valueOf(m.minMembers));
            mph.put("mode_team", String.valueOf(m.teamSize));
            mph.put("mode_fair", m.fairEquipment ? "公平装备" : "自由装备");
            mph.put("mode_health", m.fairHealth ? "公平血量" : "原生血量");
            mph.put("mode_arena", m.arena);
            mph.put("mode_schedule", m.schedule.isEmpty() ? "随时" : String.join(", ",
                    m.schedule.stream().map(com.liu.liuguild.util.TimeUtil::prettySchedule).toList()));
            mph.put("mode_duration", String.valueOf(m.durationSeconds));
            ButtonDef tpl = def.entry;
            if (tpl == null) {
                continue;
            }
            inv.setItem(slot, GuiUtil.fromButton(player, tpl, mph));
            holder.actions.put(slot, "war_queue:" + m.key);
            slot++;
        }
        // 静态按钮（退出队列 / 状态 / 历史 / 返回）
        for (Map.Entry<String, ButtonDef> e : def.buttons.entrySet()) {
            String key = e.getKey();
            if ("leave_queue".equals(key) && queuedMode == null) {
                continue;
            }
            ButtonDef b = e.getValue();
            inv.setItem(clamp(b.slot), GuiUtil.fromButton(player, b, ph));
            holder.actions.put(clamp(b.slot), actionId(key));
        }
        player.openInventory(inv);
    }

    /** 管理员 GUI：状态 / 模式 / 场地 / 装备 / 重载。 */
    public void openAdmin(Player player) {
        MenuDef def = menu("admin");
        if (def == null) {
            return;
        }
        Map<String, String> ph = placeholders(plugin.guilds().guildOf(player.getUniqueId()));
        ph.put("guild_count", String.valueOf(plugin.guilds().guildCount()));
        ph.put("queue", String.valueOf(plugin.war().queueSize()));
        ph.put("live_wars", String.valueOf(plugin.war().liveWars().size()));
        ph.put("server", plugin.serverId());
        ph.put("storage", plugin.db().isSqlite() ? "SQLite" : "MySQL");
        ph.put("cross", plugin.bus().isReady() ? "已连接" : "未连接");
        Holder holder = new Holder("admin", "", 0);
        Inventory inv = create(holder, def, ph, player);
        for (Map.Entry<String, ButtonDef> e : def.buttons.entrySet()) {
            ButtonDef b = e.getValue();
            inv.setItem(clamp(b.slot), GuiUtil.fromButton(player, b, ph));
            holder.actions.put(clamp(b.slot), actionId(e.getKey()));
        }
        player.openInventory(inv);
    }

    public void openSettings(Player player, Guild g) {
        if (g == null) {
            return;
        }
        MenuDef def = menu("settings");
        if (def == null) {
            return;
        }
        Map<String, String> ph = placeholders(g);
        IconDef icon = plugin.settings().icons.icon(g.icon());
        ph.put("icon_name", icon == null ? g.icon() : icon.display);
        ph.put("icon_cost", plugin.economy().format(plugin.settings().guild.iconChangeCost));
        ph.put("open", g.open() ? "开放" : "邀请");
        Holder holder = new Holder("settings", g.id(), 0);
        Inventory inv = create(holder, def, ph, player);
        for (Map.Entry<String, ButtonDef> e : def.buttons.entrySet()) {
            ButtonDef b = e.getValue();
            inv.setItem(clamp(b.slot), GuiUtil.fromButton(player, b, ph));
            holder.actions.put(clamp(b.slot), actionId(e.getKey()));
        }
        player.openInventory(inv);
    }

    public void openIcons(Player player, Guild g, int page) {
        if (g == null) {
            return;
        }
        MenuDef def = menu("icons");
        if (def == null || def.entry == null) {
            return;
        }
        List<IconDef> icons = new ArrayList<>();
        for (IconDef def2 : plugin.settings().icons.icons.values()) {
            if (def2.hasPermission(player)) {
                icons.add(def2);
            }
        }
        Map<String, String> ph = placeholders(g);
        Holder holder = new Holder("icons", g.id(), page);
        Inventory inv = create(holder, def, ph, player);
        int perPage = def.rows * 9 - 9;
        int from = Math.max(0, page * perPage);
        for (int i = 0; i < perPage && from + i < icons.size(); i++) {
            IconDef icon = icons.get(from + i);
            Map<String, String> iph = new LinkedHashMap<>(ph);
            iph.put("icon_name", icon.display);
            iph.put("icon_cost", plugin.economy().format(plugin.settings().guild.iconChangeCost));
            ItemStack item = plugin.craftEngine() == null
                    ? new ItemStack(icon.fallback())
                    : plugin.craftEngine().resolve(icon.item, icon.fallback());
            if (item.getItemMeta() != null) {
                var meta = item.getItemMeta();
                meta.displayName(GuiUtil.render(player, def.entry.name, iph));
                List<Component> lore = new ArrayList<>();
                for (String line : def.entry.lore) {
                    lore.add(GuiUtil.render(player, line, iph));
                }
                meta.lore(lore);
                item.setItemMeta(meta);
            }
            inv.setItem(i, item);
            holder.actions.put(i, "icon:" + icon.id);
        }
        addBack(inv, holder, def, ph, player, "back:settings", page, "icons", icons.size(), perPage);
        player.openInventory(inv);
    }

    // =====================================================================
    // 点击处理
    // =====================================================================

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (top.getHolder() instanceof Holder holder) {
            event.setCancelled(true);
            if (!(event.getWhoClicked() instanceof Player player)) {
                return;
            }
            String action = holder.actions.get(event.getRawSlot());
            if (action == null) {
                return;
            }
            clickRight = event.isRightClick();
            clickShift = event.isShiftClick();
            try {
                sound(player, Sound.UI_BUTTON_CLICK, 0.4f, 1.4f);
                handle(player, holder, action);
            } finally {
                clickRight = false;
                clickShift = false;
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }

    private void handle(Player player, Holder holder, String action) {
        Guild g = plugin.guilds().guildOf(player.getUniqueId());
        switch (action) {
            case "noop" -> {
            }
            case "close" -> player.closeInventory();
            case "members" -> openMembers(player, require(g, player), 0);
            case "war" -> openWar(player, require(g, player));
            case "settings" -> openSettings(player, require(g, player));
            case "list" -> openList(player, 0);
            case "checkin" -> {
                player.closeInventory();
                plugin.guilds().checkin(player);
            }
            case "bank" -> bankAction(player, g, clickRight);
            case "home" -> {
                if (g == null) {
                    plugin.messages().send(player, "not-in-guild");
                    return;
                }
                player.closeInventory();
                if (plugin.guildHome() == null) {
                    plugin.messages().send(player, "home-unavailable");
                    return;
                }
                plugin.guildHome().command(player, clickRight
                        ? new String[]{"home", "set"}
                        : new String[]{"home"});
            }
            case "leave" -> {
                if (g != null) {
                    GuildMember self = g.member(player.getUniqueId());
                    if (self != null && self.rank() == GuildRank.LEADER) {
                        plugin.messages().send(player, "leave-leader-denied");
                        return;
                    }
                    player.closeInventory();
                    confirm(player, "confirm-leave", g.name(), () -> plugin.guilds().leave(player));
                }
            }
            case "back:main" -> openMain(player);
            case "list:create" -> {
                if (plugin.guilds().guildOf(player.getUniqueId()) != null) {
                    plugin.messages().send(player, "already-in-guild", "guild", plugin.guilds().guildOf(player.getUniqueId()).name());
                    return;
                }
                requestText(player, "input-guild-name", text -> {
                    String trimmed = text.trim();
                    if (trimmed.isEmpty()) {
                        return;
                    }
                    String[] parts = trimmed.split("\\s+", 2);
                    String name = parts[0];
                    String tag = parts.length > 1 ? parts[1] : name;
                    plugin.guilds().create(player, name, tag);
                });
            }
            case "back:settings" -> openSettings(player, require(g, player));
            case "admin:status" -> {
                player.closeInventory();
                for (String line : plugin.statusLines()) {
                    player.sendMessage(Text.mm(line.replace('&', '\u00a7')));
                }
            }
            case "admin:arenas" -> openArenaList(player, 0);
            case "admin:guilds" -> openGuildList(player, 0);
            case "admin:wars" -> openWarList(player);
            case "admin:kits" -> openKitList(player);
            case "admin:cleanup" -> {
                player.closeInventory();
                var conf = plugin.settings().guild;
                var targets = plugin.guilds().purgeInactive(true);
                plugin.messages().sendRaw(player, "admin-cleanup-scan", "days", conf.cleanupInactiveDays, "count", targets.size());
            }
            case "admin:verify" -> {
                player.closeInventory();
                java.util.List<String> issues = plugin.guilds().verifyData(false);
                plugin.messages().sendRaw(player, "admin-verify-result", "count", issues.size());
                int shown = 0;
                for (String s : issues) {
                    if (shown++ >= 10) {
                        break;
                    }
                    player.sendMessage(Text.mm("<gray>- <white>" + s));
                }
            }
            case "admin:modes" -> showAdminList(player, plugin.settings().war.modes.size(), "工会战模式", id -> {
                com.liu.liuguild.config.WarMode m = plugin.settings().mode(id);
                return "&f" + m.key + " &7" + m.display + " 场地=" + m.arena + " 公平装备=" + m.fairEquipment;
            }, plugin.settings().war.modes.keySet());
            case "admin:arena" -> showAdminList(player, plugin.settings().arenas.arenas.size(), "竞技场", id -> {
                com.liu.liuguild.config.ArenaDef a = plugin.settings().arena(id);
                return "&f" + a.id + " 承办=" + (a.server.isBlank() ? "-" : a.server)
                        + " 世界=" + (a.world.isBlank() ? "主世界" : a.world)
                        + " 模板=" + (a.hasTemplate() ? a.template : "固定坐标");
            }, plugin.settings().arenas.arenas.keySet());
            case "admin:kit" -> showAdminList(player, plugin.settings().kit.kits.size(), "装备套装", id -> {
                var k = plugin.settings().kit.profile(id);
                return "&f" + k.key + " 快照=" + (k.hasSnapshot() ? "有" : "无") + " 物品=" + k.items.size();
            }, plugin.settings().kit.kits.keySet());
            case "admin:reload" -> {
                plugin.reloadSettings();
                plugin.messages().send(player, "reload-done");
                openAdmin(player);
            }
            case "war_leave" -> {
                if (g != null) {
                    plugin.war().leaveQueue(player, g);
                    openWar(player, g);
                }
            }
            case "war_history" -> {
                if (g != null) {
                    showHistory(player, g);
                }
            }
            case "war_status" -> {
                if (g != null) {
                    GuildWar w = plugin.war().warOf(g.id());
                    if (w == null) {
                        String q = plugin.war().queuedMode(g.id());
                        plugin.messages().send(player, q == null ? "war-not-in-war" : "war-queue-joined",
                                "mode", plugin.settings().mode(q == null ? "match" : q).display);
                    } else {
                        boolean home = w.guildA().equals(g.id());
                        plugin.messages().sendRaw(player, "war-score", "guild_a", w.nameA(), "guild_b", w.nameB(),
                                "score_a", w.scoreA(), "score_b", w.scoreB());
                        player.sendMessage(Text.mm("<gray>状态: " + w.state().display() + " 对手: "
                                + (home ? w.nameB() : w.nameA())));
                    }
                }
            }
            case "set:icon" -> openIcons(player, require(g, player), 0);
            case "set:open" -> {
                if (g != null && plugin.guilds().canManageSettings(player, g)) {
                    g.open(!g.open());
                    plugin.guilds().saveGuild(g);
                    plugin.messages().send(player, "set-open", "value", g.open() ? "开放" : "邀请");
                    openSettings(player, g);
                }
            }
            case "set:notice" -> {
                if (g != null && plugin.guilds().canManageSettings(player, g)) {
                    requestText(player, "input-notice", text -> {
                        plugin.guilds().setNotice(player, g, text);
                        openSettings(player, g);
                    });
                }
            }
            case "set:desc" -> {
                if (g != null && plugin.guilds().canManageSettings(player, g)) {
                    requestText(player, "input-desc", text -> {
                        plugin.guilds().setDescription(player, g, text);
                        openSettings(player, g);
                    });
                }
            }
            default -> handleDynamic(player, holder, action);
        }
    }

    private void handleDynamic(Player player, Holder holder, String action) {
        Guild g = plugin.guilds().guild(holder.guildId);
        if (action.startsWith("member:")) {
            UUID target = UUID.fromString(action.substring("member:".length()));
            GuildMember m = g == null ? null : g.member(target);
            if (g == null || m == null) {
                return;
            }
            Guild guild = g;
            if (isShiftClick() && isRightClick()) {
                // Shift+右键：踢出成员（需确认）
                confirm(player, "confirm-kick", m.name(), () -> plugin.guilds().kick(player, guild, target, m.name()));
            } else if (isShiftClick()) {
                // Shift+左键：转让会长（需确认，事后刷新列表）
                confirm(player, "confirm-transfer", m.name(), () -> {
                    plugin.guilds().transfer(player, guild, target, m.name());
                    openMembers(player, guild, holder.page);
                });
            } else if (isRightClick()) {
                plugin.guilds().demote(player, guild, target, m.name());
                openMembers(player, guild, holder.page);
            } else if (m.rank() == GuildRank.OFFICER) {
                plugin.messages().send(player, "promote-already-officer", "player", m.name());
            } else {
                plugin.guilds().promote(player, guild, target, m.name());
                openMembers(player, guild, holder.page);
            }
        } else if (action.startsWith("list_entry:")) {
            String id = action.substring("list_entry:".length());
            Guild target = plugin.guilds().guild(id);
            if (target != null) {
                plugin.guilds().join(player, target, false);
                player.closeInventory();
            }
        } else if (action.startsWith("icon:")) {
            String iconId = action.substring("icon:".length());
            if (g != null && plugin.guilds().setIcon(player, g, iconId)) {
                openSettings(player, g);
            }
        } else if (action.startsWith("war_queue:")) {
            if (g != null) {
                plugin.war().queue(player, g, action.substring("war_queue:".length()));
                openWar(player, g);
            }
        } else if (action.startsWith("page_members:")) {
            openMembers(player, g, Integer.parseInt(action.substring("page_members:".length())));
        } else if (action.startsWith("page_list:")) {
            openList(player, Integer.parseInt(action.substring("page_list:".length())));
        } else if (action.startsWith("page_icons:")) {
            openIcons(player, g, Integer.parseInt(action.substring("page_icons:".length())));
        } else if (action.startsWith("page_arenas:")) {
            openArenaList(player, Integer.parseInt(action.substring("page_arenas:".length())));
        } else if (action.startsWith("page_guilds:")) {
            openGuildList(player, Integer.parseInt(action.substring("page_guilds:".length())));
        } else if (action.startsWith("arena_edit:")) {
            openArenaEditor(player, action.substring("arena_edit:".length()));
        } else if (action.startsWith("arena_tp:")) {
            String id = action.substring("arena_tp:".length());
            ArenaDef a = plugin.settings().arena(id);
            org.bukkit.World world = a.world != null && !a.world.isBlank() ? Bukkit.getWorld(a.world)
                    : (Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0));
            if (world == null) {
                plugin.messages().send(player, "home-failed");
            } else {
                player.closeInventory();
                player.teleport(a.spawnA.toLocation(world));
            }
        } else if (action.startsWith("arena_setspawn:")) {
            String[] parts = action.substring("arena_setspawn:".length()).split(":", 2);
            setArenaSpawn(player, parts[0], parts.length > 1 ? parts[1] : "a");
        } else if (action.startsWith("arena_endspawn:")) {
            setArenaEndSpawn(player, action.substring("arena_endspawn:".length()));
        } else if (action.startsWith("arena_input:")) {
            String[] parts = action.substring("arena_input:".length()).split(":", 2);
            promptArenaInput(player, parts[0], parts.length > 1 ? parts[1] : "world");
        } else if (action.startsWith("arena_cycle:")) {
            String[] parts = action.substring("arena_cycle:".length()).split(":", 2);
            cycleArena(player, parts[0], parts.length > 1 ? parts[1] : "environment");
        } else if (action.startsWith("arena_toggle:")) {
            String[] parts = action.substring("arena_toggle:".length()).split(":", 2);
            toggleArena(player, parts[0], parts.length > 1 ? parts[1] : "auto-return");
        } else if (action.startsWith("arena_delete:")) {
            String id = action.substring("arena_delete:".length());
            plugin.configs().remove("arena.yml", "arenas." + id);
            plugin.reloadSettings();
            openArenaList(player, 0);
        } else if (action.startsWith("guild_edit:")) {
            openGuildEditor(player, action.substring("guild_edit:".length()));
        } else if (action.startsWith("guild_disband:")) {
            String id = action.substring("guild_disband:".length());
            Guild target = plugin.guilds().guild(id);
            if (target != null) {
                confirm(player, "confirm-disband", target.name(), () -> {
                    plugin.guilds().disband(target, true);
                    plugin.messages().send(player, "admin-disband", "guild", target.name());
                    openGuildList(player, 0);
                });
            }
        } else if (action.startsWith("guild_input:")) {
            String[] parts = action.substring("guild_input:".length()).split(":", 2);
            promptGuildInput(player, parts[0], parts.length > 1 ? parts[1] : "level");
        } else if (action.startsWith("war_edit:")) {
            openWarEditor(player, action.substring("war_edit:".length()));
        } else if (action.startsWith("war_stop:")) {
            plugin.war().forceEnd(action.substring("war_stop:".length()));
            openWarList(player);
        } else if (action.startsWith("war_scoreinput:")) {
            String[] parts = action.substring("war_scoreinput:".length()).split(":", 2);
            promptWarScore(player, parts[0], parts.length > 1 ? parts[1] : "a");
        } else if (action.startsWith("war_tp:")) {
            player.closeInventory();
            if (!plugin.war().teleportToArena(player)) {
                plugin.messages().send(player, "war-transfer-failed");
            }
        } else if (action.startsWith("kit_edit:")) {
            openKitEditor(player, action.substring("kit_edit:".length()));
        } else if (action.startsWith("kit_save:")) {
            saveKitFromPlayer(player, action.substring("kit_save:".length()));
        } else if (action.startsWith("kit_delete:")) {
            String key = sanitizeKey(action.substring("kit_delete:".length()));
            plugin.configs().remove("kit.yml", "kits." + key);
            plugin.reloadSettings();
            plugin.messages().sendRaw(player, "kit-deleted", "key", key);
            openKitList(player);
        } else if (action.equals("kit_new")) {
            player.closeInventory();
            requestText(player, "kit-input", text -> {
                saveKitFromPlayer(player, text.trim().toLowerCase(java.util.Locale.ROOT));
            });
        }
    }

    private static String sanitizeKey(String raw) {
        if (raw == null) {
            return "";
        }
        String k = raw.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_\\-]", "");
        return k.length() > 32 ? k.substring(0, 32) : k;
    }

    public void openKitList(Player player) {
        Holder holder = new Holder("kit_list", "", 0);
        Inventory inv = simpleInventory(player, holder, 6, "<gold>装备套装管理", Map.of());
        int slot = 0;
        for (Map.Entry<String, com.liu.liuguild.config.KitSettings.KitProfile> e : plugin.settings().kit.kits.entrySet()) {
            var k = e.getValue();
            inv.setItem(slot, GuiUtil.build(player, "DIAMOND_CHESTPLATE", "DIAMOND_CHESTPLATE",
                    "<white>" + e.getKey(), List.of(
                            "<gray>快照：" + (k.hasSnapshot() ? "<green>已保存" : "<dark_gray>无"),
                            "<gray>物品：<white>" + k.items.size() + "  <gray>效果：<white>" + k.effects.size(),
                            "<gray>清空背包：<white>" + k.clearInventory,
                            "", "<yellow>点击编辑"), Map.of(), null));
            holder.actions.put(slot, "kit_edit:" + e.getKey());
            slot++;
        }
        inv.setItem(49, GuiUtil.build(player, "ANVIL", "ANVIL", "<green>新建套装（使用当前装备）",
                List.of("<gray>点击后输入名字"), Map.of(), null));
        holder.actions.put(49, "kit_new");
        inv.setItem(53, GuiUtil.build(player, "ARROW", "ARROW", "<white>返回", List.of(), Map.of(), null));
        holder.actions.put(53, "admin:wars_back");
        player.openInventory(inv);
    }

    public void openKitEditor(Player player, String key) {
        var k = plugin.settings().kit.profile(key);
        Holder holder = new Holder("kit_edit", "", 0);
        Inventory inv = simpleInventory(player, holder, 3, "<gold>套装 <white>" + key, Map.of());
        inv.setItem(11, GuiUtil.build(player, "DIAMOND_CHESTPLATE", "DIAMOND_CHESTPLATE",
                "<white>保存当前装备到该套装", List.of("<gray>覆盖已有快照"), Map.of(), null));
        holder.actions.put(11, "kit_save:" + key);
        inv.setItem(13, GuiUtil.build(player, "PAPER", "PAPER", "<white>详细信息",
                List.of("<gray>物品：<white>" + k.items.size(), "<gray>效果：<white>" + k.effects.size(),
                        "<gray>快照：" + (k.hasSnapshot() ? "<green>已保存" : "<dark_gray>无")), Map.of(), null));
        holder.actions.put(13, "noop");
        inv.setItem(15, GuiUtil.build(player, "TNT", "TNT", "<red>删除该套装", List.of(), Map.of(), null));
        holder.actions.put(15, "kit_delete:" + key);
        inv.setItem(22, GuiUtil.build(player, "ARROW", "ARROW", "<white>返回列表", List.of(), Map.of(), null));
        holder.actions.put(22, "admin:kits");
        player.openInventory(inv);
    }

    private void saveKitFromPlayer(Player player, String key) {
        key = sanitizeKey(key);
        if (key.isBlank()) {
            plugin.messages().send(player, "kit-input");
            return;
        }
        String snapshot = plugin.kits().capture(player);
        plugin.configs().set("kit.yml", "kits." + key + ".enabled", true);
        plugin.configs().set("kit.yml", "kits." + key + ".snapshot", snapshot);
        plugin.reloadSettings();
        plugin.messages().sendRaw(player, "kit-saved", "key", key);
        openKitEditor(player, key);
    }

    public void openWarList(Player player) {
        Holder holder = new Holder("war_list", "", 0);
        Inventory inv = simpleInventory(player, holder, 6, "<gold>工会战管理", Map.of());
        List<GuildWar> wars = plugin.war().liveWars();
        int slot = 0;
        for (GuildWar w : wars) {
            inv.setItem(slot, GuiUtil.build(player, "NETHERITE_SWORD", "NETHERITE_SWORD",
                    "<white>" + w.nameA() + " <yellow>" + w.scoreA() + " : " + w.scoreB() + " <white>" + w.nameB(),
                    List.of("<gray>模式：<white>" + plugin.settings().mode(w.mode()).display,
                            "<gray>状态：<white>" + w.state().display(),
                            "<gray>承办：<white>" + w.hostServer(),
                            "<gray>竞技场：<white>" + w.arenaWorld(),
                            "", "<yellow>点击管理"), Map.of(), null));
            holder.actions.put(slot, "war_edit:" + w.id());
            slot++;
        }
        if (wars.isEmpty()) {
            inv.setItem(22, GuiUtil.build(player, "BARRIER", "BARRIER", "<gray>当前没有进行中的工会战", List.of(), Map.of(), null));
        }
        inv.setItem(49, GuiUtil.build(player, "ARROW", "ARROW", "<white>返回", List.of(), Map.of(), null));
        holder.actions.put(49, "admin:wars_back");
        player.openInventory(inv);
    }

    public void openWarEditor(Player player, String warId) {
        GuildWar w = plugin.war().warById(warId);
        if (w == null) {
            plugin.messages().send(player, "war-not-in-war");
            openWarList(player);
            return;
        }
        Holder holder = new Holder("war_edit", "", 0);
        Inventory inv = simpleInventory(player, holder, 3, "<gold>工会战 <white>" + w.nameA() + " <gray>vs <white>" + w.nameB(), Map.of());
        inv.setItem(10, GuiUtil.build(player, "COMPASS", "COMPASS", "<white>传送到竞技场", List.of(), Map.of(), null));
        holder.actions.put(10, "war_tp:" + warId);
        inv.setItem(12, GuiUtil.build(player, "PAPER", "PAPER", "<white>改 A 方比分：<yellow>" + w.scoreA(),
                List.of("<gray>点击输入新值"), Map.of(), null));
        holder.actions.put(12, "war_scoreinput:" + warId + ":a");
        inv.setItem(14, GuiUtil.build(player, "PAPER", "PAPER", "<white>改 B 方比分：<yellow>" + w.scoreB(),
                List.of("<gray>点击输入新值"), Map.of(), null));
        holder.actions.put(14, "war_scoreinput:" + warId + ":b");
        inv.setItem(16, GuiUtil.build(player, "TNT", "TNT", "<red>强制结束比赛",
                List.of("<gray>将立即结算奖励"), Map.of(), null));
        holder.actions.put(16, "war_stop:" + warId);
        player.openInventory(inv);
    }

    private void promptWarScore(Player player, String warId, String side) {
        player.closeInventory();
        requestText(player, "arena-input", text -> {
            int value = NumberUtil.toInt(text.trim(), 0);
            plugin.war().setScore(warId, side, value);
            openWarEditor(player, warId);
        }, "key", "比分 " + side.toUpperCase(java.util.Locale.ROOT));
    }

    // ---- 竞技场编辑动作 ----

    private void setArenaSpawn(Player player, String arenaId, String point) {
        String path = switch (point.toLowerCase(java.util.Locale.ROOT)) {
            case "b" -> "spawn-b";
            case "spec", "spectator" -> "spawn-spectator";
            default -> "spawn-a";
        };
        Location loc = player.getLocation();
        String base = "arenas." + arenaId + "." + path + ".";
        plugin.configs().set("arena.yml", base + "x", round(loc.getX()));
        plugin.configs().set("arena.yml", base + "y", round(loc.getY()));
        plugin.configs().set("arena.yml", base + "z", round(loc.getZ()));
        plugin.configs().set("arena.yml", base + "yaw", round(loc.getYaw()));
        plugin.configs().set("arena.yml", base + "pitch", round(loc.getPitch()));
        plugin.reloadSettings();
        plugin.messages().sendRaw(player, "arena-spawn-set", "arena", arenaId,
                "point", point.toUpperCase(java.util.Locale.ROOT), "world", loc.getWorld().getName());
        openArenaEditor(player, arenaId);
    }

    private void setArenaEndSpawn(Player player, String arenaId) {
        Location loc = player.getLocation();
        String base = "arenas." + arenaId + ".after-war.end-spawn.";
        plugin.configs().set("arena.yml", base + "enabled", true);
        plugin.configs().set("arena.yml", base + "world", loc.getWorld().getName());
        plugin.configs().set("arena.yml", base + "x", round(loc.getX()));
        plugin.configs().set("arena.yml", base + "y", round(loc.getY()));
        plugin.configs().set("arena.yml", base + "z", round(loc.getZ()));
        plugin.configs().set("arena.yml", base + "yaw", round(loc.getYaw()));
        plugin.configs().set("arena.yml", base + "pitch", round(loc.getPitch()));
        plugin.reloadSettings();
        plugin.messages().send(player, "arena-endspawn-set", "arena", arenaId, "world", loc.getWorld().getName());
        openArenaEditor(player, arenaId);
    }

    private void promptArenaInput(Player player, String arenaId, String key) {
        player.closeInventory();
        requestText(player, "arena-input", text -> {
            String value = text.trim();
            if (key.equalsIgnoreCase("radius")) {
                plugin.configs().set("arena.yml", "arenas." + arenaId + ".radius", NumberUtil.toInt(value, 48));
            } else if (key.equalsIgnoreCase("environment")) {
                plugin.configs().set("arena.yml", "arenas." + arenaId + ".temporary-world.environment", value);
            } else {
                plugin.configs().set("arena.yml", "arenas." + arenaId + "." + key, value);
            }
            plugin.reloadSettings();
            openArenaEditor(player, arenaId);
        }, "key", key);
    }

    private void cycleArena(Player player, String arenaId, String key) {
        ArenaDef a = plugin.settings().arena(arenaId);
        String base = "arenas." + arenaId + ".temporary-world.";
        switch (key.toLowerCase(java.util.Locale.ROOT)) {
            case "environment" -> {
                String[] values = {"NORMAL", "NETHER", "THE_END"};
                String next = next(values, a.tempWorld.environment);
                plugin.configs().set("arena.yml", base + "environment", next);
            }
            case "time" -> {
                long[] values = {-1, 0, 6000, 12000, 18000};
                long next = values[0];
                for (int i = 0; i < values.length; i++) {
                    if (values[i] == a.tempWorld.time) {
                        next = values[(i + 1) % values.length];
                        break;
                    }
                }
                plugin.configs().set("arena.yml", base + "time", next);
            }
            case "weather" -> {
                String[] values = {"", "clear", "rain", "thunder"};
                plugin.configs().set("arena.yml", base + "weather", next(values, a.tempWorld.weather));
            }
            case "difficulty" -> {
                String[] values = {"", "PEACEFUL", "EASY", "NORMAL", "HARD"};
                plugin.configs().set("arena.yml", base + "difficulty", next(values, a.tempWorld.difficulty));
            }
            case "world-type" -> {
                String[] values = {"flat", "normal"};
                plugin.configs().set("arena.yml", base + "world-type", next(values, a.tempWorld.worldType));
            }
            default -> {
            }
        }
        plugin.reloadSettings();
        openArenaEditor(player, arenaId);
    }

    private void toggleArena(Player player, String arenaId, String key) {
        ArenaDef a = plugin.settings().arena(arenaId);
        switch (key.toLowerCase(java.util.Locale.ROOT)) {
            case "keep-on-end" -> plugin.configs().set("arena.yml", "arenas." + arenaId + ".temporary-world.keep-on-end", !a.tempWorld.keepOnEnd);
            case "builtin" -> plugin.configs().set("arena.yml", "arenas." + arenaId + ".temporary-world.builtin", !a.tempWorld.builtin);
            case "auto-return" -> plugin.configs().set("arena.yml", "arenas." + arenaId + ".after-war.auto-return", !a.afterWar.autoReturn);
            default -> {
            }
        }
        plugin.reloadSettings();
        openArenaEditor(player, arenaId);
    }

    private static String next(String[] values, String current) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equalsIgnoreCase(current == null ? "" : current)) {
                return values[(i + 1) % values.length];
            }
        }
        return values.length > 0 ? values[0] : "";
    }

    // ---- 工会编辑动作 ----

    private void promptGuildInput(Player player, String guildId, String key) {
        Guild guild = plugin.guilds().guild(guildId);
        if (guild == null) {
            return;
        }
        player.closeInventory();
        requestText(player, "guild-input", text -> {
            String value = text.trim();
            switch (key.toLowerCase(java.util.Locale.ROOT)) {
                case "level" -> plugin.guilds().setLevel(guild, NumberUtil.toInt(value, guild.level()));
                case "points" -> plugin.guilds().setPoints(guild, NumberUtil.toLong(value, guild.points()));
                case "bank" -> plugin.guilds().changeBank(guild.id(), NumberUtil.toLong(value, guild.bank()) - guild.bank());
                case "notice" -> plugin.guilds().setNotice(player, guild, value);
                case "desc" -> plugin.guilds().setDescription(player, guild, value);
                default -> {
                }
            }
            openGuildEditor(player, guildId);
        }, "key", key);
    }

    private boolean isShiftClick() {
        return clickShift;
    }

    // =====================================================================
    // 竞技场 / 工会 管理 GUI
    // =====================================================================

    /** 空白管理菜单（灰玻璃填充）。 */
    private Inventory simpleInventory(Player viewer, Holder holder, int rows, String title, Map<String, String> ph) {
        Inventory inv = Bukkit.createInventory(holder, rows * 9, Text.fmt(title, flatten(ph)));
        holder.inv = inv;
        ItemStack filler = GuiUtil.build(viewer, "GRAY_STAINED_GLASS_PANE", "GRAY_STAINED_GLASS_PANE",
                " ", List.of(), Map.of(), null);
        for (int i = 0; i < inv.getSize(); i++) {
            inv.setItem(i, filler);
        }
        return inv;
    }

    public void openArenaList(Player player, int page) {
        Holder holder = new Holder("arena_list", "", page);
        Inventory inv = simpleInventory(player, holder, 6, "<gold>竞技场管理", Map.of());
        List<String> ids = new ArrayList<>(plugin.settings().arenas.arenas.keySet());
        java.util.Collections.sort(ids);
        int perPage = 45;
        int from = Math.max(0, page) * perPage;
        for (int i = 0; i < perPage && from + i < ids.size(); i++) {
            String id = ids.get(from + i);
            ArenaDef a = plugin.settings().arena(id);
            inv.setItem(i, GuiUtil.build(player, "GRASS_BLOCK", "GRASS_BLOCK",
                    "<white>" + id, List.of(
                            "<gray>承办服：<white>" + (a.server.isBlank() ? "-" : a.server),
                            "<gray>世界：<white>" + (a.world.isBlank() ? "主世界" : a.world),
                            "<gray>模板：<white>" + (a.hasTemplate() ? a.template : "固定坐标"),
                            "<gray>A 点：<white>" + fmtSpawn(a.spawnA),
                            "<gray>B 点：<white>" + fmtSpawn(a.spawnB),
                            "",
                            "<yellow>点击编辑"), Map.of(), null));
            holder.actions.put(i, "arena_edit:" + id);
        }
        addPageControls(player, holder, inv, "arenas", page, (ids.size() - 1) / perPage, "工会列表", "admin:guilds");
        player.openInventory(inv);
    }

    public void openArenaEditor(Player player, String arenaId) {
        ArenaDef a = plugin.settings().arenas.arenas.get(arenaId.toLowerCase(java.util.Locale.ROOT));
        if (a == null) {
            plugin.messages().send(player, "arena-usage");
            return;
        }
        Holder holder = new Holder("arena_edit", "", 0);
        Inventory inv = simpleInventory(player, holder, 6, "<gold>编辑竞技场 <white>" + a.id, Map.of());
        int slot = 0;
        slot = put(inv, holder, slot, player, "COMPASS", "<white>传送到 A 点",
                List.of("<gray>直接传送过去查看"), "arena_tp:" + a.id);
        slot = put(inv, holder, slot, player, "RED_BED", "<white>设置 A 出生点",
                List.of("<gray>使用当前位置", fmtSpawn(a.spawnA)), "arena_setspawn:" + a.id + ":a");
        slot = put(inv, holder, slot, player, "BLUE_BED", "<white>设置 B 出生点",
                List.of("<gray>使用当前位置", fmtSpawn(a.spawnB)), "arena_setspawn:" + a.id + ":b");
        slot = put(inv, holder, slot, player, "ENDER_EYE", "<white>设置旁观点",
                List.of("<gray>使用当前位置", fmtSpawn(a.spawnSpectator)), "arena_setspawn:" + a.id + ":spec");
        slot = put(inv, holder, slot, player, "WHITE_BED", "<white>设置集合点",
                List.of("<gray>战斗结束后统一传送点", "<gray>当前：" + (a.afterWar.endSpawnEnabled ? "已启用" : "未启用")),
                "arena_endspawn:" + a.id);
        slot = put(inv, holder, slot, player, "NAME_TAG", "<white>世界：<yellow>" + (a.world.isBlank() ? "主世界" : a.world),
                List.of("<gray>点击输入世界名（留空 = 主世界）"), "arena_input:" + a.id + ":world");
        slot = put(inv, holder, slot, player, "BEACON", "<white>承办服：<yellow>" + (a.server.isBlank() ? "默认" : a.server),
                List.of("<gray>点击输入 server-id"), "arena_input:" + a.id + ":server");
        slot = put(inv, holder, slot, player, "STRUCTURE_BLOCK", "<white>模板：<yellow>" + (a.hasTemplate() ? a.template : "无"),
                List.of("<gray>liudungeon 动态模板名", "<gray>留空 = 使用固定世界坐标"), "arena_input:" + a.id + ":template");
        slot = put(inv, holder, slot, player, "SPYGLASS", "<white>预热半径：<yellow>" + a.radius,
                List.of("<gray>点击输入数字"), "arena_input:" + a.id + ":radius");
        slot = put(inv, holder, slot, player, "GRASS_BLOCK", "<white>环境：<yellow>" + a.tempWorld.environment,
                List.of("<gray>点击循环切换 NORMAL/NETHER/THE_END"), "arena_cycle:" + a.id + ":environment");
        slot = put(inv, holder, slot, player, "DEEPSLATE", "<white>内置临时世界：<yellow>" + (a.tempWorld.builtin ? "开" : "关"),
                List.of("<gray>开启后由本插件自建世界", "<gray>environment / name-format / world-type 生效"),
                "arena_toggle:" + a.id + ":builtin");
        slot = put(inv, holder, slot, player, "STONE", "<white>世界类型：<yellow>" + a.tempWorld.worldType,
                List.of("<gray>点击循环切换 flat/normal", "<gray>仅内置临时世界生效"), "arena_cycle:" + a.id + ":world-type");
        slot = put(inv, holder, slot, player, "CLOCK", "<white>时间：<yellow>" + (a.tempWorld.time < 0 ? "不修改" : String.valueOf(a.tempWorld.time)),
                List.of("<gray>点击循环切换时间"), "arena_cycle:" + a.id + ":time");
        slot = put(inv, holder, slot, player, "WATER_BUCKET", "<white>天气：<yellow>" + (a.tempWorld.weather.isBlank() ? "不修改" : a.tempWorld.weather),
                List.of("<gray>点击循环切换 clear/rain/thunder/不修改"), "arena_cycle:" + a.id + ":weather");
        slot = put(inv, holder, slot, player, "IRON_SWORD", "<white>难度：<yellow>" + (a.tempWorld.difficulty.isBlank() ? "不修改" : a.tempWorld.difficulty),
                List.of("<gray>点击循环切换难度"), "arena_cycle:" + a.id + ":difficulty");
        slot = put(inv, holder, slot, player, "OAK_DOOR", "<white>保留世界：<yellow>" + (a.tempWorld.keepOnEnd ? "是" : "否"),
                List.of("<gray>战斗结束后是否保留动态世界"), "arena_toggle:" + a.id + ":keep-on-end");
        slot = put(inv, holder, slot, player, "ENDER_PEARL", "<white>自动返回：<yellow>" + (a.afterWar.autoReturn ? "是" : "否"),
                List.of("<gray>结束后送回参赛前位置"), "arena_toggle:" + a.id + ":auto-return");
        slot = put(inv, holder, slot, player, "BARRIER", "<red>删除竞技场",
                List.of("<gray>从 arena.yml 移除", "<red>此操作不可恢复"), "arena_delete:" + a.id);
        int backSlot = 53;
        inv.setItem(backSlot, GuiUtil.build(player, "ARROW", "ARROW", "<white>返回列表", List.of(), Map.of(), null));
        holder.actions.put(backSlot, "admin:arenas");
        player.openInventory(inv);
    }

    public void openGuildList(Player player, int page) {
        Holder holder = new Holder("guild_list", "", page);
        Inventory inv = simpleInventory(player, holder, 6, "<gold>工会管理", Map.of());
        List<Guild> guilds = new ArrayList<>(plugin.guilds().all());
        guilds.sort((a, b) -> Long.compare(b.points(), a.points()));
        int perPage = 45;
        int from = Math.max(0, page) * perPage;
        for (int i = 0; i < perPage && from + i < guilds.size(); i++) {
            Guild g = guilds.get(from + i);
            inv.setItem(i, GuiUtil.build(player, "SHIELD", "SHIELD", "<white>" + g.name() + " <dark_gray>[" + g.tag() + "]",
                    List.of("<gray>会长：<white>" + g.leaderName(),
                            "<gray>等级：<white>" + g.level() + "  <gray>成员：<white>" + g.memberCount(),
                            "<gray>资金：<white>" + g.points() + "  <gray>银行：<white>" + g.bank(),
                            "<gray>战绩：<white>" + g.wins() + "胜 " + g.losses() + "负",
                            "", "<yellow>点击管理"), Map.of(), null));
            holder.actions.put(i, "guild_edit:" + g.id());
        }
        addPageControls(player, holder, inv, "guilds", page, (guilds.size() - 1) / perPage, "竞技场列表", "admin:arenas");
        player.openInventory(inv);
    }

    public void openGuildEditor(Player player, String guildId) {
        Guild g = plugin.guilds().guild(guildId);
        if (g == null) {
            plugin.messages().send(player, "guild-not-found", "name", guildId);
            return;
        }
        Holder holder = new Holder("guild_edit", g.id(), 0);
        Inventory inv = simpleInventory(player, holder, 6, "<gold>管理工会 <white>" + g.name(), Map.of());
        int slot = 0;
        slot = put(inv, holder, slot, player, "EXPERIENCE_BOTTLE", "<white>设置等级：<yellow>" + g.level(),
                List.of("<gray>点击输入新等级"), "guild_input:" + g.id() + ":level");
        slot = put(inv, holder, slot, player, "GOLD_INGOT", "<white>设置资金：<yellow>" + g.points(),
                List.of("<gray>点击输入新资金（点数）"), "guild_input:" + g.id() + ":points");
        slot = put(inv, holder, slot, player, "EMERALD", "<white>设置银行：<yellow>" + g.bank(),
                List.of("<gray>点击输入新银行余额"), "guild_input:" + g.id() + ":bank");
        slot = put(inv, holder, slot, player, "PLAYER_HEAD", "<white>成员数：<yellow>" + g.memberCount(),
                List.of("<gray>会长：<white>" + g.leaderName()), "noop");
        slot = put(inv, holder, slot, player, "NAME_TAG", "<white>设置公告",
                List.of("<gray>点击输入新公告"), "guild_input:" + g.id() + ":notice");
        slot = put(inv, holder, slot, player, "WRITABLE_BOOK", "<white>设置简介",
                List.of("<gray>点击输入新简介"), "guild_input:" + g.id() + ":desc");
        slot = put(inv, holder, slot, player, "TNT", "<red>强制解散工会",
                List.of("<red>此操作不可恢复", "<gray>点击后在聊天栏输入 yes 确认"), "guild_disband:" + g.id());
        int backSlot = 53;
        inv.setItem(backSlot, GuiUtil.build(player, "ARROW", "ARROW", "<white>返回列表", List.of(), Map.of(), null));
        holder.actions.put(backSlot, "admin:guilds");
        player.openInventory(inv);
    }

    /** 依次放置管理按钮并登记动作。 */
    private int put(Inventory inv, Holder holder, int slot, Player viewer, String material,
                    String name, List<String> lore, String action) {
        inv.setItem(slot, GuiUtil.build(viewer, material, material, name, lore, Map.of(), null));
        holder.actions.put(slot, action);
        return slot + 1;
    }

    private void addPageControls(Player player, Holder holder, Inventory inv, String menuKey,
                                 int page, int maxPage, String switchLabel, String switchAction) {
        if (page > 0) {
            inv.setItem(45, GuiUtil.build(player, "ARROW", "ARROW", "<white>上一页", List.of(), Map.of(), null));
            holder.actions.put(45, "page_" + menuKey + ":" + (page - 1));
        }
        if (page < maxPage) {
            inv.setItem(53, GuiUtil.build(player, "ARROW", "ARROW", "<white>下一页", List.of(), Map.of(), null));
            holder.actions.put(53, "page_" + menuKey + ":" + (page + 1));
        }
        inv.setItem(49, GuiUtil.build(player, "CHEST", "CHEST", "<white>切换：" + switchLabel,
                List.of("<gray>点击切换"), Map.of(), null));
        holder.actions.put(49, switchAction);
    }

    private static String fmtSpawn(ArenaDef.Spawn s) {
        return String.format(java.util.Locale.ROOT, "%.1f, %.1f, %.1f", s.x(), s.y(), s.z());
    }

    /** 坐标保留两位小数，避免配置过长。 */
    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private void sound(Player player, Sound sound, float volume, float pitch) {
        try {
            player.playSound(player.getLocation(), sound, volume, pitch);
        } catch (Throwable ignored) {
        }
    }

    /** 管理 GUI 的信息列表页（在聊天栏输出）。 */
    private void showAdminList(Player player, int size, String title,
                               java.util.function.Function<String, String> line, Iterable<String> keys) {
        player.closeInventory();
        player.sendMessage(Text.mm("<gold>" + title + "（" + size + "）："));
        for (String key : keys) {
            player.sendMessage(Text.mm(line.apply(key).replace('&', '\u00a7')));
        }
    }

    private boolean isRightClick() {
        return clickRight;
    }

    /** 当前点击的右键 / Shift 状态（主线程串行，可由外部引擎在分发前设置）。 */
    private boolean clickRight;
    private boolean clickShift;

    /**
     * 供外部 GUI 引擎（如 Sparrow UI）复用的动作入口。
     *
     * @param guildId 工会 id，可为空
     * @param page    页码（用于翻页动作）
     */
    public void runAction(Player player, String guildId, int page, String action, boolean right, boolean shift) {
        clickRight = right;
        clickShift = shift;
        try {
            handle(player, new Holder("external", guildId == null ? "" : guildId, page), action);
        } finally {
            clickRight = false;
            clickShift = false;
        }
    }

    private void bankAction(Player player, Guild g, boolean withdraw) {
        if (g == null) {
            plugin.messages().send(player, "not-in-guild");
            return;
        }
        if (!plugin.settings().guild.bankEnabled) {
            plugin.messages().send(player, "bank-disabled");
            return;
        }
        String key = withdraw ? "input-withdraw" : "input-deposit";
        requestText(player, key, text -> {
            double amount = parseAmount(text);
            if (amount <= 0) {
                plugin.messages().send(player, "input-invalid-number");
                return;
            }
            if (withdraw) {
                plugin.guilds().withdraw(player, g, amount);
            } else {
                plugin.guilds().deposit(player, g, amount);
            }
            openMain(player);
        });
    }

    private double parseAmount(String text) {
        return Math.floor(com.liu.liuguild.util.NumberUtil.parseAmount(text));
    }

    /** 危险操作确认：先在聊天栏提示，再要求输入 yes/确认。 */
    private void confirm(Player player, String promptKey, String targetName, Runnable action) {
        plugin.messages().send(player, promptKey, "player", targetName);
        requestText(player, "input-confirm", text -> {
            String t = text.trim().toLowerCase(java.util.Locale.ROOT);
            if (t.equals("yes") || t.equals("y") || t.equals("ok") || t.equals("确认") || t.equals("是")) {
                action.run();
            } else {
                plugin.messages().send(player, "input-cancelled");
            }
        });
    }

    private void showHistory(Player player, Guild g) {
        plugin.war().historyAsync(g.id(), 10, list -> {
            if (list.isEmpty()) {
                plugin.messages().send(player, "war-history-empty");
                return;
            }
            plugin.messages().sendRaw(player, "war-history-header");
            for (GuildWar w : list) {
                boolean home = w.guildA().equals(g.id());
                String enemy = home ? w.nameB() : w.nameA();
                int self = home ? w.scoreA() : w.scoreB();
                int opp = home ? w.scoreB() : w.scoreA();
                String result = w.winner() == null || w.winner().isBlank()
                        ? "平局"
                        : (w.winner().equals(g.id()) ? "胜利" : "失败");
                plugin.messages().sendRaw(player, "war-history-line",
                        "enemy", enemy, "self", self, "opp", opp, "result", result);
            }
        });
    }

    // =====================================================================
    // 工具
    // =====================================================================

    private MenuDef menu(String key) {
        GuiSettings gui = plugin.settings().gui;
        MenuDef def = gui.menu(key);
        if (def == null) {
            plugin.getLogger().warning("gui.yml 缺少菜单: " + key);
        }
        return def;
    }

    private Inventory create(Holder holder, MenuDef def, Map<String, String> ph, Player viewer) {
        Inventory inv = Bukkit.createInventory(holder, def.rows * 9,
                Text.fmt(def.title, flatten(ph)));
        holder.inv = inv;
        if (plugin.settings().gui.filler != null) {
            ItemStack filler = GuiUtil.fromButton(viewer, plugin.settings().gui.filler, ph);
            for (int i = 0; i < inv.getSize(); i++) {
                inv.setItem(i, filler);
            }
        }
        return inv;
    }

    private void addBack(Inventory inv, Holder holder, MenuDef def, Map<String, String> ph,
                         Player viewer, String target, int page, String menuKey, int total, int perPage) {
        if (def.back != null) {
            inv.setItem(clamp(def.back.slot), GuiUtil.fromButton(viewer, def.back, ph));
            holder.actions.put(clamp(def.back.slot), target);
        }
        int maxPage = Math.max(0, (total - 1) / perPage);
        if (page > 0) {
            int slot = def.rows * 9 - 9;
            inv.setItem(slot, GuiUtil.build(viewer, "ARROW", "ARROW",
                    "<white>上一页", List.of("<gray>第 " + page + " 页"), Map.of(), null));
            holder.actions.put(slot, "page_" + menuKey + ":" + (page - 1));
        }
        if (page < maxPage) {
            int slot = def.rows * 9 - 1;
            inv.setItem(slot, GuiUtil.build(viewer, "ARROW", "ARROW",
                    "<white>下一页", List.of("<gray>第 " + (page + 2) + " 页"), Map.of(), null));
            holder.actions.put(slot, "page_" + menuKey + ":" + (page + 1));
        }
    }

    private Guild require(Guild g, Player player) {
        if (g == null) {
            plugin.messages().send(player, "not-in-guild");
        }
        return g;
    }

    private Map<String, String> placeholders(Guild g) {
        Map<String, String> ph = basePlaceholders();
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
        return ph;
    }

    /** 供外部界面引擎复用的占位符。 */
    public Map<String, String> placeholdersFor(Guild g) {
        return placeholders(g);
    }

    /** 主菜单占位符（含与具体玩家相关的签到状态）；内置 GUI 与 Sparrow UI 共用。 */
    public Map<String, String> mainPlaceholders(Player player, Guild g) {
        Map<String, String> ph = placeholders(g);
        GuildMember self = (g == null || player == null) ? null : g.member(player.getUniqueId());
        ph.put("checkin_streak", String.valueOf(self == null ? 0 : self.checkinStreak()));
        ph.put("checkin_state", !plugin.settings().guild.checkinEnabled ? "未开启"
                : (self != null && com.liu.liuguild.config.GuildSettings.alreadyCheckedIn(
                        com.liu.liuguild.util.TimeUtil.todayEpochDay(), self.lastCheckin()) ? "已签到" : "未签到"));
        return ph;
    }

    private Map<String, String> basePlaceholders() {
        return new LinkedHashMap<>();
    }

    private static Object[] flatten(Map<String, String> ph) {
        Object[] arr = new Object[ph.size() * 2];
        int i = 0;
        for (Map.Entry<String, String> e : ph.entrySet()) {
            arr[i++] = e.getKey();
            arr[i++] = e.getValue();
        }
        return arr;
    }

    private static int clamp(int slot) {
        return Math.max(0, Math.min(53, slot));
    }

    public static String actionId(String buttonId) {
        return switch (buttonId) {
            case "info" -> "noop";
            case "leave" -> "leave";
            case "toggle_open" -> "set:open";
            case "notice" -> "set:notice";
            case "desc" -> "set:desc";
            case "icon" -> "set:icon";
            case "back" -> "back:main";
            case "history" -> "war_history";
            case "leave_queue" -> "war_leave";
            case "status" -> "war_status";
            case "admin_status" -> "admin:status";
            case "admin_modes" -> "admin:modes";
            case "admin_arena" -> "admin:arenas";
            case "admin_guilds" -> "admin:guilds";
            case "admin_wars" -> "admin:wars";
            case "admin_kits" -> "admin:kits";
            case "admin_cleanup" -> "admin:cleanup";
            case "admin_verify" -> "admin:verify";
            case "admin_kit" -> "admin:kit";
            case "admin_reload" -> "admin:reload";
            case "close" -> "close";
            case "help" -> "noop";
            case "create" -> "list:create";
            default -> buttonId;
        };
    }
}
