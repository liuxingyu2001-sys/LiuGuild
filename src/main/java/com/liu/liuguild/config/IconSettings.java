package com.liu.liuguild.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.LinkedHashMap;
import java.util.Map;

/** 工会图标模块（icons.yml）。 */
public final class IconSettings {

    public final Map<String, IconDef> icons = new LinkedHashMap<>();

    public static IconSettings parse(FileConfiguration c) {
        IconSettings s = new IconSettings();
        ConfigurationSection root = c.getConfigurationSection("icons");
        if (root == null) {
            return s;
        }
        for (String id : root.getKeys(false)) {
            ConfigurationSection sec = root.getConfigurationSection(id);
            if (sec == null) {
                continue;
            }
            s.icons.put(id, new IconDef(
                    id,
                    sec.getString("display", id),
                    sec.getString("item", "SHIELD"),
                    sec.getString("fallback-material", "SHIELD"),
                    sec.getString("permission", "")));
        }
        return s;
    }

    public IconDef icon(String id) {
        return icons.get(id);
    }
}
