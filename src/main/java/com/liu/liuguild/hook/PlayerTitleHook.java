package com.liu.liuguild.hook;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * PlayerTitle 钩子（软依赖，反射调用）：工会战胜利可为参赛者发放称号。
 * <p>API：{@code cn.handyplus.title.api.PlayerTitleApi.setPlayerTitle(UUID, int)}。</p>
 */
public final class PlayerTitleHook {

    private final Method setTitle;
    private final boolean available;

    public PlayerTitleHook() {
        Method m = null;
        try {
            Plugin p = Bukkit.getPluginManager().getPlugin("PlayerTitle");
            if (p != null) {
                Class<?> api = Class.forName("cn.handyplus.title.api.PlayerTitleApi");
                m = api.getMethod("setPlayerTitle", UUID.class, int.class);
            }
        } catch (Throwable ignored) {
        }
        this.setTitle = m;
        this.available = m != null;
    }

    public boolean available() {
        return available;
    }

    public void grant(UUID player, int titleId) {
        if (!available || titleId <= 0 || player == null) {
            return;
        }
        try {
            setTitle.invoke(null, player, titleId);
        } catch (Throwable ignored) {
        }
    }
}
