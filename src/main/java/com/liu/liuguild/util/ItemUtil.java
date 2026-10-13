package com.liu.liuguild.util;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Locale;

/** 物品工具：材质解析与 ItemStack YAML 序列化/反序列化。 */
public final class ItemUtil {

    private ItemUtil() {
    }

    /** 安全解析材质名，失败返回 fallback。 */
    public static Material material(String name, Material fallback) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        Material m = Material.matchMaterial(name.trim().toUpperCase(Locale.ROOT));
        return m == null ? fallback : m;
    }

    /** 反序列化 Bucket/原版序列化的 ItemStack YAML（{@code item: ...}）。 */
    public static ItemStack deserialize(String yaml) {
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

    /** 把 ItemStack 序列化为 {@code item: ...} YAML 文本。 */
    public static String serialize(ItemStack item) {
        if (item == null) {
            return "";
        }
        YamlConfiguration c = new YamlConfiguration();
        c.set("item", item);
        return c.saveToString();
    }

    // =====================================================================
    // 整组物品 Base64 序列化（参考前置插件保存玩家装备的方式）
    // =====================================================================

    /** 把一组 ItemStack（可含 null）编码为 Base64 文本；失败返回空串。 */
    public static String encode(ItemStack[] items) {
        if (items == null || items.length == 0) {
            return "";
        }
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             BukkitObjectOutputStream out = new BukkitObjectOutputStream(bos)) {
            out.writeInt(items.length);
            for (ItemStack item : items) {
                out.writeObject(item == null ? null : item.clone());
            }
            out.flush();
            return Base64.getEncoder().encodeToString(bos.toByteArray());
        } catch (Throwable t) {
            return "";
        }
    }

    /** 解码 Base64 文本为 ItemStack 数组；失败返回空数组。 */
    public static ItemStack[] decode(String data) {
        if (data == null || data.isBlank()) {
            return new ItemStack[0];
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(data.trim());
        } catch (IllegalArgumentException e) {
            return new ItemStack[0];
        }
        try (ByteArrayInputStream bis = new ByteArrayInputStream(raw);
             BukkitObjectInputStream in = new BukkitObjectInputStream(bis)) {
            int len = in.readInt();
            if (len < 0 || len > 512) {
                return new ItemStack[0];
            }
            ItemStack[] out = new ItemStack[len];
            for (int i = 0; i < len; i++) {
                Object o = in.readObject();
                out[i] = o instanceof ItemStack is ? is : null;
            }
            return out;
        } catch (Throwable t) {
            return new ItemStack[0];
        }
    }

    /** 深拷贝物品数组。 */
    public static ItemStack[] copy(ItemStack[] in) {
        if (in == null) {
            return new ItemStack[0];
        }
        ItemStack[] out = new ItemStack[in.length];
        for (int i = 0; i < in.length; i++) {
            out[i] = in[i] == null ? null : in[i].clone();
        }
        return out;
    }
}
