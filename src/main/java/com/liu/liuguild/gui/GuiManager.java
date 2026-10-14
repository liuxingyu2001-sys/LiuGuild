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
import com.liu.liuguild.model.GuildTitle;
import com.liu.liuguild.model.GuildWar;
import com.liu.liuguild.model.ShopListing;
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
        if (plugin.war() != null && plugin.war().isWarRestricted(player.getUniqueId())
                && !player.hasPermission("liuguild.admin")) {
            plugin.messages().send(player, "war-menu-blocked");
            return;
        }
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
            mph.put("title", plugin.titles() == null ? "" : plugin.titles().display(m.uuid()));
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

    /**
     * 工会详情面板。
     *
     * @param others 是否在看别人的工会（决定是否显示管理类入口）
     */
    public void openInfo(Player player, Guild g, boolean others) {
        if (g == null) {
            plugin.messages().send(player, "guild-not-found", "name", "?");
            return;
        }
        MenuDef def = menu("info");
        if (def == null) {
            return;
        }
        Map<String, String> ph = detailPlaceholders(g);
        Holder holder = new Holder("info", g.id(), 0);
        Inventory inv = create(holder, def, ph, player);
        Guild self = plugin.guilds().guildOf(player.getUniqueId());
        boolean sameGuild = self != null && self.id().equals(g.id());
        for (Map.Entry<String, ButtonDef> e : def.buttons.entrySet()) {
            ButtonDef b = e.getValue();
            // 看别人的工会时隐藏「成员列表」（避免暴露成员名单给外人）
            if (others && !sameGuild && "members".equals(e.getKey())) {
                continue;
            }
            ItemStack item = "card".equals(e.getKey())
                    ? GuiUtil.guildIcon(plugin, player, g, b.fallback())
                    : GuiUtil.fromButton(player, b, ph);
            if ("card".equals(e.getKey()) && item.getItemMeta() != null) {
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
        long cd = plugin.guilds().renameCooldownLeft(g.id());
        ph.put("rename_cooldown", plugin.settings().guild.renameCooldownSeconds <= 0
                ? "无" : (cd > 0 ? cd + " 秒" : "可改名"));
        ph.put("rename_cost", plugin.settings().guild.renameCost <= 0
                ? "免费" : plugin.economy().format(plugin.settings().guild.renameCost));
        Holder holder = new Holder("settings", g.id(), 0);
        Inventory inv = create(holder, def, ph, player);
        GuildMember self = g.member(player.getUniqueId());
        boolean isLeader = self != null && self.rank() == GuildRank.LEADER;
        boolean canManage = plugin.guilds().canManageSettings(player, g);
        for (Map.Entry<String, ButtonDef> e : def.buttons.entrySet()) {
            // 「解散工会」只有会长能看见；「退出工会」会长不需要（需先转让）
            if ("disband".equals(e.getKey()) && !isLeader) {
                continue;
            }
            if ("leave".equals(e.getKey()) && isLeader) {
                continue;
            }
            // 无管理权限时隐藏需权限的配置项
            if (("notice".equals(e.getKey()) || "desc".equals(e.getKey())) && !canManage) {
                continue;
            }
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
    // 工会头衔面板
    // =====================================================================

    /**
     * 头衔面板：列出本工会全部头衔，左键装备/卸下，右键删除（需管理权限）。
     *
     * <p>不够格的头衔也会显示但置灰 —— 让成员知道目标是哪些。</p>
     */
    public void openTitles(Player player, Guild g) {
        if (g == null || plugin.titles() == null) {
            plugin.messages().send(player, "not-in-guild");
            return;
        }
        if (!plugin.settings().guild.titlesEnabled) {
            plugin.messages().send(player, "title-disabled");
            return;
        }
        GuildMember me = g.member(player.getUniqueId());
        GuildRank rank = me == null ? GuildRank.MEMBER : me.rank();
        boolean canManage = player.hasPermission("liuguild.title.manage")
                || (me != null && me.rank().atLeast(plugin.settings().guild.titlesManageRank));
        String equippedId = plugin.titles().equippedId(player.getUniqueId());

        Holder holder = new Holder("titles", g.id(), 0);
        Map<String, String> ph = new LinkedHashMap<>();
        ph.put("count", String.valueOf(plugin.titles().titlesOf(g.id()).size()));
        ph.put("max", String.valueOf(plugin.settings().guild.titlesMax));
        Inventory inv = simpleInventory(player, holder, 6,
                "<gold>工会头衔 <dark_gray>({count}/{max})", ph);

        List<GuildTitle> all = plugin.titles().titlesOf(g.id());
        if (all.isEmpty()) {
            inv.setItem(22, GuiUtil.build(player, "NAME_TAG", "NAME_TAG",
                    "<gray>本工会还没有任何头衔",
                    canManage
                            ? List.of("<yellow>点击创建第一个头衔")
                            : List.of("<gray>请会长/副会长创建"),
                    Map.of(), null));
            if (canManage) {
                holder.actions.put(22, "title:create");
            }
        }
        int slot = 0;
        for (GuildTitle t : all) {
            boolean equipped = t.id().equals(equippedId);
            boolean usable = t.usableBy(rank);
            String color = equipped ? "<green>" : (usable ? "<white>" : "<dark_gray>");
            List<String> lore = new ArrayList<>();
            lore.add("<gray>标识：<white>" + t.id());
            lore.add("<gray>最低职位：<white>" + t.minRank().display());
            lore.add("");
            if (equipped) {
                lore.add("<yellow>左键卸下");
            } else if (usable) {
                lore.add("<yellow>左键装备");
            } else {
                lore.add("<red>你的职位不足以使用");
            }
            if (canManage) {
                lore.add("<red>右键删除");
            }
            inv.setItem(slot, GuiUtil.build(player, "NAME_TAG", "NAME_TAG",
                    color + t.display() + " <dark_gray>[" + t.id() + "]", lore, Map.of(), null));
            holder.actions.put(slot, "title_toggle:" + t.id());
            slot++;
            if (slot >= 45) {
                break;
            }
        }

        // 底排：创建 / 清除 / 返回
        if (canManage) {
            inv.setItem(47, GuiUtil.build(player, "EMERALD", "EMERALD", "<green>创建头衔",
                    List.of("<gray>格式：<white>/guild title create <标识> <显示> [职位]",
                            "<gray>也可直接点这里输入"), Map.of(), null));
            holder.actions.put(47, "title:create");
        }
        inv.setItem(49, GuiUtil.build(player, "BARRIER", "BARRIER", "<yellow>卸下当前头衔",
                List.of("<gray>当前：<white>" + (equippedId == null ? "无" : equippedId)), Map.of(), null));
        holder.actions.put(49, "title:clear");
        inv.setItem(53, GuiUtil.build(player, "ARROW", "ARROW", "<white>返回", List.of(), Map.of(), null));
        holder.actions.put(53, "back:main");
        player.openInventory(inv);
    }

    /** 头衔面板：左键装备/卸下，右键删除。 */
    private void titleToggle(Player player, Guild g, String titleId, boolean rightClick) {
        if (g == null || plugin.titles() == null) {
            return;
        }
        if (rightClick) {
            GuildMember me = g.member(player.getUniqueId());
            boolean canManage = player.hasPermission("liuguild.title.manage")
                    || (me != null && me.rank().atLeast(plugin.settings().guild.titlesManageRank));
            if (!canManage) {
                plugin.messages().send(player, "title-no-permission");
                return;
            }
            if (plugin.titles().delete(g.id(), titleId)) {
                plugin.messages().send(player, "title-deleted", "id", titleId);
            } else {
                plugin.messages().send(player, "title-not-found");
            }
            openTitles(player, g);
            return;
        }
        GuildMember me = g.member(player.getUniqueId());
        if (me == null) {
            plugin.messages().send(player, "not-in-guild");
            return;
        }
        if (titleId.equals(plugin.titles().equippedId(player.getUniqueId()))) {
            plugin.titles().unequip(g, player.getUniqueId());
            plugin.messages().send(player, "title-unequipped");
        } else {
            String err = plugin.titles().equip(g, me, titleId);
            if (err != null) {
                plugin.messages().send(player, err, "id", titleId);
                return;
            }
            GuildTitle t = plugin.titles().equipped(player.getUniqueId());
            plugin.messages().send(player, "title-equipped",
                    "display", t == null ? titleId : t.display());
        }
        openTitles(player, g);
    }

    // =====================================================================
    // 工会商店
    // =====================================================================

    /**
     * 本会商店：列出所有上架，左键购买，Shift+右键下架，Shift+左键改价。
     *
     * @param g 目标工会（null 时取玩家自己的工会）
     */
    public void openShop(Player player, Guild g) {
        if (g == null) {
            g = plugin.guilds().guildOf(player.getUniqueId());
        }
        if (g == null || plugin.shop() == null) {
            plugin.messages().send(player, "not-in-guild");
            return;
        }
        if (!plugin.settings().guild.shopEnabled) {
            plugin.messages().send(player, "shop-disabled");
            return;
        }
        openShopPage(player, g, plugin.shop().listingsOf(g.id()), "shop", true);
    }

    /** 全服工会商店：浏览所有工会的上架（只能买，不能管）。 */
    public void openShopAll(Player player) {
        if (plugin.shop() == null) {
            plugin.messages().send(player, "shop-disabled");
            return;
        }
        if (!plugin.settings().guild.shopEnabled) {
            plugin.messages().send(player, "shop-disabled");
            return;
        }
        openShopPage(player, plugin.guilds().guildOf(player.getUniqueId()),
                plugin.shop().all(), "shop_all", false);
    }

    private void openShopPage(Player player, Guild own, List<ShopListing> listings,
                              String holderId, boolean ownShop) {
        Holder holder = new Holder(holderId, own == null ? "" : own.id(), 0);
        Map<String, String> ph = new LinkedHashMap<>();
        ph.put("guild", own == null ? "" : own.name());
        Inventory inv = simpleInventory(player, holder, 6,
                ownShop ? "<gold>工会商店 <dark_gray>· {guild}" : "<gold>全服工会商店", ph);

        if (listings.isEmpty()) {
            inv.setItem(22, GuiUtil.build(player, "CHEST", "CHEST",
                    "<gray>这里还没有任何商品", List.of(), Map.of(), null));
        }
        int slot = 0;
        for (ShopListing l : listings) {
            if (slot >= 45) {
                break;
            }
            ItemStack icon = l.preview();
            if (icon == null) {
                continue;
            }
            Guild owner = plugin.guilds().guild(l.guildId());
            boolean mine = own != null && l.guildId().equals(own.id());
            List<String> lore = new ArrayList<>();
            lore.add("<gray>售价：<white>" + plugin.economy().format(l.price()) + " <gray>/件");
            lore.add("<gray>库存：<white>" + l.stock() + " <gray>件");
            if (l.buying()) {
                lore.add("<gray>工会收购价：<white>" + plugin.economy().format(l.buyPrice()));
            }
            if (!ownShop && owner != null) {
                lore.add("<gray>出售方：<white>" + owner.name());
            }
            lore.add("<gray>上架者：<white>" + (l.sellerName() == null ? "?" : l.sellerName()));
            lore.add("");
            lore.add("<yellow>左键购买");
            if (mine && ownShop && plugin.shop().canManage(own, player)) {
                lore.add("<yellow>Shift+左键改价");
                lore.add("<red>Shift+右键下架");
                if (l.buying()) {
                    lore.add("<green>右键向工会供货");
                }
            }
            inv.setItem(slot, GuiUtil.build(player, l.preview().getType().name(),
                    l.preview().getType().name(), "<white>" + plugin.shop().itemName(l.preview()) + " <dark_gray>x" + l.stock(),
                    lore, Map.of(), null));
            holder.actions.put(slot, "shop_buy:" + l.id());
            slot++;
        }

        // 底排
        if (ownShop && own != null && plugin.shop().canManage(own, player)) {
            inv.setItem(48, GuiUtil.build(player, "EMERALD", "EMERALD", "<green>上架手持物品",
                    List.of("<gray>先把物品拿在主手", "<gray>然后点击这里输入售价"), Map.of(), null));
            holder.actions.put(48, "shop:add");
        }
        if (ownShop) {
            inv.setItem(49, GuiUtil.build(player, "ENDER_CHEST", "ENDER_CHEST", "<gold>全服商店",
                    List.of("<gray>看看其他工会在卖什么"), Map.of(), null));
            holder.actions.put(49, "shop:all");
        } else {
            inv.setItem(49, GuiUtil.build(player, "ARROW", "ARROW", "<white>返回本会商店",
                    List.of(), Map.of(), null));
            holder.actions.put(49, "shop:open");
        }
        inv.setItem(53, GuiUtil.build(player, "ARROW", "ARROW", "<white>返回", List.of(), Map.of(), null));
        holder.actions.put(53, "back:main");
        player.openInventory(inv);
    }

    /** 商店条目点击：左键购买，Shift+左键改价，Shift+右键下架/供货。 */
    private void shopClick(Player player, Guild g, String listingId, boolean right, boolean shift) {
        if (plugin.shop() == null) {
            return;
        }
        ShopListing l = plugin.shop().listing(listingId);
        if (l == null) {
            plugin.messages().send(player, "shop-not-found");
            if (g != null) openShop(player, g);
            return;
        }
        boolean mine = g != null && l.guildId().equals(g.id());
        boolean manage = mine && plugin.shop().canManage(g, player);

        if (shift && right) {
            if (!manage) {
                plugin.messages().send(player, "shop-no-permission");
                return;
            }
            final Guild shopGuild = g;
            plugin.shop().removeListing(g, player, l, () -> openShop(player, shopGuild));
            return;
        }
        if (shift) {
            if (!manage) {
                plugin.messages().send(player, "shop-no-permission");
                return;
            }
            requestText(player, "shop-prompt-price", priceStr -> {
                long price = NumberUtil.toLong(priceStr.trim(), -1);
                if (price < 0) {
                    plugin.messages().send(player, "shop-invalid-number");
                    openShop(player, g);
                    return;
                }
                requestText(player, "shop-prompt-buy-price", buyStr -> {
                    long buy = NumberUtil.toLong(buyStr.trim(), -1);
                    if (buy < 0) {
                        plugin.messages().send(player, "shop-invalid-number");
                        openShop(player, g);
                        return;
                    }
                    plugin.shop().updatePrices(g, l, price, buy);
                    plugin.messages().send(player, "shop-price-updated",
                            "item", plugin.shop().itemName(l.preview()),
                            "price", plugin.economy().format(price),
                            "buy_price", plugin.economy().format(buy));
                    openShop(player, g);
                });
            });
            return;
        }
        if (right) {
            // 右键：本会成员向工会供货（工会必须设了收购价）
            requestText(player, "shop-prompt-amount", amtStr -> {
                int amount = NumberUtil.toInt(amtStr.trim(), -1);
                if (amount <= 0) {
                    plugin.messages().send(player, "shop-amount-invalid");
                    openShop(player, g);
                    return;
                }
                plugin.shop().supply(g, player, l, amount, () -> openShop(player, g));
            });
            return;
        }
        // 左键购买：输入数量
        final Guild shopGuild = g;
        requestText(player, "shop-prompt-amount", amtStr -> {
            int amount = NumberUtil.toInt(amtStr.trim(), -1);
            if (amount <= 0) {
                plugin.messages().send(player, "shop-amount-invalid");
                openShopPage(player, shopGuild, plugin.shop().listingsOf(l.guildId()),
                        "shop", true);
                return;
            }
            plugin.shop().buy(player, shopGuild, l, amount, () -> openShop(player, shopGuild));
        });
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
            case "info:open" -> openInfo(player, require(g, player), false);
            case "info:notice" -> {
                if (g != null) {
                    plugin.messages().sendRawRich(player, "info-notice",
                            "notice", g.notice().isBlank() ? "暂无公告" : Text.sanitizeToMini(g.notice()));
                    plugin.messages().sendRawRich(player, "info-desc-line",
                            "description", g.description().isBlank() ? "暂无简介" : Text.sanitizeToMini(g.description()));
                }
            }
            case "info:desc" -> {
                if (g != null) {
                    plugin.messages().sendRawRich(player, "info-desc-line",
                            "description", g.description().isBlank() ? "暂无简介" : Text.sanitizeToMini(g.description()));
                }
            }
            case "info:members" -> openMembers(player, require(g, player), 0);
            case "info:history" -> {
                if (g != null) {
                    showHistory(player, g);
                }
            }
            case "info:back" -> {
                if (holder != null && "info".equals(holder.menu)) {
                    openMain(player);
                } else {
                    openList(player, 0);
                }
            }
            case "close" -> player.closeInventory();
            case "members" -> openMembers(player, require(g, player), 0);
            case "war" -> openWar(player, require(g, player));
            case "settings" -> openSettings(player, require(g, player));
            case "list" -> openList(player, 0);
            case "checkin" -> {
                player.closeInventory();
                plugin.guilds().checkin(player);
                // 签到状态是同步更新的，立即重开让「今日：已签到」马上刷新
                if (plugin.guilds().guildOf(player.getUniqueId()) != null) {
                    openMain(player);
                }
            }
            case "bank" -> bankAction(player, g, clickRight);
            case "guild:warehouse" -> {
                if (g == null) {
                    plugin.messages().send(player, "not-in-guild");
                    break;
                }
                if (plugin.warehouse() == null) {
                    plugin.messages().send(player, "wh-disabled");
                    break;
                }
                plugin.warehouse().open(player, g);
            }
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
                requestText(player, "create-input-name", text -> {
                    String name = text.trim();
                    if (name.isEmpty()) {
                        return;
                    }
                    // 第二步：输入标签（2~5 字符）；直接确认则用名称截断作为默认值
                    com.liu.liuguild.config.GuildSettings c = plugin.settings().guild;
                    String fallback = name.length() > c.tagMax ? name.substring(0, c.tagMax) : name;
                    if (fallback.length() < c.tagMin) {
                        fallback = name;
                    }
                    final String defaultTag = fallback;
                    requestText(player, "create-input-tag", tagText -> {
                        String tag = tagText.trim();
                        if (tag.isEmpty()) {
                            tag = defaultTag;
                        }
                        plugin.guilds().create(player, name, tag);
                    }, "min", c.tagMin, "max", c.tagMax);
                }, "min", plugin.settings().guild.nameMin, "max", plugin.settings().guild.nameMax);
            }
            case "back:settings" -> openSettings(player, require(g, player));
            case "admin:status" -> {
                player.closeInventory();
                for (String line : plugin.statusLines()) {
                    player.sendMessage(Text.mm(line.replace('&', '\u00a7')));
                }
            }
            case "admin:arenas" -> openArenaList(player, 0);
            case "title:open" -> openTitles(player, require(g, player));
            case "shop:open" -> openShop(player, require(g, player));
            case "title:create" -> {
                Guild tg = require(g, player);
                if (tg == null) {
                    break;
                }
                var m = tg.member(player.getUniqueId());
                boolean canManage = player.hasPermission("liuguild.title.manage")
                        || (m != null && m.rank().atLeast(plugin.settings().guild.titlesManageRank));
                if (!canManage) {
                    plugin.messages().send(player, "title-no-permission");
                    break;
                }
                if (plugin.titles() == null) {
                    plugin.messages().send(player, "title-disabled");
                    break;
                }
                String max = String.valueOf(plugin.settings().guild.titlesMax);
                requestText(player, "title-prompt-id", id -> {
                    String err = com.liu.liuguild.title.TitleService.validateId(id);
                    if (err != null) {
                        plugin.messages().send(player, err);
                        openTitles(player, tg);
                        return;
                    }
                    requestText(player, "title-prompt-display", display -> {
                        String err2 = com.liu.liuguild.title.TitleService.validateDisplay(display);
                        if (err2 != null) {
                            plugin.messages().send(player, err2, "max",
                                    String.valueOf(com.liu.liuguild.title.TitleService.maxDisplayLength()));
                            openTitles(player, tg);
                            return;
                        }
                        if (plugin.titles().title(tg.id(), id) == null
                                && plugin.titles().titlesOf(tg.id()).size() >= plugin.settings().guild.titlesMax) {
                            plugin.messages().send(player, "title-limit", "max", max);
                            openTitles(player, tg);
                            return;
                        }
                        String err3 = plugin.titles().create(tg.id(), id, display, GuildRank.MEMBER);
                        if (err3 != null) {
                            plugin.messages().send(player, err3, "max", max);
                        } else {
                            plugin.messages().send(player, "title-created", "id", id, "display", display);
                        }
                        openTitles(player, tg);
                    });
                });
            }
            case "title:clear" -> {
                Guild tg = require(g, player);
                if (tg == null || plugin.titles() == null) {
                    break;
                }
                if (plugin.titles().equippedId(player.getUniqueId()) == null) {
                    plugin.messages().send(player, "title-none");
                } else {
                    plugin.titles().unequip(tg, player.getUniqueId());
                    plugin.messages().send(player, "title-unequipped");
                }
                openTitles(player, tg);
            }
            case "admin:open" -> openAdmin(player);
            case "admin:guilds" -> openGuildList(player, 0);
            case "admin:wars" -> openWarList(player);
            case "admin:wars_back" -> openAdmin(player);
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
            case "admin:modes" -> openModeList(player);
            case "admin:reload" -> {
                plugin.reloadSettings();
                plugin.messages().send(player, "reload-done");
                openAdmin(player);
            }
            case "war_leave" -> {                if (g != null) {
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
                // 重开菜单，让上面的比分/状态 lore 拿到最新值
                openWar(player, require(g, player));
            }
            case "war_spectate" -> {
                player.closeInventory();
                plugin.war().spectate(player);
            }
            case "set:icon" -> openIcons(player, require(g, player), 0);
            case "guild:disband" -> {
                if (g == null) {
                    break;
                }
                if (g.leaderUuid() == null || !g.leaderUuid().equals(player.getUniqueId())) {
                    plugin.messages().send(player, "no-permission-rank");
                    break;
                }
                player.closeInventory();
                confirm(player, "confirm-disband", g.name(), () -> plugin.guilds().disband(g, true));
            }
            case "set:rename" -> {
                if (g == null) {
                    break;
                }
                if (!plugin.guilds().canManageSettings(player, g)) {
                    plugin.messages().send(player, "no-permission-rank");
                    break;
                }
                // 先输入新名称，确认后再问标签
                requestText(player, "rename-input-name", name -> {
                    if (name.isBlank()) {
                        openSettings(player, g);
                        return;
                    }
                    requestText(player, "rename-input-tag", tag -> {
                        plugin.guilds().rename(player, g, name, tag.isBlank() ? null : tag);
                        openSettings(player, g);
                    });
                });
            }
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
                if (clickRight) {
                    // 右键：查看工会详情
                    openInfo(player, target, true);
                } else {
                    // 左键：申请加入
                    plugin.guilds().join(player, target, false);
                    player.closeInventory();
                }
            }
        } else if (action.startsWith("info_view:")) {
            Guild target = plugin.guilds().guild(action.substring("info_view:".length()));
            if (target != null) {
                openInfo(player, target, true);
            }
        } else if (action.equals("join_help")) {
            sendJoinHelp(player);
        } else if (action.startsWith("icon:")) {
            String iconId = action.substring("icon:".length());
            if (g != null && plugin.guilds().setIcon(player, g, iconId)) {
                openSettings(player, g);
            }
        } else if (action.startsWith("title_toggle:")) {
            titleToggle(player, g, action.substring("title_toggle:".length()), clickRight);
        } else if (action.startsWith("shop_buy:")) {
            shopClick(player, g, action.substring("shop_buy:".length()), clickRight, isShiftClick());
        } else if (action.startsWith("shop:")) {
            switch (action.substring("shop:".length())) {
                case "add" -> {
                    if (g == null || plugin.shop() == null) {
                        break;
                    }
                    if (!plugin.shop().canManage(g, player)) {
                        plugin.messages().send(player, "shop-no-permission");
                        break;
                    }
                    ItemStack hand = player.getInventory().getItemInMainHand();
                    if (hand == null || hand.getType().isAir()) {
                        plugin.messages().send(player, "shop-hand-empty");
                        break;
                    }
                    final Guild shopGuild = g;
                    final int amount = hand.getAmount();
                    final ItemStack sample = hand.clone();
                    // 若装了 LiuShop，先给个参考价（否则工会只能瞎定价）
                    var ls = plugin.liuShop();
                    if (ls != null && ls.available()) {
                        double sug = ls.suggestPrice(sample, plugin.settings().guild.shopSellMarkup, -1);
                        if (sug > 0) {
                            plugin.messages().send(player, "shop-suggest-price",
                                    "price", plugin.economy().format(sug));
                        }
                    }
                    requestText(player, "shop-prompt-price", priceStr -> {
                        long price = NumberUtil.toLong(priceStr.trim(), -1);
                        if (price < 0) {
                            plugin.messages().send(player, "shop-invalid-number");
                            openShop(player, shopGuild);
                            return;
                        }
                        requestText(player, "shop-prompt-buy-price", buyStr -> {
                            long buy = NumberUtil.toLong(buyStr.trim(), -1);
                            if (buy < 0) {
                                plugin.messages().send(player, "shop-invalid-number");
                                openShop(player, shopGuild);
                                return;
                            }
                            String err = plugin.shop().addListing(shopGuild, player, sample, amount, price, buy);
                            if (err != null) {
                                plugin.messages().send(player, err,
                                        "max", String.valueOf(plugin.settings().guild.shopMaxListings));
                            } else {
                                plugin.messages().send(player, "shop-listed",
                                        "item", plugin.shop().itemName(sample),
                                        "amount", String.valueOf(amount),
                                        "price", plugin.economy().format(price));
                            }
                            openShop(player, shopGuild);
                        });
                    });
                }
                case "all" -> openShopAll(player);
                case "open" -> openShop(player, g);
                default -> {
                }
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
        } else if (action.startsWith("arena_setpoint:")) {
            String[] parts = action.substring("arena_setpoint:".length()).split(":", 2);
            promptArenaPoint(player, parts[0], parts.length > 1 ? parts[1] : "a");
        } else if (action.startsWith("arena_teampoint:")) {
            String[] parts = action.substring("arena_teampoint:".length()).split(":", 2);
            promptArenaInput(player, parts[0], parts.length > 1 ? parts[1] : "team-a-spawn");
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
        } else if (action.startsWith("guild_rename:")) {
            promptAdminRename(player, action.substring("guild_rename:".length()));
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
        } else if (action.startsWith("mode_edit:")) {
            openModeEditor(player, action.substring("mode_edit:".length()));
        } else if (action.startsWith("mode_toggle:")) {
            String[] parts = action.substring("mode_toggle:".length()).split(":", 2);
            if (parts.length == 2) {
                toggleMode(player, parts[0], parts[1]);
            }
        } else if (action.startsWith("mode_input:")) {
            String[] parts = action.substring("mode_input:".length()).split(":", 2);
            if (parts.length == 2) {
                promptModeInput(player, parts[0], parts[1]);
            }
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

    /**
     * 工会战模式列表（可点击进入编辑）。
     */
    public void openModeList(Player player) {
        Holder holder = new Holder("mode_list", "", 0);
        Inventory inv = simpleInventory(player, holder, 6, "<gold>工会战模式", Map.of());
        int slot = 0;
        for (com.liu.liuguild.config.WarMode m : plugin.settings().war.modes.values()) {
            if (slot >= 45) {
                break;
            }
            inv.setItem(slot, GuiUtil.build(player, "NETHERITE_SWORD", "NETHERITE_SWORD",
                    (m.enabled ? "<white>" : "<dark_gray>") + m.display + " <dark_gray>(" + m.key + ")",
                    List.of(
                            "<gray>状态：" + (m.enabled ? "<green>启用" : "<red>停用"),
                            "<gray>场地：<white>" + m.arena,
                            "<gray>人数：<white>" + m.teamSize + " <gray>最低 <white>" + m.minMembers,
                            "<gray>准备/时长：<white>" + m.prepareSeconds + "s<gray>/<white>" + m.durationSeconds + "s",
                            "<gray>公平：<white>" + (m.fairEquipment ? "装备" : "-") + " " + (m.fairHealth ? "血量" : "-"),
                            "", "<yellow>点击编辑"), Map.of(), null));
            holder.actions.put(slot, "mode_edit:" + m.key);
            slot++;
        }
        inv.setItem(53, GuiUtil.build(player, "ARROW", "ARROW", "<white>返回", List.of(), Map.of(), null));
        holder.actions.put(53, "admin:wars_back");
        player.openInventory(inv);
    }

    /**
     * 单个工会战模式的编辑器：布尔项点击切换，数值/文本项点击后用铁砧输入。
     * 写回 {@code war-modes.yml} 对应模式节点。
     */
    public void openModeEditor(Player player, String key) {
        com.liu.liuguild.config.WarMode m = plugin.settings().mode(key);
        if (m == null) {
            plugin.messages().send(player, "unknown-command");
            return;
        }
        Holder holder = new Holder("mode_edit", "", 0);
        Inventory inv = simpleInventory(player, holder, 5,
                "<gold>编辑模式 <white>" + m.display, Map.of());

        // ---- 布尔开关（第 1 排） ----
        modeToggle(inv, holder, player, key, 0, "enabled", m.enabled, "启用该模式");
        modeToggle(inv, holder, player, key, 1, "fair-equipment", m.fairEquipment, "发放统一公平装备");
        modeToggle(inv, holder, player, key, 2, "fair-health", m.fairHealth, "屏蔽蟠桃血量，保证公平血量");
        modeToggle(inv, holder, player, key, 3, "allow-spectators", m.allowSpectators, "允许成员旁观");
        modeToggle(inv, holder, player, key, 4, "keep-inventory", m.keepInventory, "死亡保留物品");
        modeToggle(inv, holder, player, key, 5, "broadcast-queue", m.broadcastQueue, "参与匹配时全服广播");

        // ---- 准备阶段相关（第 2 排） ----
        modeToggle(inv, holder, player, key, 9, "freeze-on-prepare", m.freezeOnPrepare, "准备阶段锁定移动/视角/交互");
        modeToggle(inv, holder, player, key, 10, "allow-jump-on-prepare", m.allowJumpOnPrepare, "准备阶段允许跳跃");
        modeToggle(inv, holder, player, key, 11, "prepare-platform", m.preparePlatform, "脚下垫屏障，物理禁止起跳");
        modeToggle(inv, holder, player, key, 12, "lives.balance-teams", m.balanceTeamLives, "双方总生命池按人数归一");

        // ---- 数值项（点击输入） ----
        modeInput(inv, holder, player, key, 18, "team-size", m.teamSize, "每队人数上限");
        modeInput(inv, holder, player, key, 19, "min-members", m.minMembers, "参战最低在线人数");
        modeInput(inv, holder, player, key, 20, "min-level", m.minLevel, "参战最低工会等级");
        modeInput(inv, holder, player, key, 21, "prepare-seconds", m.prepareSeconds, "入场后的准备秒数");
        modeInput(inv, holder, player, key, 22, "duration-seconds", m.durationSeconds, "战斗时长（秒）");
        modeInput(inv, holder, player, key, 23, "settle-seconds", m.settleSeconds, "结算阶段时长（秒）");
        modeInput(inv, holder, player, key, 24, "lives.per-player", m.livesPerPlayer, "每人初始生命数");
        modeInput(inv, holder, player, key, 25, "respawn-delay-seconds", m.respawnDelaySeconds, "复活等待（秒）");
        modeInput(inv, holder, player, key, 26, "score.kill", m.scoreKill, "击杀得分");

        // ---- 文本项 ----
        modeInput(inv, holder, player, key, 27, "arena", m.arena, "引用竞技场 id");
        modeInput(inv, holder, player, key, 28, "display", m.display, "显示名（GUI / 广播文案）");
        modeInput(inv, holder, player, key, 29, "description", m.description.isBlank() ? "无" : m.description, "模式描述");

        inv.setItem(40, GuiUtil.build(player, "ARROW", "ARROW", "<white>返回模式列表", List.of(), Map.of(), null));
        holder.actions.put(40, "admin:modes");
        player.openInventory(inv);
    }

    /** 布尔开关按钮（左键翻转）。 */
    private void modeToggle(Inventory inv, Holder holder, Player player, String modeKey,
                            int slot, String path, boolean value, String desc) {
        String material = value ? "LIME_DYE" : "GRAY_DYE";
        inv.setItem(slot, GuiUtil.build(player, material, material,
                "<white>" + path + "：" + (value ? "<green>开" : "<red>关"),
                List.of("<gray>" + desc, "", "<yellow>点击切换"), Map.of(), null));
        holder.actions.put(slot, "mode_toggle:" + modeKey + ":" + path);
    }

    /** 数值/文本项按钮（左键输入新值）。 */
    private void modeInput(Inventory inv, Holder holder, Player player, String modeKey,
                           int slot, String path, Object value, String desc) {
        String shown = String.valueOf(value);
        inv.setItem(slot, GuiUtil.build(player, "PAPER", "PAPER",
                "<white>" + path + "：<yellow>" + (shown.isBlank() ? "无" : shown),
                List.of("<gray>" + desc, "", "<yellow>点击输入新值"), Map.of(), null));
        holder.actions.put(slot, "mode_input:" + modeKey + ":" + path);
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
        String name = point.toLowerCase(java.util.Locale.ROOT);
        if (name.equals("spec")) {
            name = "spectator";
        }
        if (!name.matches("[a-z0-9_-]{1,16}")) {
            plugin.messages().send(player, "arena-usage");
            return;
        }
        Location loc = player.getLocation();
        String base = "arenas." + arenaId + ".spawn-" + name + ".";
        plugin.configs().set("arena.yml", base + "x", round(loc.getX()));
        plugin.configs().set("arena.yml", base + "y", round(loc.getY()));
        plugin.configs().set("arena.yml", base + "z", round(loc.getZ()));
        plugin.configs().set("arena.yml", base + "yaw", round(loc.getYaw()));
        plugin.configs().set("arena.yml", base + "pitch", round(loc.getPitch()));
        plugin.reloadSettings();
        plugin.messages().sendRaw(player, "arena-spawn-set", "arena", arenaId,
                "point", name.toUpperCase(java.util.Locale.ROOT), "world", loc.getWorld().getName());
        openArenaEditor(player, arenaId);
    }

    /** 铁砧输入一个出生点名，然后在当前位置保存。 */
    private void promptArenaPoint(Player player, String arenaId, String defaultPoint) {
        player.closeInventory();
        requestText(player, "arena-point-input", text -> {
            String name = text.trim().toLowerCase(java.util.Locale.ROOT);
            setArenaSpawn(player, arenaId, name.isEmpty() ? defaultPoint : name);
        }, "arena", arenaId, "point", defaultPoint);
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
            } else if (key.equalsIgnoreCase("team-a-spawn") || key.equalsIgnoreCase("team-b-spawn")) {
                ArenaDef arena = plugin.settings().arena(arenaId);
                String v = value.toLowerCase(java.util.Locale.ROOT);
                if (arena == null || arena.spawn(v) == null) {
                    plugin.messages().send(player, "arena-usage");
                } else {
                    plugin.configs().set("arena.yml", "arenas." + arenaId + "." + key.toLowerCase(java.util.Locale.ROOT), v);
                }
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

    // ---- 工会战模式编辑 ----

    /** 翻转模式的一个布尔项，写回 war-modes.yml。 */
    private void toggleMode(Player player, String modeKey, String path) {
        com.liu.liuguild.config.WarMode m = plugin.settings().mode(modeKey);
        if (m == null) {
            return;
        }
        boolean current = switch (path) {
            case "enabled" -> m.enabled;
            case "fair-equipment" -> m.fairEquipment;
            case "fair-health" -> m.fairHealth;
            case "allow-spectators" -> m.allowSpectators;
            case "keep-inventory" -> m.keepInventory;
            case "broadcast-queue" -> m.broadcastQueue;
            case "freeze-on-prepare" -> m.freezeOnPrepare;
            case "allow-jump-on-prepare" -> m.allowJumpOnPrepare;
            case "prepare-platform" -> m.preparePlatform;
            case "lives.balance-teams" -> m.balanceTeamLives;
            default -> false;
        };
        plugin.configs().set("war-modes.yml", "modes." + modeKey + "." + path, !current);
        plugin.reloadSettings();
        openModeEditor(player, modeKey);
    }

    /** 输入模式的一个数值/文本项（只写该模式节点，不影响其它模式）。 */
    private void promptModeInput(Player player, String modeKey, String path) {
        com.liu.liuguild.config.WarMode m = plugin.settings().mode(modeKey);
        if (m == null) {
            return;
        }
        player.closeInventory();
        requestText(player, "mode-input", text -> {
            String value = text.trim();
            if (value.isEmpty()) {
                openModeEditor(player, modeKey);
                return;
            }
            Object stored = value;
            // 数值型字段必须存为数字，写成字符串会让解析回退到默认值
            switch (path) {
                case "team-size", "min-members", "min-level", "prepare-seconds",
                     "duration-seconds", "settle-seconds", "lives.per-player",
                     "respawn-delay-seconds", "score.kill" -> stored = NumberUtil.toInt(value, 1);
                default -> {
                }
            }
            plugin.configs().set("war-modes.yml", "modes." + modeKey + "." + path, stored);
            plugin.reloadSettings();
            plugin.messages().sendRaw(player, "mode-input-set", "mode", modeKey, "key", path, "value", value);
            openModeEditor(player, modeKey);
        }, "key", modeKey + "." + path);
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

    /** 管理员改名字：先输入名称，再输入标签（可留空）。 */
    private void promptAdminRename(Player player, String guildId) {
        Guild guild = plugin.guilds().guild(guildId);
        if (guild == null) {
            return;
        }
        player.closeInventory();
        requestText(player, "rename-input-name", name -> {
            if (name.isBlank()) {
                openGuildEditor(player, guildId);
                return;
            }
            requestText(player, "rename-input-tag", tag -> {
                plugin.guilds().adminRename(player, guild, name, tag.isBlank() ? null : tag);
                openGuildEditor(player, guildId);
            });
        });
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
                            "<gray>出生点：<white>" + String.join(", ", a.spawnPointNames()),
                            "<gray>A→<white>" + a.teamASpawn + " <gray>B→<white>" + a.teamBSpawn,
                            "",
                            "<yellow>点击编辑"), Map.of(), null));
            holder.actions.put(i, "arena_edit:" + id);
        }
        addPageControls(player, holder, inv, "arenas", page, (ids.size() - 1) / perPage, "工会列表", "admin:guilds", "admin:open");
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
        slot = put(inv, holder, slot, player, "LIME_BED", "<white>设置命名出生点…",
                List.of("<gray>点击输入点名（a/b/c/d/任意）", "<gray>使用当前位置保存",
                        "<gray>已配置：<white>" + String.join(", ", a.spawnPointNames())),
                "arena_setpoint:" + a.id + ":c");
        slot = put(inv, holder, slot, player, "OAK_SIGN", "<white>A 队出生点：<yellow>" + a.teamASpawn,
                List.of("<gray>点击输入使用哪个命名点（默认 a）"), "arena_teampoint:" + a.id + ":team-a-spawn");
        slot = put(inv, holder, slot, player, "SPRUCE_SIGN", "<white>B 队出生点：<yellow>" + a.teamBSpawn,
                List.of("<gray>点击输入使用哪个命名点（默认 b）"), "arena_teampoint:" + a.id + ":team-b-spawn");
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
        addPageControls(player, holder, inv, "guilds", page, (guilds.size() - 1) / perPage, "竞技场列表", "admin:arenas", "admin:open");
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
        slot = put(inv, holder, slot, player, "NAME_TAG", "<white>改名字：<yellow>" + g.name() + " <dark_gray>[" + g.tag() + "]",
                List.of("<gray>管理员改名（绕开冷却与费用）", "<gray>不填标签 = 只改名称"),
                "guild_rename:" + g.id());
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

    /**
     * 分页控件：上/下页 + 「返回上一级」 + 「切换到另一个列表」。
     *
     * <p>之前只提供了两个列表互切，导致玩家在「竞技场↔工会」之间打转，
     * <b>无法退回管理面板</b>。现补上独立的返回按钮。</p>
     */
    private void addPageControls(Player player, Holder holder, Inventory inv, String menuKey,
                                 int page, int maxPage, String switchLabel, String switchAction,
                                 String backAction) {
        if (page > 0) {
            inv.setItem(45, GuiUtil.build(player, "ARROW", "ARROW", "<white>上一页", List.of(), Map.of(), null));
            holder.actions.put(45, "page_" + menuKey + ":" + (page - 1));
        }
        if (page < maxPage) {
            inv.setItem(53, GuiUtil.build(player, "ARROW", "ARROW", "<white>下一页", List.of(), Map.of(), null));
            holder.actions.put(53, "page_" + menuKey + ":" + (page + 1));
        }
        if (switchAction != null && switchLabel != null) {
            inv.setItem(48, GuiUtil.build(player, "CHEST", "CHEST", "<white>切换：" + switchLabel,
                    List.of("<gray>点击切换到该列表"), Map.of(), null));
            holder.actions.put(48, switchAction);
        }
        inv.setItem(50, GuiUtil.build(player, "BARRIER", "BARRIER", "<white>返回管理面板",
                List.of("<gray>回到管理菜单"), Map.of(), null));
        holder.actions.put(50, backAction == null ? "admin:open" : backAction);
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
                openMain(player);
                return;
            }
            // 银行余额是异步落库的：这里不能立即重开菜单，否则会渲染出旧余额。
            // 成功时由 GuildBankEvent 触发刷新；失败（余额不足等）无事件，用延迟兜底。
            if (withdraw) {
                plugin.guilds().withdraw(player, g, amount);
            } else {
                plugin.guilds().deposit(player, g, amount);
            }
            plugin.syncDelayed(() -> refreshIfNoMenu(player), 15L);
        });
    }

    /** 若玩家当前没有打开任何 Liuguild 菜单，则重新打开主菜单（用于异步操作后的兜底刷新）。 */
    private void refreshIfNoMenu(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        boolean hasMenu = player.getOpenInventory().getTopInventory().getHolder() instanceof Holder;
        if (!hasMenu && plugin.guilds().guildOf(player.getUniqueId()) != null) {
            openMain(player);
        }
    }

    /** 无工会引导菜单的「如何加入工会」：把可行路径逐条说清楚。 */
    private void sendJoinHelp(Player player) {
        player.closeInventory();
        plugin.messages().sendRaw(player, "join-help-header");
        plugin.messages().sendRaw(player, "join-help-line1");
        plugin.messages().sendRaw(player, "join-help-line2");
        plugin.messages().sendRaw(player, "join-help-line3");
        plugin.messages().sendRaw(player, "join-help-footer");
    }

    /**
     * 银行交易完成（余额已更新）后刷新界面，保证存的金额立即反映到 GUI 上。
     */
    @EventHandler
    public void onBankChange(com.liu.liuguild.api.event.GuildBankEvent event) {
        Player player = event.player();
        if (player == null || !player.isOnline()) {
            return;
        }
        plugin.sync(() -> openMain(player));
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

    /**
     * 工会详情面板的扩展占位符（胜率 / 在线 / 升级进度 / 建会时间等）。
     * 仅 {@code openInfo} 使用，避免主菜单每帧都算这些。
     */
    private Map<String, String> detailPlaceholders(Guild g) {
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
        ph.put("online", String.valueOf(plugin.guilds().onlineMembers(g).size()));
        long need = plugin.settings().guild.requiredPoints(g.level());
        boolean maxed = g.level() >= plugin.settings().guild.maxLevel;
        ph.put("next_level_points", maxed ? "已满级" : String.valueOf(need));
        ph.put("level_progress", maxed ? "已满级"
                : g.points() + " / " + need + "（还需 " + Math.max(0, need - g.points()) + "）");
        ph.put("created", com.liu.liuguild.util.TimeUtil.formatDate(g.createdAt()));
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
            case "info" -> "info:open";
            case "card" -> "noop";
            case "info_notice" -> "info:notice";
            case "info_desc" -> "info:desc";
            case "info_members" -> "info:members";
            case "info_history" -> "info:history";
            case "info_back" -> "info:back";
            case "leave" -> "leave";
            case "warehouse" -> "guild:warehouse";
            case "titles" -> "title:open";
            case "shop" -> "shop:open";
            case "toggle_open" -> "set:open";
            case "notice" -> "set:notice";
            case "desc" -> "set:desc";
            case "icon" -> "set:icon";
            case "rename" -> "set:rename";
            case "disband" -> "guild:disband";
            case "back" -> "back:main";
            case "history" -> "war_history";
            case "leave_queue" -> "war_leave";
            case "status" -> "war_status";
            case "spectate" -> "war_spectate";
            case "admin_status" -> "admin:status";
            case "admin_modes" -> "admin:modes";
            case "admin_arena" -> "admin:arenas";
            case "admin_guilds" -> "admin:guilds";
            case "admin_wars" -> "admin:wars";
            case "admin_kits" -> "admin:kits";
            case "admin_cleanup" -> "admin:cleanup";
            case "admin_verify" -> "admin:verify";
            case "admin_reload" -> "admin:reload";
            case "close" -> "close";
            case "help" -> "join_help";
            case "create" -> "list:create";
            default -> buttonId;
        };
    }
}
