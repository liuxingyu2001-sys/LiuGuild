package com.liu.liuguild.util;

import com.liuxingyu.liulib.LiuLib;
import org.bukkit.inventory.ItemStack;

/**
 * LiuLib 物品规格桥接（配置字符串 → 物品）。
 *
 * <p>把原本只认材质名 / {@code CE:} 的地方升级成 LiuLib 的完整规格解析：
 * <ul>
 *   <li>{@code DIAMOND} / {@code minecraft:diamond} / {@code DIAMOND:3} / {@code DIAMOND*3}</li>
 *   <li>{@code ce:namespace:id} / {@code craftengine:namespace:id}（CraftEngine 物品）</li>
 *   <li>{@code base64:...} / {@code nbt:...} / {@code bytes:...} / {@code item:base64:...}
 *       （Paper 二进制序列化，CE/NBT/PDC 全保真）</li>
 *   <li>{@code lib:物品库ID}（liuitemlib 注册的自定义前缀）</li>
 * </ul>
 *
 * <p>本类只负责“首选解析”，失败返回 {@code null}，调用方继续走原有材质 / CraftEngine
 * 逻辑作为兜底，因此老配置行为不变。所有异常都在这里吞掉：一个写错的物品串
 * 不该让整个菜单或工会战套装发放中断。
 */
public final class LiuLibItems {

    private LiuLibItems() {
    }

    /**
     * 按 LiuLib 规格构建物品。
     *
     * @param raw 配置原文（可为空）
     * @return 构建结果；无法解析时返回 {@code null}
     */
    public static ItemStack build(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LiuLib.items().buildOrNull(raw);
        } catch (Throwable t) {
            // LiuLib 未就绪 / 版本差异 / 反序列化异常：一律当“这条规格解析不了”，交给兜底
            return null;
        }
    }
}
