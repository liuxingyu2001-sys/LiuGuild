package com.liu.liuguild.config;

import org.bukkit.Material;

/** 工会图标定义。 */
public final class IconDef {

    public final String id;
    public final String display;
    public final String item;
    public final String fallbackMaterial;
    public final String permission;

    public IconDef(String id, String display, String item, String fallbackMaterial, String permission) {
        this.id = id;
        this.display = display;
        this.item = item;
        this.fallbackMaterial = fallbackMaterial;
        this.permission = permission;
    }

    public Material fallback() {
        Material m = Material.matchMaterial(
                (fallbackMaterial == null ? "SHIELD" : fallbackMaterial).toUpperCase(java.util.Locale.ROOT));
        return m == null ? Material.SHIELD : m;
    }

    public boolean hasPermission(org.bukkit.entity.Player player) {
        return permission == null || permission.isBlank() || player.hasPermission(permission);
    }
}
