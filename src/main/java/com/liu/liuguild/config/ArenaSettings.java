package com.liu.liuguild.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** 竞技场模块（arena.yml）。 */
public final class ArenaSettings {

    public boolean pregenerate = true;
    public int createTimeoutSeconds = 60;
    public Map<String, ArenaDef> arenas = new LinkedHashMap<>();

    public static ArenaSettings parse(FileConfiguration c) {
        ArenaSettings s = new ArenaSettings();
        s.pregenerate = c.getBoolean("pregenerate", s.pregenerate);
        s.createTimeoutSeconds = Math.max(5, c.getInt("create-timeout-seconds", s.createTimeoutSeconds));
        ConfigurationSection sec = c.getConfigurationSection("arenas");
        if (sec != null) {
            for (String id : sec.getKeys(false)) {
                s.arenas.put(id.toLowerCase(Locale.ROOT), ArenaDef.parse(id, sec.getConfigurationSection(id)));
            }
        }
        s.arenas.computeIfAbsent("main", k -> ArenaDef.parse("main", null));
        return s;
    }

    /** 按 id 取竞技场；未知回退 main。 */
    public ArenaDef arena(String id) {
        if (id != null && arenas.containsKey(id.toLowerCase(Locale.ROOT))) {
            return arenas.get(id.toLowerCase(Locale.ROOT));
        }
        ArenaDef main = arenas.get("main");
        return main != null ? main : ArenaDef.parse("main", null);
    }
}
