package com.liu.liuguild.gui;

import com.liu.liuguild.LiuGuild;
import com.liu.liuguild.config.ButtonDef;
import com.liu.liuguild.config.GuiSettings;
import com.liu.liuguild.config.IconDef;
import com.liu.liuguild.config.MenuDef;
import com.liu.liuguild.model.Guild;
import com.liu.liuguild.model.GuildMember;
import com.liu.liuguild.model.GuildRank;
import com.liu.liuguild.model.GuildWar;
import com.liu.liuguild.module.GuildModule;
import com.liu.liuguild.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
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
            plugin.messages().send(player, "not-in-guild");
            openList(player, 0);
            return;
        }
        MenuDef def = menu("main");
        if (def == null) {
            return;
        }
        Map<String, String> ph = placeholders(g);
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
        ph.put("state", war == null ? (queuedMode == null ? "空闲" : "排队中") : war.state().name());
        ph.put("enemy", war == null ? "-" : (war.guildA().equals(g.id()) ? war.nameB() : war.nameA()));
        ph.put("score", war == null ? "0" : String.valueOf(war.guildA().equals(g.id()) ? war.scoreA() : war.scoreB()));
        ph.put("enemy_score", war == null ? "0" : String.valueOf(war.guildA().equals(g.id()) ? war.scoreB() : war.scoreA()));
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
            mph.put("mode_schedule", m.schedule.isEmpty() ? "随时" : String.join(", ", m.schedule));
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
            currentClick = event;
            try {
                sound(player, Sound.UI_BUTTON_CLICK, 0.4f, 1.4f);
                handle(player, holder, action, event);
            } finally {
                currentClick = null;
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }

    private void handle(Player player, Holder holder, String action, InventoryClickEvent event) {
        Guild g = plugin.guilds().guildOf(player.getUniqueId());
        switch (action) {
            case "noop" -> {
            }
            case "close" -> player.closeInventory();
            case "members" -> openMembers(player, require(g, player), 0);
            case "war" -> openWar(player, require(g, player));
            case "settings" -> openSettings(player, require(g, player));
            case "list" -> openList(player, 0);
            case "bank" -> bankAction(player, g, event.isRightClick());
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
                plugin.guildHome().command(player, event.isRightClick()
                        ? new String[]{"home", "set"}
                        : new String[]{"home"});
            }
            case "leave" -> {
                if (g != null) {
                    plugin.guilds().leave(player);
                    player.closeInventory();
                }
            }
            case "back:main" -> openMain(player);
            case "list:create" -> {
                if (plugin.guilds().guildOf(player.getUniqueId()) != null) {
                    plugin.messages().send(player, "already-in-guild", "guild", plugin.guilds().guildOf(player.getUniqueId()).name());
                    return;
                }
                chatInput.request(player, plugin.messages().prefixed("input-guild-name"), text -> {
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
                plugin.reloadAll();
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
                        player.sendMessage(Text.mm("<gray>状态: " + w.state() + " 对手: "
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
                    chatInput.request(player, plugin.messages().prefixed("input-notice"), text -> {
                        plugin.guilds().setNotice(player, g, text);
                        openSettings(player, g);
                    });
                }
            }
            case "set:desc" -> {
                if (g != null && plugin.guilds().canManageSettings(player, g)) {
                    chatInput.request(player, plugin.messages().prefixed("input-desc"), text -> {
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
        }
    }

    private boolean isShiftClick() {
        return currentClick != null && currentClick.isShiftClick();
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
        return currentClick != null && currentClick.isRightClick();
    }

    /** 记录当前点击事件（主线程串行，可安全暂存）。 */
    private InventoryClickEvent currentClick;

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
        chatInput.request(player, plugin.messages().prefixed(key), text -> {
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
        chatInput.request(player, plugin.messages().prefixed("input-confirm"), text -> {
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

    private static String actionId(String buttonId) {
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
            case "admin_arena" -> "admin:arena";
            case "admin_kit" -> "admin:kit";
            case "admin_reload" -> "admin:reload";
            case "close" -> "close";
            case "create" -> "list:create";
            default -> buttonId;
        };
    }
}
