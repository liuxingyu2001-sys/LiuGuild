package com.liu.liuguild.config;

import org.bukkit.configuration.file.FileConfiguration;

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
    /** mcMMO 兼容总开关。 */
    public boolean mcmmoEnabled = true;
    /** 工会战期间静默屏蔽 mcMMO 技能/能力。 */
    public boolean mcmmoSuppressAbilities = true;
    /** 工会战期间静默屏蔽 mcMMO 技能经验。 */
    public boolean mcmmoSuppressXp = false;

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
    /** 订阅事件：默认 33554432（群聊@/C2C）；需要频道@消息再加 512。 */
    public int qqBotIntents = 33554432;
    /** 主动推送目标：channel / group / c2c；留空只响应@消息。 */
    public String qqBotPushType = "";
    public String qqBotPushId = "";
    public String qqBotCommandPrefix = "!";
    public int qqBotReconnectSeconds = 5;

    /** LiuChat 工会频道消息格式（占位符由 LiuChat 提供）。 */
    public String guildChannelFormat = "&a[${channel_name}] &f${player}&7: ${message}";

    public static MainSettings parse(FileConfiguration c) {
        MainSettings s = new MainSettings();
        s.language = c.getString("settings.language", s.language);
        s.autoReload = c.getBoolean("settings.auto-reload-config", s.autoReload);
        s.debug = c.getBoolean("settings.debug", s.debug);
        // server-id 只从每服的 server.yml 读取（见 ConfigManager#applyServerOverride）。
        // 跨服段只从 cross-server.yml 读取，config.yml 里的同名键不再生效。
        s.mcmmoEnabled = c.getBoolean("hooks.mcmmo.enabled", s.mcmmoEnabled);
        s.mcmmoSuppressAbilities = c.getBoolean("hooks.mcmmo.suppress-abilities-in-war", s.mcmmoSuppressAbilities);
        s.mcmmoSuppressXp = c.getBoolean("hooks.mcmmo.suppress-xp-in-war", s.mcmmoSuppressXp);
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
        s.webhookEnabled = c.getBoolean("hooks.webhook.enabled", s.webhookEnabled);
        s.webhookUrl = c.getString("hooks.webhook.url", s.webhookUrl == null ? "" : s.webhookUrl);
        s.webhookFormat = c.getString("hooks.webhook.format", s.webhookFormat);
        s.webhookUsername = c.getString("hooks.webhook.username", s.webhookUsername);
        s.webhookTimeoutSeconds = Math.max(1, Math.min(30,
                c.getInt("hooks.webhook.timeout-seconds", s.webhookTimeoutSeconds)));
        s.webhookRetries = Math.max(0, Math.min(5,
                c.getInt("hooks.webhook.retries", s.webhookRetries)));
        s.webhookWarResult = c.getBoolean("hooks.webhook.events.war-result", s.webhookWarResult);
        s.webhookSeasonResult = c.getBoolean("hooks.webhook.events.season-result", s.webhookSeasonResult);
        s.webhookGuildLevel = c.getBoolean("hooks.webhook.events.guild-level-up", s.webhookGuildLevel);
        s.webhookGuildCreate = c.getBoolean("hooks.webhook.events.guild-create", s.webhookGuildCreate);
        s.webhookGuildDisband = c.getBoolean("hooks.webhook.events.guild-disband", s.webhookGuildDisband);
        s.webhookApplication = c.getBoolean("hooks.webhook.events.application", s.webhookApplication);
        s.qqBotEnabled = c.getBoolean("hooks.qqbot.enabled", s.qqBotEnabled);
        s.qqBotAppId = c.getString("hooks.qqbot.app-id", s.qqBotAppId == null ? "" : s.qqBotAppId);
        s.qqBotClientSecret = c.getString("hooks.qqbot.client-secret",
                s.qqBotClientSecret == null ? "" : s.qqBotClientSecret);
        s.qqBotSandbox = c.getBoolean("hooks.qqbot.sandbox", s.qqBotSandbox);
        s.qqBotIntents = Math.max(1, c.getInt("hooks.qqbot.intents", s.qqBotIntents));
        s.qqBotPushType = c.getString("hooks.qqbot.push.type", s.qqBotPushType == null ? "" : s.qqBotPushType);
        s.qqBotPushId = c.getString("hooks.qqbot.push.id", s.qqBotPushId == null ? "" : s.qqBotPushId);
        s.qqBotCommandPrefix = c.getString("hooks.qqbot.command-prefix", s.qqBotCommandPrefix);
        s.qqBotReconnectSeconds = Math.max(1, Math.min(60,
                c.getInt("hooks.qqbot.reconnect-seconds", s.qqBotReconnectSeconds)));
        s.guildChannelFormat = c.getString("channels.guild-format", s.guildChannelFormat);
        return s;
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
