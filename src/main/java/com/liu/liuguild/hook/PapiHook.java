package com.liu.liuguild.hook;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.entity.Player;

/** PlaceholderAPI 钩子（软依赖，缺失时原样返回）。 */
public final class PapiHook {

    /** 启动时探测一次；未装 PAPI 时不再每次调用都触发 NoClassDefFoundError。 */
    private static final boolean PRESENT =
            org.bukkit.Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");

    public boolean available() {
        return PRESENT;
    }

    public String apply(Player player, String text) {
        if (!PRESENT || text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        try {
            return PlaceholderAPI.setPlaceholders(player, text);
        } catch (Throwable t) {
            return text;
        }
    }
}
