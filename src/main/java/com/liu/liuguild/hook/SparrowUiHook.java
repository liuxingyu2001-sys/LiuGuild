package com.liu.liuguild.hook;

import com.liu.liuguild.LiuGuild;
import com.liu.liuguild.config.ButtonDef;
import com.liu.liuguild.config.MenuDef;
import com.liu.liuguild.gui.GuiManager;
import com.liu.liuguild.gui.GuiUtil;
import com.liu.liuguild.model.Guild;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.item.Item;
import net.momirealms.sparrow.ui.item.ItemBuilder;
import net.momirealms.sparrow.ui.item.click.ItemClick;
import net.momirealms.sparrow.ui.pane.Element;
import net.momirealms.sparrow.ui.pane.NormalPane;
import net.momirealms.sparrow.ui.pane.Pane;
import net.momirealms.sparrow.ui.pane.PaneSize;
import net.momirealms.sparrow.ui.window.AnvilWindow;
import net.momirealms.sparrow.ui.window.NormalWindow;
import net.momirealms.sparrow.ui.window.Window;
import net.momirealms.sparrow.ui.window.WindowSession;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Sparrow UI 界面引擎：把工会菜单渲染为响应式库存界面。
 *
 * <p>Sparrow UI 已随插件打包并 relocate 到 {@code com.liu.liuguild.libs.sparrow.ui}，
 * 因此不会与其他使用 Sparrow UI 的插件冲突。若初始化或渲染失败，会自动回退到内置 GUI。</p>
 */
public final class SparrowUiHook implements Listener {

    private final LiuGuild plugin;
    private boolean available;
    private boolean failed;

    /** 每名玩家当前所在会话窗口，用于把子菜单压栈（ESC 回到上一页）。 */
    private final Map<UUID, Window> sessionWindows = new ConcurrentHashMap<>();

    public SparrowUiHook(LiuGuild plugin) {
        this.plugin = plugin;
    }

    /** 初始化 Sparrow UI；任何异常都只记录并让内置 GUI 接管。 */
    public void init() {
        try {
            SparrowUI.getInstance().setUp(plugin);
            SparrowUI.getInstance().setExceptionHandler((context, error) ->
                    plugin.getLogger().warning("Sparrow UI 异常 [" + context + "]: " + error));
            available = true;
            Bukkit.getPluginManager().registerEvents(this, plugin);
            plugin.getLogger().info("Sparrow UI 界面引擎已启用。");
        } catch (Throwable t) {
            available = false;
            plugin.getLogger().warning("Sparrow UI 初始化失败，改用内置 GUI：" + t);
        }
    }

    public boolean available() {
        return available && !failed;
    }

    /** 当前配置是否允许使用 Sparrow 引擎。 */
    public boolean enabled() {
        String engine = plugin.settings().gui.engine;
        return engine == null || !engine.equalsIgnoreCase("legacy");
    }

    /**
     * 使用 Sparrow UI 打开工会主菜单。
     *
     * @return true 表示已成功打开；false 表示调用方应回退到内置 GUI
     */
    public boolean openMain(Player player, Guild guild) {
        if (!available() || !enabled() || guild == null) {
            return false;
        }
        try {
            MenuDef def = plugin.settings().gui.menu("main");
            if (def == null) {
                return false;
            }
            Map<String, String> ph = plugin.gui().mainPlaceholders(player, guild);
            NormalPane pane = NormalPane.empty(new PaneSize(9, def.rows));

            ButtonDef filler = plugin.settings().gui.filler;
            if (filler != null) {
                ItemStack fillerStack = GuiUtil.fromButton(player, filler, ph);
                pane.setBackground(Item.builder().setItemProviderConstant(fillerStack).build().getItemProvider());
            }

            for (Map.Entry<String, ButtonDef> entry : def.buttons.entrySet()) {
                if (!plugin.gui().buttonVisible(entry.getKey())) {
                    continue;
                }
                ButtonDef button = entry.getValue();
                int slot = Math.max(0, Math.min(def.rows * 9 - 1, button.slot));
                ItemStack stack = "info".equals(entry.getKey())
                        ? GuiUtil.guildIcon(plugin, player, guild, button.fallback())
                        : GuiUtil.fromButton(player, button, ph);
                if ("info".equals(entry.getKey())) {
                    applyInfoMeta(player, guild, button, ph, stack);
                }
                String action = GuiManager.actionId(entry.getKey());
                Item item = Item.builder()
                        .setItemProviderConstant(stack)
                        .addClickHandler(click -> plugin.sync(() -> plugin.gui().runAction(player, guild.id(), 0,
                                action, click.clickType().isRightClick(), click.clickType().isShiftClick())))
                        .build();
                pane.setElement(slot, Element.item(item));
            }

            NormalWindow window = NormalWindow.builder()
                    .setTitle(GuiUtil.render(player, def.title, ph))
                    .setUpperPane(pane)
                    .build(player);
            if (window == null) {
                return false;
            }
            window.open();
            return true;
        } catch (Throwable t) {
            // 只回退这一次调用，不永久禁用（下次仍可尝试）
            plugin.getLogger().warning("Sparrow UI 渲染主菜单失败，本次回退内置 GUI：" + t);
            return false;
        }
    }

    /**
     * 使用 Sparrow UI 的**铁砧界面**收集一行文本（下栏放确认 / 取消按钮）。
     * <p>输入框内容实时回调；点「确认」后回调并关闭。返回 false 时调用方应回退到聊天栏输入。</p>
     *
     * @param title     铁砧标题
     * @param hint      提示文本（显示在确认按钮描述里，可为 null）
     * @param onConfirm 确认回调（已 trim）
     */
    public boolean openTextInput(Player player, Component title, Component hint, Consumer<String> onConfirm) {
        if (!available() || !enabled() || !plugin.settings().gui.anvilInput) {
            return false;
        }
        try {
            AtomicReference<String> text = new AtomicReference<>("");
            NormalPane upper = Pane.builder("###").setFrozen(true).build();

            List<String> lore = new ArrayList<>();
            if (hint != null) {
                lore.add(MiniMessage.miniMessage().serialize(hint));
            }
            lore.add("<gray>点击确认提交");
            lore.add("");
            lore.add("<dark_gray>输入后点这里，或直接关闭取消");

            Item confirm = Item.builder()
                    .setItemProviderConstant(GuiUtil.build(player, "LIME_CONCRETE", "LIME_CONCRETE",
                            "<green>确认", lore, Map.of(), null))
                    .addClickHandler(click -> plugin.sync(() -> {
                        click.window().close();
                        onConfirm.accept(text.get() == null ? "" : text.get().trim());
                    }))
                    .build();
            Item cancel = Item.builder()
                    .setItemProviderConstant(GuiUtil.build(player, "RED_CONCRETE", "RED_CONCRETE",
                            "<red>取消", List.of("<gray>点击取消"), Map.of(), null))
                    .addClickHandler(click -> plugin.sync(click.window()::close))
                    .build();

            NormalPane lower = Pane.builder(
                    "#########",
                    "#########",
                    "###C#X###",
                    "#########")
                    .addIngredient('C', confirm)
                    .addIngredient('X', cancel)
                    .build();

            AnvilWindow window = AnvilWindow.builder()
                    .setTitle(title == null ? Component.text("输入") : title)
                    .setUpperPane(upper)
                    .setLowerPane(lower)
                    .setTextFieldAlwaysEnabled(true)
                    .addRenameHandler(text::set)
                    .build(player);
            // 铁砧输入是独立弹窗：先结束当前菜单会话，避免两个窗口/会话互相顶替。
            reset(player);
            window.open();
            sessionWindows.put(player.getUniqueId(), window);
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("Sparrow 铁砧输入不可用，回退聊天输入: " + t.getMessage());
            return false;
        }
    }

    /**
     * 用 Sparrow UI 渲染一个已由内置 GUI 构建好的库存，并接入会话：
     * 子菜单通过 {@code navigate} 压栈，玩家按 ESC（主动关窗）时退回上一页；
     * 根菜单按 ESC 才真正关闭。
     *
     * @param actionAt 某个槽位绑定的动作；返回非 null 的槽位可点击
     * @param onClick  点击回调（槽位, 点击上下文），由调用方切回主线程处理
     * @return true 表示已用 Sparrow 打开；false 表示调用方应回退到内置库存 GUI
     */
    public boolean openWindow(Player player, Component title, Inventory inv,
                              java.util.function.IntFunction<String> actionAt,
                              java.util.function.BiConsumer<Integer, ItemClick> onClick) {
        if (!available() || !enabled() || player == null || inv == null) {
            return false;
        }
        int rows = Math.max(1, Math.min(6, inv.getSize() / 9));
        try {
            NormalPane pane = NormalPane.empty(new PaneSize(9, rows));
            int slots = Math.min(inv.getSize(), rows * 9);
            for (int slot = 0; slot < slots; slot++) {
                ItemStack stack = inv.getItem(slot);
                if (stack == null || stack.getType().isAir()) {
                    continue;
                }
                final int clickedSlot = slot;
                ItemBuilder builder = Item.builder().setItemProviderConstant(stack);
                if (actionAt != null && actionAt.apply(slot) != null && onClick != null) {
                    builder.addClickHandler(click -> onClick.accept(clickedSlot, click));
                }
                pane.setElement(slot, Element.item(builder.build()));
            }
            Component windowTitle = title == null ? Component.text("菜单") : title;
            NormalWindow window = NormalWindow.builder()
                    .setTitle(windowTitle)
                    .setUpperPane(pane)
                    .setSessionKind(WindowSession.Kind.RETAINED_STACK)
                    .setBackOnPlayerClose(true)
                    .build(player);
            if (window == null) {
                return false;
            }
            UUID id = player.getUniqueId();
            Window current = sessionWindows.get(id);
            WindowSession live = current == null ? null : current.session();
            if (live != null && live.active() && current != null && current.isOpen()) {
                current.navigate(window).thenAccept(next -> sessionWindows.put(id, next));
            } else {
                window.open();
                sessionWindows.put(id, window);
            }
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("Sparrow UI 渲染菜单失败，本次回退内置 GUI：" + t);
            sessionWindows.remove(player.getUniqueId());
            return false;
        }
    }

    /** 兼容旧调用：从 {@link GuiManager.Holder} 取标题与动作。 */
    public boolean openWindow(Player player, GuiManager.Holder holder, Inventory inv) {
        if (holder == null) {
            return false;
        }
        return openWindow(player, holder.title(), inv, holder::actionAt,
                (slot, click) -> {
                    if (!GuiManager.isActionClick(click.clickType())) {
                        return; // Q/数字键/中键等不当作普通左键
                    }
                    plugin.sync(() -> plugin.gui().dispatch(player, holder, slot,
                            click.clickType().isRightClick(), click.clickType().isShiftClick()));
                });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        // Window 会持有玩家引用；不主动移除会让 sessionWindows 随玩家数无限增长。
        reset(event.getPlayer());
    }

    /** 插件关闭/重载时清理全部会话，避免 Window 持有 Player/Inventory 泄漏。 */
    public void clearSessions() {
        for (UUID id : new java.util.ArrayList<>(sessionWindows.keySet())) {
            Window w = sessionWindows.remove(id);
            if (w != null) {
                try {
                    w.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 结束该玩家当前 Sparrow 会话（不打开新窗口）。 */
    public void reset(Player player) {
        if (player == null) {
            return;
        }
        Window w = sessionWindows.remove(player.getUniqueId());
        if (w != null) {
            try {
                w.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 关闭该玩家当前由 Sparrow 打开的菜单；未使用 Sparrow 菜单时返回 false。 */
    public boolean closeWindow(Player player) {
        if (player == null) {
            return false;
        }
        Window w = sessionWindows.remove(player.getUniqueId());
        if (w == null) {
            return false;
        }
        try {
            w.close();
        } catch (Throwable ignored) {
        }
        return true;
    }

    private void applyInfoMeta(Player player, Guild guild, ButtonDef button, Map<String, String> ph, ItemStack stack) {
        if (stack == null || stack.getItemMeta() == null) {
            return;
        }
        ItemMeta meta = stack.getItemMeta();
        Component name = GuiUtil.render(player, button.name, ph);
        meta.displayName(name);
        List<Component> lore = new ArrayList<>();
        for (String line : button.lore) {
            lore.add(GuiUtil.render(player, line, ph));
        }
        meta.lore(lore);
        stack.setItemMeta(meta);
    }
}
