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
import net.momirealms.sparrow.ui.pane.Element;
import net.momirealms.sparrow.ui.pane.NormalPane;
import net.momirealms.sparrow.ui.pane.Pane;
import net.momirealms.sparrow.ui.pane.PaneSize;
import net.momirealms.sparrow.ui.window.AnvilWindow;
import net.momirealms.sparrow.ui.window.NormalWindow;
import net.momirealms.sparrow.ui.window.Window;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Sparrow UI 界面引擎：把工会菜单渲染为响应式库存界面。
 *
 * <p>Sparrow UI 已随插件打包并 relocate 到 {@code com.liu.liuguild.libs.sparrow.ui}，
 * 因此不会与其他使用 Sparrow UI 的插件冲突。若初始化或渲染失败，会自动回退到内置 GUI。</p>
 */
public final class SparrowUiHook {

    private final LiuGuild plugin;
    private boolean available;
    private boolean failed;

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
            window.open();
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("Sparrow 铁砧输入不可用，回退聊天输入: " + t.getMessage());
            return false;
        }
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
