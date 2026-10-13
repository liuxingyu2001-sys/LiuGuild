package com.liu.liuguild.gui;

import com.liu.liuguild.LiuGuild;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** 聊天栏输入会话：用于输入公告、简介、金额等文本。 */
public final class ChatInput implements Listener {

    private final LiuGuild plugin;
    private final Map<UUID, Consumer<String>> pending = new ConcurrentHashMap<>();

    public ChatInput(LiuGuild plugin) {
        this.plugin = plugin;
    }

    public void request(Player player, Component prompt, Consumer<String> callback) {
        pending.put(player.getUniqueId(), callback);
        player.closeInventory();
        player.sendMessage(prompt);
        player.sendMessage(plugin.messages().component("input-cancel-hint"));
    }

    public void cancel(UUID uuid) {
        pending.remove(uuid);
    }

    public boolean isWaiting(UUID uuid) {
        return pending.containsKey(uuid);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        Consumer<String> cb = pending.remove(player.getUniqueId());
        if (cb == null) {
            return;
        }
        event.setCancelled(true);
        String message = event.getMessage();
        plugin.sync(() -> cb.accept(message));
    }
}
