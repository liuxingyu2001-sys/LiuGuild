package com.liu.liuguild.gui;

import com.liu.liuguild.LiuGuild;
import com.liu.liuguild.api.event.GuildFundsEvent;
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
import com.liu.liuguild.util.WorldResolver;
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
    private final GuiPlaceholders guiPlaceholders;

    public GuiManager(LiuGuild plugin) {
        this.plugin = plugin;
        this.chatInput = new ChatInput(plugin);
        this.guiPlaceholders = new GuiPlaceholders(plugin);
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
        /** 菜单标题（Sparrow 渲染时使用）。 */
        Component title;

        Holder(String menu, String guildId, int page) {
            this.menu = menu;
            this.guildId = guildId;
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }

        /** 菜单标题（Sparrow 渲染用）。 */
        public Component title() {
            return title;
        }

        /** 槽位绑定的动作；无则返回 null。 */
        public String actionAt(int slot) {
            return actions.get(slot);
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
        // 统一由 show() 渲染：Sparrow 可用时走会话式窗口（ESC 返回上一页），否则回退内置 GUI。
        MenuDef def = menu("main");
        if (def == null) {
            return;
        }
        Map<String, String> ph = mainPlaceholders(player, g);
        Holder holder = new Holder("main", g.id(), 0);
        Inventory inv = create(holder, def, ph, player);
        for (Map.Entry<String, ButtonDef> e : def.buttons.entrySet()) {
            if (!buttonVisible(e.getKey())) {
                continue;
            }
            if (("apps".equals(e.getKey()) || "log".equals(e.getKey())) && !canManage(g, player)) {
                continue;
            }
            if ("apps".equals(e.getKey()) && !plugin.settings().guild.applicationEnabled) {
                continue;
            }
            if ("mail".equals(e.getKey()) && plugin.mail() == null) {
                continue;
            }
            if ("log".equals(e.getKey()) && plugin.audit() == null) {
                continue;
            }
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
        show(player, holder, inv);
    }

    /**
     * 主菜单按钮是否应展示：依赖的可选功能缺失时直接隐藏，避免出现点了只弹“不可用”的死按钮。
     */
    public boolean buttonVisible(String buttonId) {
        if (buttonId == null) {
            return true;
        }
        return switch (buttonId) {
            case "home", "homes" -> plugin.guildHome() != null && plugin.guildHome().available();
            case "chat" -> plugin.guildChat() != null && plugin.guildChat().available();
            case "buffs" -> plugin.buffs() != null && plugin.settings().buffs.enabled;
            case "warehouse_manage" -> plugin.warehouse() != null;
            default -> true;
        };
    }

    public void openMembers(Player player, Guild g, int page) {
        openMembers(player, g, page, false);
    }

    /**
     * @param viewOnly 查看其它工会时只读：不绑定晋升/降级/踢人/邀请动作
     */
    public void openMembers(Player player, Guild g, int page, boolean viewOnly) {
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
            mph.put("joined", com.liu.liuguild.util.TimeUtil.formatDate(m.joinedAt()));
            ItemStack item = GuiUtil.build(player, def.entry.material, def.entry.fallbackMaterial,
                    def.entry.name, def.entry.lore, mph, m.uuid());
            int slot = i;
            inv.setItem(slot, item);
            if (!viewOnly) {
                holder.actions.put(slot, "member:" + m.uuid());
            }
        }
        addBack(inv, holder, def, ph, player, "back:main", page, "members", members.size(), perPage);
        // 邀请入口：权限与冷却由 GuildManager.invite 统一校验，这里只负责收集名字。
        // 槽位不能写死 51：members.rows 调小后 51 会越界，且不能与返回按钮占同一格。
        int inviteSlot = Math.min(inv.getSize() - 1, 51);
        if (inviteSlot == inv.getSize() - 5) {
            inviteSlot = inv.getSize() - 6;
        }
        if (!viewOnly && inviteSlot >= 0) {
            inv.setItem(inviteSlot, GuiUtil.build(player, "NAME_TAG", "NAME_TAG", "<green>邀请玩家",
                    List.of("<gray>输入在线玩家名字发出入会邀请"), Map.of(), null));
            holder.actions.put(inviteSlot, "invite_player");
        }
        show(player, holder, inv);
    }

    public void openNoGuild(Player player) {
        MenuDef def = menu("noguild");
        if (def == null || def.buttons.isEmpty()) {
            // 缺配置时退而求其次：至少展示工会列表（内含创建入口）。
            openList(player, 0);
            return;
        }
        Map<String, String> ph = placeholders(null);
        ph.put("invite_count", String.valueOf(plugin.guilds().pendingInvites(player.getUniqueId()).size()));
        Holder holder = new Holder("noguild", "", 0);
        Inventory inv = create(holder, def, ph, player);
        for (Map.Entry<String, ButtonDef> e : def.buttons.entrySet()) {
            if (!buttonVisible(e.getKey())) {
                continue;
            }
            if ("mail".equals(e.getKey()) && plugin.mail() == null) {
                continue;
            }
            ButtonDef b = e.getValue();
            inv.setItem(clamp(b.slot), GuiUtil.fromButton(player, b, ph));
            holder.actions.put(clamp(b.slot), actionId(e.getKey()));
        }
        show(player, holder, inv);
    }

    public void openList(Player player, int page) {
        MenuDef def = menu("list");
        if (def == null || def.entry == null) {
            return;
        }
        List<Guild> guilds = new ArrayList<>(plugin.guilds().all());
        guilds.sort((a, b) -> Long.compare(b.points(), a.points()));
        Guild self = plugin.guilds().guildOf(player.getUniqueId());
        Map<String, String> ph = new LinkedHashMap<>();
        ph.put("count", String.valueOf(guilds.size()));
        Holder holder = new Holder("list", self == null ? "" : self.id(), page);
        Inventory inv = create(holder, def, ph, player);
        int perPage = Math.max(1, (def.rows - 2) * 7);
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
            int slot = def.rows >= 3 ? 10 + (i / 7) * 9 + i % 7 : i;
            inv.setItem(slot, item);
            holder.actions.put(slot, "list_entry:" + g.id());
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
        show(player, holder, inv);
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
        ph.put("enemy", war == null ? "-" : war.parties().stream()
                .filter(party -> !party.guildId().equals(g.id()))
                .map(GuildWar.Party::name).collect(java.util.stream.Collectors.joining(",")));
        String ownSide = war == null ? null : war.sideOfGuild(g.id());
        ph.put("score", war == null || ownSide == null ? "0" : String.valueOf(war.scoreOfSide(ownSide)));
        ph.put("enemy_score", war == null ? "0" : String.valueOf(war.parties().stream()
                .filter(party -> !party.guildId().equals(g.id()))
                .mapToInt(GuildWar.Party::score).max().orElse(0)));
        ph.put("lives", String.valueOf(plugin.war() == null ? 0 : plugin.war().lives(player.getUniqueId())));
        ph.put("arena", plugin.war() == null ? "-" : plugin.war().arenaOf(war).id);
        ph.put("host", war == null ? "-" : war.hostServer());
        ph.put("ranking", war == null ? "-" : war.ranking().stream()
                .map(party -> "#" + (war.ranking().indexOf(party) + 1) + " "
                        + party.name() + " " + party.score())
                .collect(java.util.stream.Collectors.joining("  ")));
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
        show(player, holder, inv);
    }

    /** 管理员 GUI：状态 / 模式 / 场地 / 装备 / 重载。 */
    public void openAdmin(Player player) {
        if (!player.hasPermission("liuguild.admin") && !player.hasPermission("liuguild.admin.gui")) {
            plugin.messages().send(player, "no-permission");
            return;
        }
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
        show(player, holder, inv);
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
        Holder holder = new Holder("info", g.id(), others ? 1 : 0);
        Inventory inv = create(holder, def, ph, player);
        for (Map.Entry<String, ButtonDef> e : def.buttons.entrySet()) {
            ButtonDef b = e.getValue();
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
        show(player, holder, inv);
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
            if (("notice".equals(e.getKey()) || "desc".equals(e.getKey()) || "toggle_pvp".equals(e.getKey()))
                    && !canManage) {
                continue;
            }
            ButtonDef b = e.getValue();
            inv.setItem(clamp(b.slot), GuiUtil.fromButton(player, b, ph));
            holder.actions.put(clamp(b.slot), actionId(e.getKey()));
        }
        show(player, holder, inv);
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
            boolean current = icon.id != null && icon.id.equals(g.icon());
            Map<String, String> iph = new LinkedHashMap<>(ph);
            iph.put("icon_name", icon.display);
            iph.put("icon_cost", current
                    ? "当前使用（不扣费）"
                    : plugin.economy().format(plugin.settings().guild.iconChangeCost));
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
            holder.actions.put(i, current ? "noop" : "icon:" + icon.id);
        }
        addBack(inv, holder, def, ph, player, "back:settings", page, "icons", icons.size(), perPage);
        show(player, holder, inv);
    }

    // =====================================================================
    // 工会头衔面板
    // =====================================================================

    /**
     * 头衔面板：列出本工会全部头衔，左键装备/卸下，不提供自定义管理入口。
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
        String equippedId = plugin.titles().equippedId(player.getUniqueId());

        Holder holder = new Holder("titles", g.id(), 0);
        Map<String, String> ph = new LinkedHashMap<>();
        ph.put("count", String.valueOf(plugin.titles().titlesOf(g.id()).size()));
        ph.put("max", String.valueOf(plugin.settings().guild.fixedTitles.size()));
        Inventory inv = simpleInventory(player, holder, 6,
                "<gold>工会头衔 <dark_gray>({count}/{max})", ph);

        List<GuildTitle> all = plugin.titles().titlesOf(g.id());
        if (all.isEmpty()) {
            inv.setItem(22, GuiUtil.build(player, "NAME_TAG", "NAME_TAG",
                    "<gray>暂无可用的固定头衔",
                    List.of("<gray>请联系管理员配置预设头衔"),
                    Map.of(), null));
        }
        int slot = 0;
        for (GuildTitle t : all) {
            boolean equipped = t.id().equals(equippedId);
            boolean usable = t.usableBy(rank);
            String color = equipped ? "<green>" : (usable ? "<white>" : "<dark_gray>");
            List<String> lore = new ArrayList<>();
            lore.add("<gray>最低职位：<white>" + t.minRank().display());
            lore.add("");
            if (equipped) {
                lore.add("<yellow>左键卸下");
            } else if (usable) {
                lore.add("<yellow>左键装备");
            } else {
                lore.add("<red>你的职位不足以使用");
            }
            inv.setItem(slot, GuiUtil.build(player, "NAME_TAG", "NAME_TAG",
                    color + t.display(), lore, Map.of(), null));
            holder.actions.put(slot, "title_toggle:" + t.id());
            slot++;
            if (slot >= 45) {
                break;
            }
        }

        // 底排：返回 / 卸下
        GuildTitle current = plugin.titles().equipped(player.getUniqueId());
        String currentDisplay = current == null ? "无" : current.display();
        inv.setItem(50, GuiUtil.build(player, "BARRIER", "BARRIER", "<yellow>卸下当前头衔",
                List.of("<gray>当前：<white>" + currentDisplay), Map.of(), null));
        holder.actions.put(50, "title:clear");
        inv.setItem(49, GuiUtil.build(player, "ARROW", "ARROW", "<white>返回", List.of(), Map.of(), null));
        holder.actions.put(49, "back:main");
        show(player, holder, inv);
    }

    /** 头衔面板：左键装备/卸下，右键不执行操作。 */
    private void titleToggle(Player player, Guild g, String titleId, boolean rightClick) {
        if (g == null || plugin.titles() == null) {
            return;
        }
        if (rightClick) {
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
                plugin.messages().send(player, err);
                return;
            }
            GuildTitle t = plugin.titles().equipped(player.getUniqueId());
            plugin.messages().sendRich(player, "title-equipped",
                    "display", t == null ? "" : t.display());
        }
        openTitles(player, g);
    }

    // =====================================================================
    // 工会驻地（HuskHomes）
    // =====================================================================

    /** 打开工会驻地菜单：逐槽传送 / 重设 / 删除（多槽位完整暴露，而非只能操作 1 号）。 */
    public void openHomes(Player player, Guild g) {
        if (g == null) {
            plugin.messages().send(player, "not-in-guild");
            return;
        }
        var home = plugin.guildHome();
        if (home == null || !home.available()) {
            plugin.messages().send(player, "home-unavailable");
            return;
        }
        int slots = home.maxSlots(g);
        boolean manage = home.manage(player, g);
        // loadHomes 是阻塞 IO，放异步读取后再回主线程渲染。
        plugin.async(() -> {
            java.util.List<com.liu.liuguild.hook.GuildHomeHook.HomeSlot> set = home.homeSlots(g);
            plugin.sync(() -> {
                if (!player.isOnline()) {
                    return;
                }
                Guild current = plugin.guilds().guildOf(player.getUniqueId());
                if (current == null || !current.id().equals(g.id())) {
                    return;
                }
                renderHomes(player, current, slots, manage, set);
            });
        });
    }

    private void renderHomes(Player player, Guild g, int slots, boolean manage,
                             java.util.List<com.liu.liuguild.hook.GuildHomeHook.HomeSlot> set) {
        Holder holder = new Holder("homes", g.id(), 0);
        Map<String, String> ph = new LinkedHashMap<>();
        ph.put("guild", g.name());
        ph.put("slots", String.valueOf(slots));
        Inventory inv = simpleInventory(player, holder, 6,
                "<gold>工会驻地 <dark_gray>· {guild} <gray>({slots} 槽)", ph);
        Map<Integer, com.liu.liuguild.hook.GuildHomeHook.HomeSlot> bySlot = new LinkedHashMap<>();
        for (var s : set) {
            bySlot.put(s.slot(), s);
        }
        int slot = 0;
        for (int i = 1; i <= slots && slot < 45; i++, slot++) {
            var s = bySlot.get(i);
            List<String> lore = new ArrayList<>();
            if (s == null) {
                lore.add("<gray>状态：<dark_gray>未设置");
                if (manage) {
                    lore.add("<yellow>左键把当前位置设为该驻地");
                }
                inv.setItem(slot, GuiUtil.build(player, "LIGHT_GRAY_DYE", "LIGHT_GRAY_DYE",
                        "<gray>驻地 #" + i, lore, Map.of(), null));
                if (manage) {
                    holder.actions.put(slot, "home_set:" + i);
                }
            } else {
                lore.add("<gray>状态：<green>已设置");
                lore.add("<gray>服务器：<white>" + (s.server() == null || s.server().isBlank() ? "-" : s.server()));
                lore.add("<gray>世界：<white>" + (s.world() == null || s.world().isBlank() ? "-" : s.world()));
                lore.add(String.format("<gray>坐标：<white>%.1f, %.1f, %.1f", s.x(), s.y(), s.z()));
                lore.add("");
                lore.add("<yellow>左键传送");
                if (manage) {
                    lore.add("<yellow>右键重设为当前位置");
                    lore.add("<red>Shift+右键删除");
                }
                inv.setItem(slot, GuiUtil.build(player, "COMPASS", "COMPASS",
                        "<white>驻地 #" + i, lore, Map.of(), null));
                holder.actions.put(slot, "home_tp:" + i);
            }
        }
        show(player, holder, inv);
    }

    /** 驻地设为位置 / 删除后需要等异步写库回写，再延迟刷新菜单。 */
    private void reopenHomesLater(Player player, Guild g) {
        plugin.syncDelayed(() -> {
            if (player.isOnline() && plugin.guilds().guildOf(player.getUniqueId()) != null) {
                openHomes(player, g);
            }
        }, 20L);
    }

    // =====================================================================
    // 排行榜 / 入会邀请
    // =====================================================================

    /** 工会排行榜：主界面原本没有入口，只能靠 /guild top 指令。 */
    public void openTop(Player player, String category) {
        String column = switch (category == null ? "points" : category.toLowerCase(java.util.Locale.ROOT)) {
            case "bank" -> "bank";
            case "level" -> "level";
            case "wins" -> "wins";
            case "losses" -> "losses";
            case "members" -> "members";
            default -> "points";
        };
        Holder holder = new Holder("top", "", 0);
        Map<String, String> ph = new LinkedHashMap<>();
        ph.put("category", column);
        Inventory inv = simpleInventory(player, holder, 6, "<gold>工会排行榜 <dark_gray>· {category}", ph);
        List<Guild> list = plugin.guilds().top(column, 10);
        int[] rankSlots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21};
        int rank = 1;
        for (Guild g : list) {
            int slot = rankSlots[rank - 1];
            String value = switch (column) {
                case "bank" -> plugin.economy().format(g.bank());
                case "level" -> String.valueOf(g.level());
                case "wins" -> String.valueOf(g.wins());
                case "losses" -> String.valueOf(g.losses());
                case "members" -> String.valueOf(g.memberCount());
                default -> String.valueOf(g.points());
            };
            inv.setItem(slot, GuiUtil.build(player, "CE:liuguild:emblem_default", "SHIELD",
                    "<white>#" + rank + " " + g.name() + " <dark_gray>[" + g.tag() + "]",
                    List.of("<gray>等级：<white>" + g.level(),
                            "<gray>成员：<white>" + g.memberCount(),
                            "<gray>资金：<white>" + g.points(),
                            "<gray>排行值：<white>" + value,
                            "",
                            "<yellow>左键查看详情"),
                    Map.of(), null));
            holder.actions.put(slot, "info_view:" + g.id());
            rank++;
            if (rank > rankSlots.length) {
                break;
            }
        }
        String[] categories = {"points", "bank", "level", "members", "wins", "losses"};
        String[] categoryNames = {"公共资金", "金币银行", "工会等级", "成员人数", "胜场", "负场"};
        int[] catSlots = {45, 46, 47, 50, 51, 52};
        for (int i = 0; i < categories.length; i++) {
            String c = categories[i];
            boolean active = c.equals(column);
            String material = active ? "LIME_DYE" : "GRAY_DYE";
            inv.setItem(catSlots[i], GuiUtil.build(player, material, material,
                    (active ? "<green>✓ " : "<gray>") + categoryNames[i],
                    List.of("<gray>点击切换该排行"), Map.of(), null));
            holder.actions.put(catSlots[i], "top_cat:" + c);
        }
        inv.setItem(49, GuiUtil.build(player, "ARROW", "ARROW", "<white>返回主页", List.of(), Map.of(), null));
        holder.actions.put(49, "back:main");
        show(player, holder, inv);
    }

    /** 待处理入会邀请：无工会玩家过去只能手输 /guild accept <工会>。 */
    public void openInvites(Player player) {
        java.util.List<com.liu.liuguild.guild.GuildManager.PendingInvite> invites =
                plugin.guilds().pendingInvites(player.getUniqueId());
        if (invites.isEmpty()) {
            plugin.messages().send(player, "invite-none");
            return;
        }
        Holder holder = new Holder("invites", "", 0);
        Inventory inv = simpleInventory(player, holder, 3, "<gold>入会邀请", Map.of());
        int slot = 10;
        for (var inv0 : invites) {
            long left = Math.max(0, inv0.expiresAt() - System.currentTimeMillis()) / 1000L;
            inv.setItem(slot, GuiUtil.build(player, "PAPER", "PAPER", "<white>" + inv0.guildName(),
                    List.of("<gray>剩余时间：<white>" + left + " 秒", "",
                            "<green>左键接受", "<red>右键拒绝"),
                    Map.of(), null));
            holder.actions.put(slot, "invite_pick:" + inv0.guildId());
            slot++;
        }
        show(player, holder, inv);
    }

    // =====================================================================
    // 邮件 / 入会申请 / 审计 / 工会战战绩
    // =====================================================================

    /** 系统邮件：只读个人收件箱；点击条目标记已读并显示正文。 */
    public void openMail(Player player, int page) {
        if (plugin.mail() == null) {
            plugin.messages().send(player, "mail-usage");
            return;
        }
        Guild guild = plugin.guilds().guildOf(player.getUniqueId());
        plugin.mail().inboxAsync(player.getUniqueId(), 50, list -> {
            int perPage = 45;
            int maxPage = Math.max(0, (list.size() - 1) / perPage);
            int current = Math.max(0, Math.min(page, maxPage));
            Map<String, String> ph = new LinkedHashMap<>();
            ph.put("guild", guild == null ? "个人" : guild.name());
            Holder holder = new Holder("mail", guild == null ? "" : guild.id(), current);
            Inventory inv = simpleInventory(player, holder, 6,
                    "<gold>工会邮件 <dark_gray>· {guild}", ph);
            int from = current * perPage;
            int index = 0;
            for (int i = from; i < list.size() && index < perPage; i++, index++) {
                com.liu.liuguild.db.MailEntry entry = list.get(i);
                boolean unread = entry.readAt() == 0;
                List<String> lore = new ArrayList<>();
                lore.add("<gray>类型：<white>" + entry.type());
                lore.add("<gray>时间：<white>" + com.liu.liuguild.util.TimeUtil.formatDateTime(entry.createdAt()));
                lore.add("<gray>状态：<white>" + (unread ? "未读" : "已读"));
                if (entry.expiresAt() > 0) {
                    lore.add("<gray>过期：<white>" + com.liu.liuguild.util.TimeUtil.formatDateTime(entry.expiresAt()));
                }
                lore.add("");
                lore.add("<yellow>左键查看正文");
                inv.setItem(index, GuiUtil.build(player, unread ? "PAPER" : "MAP",
                        unread ? "PAPER" : "MAP", "<white>{title}", lore,
                        Map.of("title", entry.title()), null));
                holder.actions.put(index, "mail_read:" + entry.id());
            }
            if (list.isEmpty()) {
                inv.setItem(22, GuiUtil.build(player, "PAPER", "PAPER", "<gray>没有邮件",
                        List.of("<gray>工会战结果、入会申请结果会出现在这里"), Map.of(), null));
            }
            put(inv, holder, 49, player, "ARROW", "<white>返回", List.of(), "back:main");
            if (current > 0) {
                put(inv, holder, 45, player, "ARROW", "<white>上一页", List.of(),
                        "page_mail:" + (current - 1));
            }
            if (current < maxPage) {
                put(inv, holder, 53, player, "ARROW", "<white>下一页", List.of(),
                        "page_mail:" + (current + 1));
            }
            put(inv, holder, 48, player, "WRITABLE_BOOK", "<white>全部标记已读",
                    List.of("<gray>清除未读状态"), "mail_clear");
            show(player, holder, inv);
        });
    }

    /** 入会申请：官员在 GUI 内批准/拒绝，不再需要记编号。 */
    public void openApplications(Player player, Guild guild, int page) {
        if (guild == null || plugin.applications() == null || !plugin.settings().guild.applicationEnabled) {
            plugin.messages().send(player, "app-disabled");
            return;
        }
        if (!canManage(guild, player)) {
            plugin.messages().send(player, "no-permission-rank");
            return;
        }
        plugin.applications().listAsync(guild.id(), list -> {
            int perPage = 45;
            int maxPage = Math.max(0, (list.size() - 1) / perPage);
            int current = Math.max(0, Math.min(page, maxPage));
            Map<String, String> ph = new LinkedHashMap<>();
            ph.put("guild", guild.name());
            Holder holder = new Holder("applications", guild.id(), current);
            Inventory inv = simpleInventory(player, holder, 6,
                    "<gold>入会申请 <dark_gray>· {guild}", ph);
            int from = current * perPage;
            int index = 0;
            for (int i = from; i < list.size() && index < perPage; i++, index++) {
                com.liu.liuguild.db.GuildApplication app = list.get(i);
                List<String> lore = new ArrayList<>();
                lore.add("<gray>时间：<white>" + com.liu.liuguild.util.TimeUtil.formatDateTime(app.createdAt()));
                lore.add("<gray>留言：<white>" + (app.message() == null || app.message().isBlank()
                        ? "（无）" : app.message()));
                lore.add("");
                lore.add("<green>左键批准");
                lore.add("<red>右键拒绝");
                inv.setItem(index, GuiUtil.build(player, "PLAYER_HEAD", "PLAYER_HEAD",
                        "<white>" + app.playerName(), lore, Map.of(), app.player()));
                holder.actions.put(index, "app_decide:" + app.id());
            }
            if (list.isEmpty()) {
                inv.setItem(22, GuiUtil.build(player, "PAPER", "PAPER", "<gray>没有待处理申请",
                        List.of("<gray>新的入会申请会出现在这里"), Map.of(), null));
            }
            put(inv, holder, 49, player, "ARROW", "<white>返回", List.of(), "back:main");
            if (current > 0) {
                put(inv, holder, 45, player, "ARROW", "<white>上一页", List.of(),
                        "page_applications:" + (current - 1));
            }
            if (current < maxPage) {
                put(inv, holder, 53, player, "ARROW", "<white>下一页", List.of(),
                        "page_applications:" + (current + 1));
            }
            show(player, holder, inv);
        });
    }

    /** 工会审计日志（官员/会长）；只读。 */
    public void openAudit(Player player, Guild guild, int page) {
        if (guild == null || plugin.audit() == null || !canManage(guild, player)) {
            plugin.messages().send(player, "no-permission-rank");
            return;
        }
        plugin.audit().recentAsync(guild.id(), 100, list -> {
            int perPage = 45;
            int maxPage = Math.max(0, (list.size() - 1) / perPage);
            int current = Math.max(0, Math.min(page, maxPage));
            Map<String, String> ph = new LinkedHashMap<>();
            ph.put("guild", guild.name());
            Holder holder = new Holder("audit", guild.id(), current);
            Inventory inv = simpleInventory(player, holder, 6,
                    "<gold>工会日志 <dark_gray>· {guild}", ph);
            int from = current * perPage;
            int index = 0;
            for (int i = from; i < list.size() && index < perPage; i++, index++) {
                com.liu.liuguild.db.AuditEntry entry = list.get(i);
                String actor = entry.actorName() == null ? "系统" : entry.actorName();
                List<String> lore = new ArrayList<>();
                lore.add("<gray>操作者：<white>" + actor);
                lore.add("<gray>时间：<white>" + com.liu.liuguild.util.TimeUtil.formatDateTime(entry.createdAt()));
                if (entry.target() != null && !entry.target().isBlank()) {
                    lore.add("<gray>对象：<white>" + entry.target());
                }
                if (entry.amount() != 0) {
                    lore.add("<gray>数量：<white>" + entry.amount());
                }
                if (entry.detail() != null && !entry.detail().isBlank()) {
                    lore.add("<gray>详情：<white>" + entry.detail());
                }
                inv.setItem(index, GuiUtil.build(player, "PAPER", "PAPER",
                        "<white>" + com.liu.liuguild.audit.GuildAuditService.displayAction(entry.action()),
                        lore, Map.of(), null));
            }
            if (list.isEmpty()) {
                inv.setItem(22, GuiUtil.build(player, "PAPER", "PAPER", "<gray>暂无日志",
                        List.of("<gray>银行、商店、仓库等操作会记录在这里"), Map.of(), null));
            }
            put(inv, holder, 49, player, "ARROW", "<white>返回", List.of(), "back:main");
            if (current > 0) {
                put(inv, holder, 45, player, "ARROW", "<white>上一页", List.of(),
                        "page_audit:" + (current - 1));
            }
            if (current < maxPage) {
                put(inv, holder, 53, player, "ARROW", "<white>下一页", List.of(),
                        "page_audit:" + (current + 1));
            }
            show(player, holder, inv);
        });
    }

    /** 工会战个人战绩：最近一场，或指定 warId。 */
    public void openWarStats(Player player, Guild guild) {
        if (guild == null || plugin.war() == null) {
            return;
        }
        plugin.war().statsAsync(guild.id(), null, list -> {
            Map<String, String> ph = new LinkedHashMap<>();
            ph.put("guild", guild.name());
            Holder holder = new Holder("war_stats", guild.id(), 0);
            Inventory inv = simpleInventory(player, holder, 6,
                    "<gold>工会战战绩 <dark_gray>· {guild}", ph);
            int index = 0;
            for (var stat : list) {
                if (index >= 45) {
                    break;
                }
                List<String> lore = new ArrayList<>();
                lore.add("<gray>击杀：<white>" + stat.kills() + " <gray>死亡：<white>" + stat.deaths());
                lore.add("<gray>存活：<white>" + stat.survivedSeconds() + " 秒");
                lore.add("<gray>MVP：<white>" + (stat.mvp() ? "是" : "否"));
                inv.setItem(index, GuiUtil.build(player, stat.mvp() ? "GOLDEN_HELMET" : "PLAYER_HEAD",
                        stat.mvp() ? "GOLDEN_HELMET" : "PLAYER_HEAD",
                        "<white>" + (stat.playerName() == null ? stat.player().toString() : stat.playerName()),
                        lore, Map.of(), null));
                index++;
            }
            if (list.isEmpty()) {
                inv.setItem(22, GuiUtil.build(player, "PAPER", "PAPER", "<gray>暂无战绩",
                        List.of("<gray>完成一场工会战后可查看"), Map.of(), null));
            }
            put(inv, holder, 49, player, "ARROW", "<white>返回", List.of(), "war_back");
            show(player, holder, inv);
        });
    }

    private boolean canManage(Guild guild, Player player) {
        if (guild == null || player == null) {
            return false;
        }
        GuildMember member = guild.member(player.getUniqueId());
        return member != null && member.rank().atLeast(GuildRank.OFFICER);
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
        openShopPage(player, g, plugin.shop().listingsOf(g.id()), 0);
    }

    private void openShopPage(Player player, Guild own, List<ShopListing> listings, int page) {
        int maxPage = Math.max(0, (listings.size() - 1) / 28);
        page = Math.max(0, Math.min(page, maxPage));
        Holder holder = new Holder("shop", own == null ? "" : own.id(), page);
        Map<String, String> ph = new LinkedHashMap<>();
        ph.put("guild", own == null ? "" : own.name());
        Inventory inv = simpleInventory(player, holder, 6,
                "<gold>工会商店 <dark_gray>· {guild}", ph);

        if (listings.isEmpty()) {
            inv.setItem(22, GuiUtil.build(player, "CHEST", "CHEST",
                    "<gray>这里还没有任何商品", List.of(), Map.of(), null));
        }
        int index = 0;
        for (ShopListing l : listings.subList(Math.min(page * 28, listings.size()), Math.min((page + 1) * 28, listings.size()))) {
            int slot = 10 + (index / 7) * 9 + index % 7;
            index++;
            ItemStack icon = l.preview();
            if (icon == null) {
                continue;
            }
            Guild owner = plugin.guilds().guild(l.guildId());
            List<String> lore = new ArrayList<>();
            lore.add("<gray>售价：<white>" + l.price() + " <gray>工会资金/件");
            lore.add("<gray>库存：<white>" + l.stock() + " <gray>件");
            lore.add("<gray>上架者：<white>" + (l.sellerName() == null ? "?" : l.sellerName()));
            lore.add("");
            lore.add("<yellow>左键购买");
            if (owner != null && own != null && plugin.shop().canManage(own, player)) {
                lore.add("<yellow>Shift+左键改价");
                lore.add("<red>Shift+右键下架");
            }
            inv.setItem(slot, GuiUtil.build(player, l.preview().getType().name(),
                    l.preview().getType().name(), "<white>" + plugin.shop().itemName(l.preview()) + " <dark_gray>x" + l.stock(),
                    lore, Map.of(), null));
            holder.actions.put(slot, "shop_buy:" + l.id());
        }

        // 底排
        if (own != null && plugin.shop().canManage(own, player)) {
            inv.setItem(48, GuiUtil.build(player, "EMERALD", "EMERALD", "<green>上架手持物品",
                    List.of("<gray>先把物品拿在主手", "<gray>然后点击这里输入售价"), Map.of(), null));
            holder.actions.put(48, "shop:add");
        }
        put(inv, holder, 49, player, "ARROW", "<white>返回主页", List.of(), "back:main");
        put(inv, holder, 51, player, "PAPER", "<white>第 " + (page + 1) + " / " + (maxPage + 1) + " 页", List.of(), "none");
        if (page > 0) put(inv, holder, 45, player, "ARROW", "<white>上一页", List.of(), "page_shop:" + (page - 1));
        if (page < maxPage) put(inv, holder, 53, player, "ARROW", "<white>下一页", List.of(), "page_shop:" + (page + 1));
        show(player, holder, inv);
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
                plugin.shop().updatePrices(g, l, price, 0);
                plugin.messages().send(player, "shop-price-updated",
                        "item", plugin.shop().itemName(l.preview()),
                        "price", String.valueOf(price));
                openShop(player, g);
            });
            return;
        }
        if (right) {
            plugin.messages().send(player, "shop-not-buying");
            return;
        }
        // 左键购买：输入数量
        final Guild shopGuild = g;
        Runnable refresh = () -> openShop(player, shopGuild);
        requestText(player, "shop-prompt-amount", amtStr -> {
            int amount = NumberUtil.toInt(amtStr.trim(), -1);
            if (amount <= 0) {
                plugin.messages().send(player, "shop-amount-invalid");
                refresh.run();
                return;
            }
            plugin.shop().buy(player, shopGuild, l, amount, refresh);
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
            if (holder.actions.get(event.getRawSlot()) == null) {
                return;
            }
            // 只认左/右键（含 Shift）；Q、1-9、F、中键、Ctrl+Q 等一律忽略，
            // 否则会被当成“普通左键”误触解散/升职/购买等动作。
            if (!isActionClick(event.getClick())) {
                return;
            }
            dispatch(player, holder, event.getRawSlot(), event.isRightClick(), event.isShiftClick());
        }
    }

    static String requiredAdminPermission(String action) {
        return GuiActions.requiredAdminPermission(action);
    }

    /** 只有左键/右键及其 Shift 变体才算有效操作。 */
    public static boolean isActionClick(org.bukkit.event.inventory.ClickType type) {
        return GuiActions.isActionClick(type);
    }

    /** 同一玩家两次点击的最小间隔（防连点重复购买/重复扣费）。 */
    private static final long CLICK_COOLDOWN_MS = 150L;
    private final Map<UUID, Long> clickCooldown = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 由 Sparrow 点击回调派发到与内置点击一致的动作处理（主线程）。
     */
    public void dispatch(Player player, Holder holder, int slot, boolean right, boolean shift) {
        String action = holder.actions.get(slot);
        if (action == null) {
            return;
        }
        // 管理面板与命令层权限对齐：只有 liuguild.admin.gui 时不能执行 reload/解散/改资金等。
        String required = requiredAdminPermission(action);
        if (required != null && !player.hasPermission("liuguild.admin") && !player.hasPermission(required)) {
            plugin.messages().send(player, "no-permission");
            return;
        }
        long now = System.currentTimeMillis();
        Long last = clickCooldown.put(player.getUniqueId(), now);
        if (last != null && now - last < CLICK_COOLDOWN_MS) {
            return;
        }
        if (clickCooldown.size() > 500) {
            clickCooldown.entrySet().removeIf(e -> now - e.getValue() > 60_000L);
        }
        clickRight = right;
        clickShift = shift;
        try {
            sound(player, Sound.UI_BUTTON_CLICK, 0.4f, 1.4f);
            handle(player, holder, action);
        } finally {
            clickRight = false;
            clickShift = false;
        }
    }

    /**
     * 统一打开菜单：优先 Sparrow UI（支持 ESC 返回上一页），失败/禁用时回退内置库存 GUI。
     */
    public void show(Player player, Holder holder, Inventory inv) {
        com.liu.liuguild.hook.SparrowUiHook sparrow = plugin.sparrowUi();
        if (sparrow != null && sparrow.openWindow(player, holder, inv)) {
            return;
        }
        player.openInventory(inv);
    }

    /**
     * 显式返回按钮：丢弃当前 Sparrow 会话栈，避免反复返回把窗口无限压栈。
     */
    private void resetSparrow(Player player) {
        com.liu.liuguild.hook.SparrowUiHook sparrow = plugin.sparrowUi();
        if (sparrow != null) {
            sparrow.reset(player);
        }
    }

    /**
     * 统一关闭菜单：优先关闭 Sparrow 窗口（结束会话），回退内置库存 GUI。
     */
    public void closeMenu(Player player) {
        com.liu.liuguild.hook.SparrowUiHook sparrow = plugin.sparrowUi();
        if (sparrow != null && sparrow.closeWindow(player)) {
            return;
        }
        player.closeInventory();
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
                Guild target = infoTarget(holder, g);
                if (target != null) {
                    plugin.messages().sendRawRich(player, "info-notice",
                            "notice", target.notice().isBlank() ? "暂无公告" : Text.sanitizeToMini(target.notice()));
                    plugin.messages().sendRawRich(player, "info-desc-line",
                            "description", target.description().isBlank() ? "暂无简介" : Text.sanitizeToMini(target.description()));
                }
            }
            case "info:desc" -> {
                Guild target = infoTarget(holder, g);
                if (target != null) {
                    plugin.messages().sendRawRich(player, "info-desc-line",
                            "description", target.description().isBlank() ? "暂无简介" : Text.sanitizeToMini(target.description()));
                }
            }
            case "info:members" -> {
                Guild target = infoTarget(holder, g);
                if (target != null) {
                    openMembers(player, target, 0, g == null || !g.id().equals(target.id()));
                }
            }
            case "info:history" -> {
                Guild target = infoTarget(holder, g);
                if (target != null) {
                    showHistory(player, target);
                }
            }
            case "info:back" -> {
                if (holder != null && holder.page > 0) {
                    openList(player, 0);
                } else {
                    openMain(player);
                }
            }
            case "close" -> closeMenu(player);
            case "members" -> openMembers(player, require(g, player), 0);
            case "war" -> openWar(player, require(g, player));
            case "settings" -> openSettings(player, require(g, player));
            case "list" -> openList(player, 0);
            case "checkin" -> {
                closeMenu(player);
                plugin.guilds().checkin(player);
                // 签到状态是同步更新的，立即重开让「今日：已签到」马上刷新
                if (plugin.guilds().guildOf(player.getUniqueId()) != null) {
                    openMain(player);
                }
            }
            case "funds" -> openFunds(player);
            case "funds:donate" -> donateFunds(player);
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
            case "home" -> openHomes(player, require(g, player));
            case "top:open" -> openTop(player, "points");
            case "invites:open" -> openInvites(player);
            case "warehouse:manage" -> {
                if (g != null && plugin.warehouse() != null) {
                    plugin.warehouse().openMenu(player, g);
                }
            }
            case "chat:toggle" -> {
                if (g != null && plugin.guildChat() != null) {
                    plugin.guildChat().chat(player, "");
                    openMain(player);
                }
            }
            case "leave" -> {
                if (g != null) {
                    GuildMember self = g.member(player.getUniqueId());
                    if (self != null && self.rank() == GuildRank.LEADER) {
                        plugin.messages().send(player, "leave-leader-denied");
                        return;
                    }
                    closeMenu(player);
                    confirm(player, "confirm-leave", g.name(), () -> plugin.guilds().leave(player));
                }
            }
            case "back:main" -> {
                resetSparrow(player);
                openMain(player);
            }
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
            case "back:settings" -> {
                resetSparrow(player);
                openSettings(player, require(g, player));
            }
            case "admin:status" -> {
                closeMenu(player);
                plugin.async(() -> {
                    java.util.List<String> lines = plugin.statusLines();
                    plugin.sync(() -> {
                        for (String line : lines) player.sendMessage(Text.mm(line.replace('&', '\u00a7')));
                    });
                });
            }
            case "admin:arenas" -> openArenaList(player, 0);
            case "buffs:open" -> openBuffs(player);
            case "title:open" -> openTitles(player, require(g, player));
            case "shop:open" -> openShop(player, require(g, player));
            case "title:create" -> plugin.messages().send(player, "title-fixed-only");
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
                closeMenu(player);
                var conf = plugin.settings().guild;
                plugin.guilds().findInactiveGuildsAsync(targets ->
                        plugin.messages().sendRaw(player, "admin-cleanup-scan",
                                "days", conf.cleanupInactiveDays, "count", targets.size()));
            }
            case "admin:verify" -> {
                closeMenu(player);
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
                        String ownSide = w.sideOfGuild(g.id());
                        String enemies = w.parties().stream()
                                .filter(party -> !party.guildId().equals(g.id()))
                                .map(GuildWar.Party::name)
                                .collect(java.util.stream.Collectors.joining(","));
                        plugin.messages().sendRaw(player, "war-score", "guild_a", g.name(), "guild_b", enemies,
                                "score_a", ownSide == null ? 0 : w.scoreOfSide(ownSide),
                                "score_b", w.parties().stream()
                                        .filter(party -> !party.guildId().equals(g.id()))
                                        .mapToInt(GuildWar.Party::score).max().orElse(0));
                        player.sendMessage(Text.mm("<gray>状态: " + w.state().display() + " 对手: " + enemies));
                    }
                }
                // 重开菜单，让上面的比分/状态 lore 拿到最新值
                openWar(player, require(g, player));
            }
            case "war_rejoin" -> {
                closeMenu(player);
                plugin.war().rejoin(player);
            }
            case "war_stats" -> openWarStats(player, require(g, player));
            case "war_back" -> {
                if (g != null) openWar(player, g); else openMain(player);
            }
            case "war_spectate" -> {
                closeMenu(player);
                plugin.war().spectate(player);
            }
            case "mail:open" -> openMail(player, 0);
            case "apps:open" -> openApplications(player, require(g, player), 0);
            case "log:open" -> openAudit(player, require(g, player), 0);
            case "mail_clear" -> {
                plugin.mail().markAllReadAsync(player.getUniqueId());
                plugin.messages().send(player, "mail-all-read");
                openMail(player, holder == null ? 0 : holder.page);
            }
            case "back:war" -> {
                if (g != null) openWar(player, g);
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
                closeMenu(player);
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
            case "set:pvp" -> {
                if (g != null && plugin.guilds().toggleMemberPvp(player, g)) {
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

    private Guild infoTarget(Holder holder, Guild fallback) {
        if (holder != null && holder.guildId != null && !holder.guildId.isBlank()) {
            Guild target = plugin.guilds().guild(holder.guildId);
            if (target != null) {
                return target;
            }
        }
        return fallback;
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
                } else if (target.open()) {
                    // 左键：开放工会直接加入
                    plugin.guilds().join(player, target, false);
                    closeMenu(player);
                } else {
                    // 邀请制工会：左键提交申请留言，由官员审批
                    final Guild applyGuild = target;
                    closeMenu(player);
                    requestText(player, "app-input", text -> {
                        if (text == null || text.equalsIgnoreCase("cancel")) {
                            plugin.messages().send(player, "input-cancelled");
                            return;
                        }
                        plugin.applications().apply(player, applyGuild, text);
                    });
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
        } else if (action.startsWith("buff_buy:")) {
            openBuffConfirmation(player, action.substring("buff_buy:".length()));
        } else if (action.startsWith("buff_confirm:")) {
            if (g != null && plugin.buffs() != null) plugin.buffs().purchase(player, g, action.substring("buff_confirm:".length()));
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
                    requestText(player, "shop-prompt-price", priceStr -> {
                        long price = NumberUtil.toLong(priceStr.trim(), -1);
                        if (price < 0) {
                            plugin.messages().send(player, "shop-invalid-number");
                            openShop(player, shopGuild);
                            return;
                        }
                        String err = plugin.shop().addListing(shopGuild, player, sample, amount, price, 0);
                        if (err != null) {
                            plugin.messages().send(player, err,
                                    "max", String.valueOf(plugin.settings().guild.shopMaxListings));
                        } else {
                            plugin.messages().send(player, "shop-listed",
                                    "item", plugin.shop().itemName(sample),
                                    "amount", String.valueOf(amount),
                                    "price", String.valueOf(price));
                        }
                        openShop(player, shopGuild);
                    });
                }
                case "open" -> openShop(player, g);
                default -> {
                }
            }
        } else if (action.startsWith("home_tp:")) {
            int slot = NumberUtil.toInt(action.substring("home_tp:".length()), -1);
            var home = plugin.guildHome();
            if (g != null && home != null && home.available() && slot >= 1) {
                if (isShiftClick() && isRightClick()) {
                    if (home.manage(player, g)) {
                        home.deleteSlot(player, slot);
                        reopenHomesLater(player, g);
                    }
                } else if (isRightClick()) {
                    if (home.manage(player, g)) {
                        home.setSlot(player, slot);
                        reopenHomesLater(player, g);
                    }
                } else {
                    home.openSlot(player, slot);
                    closeMenu(player);
                }
            }
        } else if (action.startsWith("home_set:")) {
            int slot = NumberUtil.toInt(action.substring("home_set:".length()), -1);
            var home = plugin.guildHome();
            if (g != null && home != null && home.available() && slot >= 1 && home.manage(player, g)) {
                home.setSlot(player, slot);
                reopenHomesLater(player, g);
            }
        } else if (action.startsWith("top_cat:")) {
            openTop(player, action.substring("top_cat:".length()));
        } else if (action.startsWith("invite_pick:")) {
            Guild target = plugin.guilds().guild(action.substring("invite_pick:".length()));
            if (target == null) {
                plugin.messages().send(player, "invite-none");
                return;
            }
            if (isRightClick()) {
                plugin.guilds().deny(player, target.name());
            } else {
                plugin.guilds().accept(player, target.name());
            }
            closeMenu(player);
        } else if (action.equals("invite_player")) {
            if (g == null) {
                return;
            }
            closeMenu(player);
            requestText(player, "invite-prompt", text -> {
                String name = text == null ? "" : text.trim();
                if (!name.isEmpty()) {
                    plugin.guilds().invite(player, g, name);
                }
                openMembers(player, g, 0);
            });
        } else if (action.startsWith("war_queue:")) {
            if (g != null) {
                plugin.war().queue(player, g, action.substring("war_queue:".length()));
                openWar(player, g);
            }
        } else if (action.startsWith("mail_read:")) {
            String id = action.substring("mail_read:".length());
            plugin.mail().inboxAsync(player.getUniqueId(), 50, list -> {
                for (var entry : list) {
                    if (!entry.id().equals(id)) continue;
                    plugin.mail().markReadAsync(player.getUniqueId(), entry.id());
                    plugin.messages().sendRawRich(player, "mail-body-header",
                            "title", entry.title(),
                            "time", com.liu.liuguild.util.TimeUtil.formatDateTime(entry.createdAt()));
                    player.sendMessage(Text.mm(entry.body()));
                    plugin.messages().sendRaw(player, "mail-marked");
                    openMail(player, holder == null ? 0 : holder.page);
                    return;
                }
                plugin.messages().send(player, "mail-not-found");
            });
        } else if (action.startsWith("app_decide:")) {
            String id = action.substring("app_decide:".length());
            if (g != null) {
                plugin.applications().decide(player, g, id, !clickRight);
                plugin.syncDelayed(() -> {
                    if (player.isOnline()) openApplications(player, g, holder == null ? 0 : holder.page);
                }, 5L);
            }
        } else if (action.startsWith("page_mail:")) {
            openMail(player, Integer.parseInt(action.substring("page_mail:".length())));
        } else if (action.startsWith("page_applications:")) {
            if (g != null) openApplications(player, g,
                    Integer.parseInt(action.substring("page_applications:".length())));
        } else if (action.startsWith("page_audit:")) {
            if (g != null) openAudit(player, g, Integer.parseInt(action.substring("page_audit:".length())));
        } else if (action.startsWith("page_shop:")) {
            if (g != null) openShopPage(player, g, plugin.shop().listingsOf(g.id()),
                    Integer.parseInt(action.substring("page_shop:".length())));
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
            org.bukkit.World world = a.world != null && !a.world.isBlank() ? WorldResolver.resolve(a.world)
                    : (Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0));
            if (world == null) {
                plugin.messages().send(player, "home-failed");
            } else {
                closeMenu(player);
                player.teleport(a.spawnA.toLocation(world));
            }
        } else if (action.startsWith("arena_setspawn:")) {
            String[] parts = action.substring("arena_setspawn:".length()).split(":", 2);
            setArenaSpawn(player, parts[0], parts.length > 1 ? parts[1] : "a");
        } else if (action.startsWith("arena_setpoint:")) {
            String[] parts = action.substring("arena_setpoint:".length()).split(":", 2);
            promptArenaPoint(player, parts[0], parts.length > 1 ? parts[1] : "a");

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
            closeMenu(player);
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
            closeMenu(player);
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
        put(inv, holder, 49, player, "ARROW", "<white>返回管理面板", List.of(), "admin:wars_back");
        show(player, holder, inv);
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
        show(player, holder, inv);
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
        inv.setItem(47, GuiUtil.build(player, "ANVIL", "ANVIL", "<green>新建套装（使用当前装备）",
                List.of("<gray>点击后输入名字"), Map.of(), null));
        holder.actions.put(47, "kit_new");
        put(inv, holder, 49, player, "ARROW", "<white>返回管理面板", List.of(), "admin:wars_back");
        show(player, holder, inv);
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
        show(player, holder, inv);
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
        show(player, holder, inv);
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
        put(inv, holder, 22, player, "ARROW", "<white>返回比赛列表", List.of(), "admin:wars");
        show(player, holder, inv);
    }

    private void promptWarScore(Player player, String warId, String side) {
        closeMenu(player);
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
        plugin.configs().set("arena.yml", "arenas." + arenaId + ".world", loc.getWorld().getName());
        plugin.reloadSettings();
        plugin.messages().sendRaw(player, "arena-spawn-set", "arena", arenaId,
                "point", name.toUpperCase(java.util.Locale.ROOT), "world", loc.getWorld().getName());
        openArenaEditor(player, arenaId);
    }

    /** 铁砧输入一个出生点名，然后在当前位置保存。 */
    private void promptArenaPoint(Player player, String arenaId, String defaultPoint) {
        closeMenu(player);
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
        closeMenu(player);
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
        closeMenu(player);
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
        closeMenu(player);
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
        closeMenu(player);
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
        Component rendered = Text.fmt(title, flatten(ph));
        Inventory inv = Bukkit.createInventory(holder, rows * 9, rendered);
        holder.inv = inv;
        holder.title = rendered;
        ItemStack filler = GuiUtil.build(viewer, "GRAY_STAINED_GLASS_PANE", "GRAY_STAINED_GLASS_PANE",
                " ", List.of(), Map.of(), null);
        for (int i = 0; i < inv.getSize(); i++) {
            inv.setItem(i, filler);
        }
        put(inv, holder, inv.getSize() - 5, viewer, "ARROW", "<white>返回主页", List.of(), "back:main");
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
                            "<gray>点位自动分组，队内循环分配",
                            "",
                            "<yellow>点击编辑"), Map.of(), null));
            holder.actions.put(i, "arena_edit:" + id);
        }
        addPageControls(player, holder, inv, "arenas", page, (ids.size() - 1) / perPage, "工会列表", "admin:guilds", "admin:open");
        show(player, holder, inv);
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
        slot = put(inv, holder, slot, player, "RED_BED", "<white>设置点 a",
                List.of("<gray>使用当前位置", fmtSpawn(a.spawn("a"))), "arena_setspawn:" + a.id + ":a");
        slot = put(inv, holder, slot, player, "BLUE_BED", "<white>设置点 b",
                List.of("<gray>使用当前位置", fmtSpawn(a.spawn("b"))), "arena_setspawn:" + a.id + ":b");
        slot = put(inv, holder, slot, player, "ENDER_EYE", "<white>设置旁观点",
                List.of("<gray>使用当前位置", fmtSpawn(a.spawnSpectator)), "arena_setspawn:" + a.id + ":spec");
        slot = put(inv, holder, slot, player, "LIME_BED", "<white>设置命名出生点…",
                List.of("<gray>点击输入点名（a/b/c/d/任意）", "<gray>使用当前位置保存",
                        "<gray>已配置：<white>" + String.join(", ", a.spawnPointNames())),
                "arena_setpoint:" + a.id + ":c");

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
        int backSlot = 49;
        inv.setItem(backSlot, GuiUtil.build(player, "ARROW", "ARROW", "<white>返回列表", List.of(), Map.of(), null));
        holder.actions.put(backSlot, "admin:arenas");
        show(player, holder, inv);
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
        show(player, holder, inv);
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
        int backSlot = 49;
        inv.setItem(backSlot, GuiUtil.build(player, "ARROW", "ARROW", "<white>返回列表", List.of(), Map.of(), null));
        holder.actions.put(backSlot, "admin:guilds");
        show(player, holder, inv);
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
        inv.setItem(49, GuiUtil.build(player, "ARROW", "ARROW", "<white>返回管理面板",
                List.of("<gray>回到管理菜单"), Map.of(), null));
        holder.actions.put(49, backAction == null ? "admin:open" : backAction);
    }

    private static String fmtSpawn(ArenaDef.Spawn s) {
        if (s == null) return "未设置";
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

    /** 工会增益：普通成员可查看，购买权限在数据库事务中再次校验。 */
    public void openBuffs(Player player) {
        Guild g = plugin.guilds().guildOf(player.getUniqueId());
        if (g == null) { plugin.messages().send(player, "not-in-guild"); return; }
        var config = plugin.settings().buffs;
        if (!config.enabled || plugin.buffs() == null) { plugin.messages().send(player, "buff-unavailable"); return; }
        Holder holder = new Holder("buffs", g.id(), 0);
        Inventory inv = simpleInventory(player, holder, 4, "<gold>工会限时增益", Map.of());
        put(inv, holder, 4, player, "BEACON", "<gold>全体成员共享",
                List.of("<gray>公共工会资金：" + g.points(), "<gray>使用槽位：" + plugin.buffs().activeCount(g.id()) + "/" + config.slots(g.level()),
                        "<gray>离线不暂停；工会战期间停用", "<gray>同类续时，最多累计 " + config.maxSeconds / 3600.0 + " 小时"), "noop");
        int[] slots = {10,12,14,16}; int i = 0;
        GuildMember member = g.member(player.getUniqueId());
        boolean canBuy = member != null && member.rank().atLeast(config.purchaseRank);
        for (var buff : config.buffs.values()) {
            long seconds = Math.max(0, (plugin.buffs().expires(g.id(), buff.id()) - System.currentTimeMillis()) / 1000);
            String effect = buff.effect().equals("EXPERIENCE") ? "原版经验 +" + Math.round(buff.experienceBonus() * 100) + "%" : "等级 " + (buff.amplifier() + 1);
            put(inv, holder, slots[i++], player, "POTION", "<aqua>" + buff.name(),
                    List.of("<gray>效果：" + effect, "<gray>剩余：" + seconds / 60 + " 分 " + seconds % 60 + " 秒",
                            "<gray>单次时长：" + buff.seconds() / 60.0 + " 分钟", "<gray>费用：" + buff.cost() + " 公共工会资金",
                            canBuy ? "<yellow>点击购买 / 续时（需确认）" : "<gray>需要职位：" + config.purchaseRank.display()),
                    canBuy ? "buff_buy:" + buff.id() : "noop");
        }
        show(player, holder, inv);
    }

    private void openBuffConfirmation(Player player, String id) {
        Guild g = plugin.guilds().guildOf(player.getUniqueId());
        var buff = plugin.settings().buffs.buffs.get(id);
        if (g == null || buff == null) return;
        Holder holder = new Holder("buff_confirm", g.id(), 0);
        Inventory inv = simpleInventory(player, holder, 3, "<gold>确认购买工会增益", Map.of());
        put(inv, holder, 11, player, "LIME_CONCRETE", "<green>确认购买：" + buff.name(),
                List.of("<gray>扣除 " + buff.cost() + " 公共工会资金", "<gray>增加 " + buff.seconds() / 60.0 + " 分钟", "<red>购买后不可退款"), "buff_confirm:" + id);
        put(inv, holder, 15, player, "RED_CONCRETE", "<white>取消", List.of(), "buffs:open");
        put(inv, holder, 22, player, "ARROW", "<white>返回增益列表", List.of(), "buffs:open");
        show(player, holder, inv);
    }

    public void openFunds(Player player) {
        Guild g = plugin.guilds().guildOf(player.getUniqueId());
        if (g == null) { plugin.messages().send(player, "not-in-guild"); return; }
        plugin.async(() -> {
            try {
                long balance = new com.liu.liuguild.db.PersonalFundsRepository(plugin.db()).balance(player.getUniqueId());
                plugin.sync(() -> {
                    if (!player.isOnline()) return;
                    Holder holder = new Holder("funds", g.id(), 0);
                    Inventory inv = simpleInventory(player, holder, 3, "<gold>资金与金币", Map.of());
                    put(inv, holder, 11, player, "EXPERIENCE_BOTTLE", "<green>个人工会资金：" + balance,
                            List.of("<gray>通过每日签到获得，离会保留", "<yellow>点击捐献给当前工会", "<gray>不可提现、不可兑换金币"), "funds:donate");
                    put(inv, holder, 13, player, "BEACON", "<gold>公共工会资金：" + g.points(),
                            List.of("<gray>捐献、工会战与活跃奖励", "<gray>用于工会建设、仓库续期与升级", "<gray>捐献不可撤回，不兑换金币"), "none");
                    put(inv, holder, 15, player, "GOLD_INGOT", "<yellow>工会金币：" + plugin.economy().format(g.bank()),
                            List.of("<gray>Vault 金币，与建设资金完全独立", "<gray>来自金币存入和商店销售", "<gray>银行入口：左键存入 / 右键取出"), "bank");
                    put(inv, holder, 22, player, "ARROW", "<white>返回工会主页", List.of(), "back:main");
                    show(player, holder, inv);
                });
            } catch (java.sql.SQLException e) {
                plugin.getLogger().warning("查询个人资金失败: " + e.getMessage());
                plugin.sync(() -> plugin.messages().send(player, "funds-failed"));
            }
        });
    }

    private void donateFunds(Player player) {
        Guild g = plugin.guilds().guildOf(player.getUniqueId());
        if (g == null) return;
        requestText(player, "funds-prompt", text -> {
            long amount = NumberUtil.toLong(text.trim(), -1);
            if (amount <= 0) { plugin.messages().send(player, "input-invalid-number"); openFunds(player); return; }
            plugin.async(() -> {
                try {
                    boolean ok = new com.liu.liuguild.db.PersonalFundsRepository(plugin.db())
                            .donate(player.getUniqueId(), g.id(), amount);
                    if (ok) {
                        plugin.guilds().reloadGuild(g.id());
                        plugin.broadcastGuildChange(g.id());
                    }
                    plugin.sync(() -> {
                        if (ok) {
                            Bukkit.getPluginManager().callEvent(new GuildFundsEvent(
                                    g, player, GuildFundsEvent.Action.DONATE, amount));
                        }
                        plugin.messages().send(player, ok ? "funds-donated" : "funds-insufficient", "amount", amount);
                        openFunds(player);
                    });
                } catch (java.sql.SQLException e) {
                    plugin.getLogger().warning("捐献资金失败: " + e.getMessage());
                    plugin.sync(() -> plugin.messages().send(player, "funds-failed"));
                }
            });
        });
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
        closeMenu(player);
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
        plugin.sync(() -> {
            // 只有当前正停在本插件的“主菜单/资金”页时才刷新；不要顶掉其它界面或玩家正在操作的容器。
            var top = player.getOpenInventory().getTopInventory();
            if (!(top.getHolder() instanceof Holder holder)) {
                return;
            }
            if ("main".equals(holder.menu)) {
                openMain(player);
            } else if ("funds".equals(holder.menu)) {
                openFunds(player);
            }
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
                String side = w.sideOfGuild(g.id());
                if (side == null) {
                    continue;
                }
                String enemy = w.enemyOf(g.id());
                int self = w.scoreOfSide(side);
                int opp = w.parties().stream().filter(party -> !party.guildId().equals(g.id()))
                        .mapToInt(GuildWar.Party::score).max().orElse(0);
                boolean draw = w.winner() == null || w.winner().isBlank();
                String result = draw ? "平局" : (w.winner().equals(g.id()) ? "胜利" : "失败");
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
        Component title = Text.fmt(def.title, flatten(ph));
        Inventory inv = Bukkit.createInventory(holder, def.rows * 9, title);
        holder.inv = inv;
        holder.title = title;
        if (plugin.settings().gui.filler != null) {
            ItemStack filler = GuiUtil.fromButton(viewer, plugin.settings().gui.filler, ph);
            for (int i = 0; i < inv.getSize(); i++) {
                inv.setItem(i, filler);
            }
        }
        String target = switch (holder.menu) {
            case "main", "noguild" -> "close";
            case "icons" -> "back:settings";
            default -> "back:main";
        };
        // 静态返回/关闭按钮已配置时，不额外生成第二个导航入口。
        boolean hasNavigation = def.buttons.keySet().stream()
                .anyMatch(key -> key.equals("back") || key.equals("info_back") || key.equals("close"));
        if (!hasNavigation && def.back == null) {
            put(inv, holder, inv.getSize() - 5, viewer, "ARROW",
                    "close".equals(target) ? "<white>关闭菜单" : "<white>返回工会主页", List.of(), target);
        }
        return inv;
    }

    private void addBack(Inventory inv, Holder holder, MenuDef def, Map<String, String> ph,
                         Player viewer, String target, int page, String menuKey, int total, int perPage) {
        int backSlot = def.back == null ? inv.getSize() - 5 : Math.min(inv.getSize() - 1, Math.max(0, def.back.slot));
        inv.setItem(backSlot, def.back == null
                ? GuiUtil.build(viewer, "ARROW", "ARROW", "<white>返回", List.of(), Map.of(), null)
                : GuiUtil.fromButton(viewer, def.back, ph));
        holder.actions.put(backSlot, target);
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
        return guiPlaceholders.placeholders(g);
    }

    private Map<String, String> detailPlaceholders(Guild g) {
        return guiPlaceholders.detailPlaceholders(g);
    }

    /** 供外部界面引擎复用的占位符。 */
    public Map<String, String> placeholdersFor(Guild g) {
        return guiPlaceholders.placeholders(g);
    }

    /** 主菜单占位符（含与具体玩家相关的签到状态）；内置 GUI 与 Sparrow UI 共用。 */
    public Map<String, String> mainPlaceholders(Player player, Guild g) {
        return guiPlaceholders.mainPlaceholders(player, g);
    }

    private static Object[] flatten(Map<String, String> ph) {
        return GuiPlaceholders.flatten(ph);
    }

    private static int clamp(int slot) {
        return Math.max(0, Math.min(53, slot));
    }

    public static String actionId(String buttonId) {
        return GuiActions.actionId(buttonId);
    }
}
