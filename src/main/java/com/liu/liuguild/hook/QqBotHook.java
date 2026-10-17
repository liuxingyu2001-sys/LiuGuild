package com.liu.liuguild.hook;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.liu.liuguild.LiuGuild;
import com.liu.liuguild.model.Guild;
import com.liu.liuguild.model.GuildWar;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * QQ 官方机器人（QQ 开放平台）WebSocket 接入。
 *
 * <p>负责获取 access_token、连接网关、Identify/Resume、心跳与断线重连，
 * 并响应群聊/C2C/频道里的 @机器人 指令。主动推送复用 {@link WebhookHook} 的事件文本。</p>
 *
 * <p>协议线程不直接访问 Bukkit 数据：指令解析后通过 {@code plugin.sync} 回主线程生成回复，
 * HTTP/WebSocket 均在独立线程执行。</p>
 */
public final class QqBotHook {

    private static final String TOKEN_URL = "https://bots.qq.com/app/getAppAccessToken";

    private final LiuGuild plugin;
    private final com.google.gson.Gson gson = new com.google.gson.Gson();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "LiuGuild-QQBot");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final AtomicBoolean connecting = new AtomicBoolean();
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean();
    private final AtomicLong lastSeq = new AtomicLong();
    private final AtomicInteger msgSeq = new AtomicInteger(1);

    private volatile WebSocket socket;
    private volatile String accessToken;
    private volatile long tokenExpireAt;
    private volatile String sessionId;
    private volatile boolean ready;
    private volatile ScheduledFuture<?> heartbeat;

    public QqBotHook(LiuGuild plugin) {
        this.plugin = plugin;
    }

    public boolean enabled() {
        var s = plugin.settings().main;
        return s.qqBotEnabled && s.qqBotAppId != null && !s.qqBotAppId.isBlank()
                && s.qqBotClientSecret != null && !s.qqBotClientSecret.isBlank();
    }

    public void start() {
        if (!plugin.settings().main.qqBotEnabled) {
            return;
        }
        if (!enabled()) {
            plugin.getLogger().warning("QQ 机器人已启用，但 hooks.qqbot.app-id / client-secret 未填写，已跳过");
            return;
        }
        stopped.set(false);
        ensureToken(this::connect);
    }

    public void stop() {
        stopped.set(true);
        cancelHeartbeat();
        WebSocket ws = socket;
        socket = null;
        if (ws != null) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "plugin disable");
            } catch (Throwable ignored) {
            }
            ws.abort();
        }
        scheduler.shutdownNow();
    }

    /** 主动推送文本到配置的 channel/group/c2c；未配置目标则静默。 */
    public void push(String content) {
        if (!enabled() || content == null || content.isBlank()) {
            return;
        }
        var s = plugin.settings().main;
        String type = s.qqBotPushType == null ? "" : s.qqBotPushType.toLowerCase(Locale.ROOT);
        if (type.isBlank() || s.qqBotPushId == null || s.qqBotPushId.isBlank()) {
            return;
        }
        String text = content.length() > 1800 ? content.substring(0, 1800) : content;
        String prefixed = "[" + plugin.serverId() + "] " + text;
        ensureToken(() -> sendApi(new Context(type, s.qqBotPushId, null), prefixed));
    }

    private void ensureToken(Runnable then) {
        if (stopped.get()) {
            return;
        }
        if (accessToken != null && tokenExpireAt > System.currentTimeMillis() + 30_000L) {
            then.run();
            return;
        }
        if (!refreshing.compareAndSet(false, true)) {
            scheduler.schedule(() -> ensureToken(then), 500, TimeUnit.MILLISECONDS);
            return;
        }
        JsonObject body = new JsonObject();
        body.addProperty("appId", plugin.settings().main.qqBotAppId);
        body.addProperty("clientSecret", plugin.settings().main.qqBotClientSecret);
        HttpRequest req = HttpRequest.newBuilder(URI.create(TOKEN_URL))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .orTimeout(15, TimeUnit.SECONDS)
                .whenComplete((resp, err) -> {
                    refreshing.set(false);
                    if (err != null || resp == null || resp.statusCode() / 100 != 2) {
                        plugin.getLogger().warning("QQ 机器人获取 access_token 失败"
                                + (err == null ? "（HTTP " + (resp == null ? "?" : resp.statusCode()) + "）"
                                : "（" + err.getClass().getSimpleName() + "）"));
                        scheduleReconnect();
                        return;
                    }
                    try {
                        JsonObject json = JsonParser.parseString(resp.body()).getAsJsonObject();
                        String token = json.has("access_token") ? json.get("access_token").getAsString() : null;
                        if (token == null || token.isBlank()) {
                            plugin.getLogger().warning("QQ 机器人 token 响应缺少 access_token");
                            scheduleReconnect();
                            return;
                        }
                        long expires = json.has("expires_in") ? json.get("expires_in").getAsLong() : 7200L;
                        accessToken = token;
                        tokenExpireAt = System.currentTimeMillis() + Math.max(60, expires - 60) * 1000L;
                        then.run();
                    } catch (Throwable t) {
                        plugin.getLogger().warning("QQ 机器人 token 解析失败: " + t.getMessage());
                        scheduleReconnect();
                    }
                });
    }

    private void connect() {
        if (stopped.get() || !connecting.compareAndSet(false, true)) {
            return;
        }
        String base = plugin.settings().main.qqBotSandbox
                ? "wss://sandbox.api.sgroup.qq.com/websocket"
                : "wss://api.sgroup.qq.com/websocket";
        http.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(URI.create(base), new Listener())
                .whenComplete((ws, err) -> {
                    connecting.set(false);
                    if (err != null || ws == null) {
                        plugin.getLogger().warning("QQ 机器人 WebSocket 连接失败: "
                                + (err == null ? "未知错误" : err.getClass().getSimpleName()));
                        scheduleReconnect();
                        return;
                    }
                    socket = ws;
                });
    }

    private void scheduleReconnect() {
        if (stopped.get() || !reconnectScheduled.compareAndSet(false, true)) {
            return;
        }
        long delay = Math.max(1, plugin.settings().main.qqBotReconnectSeconds);
        scheduler.schedule(() -> {
            reconnectScheduled.set(false);
            if (stopped.get()) {
                return;
            }
            ensureToken(this::connect);
        }, delay, TimeUnit.SECONDS);
    }

    private void cancelHeartbeat() {
        ScheduledFuture<?> hb = heartbeat;
        heartbeat = null;
        if (hb != null) {
            hb.cancel(false);
        }
    }

    private void startHeartbeat(long intervalMs) {
        cancelHeartbeat();
        long interval = Math.max(1000, intervalMs);
        heartbeat = scheduler.scheduleAtFixedRate(() -> {
            try {
                if (socket != null && !socket.isOutputClosed()) {
                    socket.sendText(QqBotProtocol.heartbeat(lastSeq.get()), true);
                }
            } catch (Throwable ignored) {
            }
        }, interval, interval, TimeUnit.MILLISECONDS);
    }

    private void identify(WebSocket ws) {
        if (sessionId != null && !sessionId.isBlank() && lastSeq.get() > 0) {
            ws.sendText(QqBotProtocol.resume(accessToken, sessionId, lastSeq.get()), true);
        } else {
            ws.sendText(QqBotProtocol.identify(accessToken, plugin.settings().main.qqBotIntents), true);
        }
    }

    private void onFrame(String raw) {
        JsonObject frame;
        try {
            frame = JsonParser.parseString(raw).getAsJsonObject();
        } catch (Throwable t) {
            return;
        }
        int op = frame.has("op") ? frame.get("op").getAsInt() : -1;
        if (frame.has("s") && !frame.get("s").isJsonNull()) {
            lastSeq.set(frame.get("s").getAsLong());
        }
        if (op == 10) {
            long interval = 30000;
            if (frame.has("d") && frame.get("d").isJsonObject()) {
                JsonObject d = frame.getAsJsonObject("d");
                if (d.has("heartbeat_interval")) {
                    interval = d.get("heartbeat_interval").getAsLong();
                }
            }
            startHeartbeat(interval);
            WebSocket ws = socket;
            if (ws != null) {
                identify(ws);
            }
            return;
        }
        if (op == 11) {
            return;
        }
        if (op == 1) {
            // 服务端要求立即心跳
            WebSocket ws = socket;
            if (ws != null) {
                ws.sendText(QqBotProtocol.heartbeat(lastSeq.get()), true);
            }
            return;
        }
        if (op == 7) {
            reconnectNow();
            return;
        }
        if (op == 9) {
            sessionId = null;
            lastSeq.set(0);
            reconnectNow();
            return;
        }
        if (op != 0) {
            return;
        }
        String type = frame.has("t") ? frame.get("t").getAsString() : "";
        if (!frame.has("d") || !frame.get("d").isJsonObject()) {
            return;
        }
        JsonObject d = frame.getAsJsonObject("d");
        if ("READY".equals(type)) {
            if (d.has("session_id")) {
                sessionId = d.get("session_id").getAsString();
            }
            ready = true;
            plugin.getLogger().info("QQ 机器人已连接（WebSocket READY）");
            return;
        }
        if ("RESUMED".equals(type)) {
            ready = true;
            return;
        }
        if ("AT_MESSAGE_CREATE".equals(type) || "DIRECT_MESSAGE_CREATE".equals(type)) {
            String channel = string(d, "channel_id");
            if (channel != null && !channel.isBlank()) {
                handleMessage(new Context("channel", channel, string(d, "id")), d);
            }
            return;
        }
        if ("GROUP_AT_MESSAGE_CREATE".equals(type)) {
            String group = string(d, "group_openid");
            if (group != null && !group.isBlank()) {
                handleMessage(new Context("group", group, string(d, "id")), d);
            }
            return;
        }
        if ("C2C_MESSAGE_CREATE".equals(type)) {
            String openid = null;
            if (d.has("author") && d.get("author").isJsonObject()) {
                JsonObject a = d.getAsJsonObject("author");
                openid = string(a, "user_openid");
                if (openid == null) {
                    openid = string(a, "id");
                }
            }
            if (openid != null && !openid.isBlank()) {
                handleMessage(new Context("c2c", openid, string(d, "id")), d);
            }
        }
    }

    private void handleMessage(Context ctx, JsonObject d) {
        String content = string(d, "content");
        if (content == null || content.isBlank()) {
            return;
        }
        String cleaned = content.replaceFirst("^\\s*<@!?\\d+>\\s*", "").trim();
        String prefix = plugin.settings().main.qqBotCommandPrefix;
        if (prefix == null) {
            prefix = "!";
        }
        if (!cleaned.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) {
            return;
        }
        String command = cleaned.substring(prefix.length()).trim();
        if (command.isBlank()) {
            return;
        }
        final String cmd = command;
        if (!plugin.isEnabled()) {
            return;
        }
        plugin.sync(() -> {
            String reply = execute(cmd);
            if (reply != null && !reply.isBlank()) {
                String text = reply.length() > 1800 ? reply.substring(0, 1800) : reply;
                ensureToken(() -> sendApi(ctx, text));
            }
        });
    }

    /** 主线程执行查询指令。 */
    private String execute(String command) {
        String[] p = command.split("\\s+", 2);
        String root = p[0].toLowerCase(Locale.ROOT);
        String arg = p.length > 1 ? p[1].trim() : "";
        return switch (root) {
            case "help", "帮助" -> """
                    【LiuGuild 指令】
                    !guild <工会名>  查询工会
                    !war <工会名>    查询工会战
                    !top             资金排行
                    !help            本帮助""";
            case "guild", "g" -> guildInfo(arg);
            case "war", "战况" -> warInfo(arg);
            case "top", "排行" -> topInfo();
            default -> "未知指令，发送 " + plugin.settings().main.qqBotCommandPrefix + "help 查看帮助";
        };
    }

    private String guildInfo(String name) {
        if (name.isBlank()) {
            return "用法：!guild <工会名>";
        }
        Guild g = plugin.guilds().guildByName(name);
        if (g == null) {
            return "找不到工会：" + name;
        }
        return "【工会】" + g.name() + " [" + g.tag() + "]\n"
                + "等级：" + g.level() + "｜资金：" + g.points() + "｜银行：" + g.bank() + "\n"
                + "成员：" + g.memberCount() + "｜战绩：" + g.wins() + " 胜 " + g.losses() + " 负\n"
                + "会长：" + g.leaderName() + "｜" + (g.open() ? "开放加入" : "邀请制");
    }

    private String warInfo(String name) {
        Guild g;
        if (name.isBlank()) {
            g = null;
        } else {
            g = plugin.guilds().guildByName(name);
            if (g == null) {
                return "找不到工会：" + name;
            }
        }
        GuildWar war = null;
        String queued = null;
        if (g != null) {
            war = plugin.war() == null ? null : plugin.war().warOf(g.id());
            queued = plugin.war() == null ? null : plugin.war().queuedMode(g.id());
        }
        if (war == null) {
            if (queued != null) {
                return "【战况】" + g.name() + " 正在排队：" + queued;
            }
            return g == null ? "请指定工会名：!war <工会名>" : "【战况】" + g.name() + " 当前没有进行中的工会战";
        }
        boolean home = war.guildA().equals(g.id());
        return "【战况】" + war.nameA() + " " + war.scoreA() + " : " + war.scoreB() + " " + war.nameB() + "\n"
                + "阶段：" + war.state().display() + "｜承办服：" + war.hostServer() + "\n"
                + "你查询的一方：" + g.name() + "｜比分：" + (home ? war.scoreA() : war.scoreB());
    }

    private String topInfo() {
        List<Guild> list = new ArrayList<>(plugin.guilds().all());
        list.sort(Comparator.comparingLong((Guild g) -> g.points()).reversed());
        StringBuilder sb = new StringBuilder("【资金排行】");
        for (int i = 0; i < Math.min(5, list.size()); i++) {
            Guild g = list.get(i);
            sb.append("\n#").append(i + 1).append(" ").append(g.name())
                    .append("：").append(g.points());
        }
        if (list.isEmpty()) {
            sb.append("\n暂无工会");
        }
        return sb.toString();
    }

    private void reconnectNow() {
        WebSocket ws = socket;
        socket = null;
        ready = false;
        if (ws != null) {
            try {
                ws.abort();
            } catch (Throwable ignored) {
            }
        }
        scheduleReconnect();
    }

    private void sendApi(Context ctx, String content) {
        if (stopped.get() || accessToken == null || ctx == null) {
            return;
        }
        var s = plugin.settings().main;
        String base = s.qqBotSandbox ? "https://sandbox.api.sgroup.qq.com" : "https://api.sgroup.qq.com";
        String path;
        JsonObject body = new JsonObject();
        body.addProperty("content", content);
        if ("group".equals(ctx.type)) {
            path = "/v2/groups/" + encode(ctx.id) + "/messages";
            body.addProperty("msg_type", 0);
        } else if ("c2c".equals(ctx.type)) {
            path = "/v2/users/" + encode(ctx.id) + "/messages";
            body.addProperty("msg_type", 0);
        } else {
            path = "/channels/" + encode(ctx.id) + "/messages";
        }
        if (ctx.msgId != null && !ctx.msgId.isBlank()) {
            body.addProperty("msg_id", ctx.msgId);
            if (!"channel".equals(ctx.type)) {
                body.addProperty("msg_seq", msgSeq.getAndIncrement());
            }
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Authorization", "QQBot " + accessToken)
                .header("X-Union-Appid", plugin.settings().main.qqBotAppId)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .orTimeout(15, TimeUnit.SECONDS)
                .whenComplete((resp, err) -> {
                    if (err != null) {
                        plugin.getLogger().warning("QQ 机器人发送消息失败: " + err.getClass().getSimpleName());
                        return;
                    }
                    if (resp != null && resp.statusCode() == 401) {
                        accessToken = null;
                        ensureToken(() -> sendApi(ctx, content));
                        return;
                    }
                    if (resp != null && resp.statusCode() / 100 != 2) {
                        plugin.getLogger().warning("QQ 机器人发送消息失败：HTTP " + resp.statusCode());
                    }
                });
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String string(JsonObject o, String key) {
        try {
            return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 回复目标。 */
    private record Context(String type, String id, String msgId) {
    }

    private final class Listener implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            socket = webSocket;
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String raw = buffer.toString();
                buffer.setLength(0);
                try {
                    onFrame(raw);
                } catch (Throwable t) {
                    plugin.getLogger().warning("QQ 机器人处理消息失败: " + t.getMessage());
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            ready = false;
            socket = null;
            plugin.getLogger().info("QQ 机器人连接关闭（" + statusCode + "），准备重连");
            scheduleReconnect();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            ready = false;
            socket = null;
            plugin.getLogger().warning("QQ 机器人连接异常: " + error.getClass().getSimpleName());
            scheduleReconnect();
        }
    }
}
