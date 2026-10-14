package com.liu.liuguild.gui;

import com.liu.liuguild.LiuGuild;
import com.liu.liuguild.config.ButtonDef;
import com.liu.liuguild.config.IconDef;
import com.liu.liuguild.model.Guild;
import com.liu.liuguild.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** GUI 物品构建工具（占位符替换 + PAPI + CraftEngine + 玩家头像）。 */
public final class GuiUtil {

    private GuiUtil() {
    }

    public static ItemStack build(Player viewer, String material, String fallback,
                                  String name, List<String> lore, Map<String, String> ph, UUID headOwner) {
        Material fb = matchMaterial(fallback);
        ItemStack item;
        if (headOwner != null) {
            item = new ItemStack(Material.PLAYER_HEAD);
            if (item.getItemMeta() instanceof SkullMeta skull) {
                try {
                    skull.setOwningPlayer(Bukkit.getOfflinePlayer(headOwner));
                } catch (Throwable ignored) {
                }
                item.setItemMeta(skull);
            }
        } else {
            item = LiuGuild.get().craftEngine().resolve(material, fb);
            if (item == null) {
                item = new ItemStack(fb);
            }
        }
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(render(viewer, name, ph));
            List<Component> loreComponents = new ArrayList<>();
            if (lore != null) {
                for (String line : lore) {
                    loreComponents.add(render(viewer, line, ph));
                }
            }
            meta.lore(loreComponents);
            item.setItemMeta(meta);
        }
        return item;
    }

    public static ItemStack fromButton(Player viewer, ButtonDef def, Map<String, String> ph) {
        if (def == null) {
            return new ItemStack(Material.STONE);
        }
        return build(viewer, def.material, def.fallbackMaterial, def.name, def.lore, ph, null);
    }

    /** 工会图标：优先使用导入的自定义图标数据，其次 icons.yml 定义。 */
    public static ItemStack guildIcon(LiuGuild plugin, Player viewer, Guild guild, Material fallback) {
        if (guild != null && !guild.iconData().isBlank()) {
            ItemStack custom = deserializeItem(guild.iconData());
            if (custom != null) {
                return custom;
            }
        }
        IconDef def = guild == null ? null : plugin.settings().icons.icon(guild.icon());
        if (def != null) {
            ItemStack item = plugin.craftEngine().resolve(def.item, def.fallback());
            return item == null ? new ItemStack(fallback) : item;
        }
        return new ItemStack(fallback);
    }

    public static ItemStack deserializeItem(String yaml) {
        if (yaml == null || yaml.isBlank()) {
            return null;
        }
        try {
            YamlConfiguration c = new YamlConfiguration();
            c.loadFromString(yaml);
            return c.getItemStack("item");
        } catch (Throwable t) {
            return null;
        }
    }

    public static Component render(Player viewer, String text, Map<String, String> ph) {
        String out = text == null ? "" : text;
        LiuGuild plugin = LiuGuild.get();
        if (plugin != null && plugin.papi() != null && viewer != null) {
            out = plugin.papi().apply(viewer, out);
        }
        // 物品名称/描述默认会被客户端渲染为斜体，这里显式关闭默认斜体
        // （配置里写 <italic> 仍可局部开启）。
        return Text.fmtSelective(out, RICH_KEYS, flatten(ph))
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false);
    }

    /** 允许携带颜色格式的占位符；其余（玩家名/工会名等）按纯文本插入，避免注入。 */
    private static final java.util.Set<String> RICH_KEYS = java.util.Set.of("notice", "description", "icon_name");

    private static Object[] flatten(Map<String, String> ph) {
        Object[] arr = new Object[ph.size() * 2];
        int i = 0;
        for (Map.Entry<String, String> e : ph.entrySet()) {
            arr[i++] = e.getKey();
            arr[i++] = e.getValue();
        }
        return arr;
    }

    private static Material matchMaterial(String name) {
        if (name == null) {
            return Material.STONE;
        }
        Material m = Material.matchMaterial(name.toUpperCase(java.util.Locale.ROOT));
        return m == null ? Material.STONE : m;
    }
}
