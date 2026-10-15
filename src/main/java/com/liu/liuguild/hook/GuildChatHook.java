package com.liu.liuguild.hook;

import com.liu.liuguild.LiuGuild;
import com.liu.liuguild.model.Guild;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.HandlerList;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Runtime MEMBERS channels. All messages continue through LiuChat moderation. */
public final class GuildChatHook implements Listener {
    private final LiuGuild plugin;
    private Object api;
    private Class<?> apiType;
    private Class<?> channelType;
    private Class<?> scopeType;
    private Plugin dependency;
    private Method remoteDelivery;
    private final Set<String> owned = new HashSet<>();
    private final Map<UUID, PermissionAttachment> attachments = new HashMap<>();
    private final Map<UUID, String> previous = new HashMap<>();
    /** 上次同步时的「在线玩家→频道」签名；无变化时跳过重复的反射订阅。 */
    private volatile String lastSignature = null;

    public GuildChatHook(LiuGuild plugin) { this.plugin = plugin; }

    public void start() {
        dependency = Bukkit.getPluginManager().getPlugin("LiuChat");
        if (dependency == null || !dependency.isEnabled()) return;
        try {
            ClassLoader loader = dependency.getClass().getClassLoader();
            apiType = loader.loadClass("com.liu.liuchat.api.ChannelApi");
            channelType = loader.loadClass("com.liu.liuchat.api.ChatChannel");
            scopeType = loader.loadClass("com.liu.liuchat.api.ChatChannel$Scope");
            api = dependency.getClass().getMethod("getChannelApi").invoke(dependency);
            registerGuard(loader, "ChannelMessageEvent", "getChannel");
            registerGuard(loader, "ChannelSwitchEvent", "getNext");
            try {
                remoteDelivery = dependency.getClass().getMethod("deliverChannelRemote", String.class,
                        String.class, String.class, net.kyori.adventure.text.Component.class);
                registerRelay(loader);
            } catch (ReflectiveOperationException ignored) {
                plugin.getLogger().info("LiuChat 缺少审核后频道 API，工会频道仅本服可用。");
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            api = null;
            HandlerList.unregisterAll(this);
            plugin.getLogger().warning("LiuChat 工会频道 API 不可用: " + e.getMessage());
        }
    }

    public boolean available() { return api != null; }
    private static String id(Guild guild) { return "g_" + guild.id(); }
    private static String permission(String id) { return "liuguild.channel." + id; }

    private Object call(String name, Class<?>[] types, Object... args) throws ReflectiveOperationException {
        return apiType.getMethod(name, types).invoke(api, args);
    }

    private void registerGuard(ClassLoader loader, String name, String getter) throws ReflectiveOperationException {
        Class<? extends Event> type = loader.loadClass("com.liu.liuchat.api.event." + name).asSubclass(Event.class);
        Method getChannel = type.getMethod(getter);
        Method getPlayer = type.getMethod("getPlayer");
        Method cancel = type.getMethod("setCancelled", boolean.class);
        Bukkit.getPluginManager().registerEvent(type, this, EventPriority.HIGHEST, (listener, event) -> {
            try {
                String channel = (String) channelType.getMethod("id").invoke(getChannel.invoke(event));
                if (!owned.contains(channel)) return;
                Player player = (Player) getPlayer.invoke(event);
                Guild guild = plugin.guilds().guildOf(player.getUniqueId());
                if (guild == null || !id(guild).equals(channel)) cancel.invoke(event, true);
            } catch (ReflectiveOperationException e) {
                throw new org.bukkit.event.EventException(e);
            }
        }, plugin, true);
    }

    private void registerRelay(ClassLoader loader) throws ReflectiveOperationException {
        Class<? extends Event> type = loader.loadClass("com.liu.liuchat.api.event.ChannelDeliveredEvent").asSubclass(Event.class);
        Method getChannel = type.getMethod("getChannel");
        Method getPlayer = type.getMethod("getPlayer");
        Method getRendered = type.getMethod("getRendered");
        Bukkit.getPluginManager().registerEvent(type, this, EventPriority.MONITOR, (listener, event) -> {
            try {
                String channel = (String) channelType.getMethod("id").invoke(getChannel.invoke(event));
                if (!owned.contains(channel) || !plugin.bus().isReady()) return;
                Player player = (Player) getPlayer.invoke(event);
                Guild guild = plugin.guilds().guildOf(player.getUniqueId());
                if (guild == null || !id(guild).equals(channel)) return;
                String json = net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson()
                        .serialize((net.kyori.adventure.text.Component) getRendered.invoke(event));
                plugin.bus().publish("GUILD_CHAT", com.liu.liuguild.crossserver.CrossServerBus.map(
                        "server", plugin.serverId(), "guild", guild.id(), "uuid", player.getUniqueId().toString(),
                        "name", player.getName(), "json", json));
            } catch (ReflectiveOperationException e) { throw new org.bukkit.event.EventException(e); }
        }, plugin);
    }

    public void receive(Map<String, String> data) {
        if (!available() || remoteDelivery == null || plugin.serverId().equals(data.get("server"))) return;
        plugin.sync(() -> {
            Guild guild = plugin.guilds().guild(data.get("guild"));
            String json = data.get("json");
            if (guild == null || json == null || json.length() > 65536) return;
            refresh();
            try {
                net.kyori.adventure.text.Component rendered = net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson().deserialize(json);
                remoteDelivery.invoke(dependency, id(guild), data.get("uuid"), data.get("name"), rendered);
            } catch (ReflectiveOperationException | RuntimeException e) {
                plugin.getLogger().warning("接收跨服工会聊天失败: " + e.getMessage());
            }
        });
    }

    /** Main thread: reconcile membership, including changes received from other servers. */
    public void refresh() {
        if (!available()) {
            return;
        }
        String current = signature();
        if (current.equals(lastSignature)) {
            return;
        }
        try {
            reconcile();
            lastSignature = current;
        } catch (ReflectiveOperationException | RuntimeException e) {
            plugin.getLogger().warning("同步工会频道失败: " + e.getMessage());
        }
    }

    /** 在线玩家与所属工会的轻量签名，用于避免每心跳重复反射同步。 */
    private String signature() {
        StringBuilder sb = new StringBuilder();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Guild guild = plugin.guilds().guildOf(player.getUniqueId());
            sb.append(player.getUniqueId()).append('=');
            if (guild != null) {
                sb.append(guild.id()).append(':').append(guild.name());
            }
            sb.append(';');
        }
        return sb.toString();
    }

    private void reconcile() throws ReflectiveOperationException {
        {
            Set<String> needed = new HashSet<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                Guild guild = plugin.guilds().guildOf(player.getUniqueId());
                if (guild != null) {
                    String channel = id(guild);
                    needed.add(channel);
                    if (!owned.contains(channel)) {
                        @SuppressWarnings({"unchecked", "rawtypes"})
                        Object scope = Enum.valueOf((Class) scopeType, "MEMBERS");
                        Object definition = channelType.getConstructor(String.class, String.class, scopeType,
                                String.class, String.class, String.class, double.class).newInstance(channel,
                                "工会·" + guild.name(), scope, permission(channel), permission(channel),
                                plugin.settings().main.guildChannelFormat, 100.0);
                        if (Boolean.TRUE.equals(call("register", new Class<?>[]{channelType}, definition))) {
                            owned.add(channel);
                            Bukkit.getPluginManager().addPermission(new Permission(permission(channel), PermissionDefault.FALSE));
                        }
                    }
                }
            }
            for (Player player : Bukkit.getOnlinePlayers()) {
                UUID uuid = player.getUniqueId();
                Guild guild = plugin.guilds().guildOf(uuid);
                String current = guild == null ? "" : id(guild);
                PermissionAttachment attachment = attachments.computeIfAbsent(uuid, ignored -> player.addAttachment(plugin));
                for (String channel : owned) {
                    boolean member = current.equals(channel);
                    if (!Objects.equals(attachment.getPermissions().get(permission(channel)), member)) {
                        attachment.setPermission(permission(channel), member);
                    }
                    if (member) call("subscribe", new Class<?>[]{Player.class, String.class}, player, channel);
                    else call("unsubscribe", new Class<?>[]{UUID.class, String.class}, uuid, channel);
                }
            }
            for (String channel : new HashSet<>(owned)) {
                if (!needed.contains(channel)) {
                    call("unregister", new Class<?>[]{String.class}, channel);
                    owned.remove(channel);
                    Bukkit.getPluginManager().removePermission(permission(channel));
                    for (PermissionAttachment attachment : attachments.values()) attachment.unsetPermission(permission(channel));
                }
            }
        }
    }

    /** /guild chat [message]: switch or send once without changing the current channel. */
    public void chat(Player player, String message) {
        if (!available()) { plugin.messages().send(player, "guild-chat-unavailable"); return; }
        Guild guild = plugin.guilds().guildOf(player.getUniqueId());
        if (guild == null) { plugin.messages().send(player, "not-in-guild"); return; }
        refresh();
        String channel = id(guild);
        if (!owned.contains(channel)) { plugin.messages().send(player, "guild-chat-unavailable"); return; }
        try {
            if (message.isBlank()) {
                Object current = call("current", new Class<?>[]{UUID.class}, player.getUniqueId());
                String selected = (String) channelType.getMethod("id").invoke(current);
                boolean leaving = selected.equals(channel);
                String target = leaving ? previous.getOrDefault(player.getUniqueId(), "global") : channel;
                Object result = call("switchChannel", new Class<?>[]{Player.class, String.class}, player, target);
                if ("SENT".equals(result.toString())) {
                    if (leaving) previous.remove(player.getUniqueId());
                    else previous.put(player.getUniqueId(), selected);
                    plugin.messages().send(player, leaving ? "guild-chat-off" : "guild-chat-on");
                } else plugin.messages().send(player, "guild-chat-result", "result", describe(result.toString()));
            } else {
                Object future = call("send", new Class<?>[]{Player.class, String.class, String.class}, player, channel, message);
                ((CompletableFuture<?>) future).whenComplete((result, error) -> plugin.sync(() -> {
                    if (player.isOnline() && (error != null || !"SENT".equals(String.valueOf(result)))) {
                        plugin.messages().send(player, "guild-chat-result", "result", error == null ? describe(String.valueOf(result)) : "发送失败，请稍后重试");
                    }
                }));
            }
        } catch (ReflectiveOperationException e) { plugin.messages().send(player, "guild-chat-unavailable"); }
    }

    private static String describe(String result) {
        return switch (result) {
            case "MUTED" -> "你当前被禁言";
            case "COOLDOWN" -> "发言过快，请稍后重试";
            case "REPEATED" -> "请勿重复发送相同消息";
            case "FILTERED" -> "消息未通过聊天过滤";
            case "NO_PERMISSION", "NOT_MEMBER" -> "没有该频道的发言权限";
            case "OFFLINE" -> "玩家已离线";
            case "CANCELLED" -> "发言或频道切换已取消";
            default -> "频道暂不可用，请重新尝试";
        };
    }

    /** 玩家当前是否处于本工会频道（供 GUI 展示开关状态）。 */
    public boolean inGuildChannel(Player player) {
        if (!available() || player == null) {
            return false;
        }
        Guild guild = plugin.guilds().guildOf(player.getUniqueId());
        if (guild == null) {
            return false;
        }
        String channel = id(guild);
        if (!owned.contains(channel)) {
            return false;
        }
        try {
            Object current = call("current", new Class<?>[]{UUID.class}, player.getUniqueId());
            String selected = (String) channelType.getMethod("id").invoke(current);
            return channel.equals(selected);
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    public void forget(Player player) {
        PermissionAttachment attachment = attachments.remove(player.getUniqueId());
        if (attachment != null) player.removeAttachment(attachment);
        previous.remove(player.getUniqueId());
    }

    public void close() {
        if (available()) {
            for (String channel : owned) {
                try { call("unregister", new Class<?>[]{String.class}, channel); }
                catch (ReflectiveOperationException ignored) { }
                Bukkit.getPluginManager().removePermission(permission(channel));
            }
        }
        for (Player player : Bukkit.getOnlinePlayers()) forget(player);
        HandlerList.unregisterAll(this);
        owned.clear();
        lastSignature = null;
        api = null;
    }
}
