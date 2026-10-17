package com.liu.liuguild.crossserver;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.liu.liuguild.config.MainSettings;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 通过代理插件消息把玩家送到目标子服。
 * 使用 BungeeCord 的 {@code Connect} 子通道，BungeeCord / Velocity 均原生支持。
 *
 * <p>目标服务器名必须与代理（BungeeCord / Velocity）的 {@code [servers]} 登记名一致。
 * 插件内部的 {@code server-id} 历史上有可能是自动生成的（与本服代理名不同），所以这里
 * 额外通过 {@code GetServer} 子通道探测「本服在代理里的登记名」，供工会战承办服判断使用。</p>
 */
public final class ServerTransfer implements PluginMessageListener {

    private static final String CHANNEL = "BungeeCord";

    private final Plugin plugin;
    private final MainSettings conf;
    private volatile boolean registered;

    /** 本服在代理中的登记名（GetServer 返回）；探测前为 null。 */
    private volatile String proxyName;
    /** 代理登记的全部子服名（小写，GetServers 返回）；用于校验承办服是否存在。 */
    private volatile List<String> proxyServers = List.of();
    /** GetServers 的刷新节流：列表会随代理增删子服变化，不能只在首次为空时请求。 */
    private static final long SERVER_LIST_REFRESH_MS = 5L * 60L * 1000L;
    private static final long SERVER_LIST_REQUEST_COOLDOWN_MS = 30L * 1000L;
    private volatile long serversLoadedAt;
    private volatile long serversRequestedAt;
    /** 探测到本服代理名后的回调（用于补领早于探测到达的工会战）。 */
    private volatile java.util.function.Consumer<String> onDetected;

    public ServerTransfer(Plugin plugin, MainSettings conf) {
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
        try {
            Bukkit.getMessenger().registerIncomingPluginChannel(plugin, CHANNEL, this);
        } catch (Throwable t) {
            plugin.getLogger().warning("注册代理回执通道失败（无法自动探测本服登记名）: " + t.getMessage());
        }
    }

    public boolean available() {
        return registered && conf.transferEnabled;
    }

    /**
     * 有玩家在线时向代理查询本服的登记名。未探测到之前每次玩家加入都会重试，
     * 直到拿到结果（用于把 {@code arena.server} 的代理名
     * 与本服对应起来）。
     */
    public void detect(Player player) {
        if (player == null || !registered) {
            return;
        }
        if (proxyName == null) {
            send(player, "GetServer");
        }
        // 列表可能因代理新增/重载子服而变化：有玩家进服时按节流刷新，而不是只查一次。
        requestServers(player);
    }

    /** 本服代理登记名；未探测到返回 null。 */
    public String proxyName() {
        return proxyName;
    }

    /** 注册代理名探测回调（可多次覆盖）。 */
    public void onDetected(java.util.function.Consumer<String> callback) {
        this.onDetected = callback;
    }

    /** 代理登记的子服名（小写）；未探测到或尚未刷新到返回旧值。 */
    public List<String> proxyServers() {
        return proxyServers;
    }

    /** 代理中是否存在该子服名（大小写不敏感）；列表未知时返回 true（不做误报）。 */
    public boolean knowsServer(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        List<String> list = proxyServers;
        if (list.isEmpty()) {
            return true;
        }
        for (String s : list) {
            if (s.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
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

    private void send(Player player, String sub) {
        try {
            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            out.writeUTF(sub);
            player.sendPluginMessage(plugin, CHANNEL, out.toByteArray());
        } catch (Throwable t) {
            plugin.getLogger().warning("向代理发送 " + sub + " 失败: " + t.getMessage());
        }
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!CHANNEL.equals(channel)) {
            return;
        }
        try {
            ByteArrayDataInput in = ByteStreams.newDataInput(message);
            String sub = in.readUTF();
            switch (sub) {
                case "GetServer" -> {
                    String name = in.readUTF();
                    if (name != null && !name.isBlank()) {
                        boolean first = proxyName == null;
                        proxyName = name;
                        if (!name.equalsIgnoreCase(conf.serverId)) {
                            plugin.getLogger().warning("server-id(" + conf.serverId + ") 与代理登记名(" + name
                                    + ") 不一致；工会战承办服按代理名判断，跨服传送与承办已按代理名 " + name
                                    + " 处理。建议把 server.yml 的 server-id 改为 " + name + " 以保持一致。");
                        } else {
                            plugin.getLogger().info("代理登记名校验通过：" + name);
                        }
                        if (first && onDetected != null) {
                            onDetected.accept(name);
                        }
                    }
                    requestServers(player);
                }
                case "GetServers" -> {
                    List<String> list = new ArrayList<>();
                    while (true) {
                        String s;
                        try {
                            s = in.readUTF();
                        } catch (Throwable eof) {
                            break;
                        }
                        if (s == null || s.isEmpty()) {
                            break;
                        }
                        list.add(s.toLowerCase(Locale.ROOT));
                    }
                    if (!list.isEmpty()) {
                        proxyServers = list;
                        serversLoadedAt = System.currentTimeMillis();
                    }
                }
                default -> {
                }
            }
        } catch (Throwable ignored) {
            // 其它插件的同通道消息格式不同，忽略即可。
        }
    }

    private void requestServers(Player player) {
        long now = System.currentTimeMillis();
        if (!shouldRequestServers(proxyServers.isEmpty(), serversLoadedAt, serversRequestedAt, now)) {
            return;
        }
        serversRequestedAt = now;
        send(player, "GetServers");
    }

    /** 纯函数：列表为空或过期，且距上次请求已超过冷却时间。 */
    static boolean shouldRequestServers(boolean listEmpty, long loadedAt, long requestedAt, long now) {
        boolean stale = listEmpty || now - loadedAt >= SERVER_LIST_REFRESH_MS;
        return stale && now - requestedAt >= SERVER_LIST_REQUEST_COOLDOWN_MS;
    }
}
