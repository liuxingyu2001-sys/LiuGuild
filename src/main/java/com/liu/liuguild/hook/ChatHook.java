package com.liu.liuguild.hook;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;

/**
 * LiuChat 钩子（软依赖，反射访问，缺少时优雅降级）：
 * 借助 LiuChat 的跨服链路把工会战通知广播到所有子服。
 */
public final class ChatHook {

    private final Object liuChat;
    private final Method broadcast;
    private final Method deliverPrivate;

    public ChatHook() {
        Object instance = null;
        Method bc = null;
        Method dp = null;
        try {
            Plugin p = Bukkit.getPluginManager().getPlugin("LiuChat");
            if (p != null) {
                instance = p;
                bc = p.getClass().getMethod("broadcastAnnouncement", Player.class, BaseComponent[].class);
                dp = p.getClass().getMethod("deliverPrivate", Player.class, String.class, BaseComponent[].class);
            }
        } catch (Throwable ignored) {
        }
        this.liuChat = instance;
        this.broadcast = bc;
        this.deliverPrivate = dp;
    }

    public boolean available() {
        return liuChat != null && broadcast != null;
    }

    /** 跨服公告；返回是否已交给 LiuChat。 */
    public boolean broadcast(Player carrier, Component message) {
        if (!available() || message == null) {
            return false;
        }
        try {
            BaseComponent[] arr = toComponents(message);
            broadcast.invoke(liuChat, carrier, arr);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 跨服私密投递。 */
    public boolean deliverPrivate(Player carrier, String targetName, Component message) {
        if (liuChat == null || deliverPrivate == null || message == null) {
            return false;
        }
        try {
            BaseComponent[] arr = toComponents(message);
            Object r = deliverPrivate.invoke(liuChat, carrier, targetName, arr);
            return Boolean.TRUE.equals(r);
        } catch (Throwable t) {
            return false;
        }
    }

    private static BaseComponent[] toComponents(Component message) {
        String legacy = LegacyComponentSerializer.legacySection().serialize(message);
        return TextComponent.fromLegacyText(legacy);
    }
}
