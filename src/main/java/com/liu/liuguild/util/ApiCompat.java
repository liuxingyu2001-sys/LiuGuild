package com.liu.liuguild.util;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;
import org.bukkit.potion.PotionEffectType;

import java.util.Locale;

/**
 * 跨版本兼容工具。
 *
 * <p>新版 Bukkit（如 Leaf 26.2 / 1.21.11）把很多“按名字取枚举”的 API 语义改成了
 * 命名空间键，旧写法（{@code XxxType.getByName("SPEED")}）运行时可能返回 {@code null}，
 * 导致功能**静默失效**（gamerule、药水效果都踩过这个坑）。
 *
 * <p>统一策略：<b>先遍历枚举常量比对名字（跨版本稳定）</b>，再回退注册表查询，
 * 最后才退回老 API，全程吞掉异常。</p>
 */
public final class ApiCompat {

    private ApiCompat() {
    }

    /**
     * 按名字解析药水效果类型，支持 {@code SPEED} / {@code speed} / {@code minecraft:speed}。
     *
     * @return 找不到时返回 {@code null}
     */
    public static PotionEffectType potionEffect(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String raw = name.trim();
        String path = raw.toLowerCase(Locale.ROOT);
        if (path.startsWith("minecraft:")) {
            path = path.substring("minecraft:".length());
        }
        // 1) 枚举常量 name 比对（跨版本最稳）
        try {
            for (PotionEffectType type : PotionEffectType.values()) {
                if (type.getName().equalsIgnoreCase(path)) {
                    return type;
                }
            }
        } catch (Throwable ignored) {
        }
        // 2) 现代注册表查询
        try {
            PotionEffectType byKey = Registry.EFFECT.get(NamespacedKey.minecraft(path));
            if (byKey != null) {
                return byKey;
            }
        } catch (Throwable ignored) {
        }
        // 3) 旧 API 兜底
        try {
            return PotionEffectType.getByName(raw);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 实体当前最大生命值（新版用属性注册表，旧版回退 getMaxHealth）。 */
    public static double maxHealth(LivingEntity entity) {
        if (entity == null) {
            return 20.0;
        }
        try {
            // 用注册表拿属性，避开 GENERIC_MAX_HEALTH / MAX_HEALTH 常量改名问题
            Attribute maxHealth = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("max_health"));
            if (maxHealth != null) {
                AttributeInstance attr = entity.getAttribute(maxHealth);
                if (attr != null && attr.getValue() > 0) {
                    return attr.getValue();
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            @SuppressWarnings("deprecation")
            double legacy = entity.getMaxHealth();
            return legacy > 0 ? legacy : 20.0;
        } catch (Throwable ignored) {
            return 20.0;
        }
    }

    /** 把实体血量设为安全范围内的值（永不因上限变化而“超血”报错）。 */
    public static void setHealthSafely(LivingEntity entity, double health) {
        if (entity == null) {
            return;
        }
        double max = maxHealth(entity);
        double value = Math.max(1.0, Math.min(health, max));
        try {
            entity.setHealth(value);
        } catch (Throwable ignored) {
            // 极端情况下（如实体已移除）忽略
        }
    }
}
