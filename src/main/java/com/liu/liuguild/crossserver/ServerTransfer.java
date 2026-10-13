package com.liu.liuguild.crossserver;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.liu.liuguild.config.GuildConfig;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * 通过代理插件消息把玩家送到目标子服。
 * 使用 BungeeCord 的 {@code Connect} 子通道，BungeeCord / Velocity 均原生支持。
 * 目标服务器名约定为配置里的 server-id（需与代理 [servers] 登记名一致）。
 */
public final class ServerTransfer {

    private static final String CHANNEL = "BungeeCord";

    private final Plugin plugin;
    private final GuildConfig conf;
    private volatile boolean registered;

    public ServerTransfer(Plugin plugin, GuildConfig conf) {
        this.plugin = plugin;
        this.conf = conf;
    }

    public void register() {
        try {
            Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
            registered = true;
        } catch (Throwable t) {
            plugin.getLogger().warning("注册代理传送通道失败: " + t.getMessage());
            registered = false;
        }
    }

    public boolean available() {
        return registered && conf.transferEnabled;
    }

    /** 把玩家连接到目标子服；返回是否已发出。 */
    public boolean connect(Player player, String targetServer) {
        if (!available() || player == null || targetServer == null || targetServer.isBlank()) {
            return false;
        }
        try {
            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            out.writeUTF("Connect");
            out.writeUTF(targetServer);
            player.sendPluginMessage(plugin, CHANNEL, out.toByteArray());
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("跨服传送失败(" + player.getName() + " -> " + targetServer + "): " + t.getMessage());
            return false;
        }
    }
}
