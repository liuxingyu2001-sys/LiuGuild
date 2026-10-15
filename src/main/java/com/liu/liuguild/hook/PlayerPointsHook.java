package com.liu.liuguild.hook;

import org.bukkit.Bukkit;
import java.util.UUID;

/** PlayerPoints 3.3.5 getAPI/take/give，反射保留可选依赖。主线程调用。 */
public final class PlayerPointsHook {
    public boolean change(UUID player, long amount, boolean refund) {
        if (amount < 0 || amount > Integer.MAX_VALUE) return false;
        try {
            var plugin = Bukkit.getPluginManager().getPlugin("PlayerPoints");
            if (plugin == null || !plugin.isEnabled()) return false;
            Object api = plugin.getClass().getMethod("getAPI").invoke(plugin);
            return Boolean.TRUE.equals(api.getClass().getMethod(refund ? "give" : "take", UUID.class, int.class)
                    .invoke(api, player, (int) amount));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) { return false; }
    }
}
