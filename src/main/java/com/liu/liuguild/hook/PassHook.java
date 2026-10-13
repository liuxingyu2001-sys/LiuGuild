package com.liu.liuguild.hook;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * liupass 战令钩子（软依赖，反射访问）：
 * 工会战结算后为参与者发放通行证经验。
 */
public final class PassHook {

    private final Method addXpWithPass;
    private final Method addXpDefault;
    private final boolean available;

    public PassHook() {
        Method withPass = null;
        Method def = null;
        try {
            Plugin p = Bukkit.getPluginManager().getPlugin("liupass");
            if (p != null || classPresent()) {
                Class<?> api = Class.forName("com.liupass.core.api.PassAPI");
                withPass = api.getMethod("addXp", UUID.class, String.class, int.class);
                def = api.getMethod("addXp", UUID.class, int.class);
            }
        } catch (Throwable ignored) {
        }
        this.addXpWithPass = withPass;
        this.addXpDefault = def;
        this.available = withPass != null;
    }

    private static boolean classPresent() {
        try {
            Class.forName("com.liupass.core.api.PassAPI");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean available() {
        return available;
    }

    public void addXp(UUID uuid, String passId, int amount) {
        if (!available || amount <= 0) {
            return;
        }
        try {
            if (passId != null && !passId.isBlank()) {
                addXpWithPass.invoke(null, uuid, passId, amount);
            } else if (addXpDefault != null) {
                addXpDefault.invoke(null, uuid, amount);
            }
        } catch (Throwable ignored) {
        }
    }
}
