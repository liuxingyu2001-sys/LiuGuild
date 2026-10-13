package com.liu.liuguild.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;

/** GUI 按钮定义。 */
public final class ButtonDef {

    public final int slot;
    public final String material;
    public final String fallbackMaterial;
    public final String name;
    public final List<String> lore;

    public ButtonDef(int slot, String material, String fallbackMaterial, String name, List<String> lore) {
        this.slot = slot;
        this.material = material;
        this.fallbackMaterial = fallbackMaterial;
        this.name = name;
        this.lore = lore;
    }

    public Material fallback() {
        Material m = fallbackMaterial == null ? null : Material.matchMaterial(fallbackMaterial.toUpperCase(java.util.Locale.ROOT));
        return m == null ? Material.STONE : m;
    }

    public static ButtonDef parse(ConfigurationSection s, int defaultSlot) {
        if (s == null) {
            return null;
        }
        List<String> lore = new ArrayList<>(s.getStringList("lore"));
        return new ButtonDef(
                s.getInt("slot", defaultSlot),
                s.getString("material", "STONE"),
                s.getString("fallback-material", "STONE"),
                s.getString("name", ""),
                lore);
    }
}
