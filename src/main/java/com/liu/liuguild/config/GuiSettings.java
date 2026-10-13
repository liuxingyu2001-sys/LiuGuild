package com.liu.liuguild.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.LinkedHashMap;
import java.util.Map;

/** GUI 菜单模块（gui.yml）：所有菜单/按钮/填充物集中于此。 */
public final class GuiSettings {

    public ButtonDef filler;
    /** 界面引擎：legacy（内置）/ auto（优先 Sparrow UI）/ sparrow（强制）。 */
    public String engine = "auto";
    public final Map<String, MenuDef> menus = new LinkedHashMap<>();

    public static GuiSettings parse(FileConfiguration c) {
        GuiSettings s = new GuiSettings();
        ConfigurationSection root = c.getConfigurationSection("gui");
        if (root == null) {
            return s;
        }
        s.filler = ButtonDef.parse(root.getConfigurationSection("filler"), -1);
        s.engine = root.getString("engine", s.engine);
        for (String key : root.getKeys(false)) {
            if ("filler".equals(key) || "engine".equals(key)) {
                continue;
            }
            ConfigurationSection menu = root.getConfigurationSection(key);
            if (menu == null) {
                continue;
            }
            MenuDef def = new MenuDef(key,
                    menu.getString("title", key),
                    menu.getInt("rows", 6));
            ConfigurationSection buttons = menu.getConfigurationSection("buttons");
            if (buttons != null) {
                for (String id : buttons.getKeys(false)) {
                    ButtonDef b = ButtonDef.parse(buttons.getConfigurationSection(id), 0);
                    if (b != null) {
                        def.buttons.put(id, b);
                    }
                }
            }
            def.entry = ButtonDef.parse(menu.getConfigurationSection("entry"), -1);
            def.back = ButtonDef.parse(menu.getConfigurationSection("back"), -1);
            s.menus.put(key, def);
        }
        return s;
    }

    public MenuDef menu(String key) {
        return menus.get(key);
    }
}
