package com.liu.liuguild.hook;

import com.liu.liuguild.LiuGuild;
import com.liu.liuguild.db.WarPlayerStat;
import com.liu.liuguild.model.Guild;
import com.liu.liuguild.model.GuildWar;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Discord / 通用 JSON webhook。
 *
 * <p>只发送文本摘要，不包含数据库密码、Redis 凭据或完整 URL。HTTP 请求在
 * HttpClient 自己的线程执行，不阻塞主线程；失败按配置重试。</p>
 */
public final class WebhookHook {

    private final LiuGuild plugin;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public WebhookHook(LiuGuild plugin) {
        this.plugin = plugin;
    }

    public boolean enabled() {
        var s = plugin.settings().main;
        return s.webhookEnabled && s.webhookUrl != null && !s.webhookUrl.isBlank();
    }

    public void warResult(GuildWar war, String winner, List<WarPlayerStat> stats, WarPlayerStat mvp) {
        if (!plugin.settings().main.webhookWarResult || war == null) {
            return;
        }
        String winnerName = winner == null || winner.isBlank() ? "平局"
                : (winner.equals(war.guildA()) ? war.nameA() : war.nameB());
        StringBuilder sb = new StringBuilder();
        sb.append("**工会战结束**\n")
                .append(war.nameA()).append(" ").append(war.scoreA()).append(" : ")
                .append(war.scoreB()).append(" ").append(war.nameB()).append("\n")
                .append("结果：").append(winnerName)
                .append("｜承办服：").append(war.hostServer());
        if (mvp != null) {
            sb.append("\nMVP：").append(mvp.playerName())
                    .append("（击杀 ").append(mvp.kills())
                    .append(" / 死亡 ").append(mvp.deaths())
                    .append(" / 存活 ").append(mvp.survivedSeconds()).append("s）");
        }
        send(sb.toString());
    }

    public void seasonResult(String mode, String host, List<String> standingLines) {
        if (!plugin.settings().main.webhookSeasonResult) {
            return;
        }
        StringBuilder sb = new StringBuilder("**赛季混战结束**\n")
                .append("模式：").append(mode).append("｜承办服：").append(host);
        if (standingLines != null) {
            for (String line : standingLines) {
                sb.append("\n").append(line);
            }
        }
        send(sb.toString());
    }

    public void guildLevelUp(Guild guild, int oldLevel, int newLevel) {
        if (!plugin.settings().main.webhookGuildLevel) {
            return;
        }
        send("**工会升级**\n" + guild.name() + "：" + oldLevel + " → " + newLevel + " 级");
    }

    public void guildCreate(Guild guild, String actor) {
        if (!plugin.settings().main.webhookGuildCreate) {
            return;
        }
        send("**新建工会**\n" + guild.name() + " [" + guild.tag() + "]｜会长：" + actor);
    }

    public void guildDisband(Guild guild, String cause) {
        if (!plugin.settings().main.webhookGuildDisband) {
            return;
        }
        send("**工会解散**\n" + guild.name() + "｜原因：" + cause);
    }

    public void application(Guild guild, String player, String message) {
        if (!plugin.settings().main.webhookApplication) {
            return;
        }
        send("**新的入会申请**\n" + player + " → " + guild.name()
                + (message == null || message.isBlank() ? "" : "：" + message));
    }

    /** 发送一条文本；调用方可来自任意线程。 */
    public void send(String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        if (plugin.qqBot() != null) {
            plugin.qqBot().push(content);
        }
        if (!enabled()) {
            return;
        }
        String text = "[" + plugin.serverId() + "] " + content;
        if (text.length() > 1900) {
            text = text.substring(0, 1900);
        }
        String payload = payload(text);
        attempt(payload, 0);
    }

    private void attempt(String payload, int attempt) {
        var s = plugin.settings().main;
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(s.webhookUrl))
                    .timeout(Duration.ofSeconds(Math.max(1, s.webhookTimeoutSeconds)))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();
        } catch (Throwable t) {
            plugin.getLogger().warning("Webhook URL 无效，已跳过（请检查 hooks.webhook.url）");
            return;
        }
        client.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .orTimeout(Math.max(1, s.webhookTimeoutSeconds) + 2L, TimeUnit.SECONDS)
                .whenComplete((response, error) -> {
                    boolean ok = error == null && response != null
                            && response.statusCode() >= 200 && response.statusCode() < 300;
                    if (ok) {
                        return;
                    }
                    if (attempt < Math.max(0, s.webhookRetries)) {
                        long delay = 1L << Math.min(4, attempt);
                        CompletableFuture.delayedExecutor(delay, TimeUnit.SECONDS)
                                .execute(() -> attempt(payload, attempt + 1));
                    } else {
                        plugin.getLogger().warning("Webhook 推送失败"
                                + (error == null ? "（HTTP " + (response == null ? "?" : response.statusCode()) + "）"
                                : "（" + error.getClass().getSimpleName() + "）"));
                    }
                });
    }

    private String payload(String content) {
        return WebhookPayload.build(plugin.settings().main.webhookFormat,
                plugin.settings().main.webhookUsername, content);
    }
}
