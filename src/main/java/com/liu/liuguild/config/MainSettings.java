package com.liu.liuguild.config;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 主配置模块（config.yml：settings / hooks / channels；跨服段只来自 cross-server.yml，server-id 来自每服 server.yml）。 */
public final class MainSettings {

    public String language = "zh_CN";
    public boolean autoReload = true;
    public boolean debug = false;
    public String serverId = "";

    public boolean crossServerEnabled = true;
    public String redisHost = "127.0.0.1";
    public int redisPort = 6379;
    public String redisPassword = "";
    public int redisDatabase = 0;
    public int redisPoolSize = 8;
    public int redisTimeoutMs = 2000;
    public String redisChannel = "liuguild";
    public boolean transferEnabled = true;
    public int heartbeatSeconds = 5;
    // ---- 可选插件钩子 ----
    /** 工会战期间完全屏蔽 mcMMO：技能/能力与全部提示。 */
    public boolean mcmmoBlockInWar = true;

    // ---- 宠物（工会战屏蔽） ----
    /** 工会战期间屏蔽 mcPets/LiuPets 与 MyPet 的召唤、骑乘、技能和已有宠物。 */
    public boolean petsBlockInWar = true;

    // ---- liupass（战令） ----
    /** 是否向 Liupass 上报工会玩法任务进度（需 Liupass 1.6.3+ 扩展任务类型）。 */
    public boolean passEnabled = true;
    public boolean passJoin = true;
    public boolean passCreate = true;
    public boolean passWar = true;
    public boolean passWarWin = true;
    public boolean passWarKill = true;
    public boolean passCheckin = true;
    public boolean passDonate = true;
    /** 工会银行存入推进任务；存取循环可能刷进度，默认关闭。 */
    public boolean passBankDeposit = false;
    /** 是否继续支持旧版按任务 ID 绑定的 tasks.yml。 */
    public boolean passLegacyTaskIds = true;

    // ---- Webhook（Discord/通用 JSON） ----
    public boolean webhookEnabled = false;
    public String webhookUrl = "";
    /** discord / generic；generic 发送 {"content": "..."}。 */
    public String webhookFormat = "discord";
    public String webhookUsername = "LiuGuild";
    public int webhookTimeoutSeconds = 5;
    public int webhookRetries = 2;
    public boolean webhookWarResult = true;
    public boolean webhookSeasonResult = true;
    public boolean webhookGuildLevel = true;
    public boolean webhookGuildCreate = false;
    public boolean webhookGuildDisband = true;
    public boolean webhookApplication = true;

    // ---- QQ 官方机器人（WebSocket） ----
    public boolean qqBotEnabled = false;
    public String qqBotAppId = "";
    public String qqBotClientSecret = "";
    public boolean qqBotSandbox = false;
    /** 自定义网关；留空自动请求 /gateway，失败回退内置地址。 */
    public String qqBotGateway = "";
    /** 由 intents 名称 + intents-bits 计算出的位掩码。 */
    public int qqBotIntents = 33554432;
    public int qqBotReconnectSeconds = 5;
    public int qqBotReconnectMaxSeconds = 60;
    public int qqBotTimeoutSeconds = 15;
    public boolean qqBotReceiveEnabled = true;
    public Set<String> qqBotReceiveChannels = Set.of();
    public boolean qqBotLogTargets = true;
    public int qqBotReceiveMaxLength = 300;
    public boolean qqBotSendEnabled = false;
    /** group:openid / c2c:openid / channel:id / direct:id；无前缀按 group。 */
    public List<String> qqBotSendTargets = List.of();
    public int qqBotSendIntervalMs = 1000;
    public int qqBotSendMaxLength = 2000;
    public boolean qqBotPassiveReply = true;
    public String qqBotCommandPrefix = "!";

    /** LiuChat 工会频道消息格式（占位符由 LiuChat 提供）。 */
    public String guildChannelFormat = "&a[${channel_name}] &f${player}&7: ${message}";

    public static MainSettings parse(FileConfiguration c) {
        MainSettings s = new MainSettings();
        s.language = c.getString("settings.language", s.language);
        s.autoReload = c.getBoolean("settings.auto-reload-config", s.autoReload);
        s.debug = c.getBoolean("settings.debug", s.debug);
        // server-id 只从每服的 server.yml 读取（见 ConfigManager#applyServerOverride）。
        // 跨服段只从 cross-server.yml 读取，config.yml 里的同名键不再生效。
        s.mcmmoBlockInWar = c.getBoolean("hooks.mcmmo.block-in-war", s.mcmmoBlockInWar);
        s.petsBlockInWar = c.getBoolean("hooks.pets.block-in-war", s.petsBlockInWar);
        s.passEnabled = c.getBoolean("hooks.liupass.enabled", s.passEnabled);
        s.passJoin = c.getBoolean("hooks.liupass.join", s.passJoin);
        s.passCreate = c.getBoolean("hooks.liupass.create", s.passCreate);
        s.passWar = c.getBoolean("hooks.liupass.war", s.passWar);
        s.passWarWin = c.getBoolean("hooks.liupass.war-win", s.passWarWin);
        s.passWarKill = c.getBoolean("hooks.liupass.war-kill", s.passWarKill);
        s.passCheckin = c.getBoolean("hooks.liupass.checkin", s.passCheckin);
        s.passDonate = c.getBoolean("hooks.liupass.donate", s.passDonate);
        s.passBankDeposit = c.getBoolean("hooks.liupass.bank-deposit", s.passBankDeposit);
        s.passLegacyTaskIds = c.getBoolean("hooks.liupass.legacy-task-ids", s.passLegacyTaskIds);
        // webhook / qqbot 属于每服独立配置，只从 server.yml 读取（见 applyServerHooks）。
        s.guildChannelFormat = c.getString("channels.guild-format", s.guildChannelFormat);
        return s;
    }

    /**
     * 读取每服独立的 webhook / QQ 机器人配置（唯一来源：server.yml）。
     *
     * <p>这两项会连接外部服务或主动推送；如果放在共享 config.yml，多台子服会重复建连、
     * 重复推送。因此不从 config.yml 解析，server.yml 不存在对应段时保持默认关闭。</p>
     */
    public static void applyServerHooks(MainSettings s, FileConfiguration c) {
        if (s == null || c == null) {
            return;
        }
        if (c.isConfigurationSection("webhook")) {
            s.webhookEnabled = c.getBoolean("webhook.enabled", false);
            s.webhookUrl = c.getString("webhook.url", "");
            s.webhookFormat = c.getString("webhook.format", "discord");
            s.webhookUsername = c.getString("webhook.username", "LiuGuild");
            s.webhookTimeoutSeconds = Math.max(1, Math.min(30,
                    c.getInt("webhook.timeout-seconds", 5)));
            s.webhookRetries = Math.max(0, Math.min(5, c.getInt("webhook.retries", 2)));
            s.webhookWarResult = c.getBoolean("webhook.events.war-result", true);
            s.webhookSeasonResult = c.getBoolean("webhook.events.season-result", true);
            s.webhookGuildLevel = c.getBoolean("webhook.events.guild-level-up", true);
            s.webhookGuildCreate = c.getBoolean("webhook.events.guild-create", false);
            s.webhookGuildDisband = c.getBoolean("webhook.events.guild-disband", true);
            s.webhookApplication = c.getBoolean("webhook.events.application", true);
        }
        if (c.isConfigurationSection("qqbot")) {
            s.qqBotEnabled = c.getBoolean("qqbot.enable", c.getBoolean("qqbot.enabled", false));
            s.qqBotAppId = c.getString("qqbot.app-id", "");
            // 新配置用 app-secret；兼容旧 server.yml 的 client-secret。
            String secret = c.getString("qqbot.app-secret", "");
            if (secret == null || secret.isBlank()) {
                secret = c.getString("qqbot.client-secret", "");
            }
            s.qqBotClientSecret = secret == null ? "" : secret;
            s.qqBotSandbox = c.getBoolean("qqbot.sandbox", false);
            s.qqBotGateway = c.getString("qqbot.connection.gateway", "");
            s.qqBotReconnectSeconds = Math.max(1, Math.min(3600,
                    c.getInt("qqbot.connection.reconnect-seconds", 5)));
            s.qqBotReconnectMaxSeconds = Math.max(s.qqBotReconnectSeconds, Math.min(3600,
                    c.getInt("qqbot.connection.reconnect-max-seconds", 60)));
            s.qqBotTimeoutSeconds = Math.max(3, Math.min(120,
                    c.getInt("qqbot.connection.timeout-seconds", 15)));

            List<String> intentNames = c.getStringList("qqbot.intents");
            int bits = intentNames.isEmpty() ? numericIntent(c.get("qqbot.intents")) : intentBits(intentNames);
            bits |= Math.max(0, c.getInt("qqbot.intents-bits", 0));
            s.qqBotIntents = bits == 0 ? 33554432 : bits;

            s.qqBotReceiveEnabled = c.getBoolean("qqbot.receive.enable", true);
            s.qqBotReceiveChannels = Set.copyOf(readStrings(c, "qqbot.receive.channels"));
            s.qqBotLogTargets = c.getBoolean("qqbot.receive.log-targets", true);
            s.qqBotReceiveMaxLength = Math.max(1, Math.min(4000,
                    c.getInt("qqbot.receive.max-length", 300)));
            s.qqBotCommandPrefix = c.getString("qqbot.command-prefix", "!");

            boolean newSend = c.isConfigurationSection("qqbot.send");
            s.qqBotSendEnabled = c.getBoolean("qqbot.send.enable", false);
            List<String> targets = new ArrayList<>(readStrings(c, "qqbot.send.targets"));
            if (targets.isEmpty()) {
                // 兼容旧版 push.type/push.id。
                String type = c.getString("qqbot.push.type", "");
                String id = c.getString("qqbot.push.id", "");
                if (type != null && !type.isBlank() && id != null && !id.isBlank()) {
                    targets.add(type.toLowerCase(Locale.ROOT) + ":" + id);
                    if (!newSend) {
                        s.qqBotSendEnabled = true;
                    }
                }
            }
            s.qqBotSendTargets = List.copyOf(targets);
            s.qqBotSendIntervalMs = Math.max(0, Math.min(60000,
                    c.getInt("qqbot.send.interval-ms", 1000)));
            s.qqBotSendMaxLength = Math.max(1, Math.min(4000,
                    c.getInt("qqbot.send.max-length", 2000)));
            s.qqBotPassiveReply = c.getBoolean("qqbot.send.passive-reply", true);
        }
    }

    private static List<String> readStrings(FileConfiguration c, String path) {
        List<String> out = new ArrayList<>();
        for (String raw : c.getStringList(path)) {
            if (raw != null && !raw.isBlank()) {
                out.add(raw.strip());
            }
        }
        return out;
    }

    private static int numericIntent(Object raw) {
        if (raw instanceof Number n) {
            return n.intValue();
        }
        if (raw instanceof String str) {
            try {
                return Integer.parseInt(str.strip());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    /** 与 QQ 官方文档一致：命名 intent 转位掩码；未知名称忽略。 */
    static int intentBits(List<String> names) {
        int bits = 0;
        for (String raw : names) {
            String name = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
            switch (name) {
                case "group", "c2c" -> bits |= 1 << 25;
                case "guild" -> bits |= 1 << 30;
                case "guild-private" -> bits |= 1 << 9;
                case "direct" -> bits |= 1 << 12;
                case "all" -> bits |= (1 << 25) | (1 << 30) | (1 << 12);
                default -> {
                }
            }
        }
        return bits;
    }

    /** 读取跨服段（唯一来源：cross-server.yml）。 */
    public static void applyCrossServer(MainSettings s, FileConfiguration c) {
        if (c == null) {
            return;
        }
        s.crossServerEnabled = c.getBoolean("enabled", s.crossServerEnabled);
        s.redisHost = c.getString("redis.host", s.redisHost);
        s.redisPort = clamp(c.getInt("redis.port", s.redisPort), 1, 65535);
        s.redisPassword = c.getString("redis.password", s.redisPassword);
        s.redisDatabase = Math.max(0, c.getInt("redis.database", s.redisDatabase));
        s.redisPoolSize = clamp(c.getInt("redis.pool-size", s.redisPoolSize), 1, 64);
        s.redisTimeoutMs = Math.max(200, c.getInt("redis.timeout-ms", s.redisTimeoutMs));
        s.redisChannel = sanitize(c.getString("redis.channel", s.redisChannel), "liuguild");
        s.transferEnabled = c.getBoolean("transfer.enabled", s.transferEnabled);
        s.heartbeatSeconds = clamp(c.getInt("heartbeat-seconds", s.heartbeatSeconds), 2, 60);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static String sanitize(String v, String fallback) {
        if (v == null) {
            return fallback;
        }
        String out = v.trim().replace(":", "").replace(" ", "");
        return out.isEmpty() ? fallback : out;
    }
}
