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
import com.liu.liuguild.hook.ChatHook;
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
    private CrossServerBus bus;
    private GuildManager guilds;
    private WarManager war;
    private KitService kits;
    private GuiManager gui;
    private PlayerguildImporter importer;
    private ModuleRegistry modules;

    private EconomyHook economy;
    private GuildChatHook guildChat;
    /** 启动配置检查的告警（供状态命令展示）。 */
    private final java.util.List<String> configWarnings = new java.util.ArrayList<>();
    private ChatHook chat;
    private PassHook pass;
    private CraftEngineHook craftEngine;
    private DungeonHook dungeon;
    private SparrowSyncHook sparrow;
    private PapiHook papi;
    private PlayerTitleHook playerTitle;
    private LuckyPeachesHook luckyPeaches;
    private com.liu.liuguild.hook.ResidenceHook residence;
    private NotBountiesHook notBounties;
    private com.liu.liuguild.hook.McMmoHook mcmmo;
    private com.liu.liuguild.hook.GuildHomeHook guildHome;
    private com.liu.liuguild.hook.SparrowUiHook sparrowUi;
    private com.liu.liuguild.hook.PacketEventsHook packetEvents;

    private BukkitTask heartbeatTask;
    private BukkitTask configWatchTask;
    private BukkitTask presenceTask;
    private BukkitTask cleanupTask;
    private volatile long configFingerprint;

    public static LiuGuild get() {
        return instance;
    }

    // =====================================================================
    // 生命周期
    // =====================================================================

    @Override
    public void onEnable() {
        instance = this;
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

        // 软依赖钩子（缺失自动降级）
        economy = safe(EconomyHook::new);
        chat = safe(ChatHook::new);
        pass = safe(PassHook::new);
        craftEngine = safe(CraftEngineHook::new);
        dungeon = safe(() -> new DungeonHook(this));
        sparrow = safe(SparrowSyncHook::new);
        papi = safe(PapiHook::new);
        playerTitle = safe(PlayerTitleHook::new);
        luckyPeaches = safe(LuckyPeachesHook::new);
        residence = safe(() -> new com.liu.liuguild.hook.ResidenceHook(this));
        if (residence != null) residence.init();
        notBounties = safe(() -> new NotBountiesHook(this));
        mcmmo = safe(() -> new com.liu.liuguild.hook.McMmoHook(this));
        guildHome = safe(() -> new com.liu.liuguild.hook.GuildHomeHook(this));
        sparrowUi = safe(() -> new com.liu.liuguild.hook.SparrowUiHook(this));
        if (sparrowUi != null) sparrowUi.init();
        packetEvents = safe(() -> new com.liu.liuguild.hook.PacketEventsHook(this));
        if (packetEvents != null) packetEvents.init();

        // 核心管理器
        guilds = new GuildManager(this);
        kits = new KitService(this);
        kits.preload();
        war = new WarManager(this);
        gui = new GuiManager(this);
        importer = new PlayerguildImporter(this);

        RedisBridge bridge = new RedisBridge(this, settings().main);
        ServerTransfer transfer = new ServerTransfer(this, settings().main);
        transfer.register();
        bus = new CrossServerBus(this, settings().main, bridge, transfer);

        // 模块注册（按 order 启停）
        modules = new ModuleRegistry(this);
        modules.register(war);
        modules.register(gui);
        modules.register(new GuildListener(this));
        modules.register(new WarListener(this));
        modules.register(new com.liu.liuguild.listener.WarFreezeListener(this));
        modules.register(new com.liu.liuguild.listener.GuildSyncModule(this));
        bus.addListener(modules::dispatchCrossServer);
        modules.enableAll();
        bus.startAsync();

        new com.liu.liuguild.listener.GuildTaskListener(this).start();
        if (mcmmo != null) mcmmo.register();
        guildChat = new GuildChatHook(this);
        guildChat.start();
        GuildAPI.init(this);
        registerPapi();
        registerCommands();
        guilds.reloadAll();
        startTasks();
        syncDelayed(this::validateConfig, 60L);

        getLogger().info("LiuGuild 已启用：server-id=" + serverId()
                + "，存储=" + (database.isSqlite() ? "SQLite" : "MySQL")
                + "，模式=" + settings().war.modes.keySet()
                + "，场地=" + settings().arenas.arenas.keySet());
    }

    @Override
    public void onDisable() {
        if (guildChat != null) guildChat.close();
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
        if (configManager == null) {
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
            return null;
        }
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

    public ModuleRegistry modules() {
        return modules;
    }

    public EconomyHook economy() {
        return economy;
    }

    public GuildChatHook guildChat() {
        return guildChat;
    }

    public ChatHook chat() {
        return chat;
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

    public PlayerTitleHook playerTitle() {
        return playerTitle;
    }

    public LuckyPeachesHook luckyPeaches() {
        return luckyPeaches;
    }

    public com.liu.liuguild.hook.ResidenceHook residence() {
        return residence;
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

    public void async(Runnable task) {
        getServer().getScheduler().runTaskAsynchronously(this, task);
    }

    public void sync(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            getServer().getScheduler().runTask(this, task);
        }
    }

    public void syncDelayed(Runnable task, long delayTicks) {
        getServer().getScheduler().runTaskLater(this, task, Math.max(0, delayTicks));
    }

    // ---- 跨服工会数据同步 ----

    /** 广播某工会数据已变更，令其它子服重载。 */
    public void broadcastGuildChange(String guildId) {
        if (bus == null || !bus.isReady() || guildId == null) {
            return;
        }
        bus.publish("GUILD_CHANGED", com.liu.liuguild.crossserver.CrossServerBus.map(
                "guild", guildId, "server", serverId()));
    }

    /** 广播某成员数据已变更。 */
    public void broadcastMemberChange(UUID uuid, String guildId) {
        if (bus == null || !bus.isReady() || uuid == null) {
            return;
        }
        bus.publish("MEMBER_CHANGED", com.liu.liuguild.crossserver.CrossServerBus.map(
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
                configWarnings.add("工会战已启用但未定义任何模式（war-modes.yml）。");
            } else if (!s.war.modes.containsKey(s.war.defaultMode)) {
                configWarnings.add("war.default-mode=" + s.war.defaultMode + " 未在 war-modes.yml 中定义。");
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
            out.add("&7存储: &f" + (database.isSqlite() ? "SQLite" : "MySQL")
                    + " &7健康: &f" + database.healthy() + " &7连接有效: &f" + c.isValid(2));
        } catch (Throwable t) {
            out.add("&7存储: &c异常 (" + t.getMessage() + ")");
        }
        out.add("&7工会数: &f" + guilds.guildCount());
        // 跨服
        out.add("&7跨服: &f" + (bus.isReady() ? "已连接" : (s.main.crossServerEnabled ? "未连接/降级" : "未启用"))
                + " &7Redis: &f" + s.main.redisHost + ":" + s.main.redisPort + " &7频道: &f" + s.main.redisChannel);
        // 工会战
        out.add("&7工会战: &f" + (s.war.enabled ? "启用" : "关闭")
                + " &7承办模式: &f" + s.war.serverMode
                + " &7赛事: &f" + s.war.modes.keySet());
        out.add("&7匹配队列: &f" + war.queueSize() + " &7进行中: &f" + war.liveWars().size()
                + " &7本服承办: &f" + (war.currentWar() == null ? "无" : war.currentWar().id())
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
                + hook("LiuChat", chat != null && chat.available())
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
                + hook("Residence", residence != null && residence.available())
                + hook("PacketEvents", packetEvents != null && packetEvents.available()));
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
