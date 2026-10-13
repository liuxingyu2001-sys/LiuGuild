package com.liu.liuguild.hook;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.entity.Player;

/** PlaceholderAPI 钩子（软依赖，缺失时原样返回）。 */
public final class PapiHook {

    public String apply(Player player, String text) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        try {
            return PlaceholderAPI.setPlaceholders(player, text);
        } catch (Throwable t) {
            return text;
        }
    }
}
