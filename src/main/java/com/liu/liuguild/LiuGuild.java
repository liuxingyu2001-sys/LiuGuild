package com.liu.liuguild;

import com.liu.liuguild.api.GuildAPI;
import com.liu.liuguild.command.GuildAdminCommand;
import com.liu.liuguild.command.GuildCommand;
import com.liu.liuguild.config.ConfigManager;
import com.liu.liuguild.config.Messages;
import com.liu.liuguild.config.Settings;
import com.liu.liuguild.crossserver.CrossServerBus;
import com.liu.liuguild.crossserver.RedisBridge;
import com.liu.liuguild.crossserver.ServerTransfer;
import com.liu.liuguild.db.Database;
import com.liu.liuguild.db.DbConfig;
import com.liu.liuguild.db.GuildRepository;
import com.liu.liuguild.guild.GuildManager;
import com.liu.liuguild.gui.GuiManager;
import com.liu.liuguild.hook.GuildChatHook;
import com.liu.liuguild.hook.CraftEngineHook;
import com.liu.liuguild.hook.DungeonHook;
import com.liu.liuguild.hook.EconomyHook;
import com.liu.liuguild.hook.GuildPlaceholders;
import com.liu.liuguild.hook.LuckyPeachesHook;
import com.liu.liuguild.hook.NotBountiesHook;
import com.liu.liuguild.hook.PapiHook;
import com.liu.liuguild.hook.PassHook;
import com.liu.liuguild.hook.PlayerTitleHook;
import com.liu.liuguild.hook.SparrowSyncHook;
import com.liu.liuguild.importing.PlayerguildImporter;
import com.liu.liuguild.listener.GuildListener;
import com.liu.liuguild.listener.WarListener;
import com.liu.liuguild.module.ModuleRegistry;
import com.liu.liuguild.util.TimeUtil;
import com.liu.liuguild.war.KitService;
import com.liu.liuguild.war.WarManager;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * LiuGuild 主类：负责装配模块、软依赖钩子、命令与后台任务。
 * <p>所有软依赖（liudungeon / LiuChat / liupass / CraftEngine / Sparrow Sync / Vault / PAPI / PlayerTitle）
 * 均以反射方式访问，缺失时对应功能自动降级，不影响启动。</p>
 */
public final class LiuGuild extends JavaPlugin {

    private static LiuGuild instance;

    private ConfigManager configManager;
    private Database database;
    private GuildRepository repo;
    private com.liu.liuguild.audit.GuildAuditService audit;
    private com.liu.liuguild.mail.MailService mail;
    private com.liu.liuguild.application.GuildApplications applications;
    private com.liu.liuguild.hook.WebhookHook webhook;
    private com.liu.liuguild.hook.QqBotHook qqBot;
    private com.liu.liuguild.hook.CustomNameplatesHook customNameplates;
    private CrossServerBus bus;
    private GuildManager guilds;
    private WarManager war;
    private KitService kits;
    private GuiManager gui;
    private PlayerguildImporter importer;
    private com.liu.liuguild.buff.BuffService buffs;
    private com.liu.liuguild.war.SeasonService seasons;
    public com.liu.liuguild.war.SeasonService seasons() { return seasons; }
    private ModuleRegistry modules;

    private EconomyHook economy;
    private GuildChatHook guildChat;
    /** 启动配置检查的告警（供状态命令展示）。 */
    private final java.util.List<String> configWarnings = new java.util.ArrayList<>();
    private PassHook pass;
    private CraftEngineHook craftEngine;
    private DungeonHook dungeon;
    private SparrowSyncHook sparrow;
    private PapiHook papi;
    private PlayerTitleHook playerTitle;
    private LuckyPeachesHook luckyPeaches;
    private com.liu.liuguild.warehouse.WarehouseService warehouse;
    private com.liu.liuguild.activity.ActivityService activity;
    private com.liu.liuguild.title.TitleService titles;
    private com.liu.liuguild.shop.ShopService shop;
    private com.liu.liuguild.hook.LiuShopHook liuShop;
    private NotBountiesHook notBounties;
    private com.liu.liuguild.hook.McMmoHook mcmmo;
    private com.liu.liuguild.hook.GuildHomeHook guildHome;
    private com.liu.liuguild.hook.SparrowUiHook sparrowUi;
    private com.liu.liuguild.hook.LuckPermsHook luckPerms;
    private com.liu.liuguild.hook.PacketEventsHook packetEvents;

    private BukkitTask heartbeatTask;
    private BukkitTask configWatchTask;
    private BukkitTask presenceTask;
    private BukkitTask cleanupTask;
    private volatile long configFingerprint;
    /** 关服中：Bukkit 调度器已拒绝新任务，异步收尾改走自建线程。 */
    private volatile boolean shuttingDown;
    /** 关服收尾线程（单线程，daemon）：只执行数据库/文件类的清理，不碰 Bukkit 对象。 */
    private java.util.concurrent.ExecutorService teardownExecutor;

    public static LiuGuild get() {
        return instance;
    }

    // =====================================================================
    // 生命周期
    // =====================================================================

    @Override
    public void onEnable() {
        instance = this;
        shuttingDown = false;
        saveDefaultConfig();

        // 配置
        configManager = new ConfigManager(this);
        configManager.load();
        configFingerprint = configManager.fingerprint();

        // 数据库
        database = new Database(DbConfig.parse(configManager.file("database.yml")), getLogger());
        try {
            database.init();
        } catch (Exception e) {
            getLogger().severe("数据库初始化失败，插件无法启动: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        repo = new GuildRepository(database);
        audit = new com.liu.liuguild.audit.GuildAuditService(this);
        mail = new com.liu.liuguild.mail.MailService(this);
        applications = new com.liu.liuguild.application.GuildApplications(this);
        webhook = new com.liu.liuguild.hook.WebhookHook(this);
        qqBot = new com.liu.liuguild.hook.QqBotHook(this);
        customNameplates = new com.liu.liuguild.hook.CustomNameplatesHook(this);

        // 软依赖钩子（缺失自动降级）。对直接引用第三方类的钩子先做插件存在性判断，
        // 避免在缺少依赖时触发类初始化失败（虽然 safe() 能兜住，但会多一轮异常且无日志）。
        economy = hasPlugin("Vault") ? safe(EconomyHook::new) : null;
        pass = safe(() -> new PassHook(this));
        craftEngine = safe(CraftEngineHook::new);
        dungeon = hasPlugin("liudungeon") ? safe(() -> new DungeonHook(this)) : null;
        sparrow = safe(SparrowSyncHook::new);
        papi = hasPlugin("PlaceholderAPI") ? safe(PapiHook::new) : null;
        playerTitle = safe(PlayerTitleHook::new);
        luckyPeaches = safe(LuckyPeachesHook::new);
        notBounties = safe(() -> new NotBountiesHook(this));
        mcmmo = safe(() -> new com.liu.liuguild.hook.McMmoHook(this));
        guildHome = safe(() -> new com.liu.liuguild.hook.GuildHomeHook(this));
        sparrowUi = safe(() -> new com.liu.liuguild.hook.SparrowUiHook(this));
        if (sparrowUi != null) sparrowUi.init();
        packetEvents = hasPlugin("packetevents") ? safe(() -> new com.liu.liuguild.hook.PacketEventsHook(this)) : null;
        if (packetEvents != null) packetEvents.init();
        liuShop = safe(() -> new com.liu.liuguild.hook.LiuShopHook(this));
        if (liuShop != null) liuShop.init();

        // 核心管理器
        guilds = new GuildManager(this);
        kits = new KitService(this);
        kits.preload();
        war = new WarManager(this);
        luckPerms = hasPlugin("LuckPerms") ? safe(() -> new com.liu.liuguild.hook.LuckPermsHook(this)) : null;
        if (luckPerms != null) luckPerms.register();
        gui = new GuiManager(this);
        importer = new PlayerguildImporter(this);

        RedisBridge bridge = new RedisBridge(this, settings().main);
        ServerTransfer transfer = new ServerTransfer(this, settings().main);
        transfer.register();
        bus = new CrossServerBus(this, settings().main, bridge, transfer);
        transfer.onDetected(name -> war.onProxyNameDetected(name));

        // 模块注册（按 order 启停）
        modules = new ModuleRegistry(this);
        modules.register(war);
        seasons = new com.liu.liuguild.war.SeasonService(this);
        modules.register(seasons);
        modules.register(gui);
        modules.register(new GuildListener(this));
        modules.register(new WarListener(this));
        modules.register(new com.liu.liuguild.listener.WarFreezeListener(this));
        warehouse = new com.liu.liuguild.warehouse.WarehouseService(this);
        modules.register(warehouse);
        activity = new com.liu.liuguild.activity.ActivityService(this);
        modules.register(activity);
        titles = new com.liu.liuguild.title.TitleService(this);
        modules.register(titles);
        shop = new com.liu.liuguild.shop.ShopService(this);
        modules.register(shop);
        buffs = new com.liu.liuguild.buff.BuffService(this);
        modules.register(buffs);
        modules.register(new com.liu.liuguild.listener.GuildSyncModule(this));
        bus.addListener(modules::dispatchCrossServer);
        modules.enableAll();
        bus.startAsync();

        new com.liu.liuguild.listener.GuildTaskListener(this).start();
        if (mcmmo != null) mcmmo.register();
        guildChat = new GuildChatHook(this);
        guildChat.start();
        GuildAPI.init(this);
        if (pass != null) Bukkit.getPluginManager().registerEvents(pass, this);
        registerPapi();
        registerCommands();
        guilds.reloadAll();
        startTasks();
        syncDelayed(this::validateConfig, 60L);
        if (qqBot != null) {
            qqBot.start();
        }
        if (customNameplates != null) {
            customNameplates.start();
        }

        getLogger().info("LiuGuild 已启用：server-id=" + serverId()
                + "，存储=" + (database.isSqlite() ? "SQLite" : "MySQL")
                + "，模式=" + settings().war.modes.keySet()
                + "，场地=" + settings().arenas.arenas.keySet());
    }

    @Override
    public void onDisable() {
        // 先置关服标志：此后 async 不再走 Bukkit 调度器（插件已禁用时会抛
        // IllegalPluginAccessException，把 cleanup 链整条打断），改为自建线程收尾。
        shuttingDown = true;
        if (qqBot != null) qqBot.stop();
        if (customNameplates != null) customNameplates.stop();
        if (pass != null) pass.close();
        if (guildChat != null) guildChat.close();
        if (packetEvents != null) packetEvents.close();
        if (sparrowUi != null) sparrowUi.clearSessions();
        if (luckPerms != null) luckPerms.unregister();
        if (modules != null) {
            modules.disableAll();
        }
        if (bus != null) {
            bus.shutdown();
        }
        if (heartbeatTask != null) {
            heartbeatTask.cancel();
        }
        if (configWatchTask != null) {
            configWatchTask.cancel();
        }
        if (presenceTask != null) {
            presenceTask.cancel();
        }
        if (cleanupTask != null) {
            cleanupTask.cancel();
        }
        // 关服收尾线程里的删除只依赖数据库：必须在关池前排队完成。
        drainTeardown(5000L);
        if (guilds != null && database != null) {
            guilds.saveAllSync();
        }
        if (database != null) {
            database.shutdown();
        }
        instance = null;
    }

    // =====================================================================
    // 任务
    // =====================================================================

    private void startTasks() {
        int heartbeat = Math.max(2, settings().main.heartbeatSeconds);
        presenceTask = getServer().getScheduler().runTaskTimer(this, () -> {
            bus.tickPresence(Bukkit.getOnlinePlayers());
            guilds.purge();
            guildChat.refresh();
        }, 20L * heartbeat, 20L * heartbeat);
        if (settings().guild.cleanupEnabled) {
            long ticks = Math.max(20L * 60L, settings().guild.cleanupIntervalMinutes * 60L * 20L);
            cleanupTask = getServer().getScheduler().runTaskTimer(this,
                    () -> guilds.autoCleanup(), ticks, ticks);
        }
        if (settings().main.autoReload) {
            configWatchTask = getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
                long fp = configManager.fingerprint();
                if (fp != configFingerprint) {
                    // 共享目录下任一子服改动都会触发所有子服重载：只重载配置，不重读全部工会数据。
                    sync(this::reloadSettings);
                }
            }, 100L, 100L);
        }
    }

    /** 重载全部配置与模块（命令 / 自动热重载调用）。 */
    public void reloadAll() {
        reloadSettings();
        if (guilds != null) {
            guilds.reloadAll();
        }
    }

    /**
     * 仅重载配置与消息、重启定时任务；不重读数据库中的工会数据。
     * 适用于修改竞技场 / 套装 / 模式等配置后的热重载，避免无意义的全表加载。
     * <p>注意：不要命名为 {@code reloadConfig()}，否则会覆盖 {@code JavaPlugin.reloadConfig()}，
     * 导致 Bukkit 首次调用 {@code getConfig()} 时进入本方法而 NPE。</p>
     */
    public void reloadSettings() {
        if (configManager == null || shuttingDown) {
            return;
        }
        configManager.load();
        configFingerprint = configManager.fingerprint();
        if (kits != null) {
            kits.preload();
        }
        if (modules != null) {
            modules.reloadAll();
        }
        // 按新配置重建全局任务，避免清理开关和间隔继续沿用旧值。
        if (presenceTask != null) { presenceTask.cancel(); presenceTask = null; }
        if (cleanupTask != null) { cleanupTask.cancel(); cleanupTask = null; }
        if (configWatchTask != null) { configWatchTask.cancel(); configWatchTask = null; }
        startTasks();
    }

    // =====================================================================
    // 命令 / PAPI
    // =====================================================================

    private void registerCommands() {
        PluginCommand guild = getCommand("guild");
        if (guild != null) {
            GuildCommand cmd = new GuildCommand(this);
            guild.setExecutor(cmd);
            guild.setTabCompleter(cmd);
        }
        PluginCommand admin = getCommand("guildadmin");
        if (admin != null) {
            GuildAdminCommand cmd = new GuildAdminCommand(this);
            admin.setExecutor(cmd);
            admin.setTabCompleter(cmd);
        }
    }

    private void registerPapi() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
            return;
        }
        try {
            new GuildPlaceholders(this).register();
        } catch (Throwable t) {
            getLogger().warning("注册 PAPI 变量失败: " + t.getMessage());
        }
    }

    private <T> T safe(Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (Throwable t) {
            // 降级是预期行为，但不能完全静默：debug 日志里能看到是哪个可选组件初始化失败。
            getLogger().fine("可选组件初始化失败，已降级: " + t);
            return null;
        }
    }

    /** 软依赖是否已安装（不触发其类加载）。 */
    private boolean hasPlugin(String name) {
        return getServer().getPluginManager().getPlugin(name) != null;
    }

    // =====================================================================
    // 便捷访问
    // =====================================================================

    public ConfigManager configManager() { return configManager; }

    public Settings settings() {
        return configManager.settings();
    }

    public Messages messages() {
        return configManager.messages();
    }

    public ConfigManager configs() {
        return configManager;
    }

    public Database db() {
        return database;
    }

    public GuildRepository repo() {
        return repo;
    }

    public com.liu.liuguild.audit.GuildAuditService audit() {
        return audit;
    }

    public com.liu.liuguild.mail.MailService mail() {
        return mail;
    }

    public com.liu.liuguild.application.GuildApplications applications() {
        return applications;
    }

    public com.liu.liuguild.hook.WebhookHook webhook() {
        return webhook;
    }

    public com.liu.liuguild.hook.QqBotHook qqBot() {
        return qqBot;
    }

    public CrossServerBus bus() {
        return bus;
    }

    public GuildManager guilds() {
        return guilds;
    }

    public WarManager war() {
        return war;
    }

    public KitService kits() {
        return kits;
    }

    public GuiManager gui() {
        return gui;
    }

    public PlayerguildImporter importer() {
        return importer;
    }

    public com.liu.liuguild.buff.BuffService buffs() { return buffs; }

    public ModuleRegistry modules() {
        return modules;
    }

    public EconomyHook economy() {
        return economy;
    }

    public GuildChatHook guildChat() {
        return guildChat;
    }

    public PassHook pass() {
        return pass;
    }

    public CraftEngineHook craftEngine() {
        return craftEngine;
    }

    public DungeonHook dungeon() {
        return dungeon;
    }

    public SparrowSyncHook sparrow() {
        return sparrow;
    }

    public PapiHook papi() {
        return papi;
    }

    public com.liu.liuguild.hook.LuckPermsHook luckPerms() {
        return luckPerms;
    }

    public PlayerTitleHook playerTitle() {
        return playerTitle;
    }

    public LuckyPeachesHook luckyPeaches() {
        return luckyPeaches;
    }

    public com.liu.liuguild.warehouse.WarehouseService warehouse() {
        return warehouse;
    }

    public com.liu.liuguild.activity.ActivityService activity() {
        return activity;
    }

    public com.liu.liuguild.title.TitleService titles() {
        return titles;
    }

    public com.liu.liuguild.shop.ShopService shop() {
        return shop;
    }

    public com.liu.liuguild.hook.LiuShopHook liuShop() {
        return liuShop;
    }

    public com.liu.liuguild.hook.PacketEventsHook packetEvents() {
        return packetEvents;
    }

    public NotBountiesHook notBounties() {
        return notBounties;
    }

    public com.liu.liuguild.hook.McMmoHook mcmmo() {
        return mcmmo;
    }

    public com.liu.liuguild.hook.GuildHomeHook guildHome() {
        return guildHome;
    }

    public com.liu.liuguild.hook.SparrowUiHook sparrowUi() {
        return sparrowUi;
    }

    /** 本服 server-id（config.yml 或 server.yml 覆盖）。 */
    public String serverId() {
        String id = settings().main.serverId;
        return id == null || id.isBlank() ? "server-1" : id;
    }

    public Player player(UUID uuid) {
        return uuid == null ? null : Bukkit.getPlayer(uuid);
    }

    public Player playerExact(String name) {
        return name == null ? null : Bukkit.getPlayerExact(name);
    }

    public OfflinePlayer offline(UUID uuid) {
        return Bukkit.getOfflinePlayer(uuid);
    }

    // ---- 调度 ----

    /**
     * 异步执行。
     * <p>关服后 Bukkit 调度器会拒绝注册任务（{@code IllegalPluginAccessException}），
     * 这里降级到自建收尾线程，保证托管装备清理、临时世界目录删除等收尾 IO 不被中断。</p>
     */
    public void async(Runnable task) {
        if (task == null) {
            return;
        }
        if (!shuttingDown && isEnabled()) {
            try {
                getServer().getScheduler().runTaskAsynchronously(this, task);
                return;
            } catch (IllegalPluginAccessException ignored) {
                // 与 onDisable 并发时降级到收尾线程
            }
        }
        runTeardown(task);
    }

    public void sync(Runnable task) {
        if (task == null) {
            return;
        }
        if (Bukkit.isPrimaryThread()) {
            task.run();
            return;
        }
        if (!shuttingDown && isEnabled()) {
            try {
                getServer().getScheduler().runTask(this, task);
                return;
            } catch (IllegalPluginAccessException ignored) {
                // 关服中无法再排主线程任务，直接跳过（此时只剩收尾状态回写）
            }
        }
    }

    public void syncDelayed(Runnable task, long delayTicks) {
        if (task == null) {
            return;
        }
        if (shuttingDown || !isEnabled()) {
            return;
        }
        try {
            getServer().getScheduler().runTaskLater(this, task, Math.max(0, delayTicks));
        } catch (IllegalPluginAccessException ignored) {
        }
    }

    private java.util.concurrent.ExecutorService teardown() {
        java.util.concurrent.ExecutorService ex = teardownExecutor;
        if (ex != null) {
            return ex;
        }
        synchronized (this) {
            if (teardownExecutor == null) {
                teardownExecutor = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "liuguild-teardown");
                    t.setDaemon(true);
                    return t;
                });
            }
            return teardownExecutor;
        }
    }

    private void runTeardown(Runnable task) {
        try {
            teardown().execute(task);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // 收尾线程已关闭（插件已完全卸载）：尽力执行，异常由任务自身兜住。
            try {
                task.run();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 等待收尾线程完成已排队的 IO，再关闭（不接收新任务）。 */
    private void drainTeardown(long millis) {
        java.util.concurrent.ExecutorService ex = teardownExecutor;
        if (ex == null) {
            return;
        }
        ex.shutdown();
        try {
            if (!ex.awaitTermination(millis, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                getLogger().warning("关服收尾任务仍在执行，已超时（剩余任务可能未完成）");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- 跨服工会数据同步 ----

    /**
     * 跨服通知统一入口：主线程只入队，Redis IO 永远在异步线程执行。
     * 单服模式保持本地即时分发。
     */
    public void publishAsync(String type, java.util.Map<String, String> data) {
        if (bus == null || type == null || data == null) return;
        if (!bus.isReady()) {
            bus.publish(type, data);
            return;
        }
        async(() -> bus.publish(type, data));
    }

    /** 同一工会的变更广播合并窗口（毫秒）：高频资金发放不再逐笔触发其它子服重载。 */
    private static final long GUILD_BROADCAST_MIN_MS = 500L;
    private final java.util.Map<String, Long> lastGuildBroadcast = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<String> trailingGuildBroadcast = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 广播某工会数据已变更，令其它子服重载（窗口内合并，结束时补发一次保证最终一致）。 */
    public void broadcastGuildChange(String guildId) {
        if (bus == null || guildId == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastGuildBroadcast.get(guildId);
        if (last != null && now - last < GUILD_BROADCAST_MIN_MS) {
            if (trailingGuildBroadcast.add(guildId)) {
                syncDelayed(() -> {
                    trailingGuildBroadcast.remove(guildId);
                    sendGuildChanged(guildId);
                }, 12L);
            }
            return;
        }
        sendGuildChanged(guildId);
        if (lastGuildBroadcast.size() > 2000) {
            lastGuildBroadcast.entrySet().removeIf(e -> now - e.getValue() > 5L * 60L * 1000L);
        }
    }

    private void sendGuildChanged(String guildId) {
        lastGuildBroadcast.put(guildId, System.currentTimeMillis());
        publishAsync("GUILD_CHANGED", com.liu.liuguild.crossserver.CrossServerBus.map(
                "guild", guildId, "server", serverId()));
    }

    /** 广播某成员数据已变更。 */
    public void broadcastMemberChange(UUID uuid, String guildId) {
        if (bus == null || uuid == null) {
            return;
        }
        publishAsync("MEMBER_CHANGED", com.liu.liuguild.crossserver.CrossServerBus.map(
                "uuid", uuid.toString(), "guild", guildId == null ? "" : guildId, "server", serverId()));
    }

    // =====================================================================
    // 启动配置检查
    // =====================================================================

    /** 启动后延迟执行：检查常见配置错误并打印可操作的告警。 */
    private void validateConfig() {
        configWarnings.clear();
        Settings s = settings();

        if (!database.healthy()) {
            configWarnings.add("数据库连接不健康，请检查 database.yml。");
        }
        if (s.guild.createCost > 0 && !economy.available()) {
            configWarnings.add("guild.create-cost > 0 但未检测到 Vault 经济插件，创建费用将被忽略。");
        }
        if (s.war.enabled) {
            if (s.war.modes.isEmpty()) {
                configWarnings.add("工会战已启用但未定义任何模式（war-modes.yml 或 war.yml）。");
            } else if (!s.war.modes.containsKey(s.war.defaultMode)) {
                configWarnings.add("war.default-mode=" + s.war.defaultMode + " 未在 war-modes.yml 或 war.yml 中定义。");
            }
            for (com.liu.liuguild.config.WarMode m : s.war.modes.values()) {
                com.liu.liuguild.config.ArenaDef a = s.arenas.arenas.get(
                        m.arena == null ? "" : m.arena.toLowerCase(java.util.Locale.ROOT));
                if (a == null) {
                    configWarnings.add("模式 " + m.key + " 引用了不存在的场地 " + m.arena + "，将回退到默认场地。");
                    continue;
                }
                if (a.hasTemplate() && !(dungeon != null && dungeon.available())) {
                    configWarnings.add("场地 " + a.id + " 配置了模板 " + a.template
                            + " 但未安装 liudungeon，将回退到固定世界。");
                } else if (a.hasTemplate() && dungeon != null && !dungeon.hasTemplate(a.template)) {
                    configWarnings.add("场地 " + a.id + " 的模板 " + a.template + " 在 liudungeon 中不存在。");
                }
                if (!a.hasTemplate() && !a.tempWorld.builtin && a.world.isBlank() && Bukkit.getWorlds().isEmpty()) {
                    configWarnings.add("场地 " + a.id + " 未设置世界且服务器无可加载世界。");
                }
            }
        }
        if (s.guild.homeEnabled && !(guildHome != null && guildHome.available())) {
            configWarnings.add("guild.home.enabled=true 但未检测到 HuskHomes，驻地功能不可用。");
        }
        if (s.main.mcmmoEnabled && !(mcmmo != null && mcmmo.available())) {
            configWarnings.add("hooks.mcmmo.enabled=true 但未检测到 mcMMO。");
        }
        if ("sparrow".equalsIgnoreCase(s.gui.engine) && !(sparrowUi != null && sparrowUi.enabled())) {
            configWarnings.add("gui.engine=sparrow 但 Sparrow UI 不可用，已回退到内置 GUI。");
        }
        if (s.main.crossServerEnabled && bus != null && !bus.isReady()) {
            configWarnings.add("已启用跨服但 Redis 未连接，工会战将限制在单服。");
        }
        if (!configWarnings.isEmpty()) {
            getLogger().warning("配置检查发现 " + configWarnings.size() + " 项需注意的问题：");
            for (String w : configWarnings) {
                getLogger().warning(" - " + w);
            }
        }
    }

    /** 启动配置检查的告警（可能为空）。 */
    public List<String> configWarnings() {
        return List.copyOf(configWarnings);
    }

    // =====================================================================
    // 详细状态（/guildadmin status）
    // =====================================================================

    /** 组装插件运行状态明细，供管理指令 / 控制台输出。 */
    public List<String> statusLines() {
        List<String> out = new ArrayList<>();
        Settings s = settings();
        out.add("&6===== LiuGuild 运行状态 =====");
        out.add("&7版本: &f" + getPluginMeta().getVersion() + " &7server-id: &f" + serverId());
        out.add("&7配置目录: &f" + configManager.root().getAbsolutePath());
        out.add("&7语言: &f" + s.main.language + " &7自动重载: &f" + s.main.autoReload);
        // 数据库
        try (var c = database.source().getConnection()) {
            // 只借一次连接：healthy() 会再开一次连接 + SELECT 1，状态命令没必要付两次往返。
            out.add("&7存储: &f" + (database.isSqlite() ? "SQLite" : "MySQL")
                    + " &7连接有效: &f" + c.isValid(2));
        } catch (Throwable t) {
            out.add("&7存储: &c异常 (" + t.getMessage() + ")");
        }
        out.add("&7工会数: &f" + guilds.guildCount());
        // 跨服
        out.add("&7跨服: &f" + (bus.isReady() ? "已连接" : (s.main.crossServerEnabled ? "未连接/降级" : "未启用"))
                + " &7Redis: &f" + s.main.redisHost + ":" + s.main.redisPort + " &7频道: &f" + s.main.redisChannel);
        // 工会战
        out.add("&7工会战: &f" + (s.war.enabled ? "启用" : "关闭")
                + " &7赛事: &f" + s.war.modes.keySet());
        String hostedIds = war.localWars().isEmpty() ? "无"
                : war.localWars().stream()
                .map(w -> w.id() + "@" + war.arenaOf(w).id)
                .collect(java.util.stream.Collectors.joining(","));
        out.add("&7匹配队列: &f" + war.queueSize() + " &7进行中: &f" + war.liveWars().size()
                + " &7本服承办: &f" + hostedIds
                + " &7并发上限: &f" + war.localArenaCapacity()
                + " &7待发放奖励: &f" + war.pendingSettlements());
        // 预加载
        out.add("&7预加载: &f套装 " + (kits == null ? 0 : kits.preloadedKits()) + " 套 &7场地 " + s.arenas.arenas.size()
                + " 个 &7模式 " + s.war.modes.size() + " 个 &7图标 " + s.icons.icons.size() + " 个");
        // 场地
        s.arenas.arenas.forEach((id, a) -> out.add("&8 - 场地 &f" + id + " &7承办=&f"
                + (a.server.isBlank() ? "-" : a.server) + " &7世界=&f" + (a.world.isBlank() ? "主世界" : a.world)
                + " &7模板=&f" + (a.hasTemplate() ? a.template : "固定坐标")));
        // 钩子
        out.add("&7钩子: " + hook("liudungeon", dungeon != null && dungeon.available())
                + hook("LiuChat", guildChat != null && guildChat.available())
                + hook("liupass", pass != null && pass.available())
                + hook("CraftEngine", craftEngine != null && craftEngine.available())
                + hook("SparrowSync", sparrow != null && sparrow.available())
                + hook("LuckyPeaches", luckyPeaches != null && luckyPeaches.available())
                + hook("Vault", economy != null && economy.available())
                + hook("PAPI", papi != null)
                + hook("PlayerTitle", playerTitle != null && playerTitle.available())
                + hook("NotBounties", notBounties != null && notBounties.available())
                + hook("HuskHomes", guildHome != null && guildHome.available())
                + hook("mcMMO", mcmmo != null && mcmmo.available())
                + hook("SparrowUI", sparrowUi != null && sparrowUi.enabled())
                + hook("PacketEvents", packetEvents != null && packetEvents.available()));
        if (pass != null && pass.available()) {
            out.add("&7liupass 任务类型: &f" + (!s.main.passEnabled
                    ? "任务进度已关闭（hooks.liupass.enabled: false）"
                    : pass.taskTypesAvailable()
                    ? pass.registeredTypes() + " 个已注册（type: liuguild:xxx）"
                    : "旧版 API，未支持扩展任务类型（仅经验 / tasks.yml）"));
        }
        // 模块
        out.add("&7模块: &f" + modules.ids());
        if (!configWarnings.isEmpty()) {
            out.add("&e配置告警: &f" + configWarnings.size() + " 项（详见启动日志）");
            for (String w : configWarnings) {
                out.add("&8 - &f" + w);
            }
        }
        return out;
    }

    private static String hook(String name, boolean ok) {
        return (ok ? "&a" : "&8") + name + " ";
    }

    /** 供状态展示的时间字符串。 */
    public String now() {
        return TimeUtil.formatDateTime(System.currentTimeMillis());
    }
}
