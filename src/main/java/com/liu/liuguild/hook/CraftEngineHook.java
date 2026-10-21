package com.liu.liuguild.hook;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Locale;

/**
 * CraftEngine 钩子（软依赖，反射调用）：
 * <ul>
 *   <li>{@code net.momirealms.craftengine.bukkit.api.CraftEngineItems.byId(String)} → BukkitItemDefinition</li>
 *   <li>{@code buildBukkitItem()} → ItemStack</li>
 * </ul>
 * 用于工会图标、GUI 按钮、工会战统一装备等自定义外观。缺失或失败时回退原生物品。
 */
public final class CraftEngineHook {

    private final boolean ready;
    private Method byIdString;
    private Method buildBukkitItem;
    private Method getCustomItemId;

    public CraftEngineHook() {
        Plugin ce = Bukkit.getPluginManager().getPlugin("CraftEngine");
        boolean ok = false;
        if (ce != null) {
            try {
                Class<?> items = Class.forName("net.momirealms.craftengine.bukkit.api.CraftEngineItems");
                byIdString = items.getMethod("byId", String.class);
                Class<?> def = Class.forName("net.momirealms.craftengine.bukkit.item.BukkitItemDefinition");
                buildBukkitItem = def.getMethod("buildBukkitItem");
                try {
                    getCustomItemId = items.getMethod("getCustomItemId", ItemStack.class);
                } catch (NoSuchMethodException ignored) {
                }
                trySetAccessible(byIdString);
                trySetAccessible(buildBukkitItem);
                trySetAccessible(getCustomItemId);
                ok = true;
            } catch (Throwable t) {
                ok = false;
            }
        }
        this.ready = ok;
    }

    /** CraftEngine 插件是否可用。 */
    public boolean available() {
        return ready;
    }

    private static void trySetAccessible(Method m) {
        if (m == null) {
            return;
        }
        try {
            m.setAccessible(true);
        } catch (Throwable ignored) {
        }
    }

    /** 按自定义物品 id 构建 ItemStack；不存在返回 null。 */
    public ItemStack byId(String id) {
        if (!ready || id == null || id.isBlank()) {
            return null;
        }
        try {
            Object def = byIdString.invoke(null, id);
            if (def == null) {
                return null;
            }
            Object item = buildBukkitItem.invoke(def);
            return item instanceof ItemStack stack ? stack : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 解析物品描述：
     * <ul>
     *   <li>优先交给 LiuLib 完整物品规格（{@code base64:...} / {@code nbt:...} /
     *       {@code lib:...} / {@code ce:...} / {@code DIAMOND*3}）</li>
     *   <li>{@code CE:namespace:id} → CraftEngine 自定义物品（老写法兜底）</li>
     *   <li>其它 → 原生 Material（大小写不敏感，非法则回退 fallback）</li>
     * </ul>
     */
    public ItemStack resolve(String spec, Material fallback) {
        if (spec == null || spec.isBlank()) {
            return new ItemStack(fallback);
        }
        String s = spec.trim();
        // 首选 LiuLib 完整物品规格；失败再走原来的 CE / 材质名兜底
        ItemStack custom = com.liu.liuguild.util.LiuLibItems.build(s);
        if (custom != null && !custom.getType().isAir()) {
            return custom;
        }
        if (s.regionMatches(true, 0, "CE:", 0, 3)) {
            ItemStack ce = byId(s.substring(3));
            return ce != null ? ce : new ItemStack(fallback);
        }
        Material mat = Material.matchMaterial(s.toUpperCase(Locale.ROOT));
        return new ItemStack(mat == null ? fallback : mat);
    }

    /** 判断物品是否为 CraftEngine 自定义物品。 */
    public boolean isCustomItem(ItemStack item) {
        if (!ready || item == null || getCustomItemId == null) {
            return false;
        }
        try {
            Object r = getCustomItemId.invoke(null, item);
            if (r == null) {
                return false;
            }
            if (r instanceof java.util.Optional<?> opt) {
                return opt.isPresent();
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
