package com.liu.liuguild.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 工会战模式模块（war-modes.yml）：每种模式一份独立配置。 */
public final class WarModeSettings {

    /** 全部模式（key 小写）。 */
    public Map<String, WarMode> modes = new LinkedHashMap<>();
    /** 默认模式 key。 */
    public String defaultMode = "match";

    /**
     * @param c     war-modes.yml
     * @param war   war.yml（提供 defaults 与 default-mode）
     */
    public static WarModeSettings parse(FileConfiguration c, WarSettings war) {
        WarModeSettings s = new WarModeSettings();
        s.defaultMode = war.defaultMode;
        WarMode base = war.defaults;

        ConfigurationSection sec = c.getConfigurationSection("modes");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                String k = key.toLowerCase(Locale.ROOT);
                // 逐层覆盖：defaults → war.yml 同级模式/ war.modes → war-modes.yml。
                WarMode start = war.inlineModes.getOrDefault(k, base);
                s.modes.put(k, WarMode.parse(k, sec.getConfigurationSection(key), start));
            }
        }
        // war.yml 里写过、但 war-modes.yml 没写的模式也要生效。
        for (Map.Entry<String, WarMode> entry : war.inlineModes.entrySet()) {
            s.modes.putIfAbsent(entry.getKey(), entry.getValue().copy());
        }
        // 内置模式兜底
        s.modes.computeIfAbsent("match", k -> {
            WarMode m = base.copy();
            m.key = "match";
            m.display = "匹配工会战";
            m.description = "玩家在工会菜单中点击参与匹配，凑齐两队即刻开战。";
            return m;
        });
        s.modes.computeIfAbsent("season", k -> {
            WarMode m = base.copy();
            m.key = "season";
            m.display = "赛季工会战";
            m.description = "在配置的时间表到达时自动开启的赛季赛事。";
            m.schedule = new ArrayList<>(List.of("sat 20:00", "sun 20:00"));
            return m;
        });
        if (!s.modes.containsKey(s.defaultMode)) {
            s.defaultMode = s.modes.keySet().iterator().next();
        }
        return s;
    }

    public WarMode mode(String key) {
        if (key != null && modes.containsKey(key.toLowerCase(Locale.ROOT))) {
            return modes.get(key.toLowerCase(Locale.ROOT));
        }
        WarMode def = modes.get(defaultMode);
        return def != null ? def : new WarMode();
    }

    public List<WarMode> enabled() {
        List<WarMode> out = new ArrayList<>();
        for (WarMode m : modes.values()) {
            if (m.enabled) {
                out.add(m);
            }
        }
        return out;
    }
}
