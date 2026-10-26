package com.liu.liuguild.gui;

import com.liu.liuguild.LiuGuild;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** 聊天栏输入会话：用于输入公告、简介、金额等文本。 */
public final class ChatInput implements Listener {

    /** 输入会话超时：避免玩家关掉界面后，下一句正常聊天被当成输入吞掉。 */
    private static final long TIMEOUT_MS = 60_000L;

    private final LiuGuild plugin;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    private record Pending(Consumer<String> callback, Runnable cancel, long expiresAt) {
    }

    public ChatInput(LiuGuild plugin) {
        this.plugin = plugin;
    }

    public void request(Player player, Component prompt, Consumer<String> callback, Runnable onCancel) {
        begin(player, new Pending(callback, onCancel, System.currentTimeMillis() + TIMEOUT_MS), prompt);
    }

    public void request(Player player, Component prompt, Consumer<String> callback) {
        begin(player, new Pending(callback, null, System.currentTimeMillis() + TIMEOUT_MS), prompt);
    }

    private void begin(Player player, Pending next, Component prompt) {
        purgeExpired();
        // 覆盖旧会话时必须同时覆盖 cancel 回调，避免上一次输入的取消逻辑串到这次。
        pending.put(player.getUniqueId(), next);
        plugin.gui().closeMenu(player);
        player.sendMessage(prompt);
        player.sendMessage(plugin.messages().component("input-cancel-hint"));
    }

    public void cancel(UUID uuid) {
        pending.remove(uuid);
    }

    public boolean isWaiting(UUID uuid) {
        Pending p = pending.get(uuid);
        if (p == null) {
            return false;
        }
        if (p.expiresAt <= System.currentTimeMillis()) {
            pending.remove(uuid, p);
            return false;
        }
        return true;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        Pending input = pending.remove(player.getUniqueId());
        if (input == null) {
            return;
        }
        event.setCancelled(true);
        String message = PlainTextComponentSerializer.plainText().serialize(event.message());
        if (input.expiresAt <= System.currentTimeMillis()) {
            plugin.sync(() -> {
                plugin.messages().send(player, "input-cancelled");
                if (input.cancel != null) input.cancel.run();
            });
            return;
        }
        if (message.equalsIgnoreCase("cancel") || message.equalsIgnoreCase("取消")) {
            plugin.sync(() -> {
                plugin.messages().send(player, "input-cancelled");
                if (input.cancel != null) input.cancel.run();
            });
            return;
        }
        plugin.sync(() -> input.callback.accept(message));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pending.remove(event.getPlayer().getUniqueId());
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis();
        pending.entrySet().removeIf(e -> e.getValue().expiresAt <= now);
    }
}
