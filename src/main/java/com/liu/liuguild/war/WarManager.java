package com.liu.liuguild.war;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import com.liu.liuguild.LiuGuild;
import com.liu.liuguild.api.event.GuildKillEvent;
import com.liu.liuguild.api.event.GuildWarEndEvent;
import com.liu.liuguild.api.event.GuildWarMatchEvent;
import com.liu.liuguild.api.event.GuildWarStartEvent;
import com.liu.liuguild.config.ArenaDef;
import com.liu.liuguild.config.WarMode;
import com.liu.liuguild.config.WarSettings;
import com.liu.liuguild.util.NumberUtil;
import com.liu.liuguild.util.TimeUtil;
import com.liu.liuguild.crossserver.CrossServerBus;
import com.liu.liudungeon.api.CreateOptions.ChunkPosition;
import com.liu.liuguild.hook.ArenaWorld;
import com.liu.liuguild.model.Guild;
import com.liu.liuguild.model.GuildMember;
import com.liu.liuguild.model.GuildWar;
import com.liu.liuguild.model.WarState;
import com.liu.liuguild.module.GuildModule;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 工会战模块：跨服匹配 → 专属承办服 → 统一装备 → 击杀计分 → 结算奖励。
 * <p>匹配队列与实时战况走 Redis（{@link CrossServerBus}）；每个工会的离线数据走 MySQL；
 * 玩家数据在参赛期间的还原优先使用 Sparrow Sync 快照，未安装时回退本地快照。</p>
 */
public final class WarManager implements GuildModule {

    private final LiuGuild plugin;

    /** 本服承办的工会战（未承办时为 null）。 */
    private volatile GuildWar current;
    /** 当前竞技场世界。 */
    private volatile ArenaWorld arena;
    private volatile World fallbackWorld;
    /** 由 LiuGuild 自建并负责销毁的临时竞技场世界。 */
    private volatile World builtinWorld;
    /** 当前使用的竞技场定义。 */
    private volatile ArenaDef currentArena;

    private final Map<UUID, Location> returnLoc = new ConcurrentHashMap<>();
    private final Map<UUID, String> sideOf = new ConcurrentHashMap<>();
    private final Set<UUID> capturing = ConcurrentHashMap.newKeySet();
    private final Map<UUID, KitService.Snapshot> localSnap = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> sparrowSnap = new ConcurrentHashMap<>();
    private final Set<UUID> spectators = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> livesRemaining = new ConcurrentHashMap<>();
    private final Set<UUID> eliminated = ConcurrentHashMap.newKeySet();
    /** 准备阶段被锁定（冻结）的参赛者；开战时解除。 */
    private final Set<UUID> frozen = ConcurrentHashMap.newKeySet();
    /** 工会战内部合法发起的传送白名单，免于 WarListener 的传送拦截。 */
    private final Set<UUID> allowingWarTeleport = ConcurrentHashMap.newKeySet();
    /** 准备阶段垫的屏障方块：原方块类型，解锁后恢复。 */
    private final Map<Location, org.bukkit.Material> preparedPlatform = new ConcurrentHashMap<>();

    /** 执行一次工会战合法的内部传送（加入出生点、复活、赛后回城等）。 */
    public boolean warTeleport(Player player, Location target) {
        if (player == null || target == null || target.getWorld() == null) {
            return false;
        }
        UUID uuid = player.getUniqueId();
        allowingWarTeleport.add(uuid);
        try {
            return player.teleport(target);
        } finally {
            allowingWarTeleport.remove(uuid);
        }
    }

    /** 当前是否正在执行工会战内部合法的传送。 */
    public boolean isAllowingWarTeleport(UUID uuid) {
        return uuid != null && allowingWarTeleport.contains(uuid);
    }

    /** 玩家是否处于「准备阶段锁定」状态。 */
    public boolean isFrozen(UUID uuid) {
        return frozen.contains(uuid) || capturing.contains(uuid);
    }

    public boolean isCapturing(UUID uuid) {
        return capturing.contains(uuid);
    }

    /** 玩家当前的锁定坐标（准备阶段出生点）；未锁定时返回 null。 */
    public Location freezeLocation(UUID uuid) {
        if (!frozen.contains(uuid)) {
            return null;
        }
        String side = sideOf.get(uuid);
        GuildWar war = current;
        if (side == null || war == null) {
            return null;
        }
        WarMode mode = modeOf(war);
        if (mode == null) {
            return null;
        }
        ArenaDef def = plugin.settings().arena(mode.arena);
        World world = arenaWorld();
        if (def == null || world == null) {
            return null;
        }
        ArenaDef.Spawn spawn = def.teamSpawn(side);
        return spawn == null ? null : spawn.toLocation(world);
    }

    /** 该玩家是否可以跳跃（由模式配置决定）。 */
    public boolean allowJumpOnPrepare(UUID uuid) {
        GuildWar war = current;
        if (war == null) {
            return true;
        }
        WarMode mode = modeOf(war);
        return mode == null || mode.allowJumpOnPrepare;
    }

    /** 所有服务器共享的「进行中工会战」镜像，用于战况展示。 */
    private final Map<String, GuildWar> observed = new ConcurrentHashMap<>();
    /** 上次清理过期跨服镜像的时间戳（节流用）。 */
    private volatile long lastMirrorPruneAt;
    /** 无 Redis 时的本服匹配队列。 */
    private final List<String> localQueue = new CopyOnWriteArrayList<>();
    private final Map<String, Long> localQueuedAt = new ConcurrentHashMap<>();
    /** 本服队列中每个工会选择的模式。 */
    private final Map<String, String> localMode = new ConcurrentHashMap<>();
    /** 允许跨服转发的队列通知 key（白名单，防任意消息 key）。 */
    private static final java.util.Set<String> QUEUE_NOTIFY_KEYS =
            java.util.Set.of("war-queue-timeout", "war-queue-removed");

    private BukkitTask queueTask;
    private BukkitTask tickTask;
    private BukkitTask seasonTask;
    private final Map<String, Long> lastSeasonTrigger = new ConcurrentHashMap<>();
    private volatile long phaseUntil;
    private volatile long lastBroadcastSecond = -1;
    private volatile boolean settling;
    /** 结算阶段分帧发放的奖励任务队列（避免一次性在主线程阻塞）。 */
    private final java.util.Deque<Runnable> settleQueue = new java.util.concurrent.ConcurrentLinkedDeque<>();
    private volatile String forcedWinner = "";
    private final Set<UUID> handledLethal = ConcurrentHashMap.newKeySet();
    /** 比赛期间的 BossBar（比分 + 剩余时间）。 */
    private volatile BossBar warBar;

    public WarManager(LiuGuild plugin) {
        this.plugin = plugin;
    }

    public WarSettings conf() {
        return plugin.settings().war;
    }

    /** 某场工会战使用的模式配置。 */
    public WarMode modeOf(GuildWar war) {
        return conf().mode(war == null ? conf().defaultMode : war.mode());
    }

    /** 按 key 取模式配置。 */
    public WarMode mode(String key) {
        return conf().mode(key);
    }

    /** 当前承办中的工会战模式（无工会战时返回默认模式）。 */
    public WarMode activeMode() {
        return current == null ? conf().defaultModeObj() : modeOf(current);
    }

    // =====================================================================
    // 模块生命周期
    // =====================================================================

    @Override
    public String id() {
        return "war";
    }

    @Override
    public int order() {
        return 30;
    }

    @Override
    public void enable(LiuGuild plugin) {
        if (!conf().enabled) {
            plugin.getLogger().info("工会战模块：未启用（war.enabled=false）。");
            return;
        }
        scheduleTasks();
        // 只在插件启动时做中断恢复；热重载不能碰本服正在进行的战斗。
        recoverInterruptedWars();
        plugin.getLogger().info("工会战模块已启用（server-mode=" + conf().serverMode
                + "，承办服=" + (conf().dedicated() ? conf().dedicatedServer : "本服")
                + "，模式=" + conf().modes.keySet() + "）。");
    }

    /** 启动/重载共用的定时任务装配，不触发崩溃恢复。 */
    private void scheduleTasks() {
        int interval = Math.max(1, conf().queueIntervalSeconds);
        queueTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::matchTick,
                20L * interval, 20L * interval);
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::warTick, 20L, 20L);
        seasonTask = Bukkit.getScheduler().runTaskTimer(plugin, this::seasonTick, 40L, 20L);
    }

    /**
     * 启动时收尾本服中断的工会战。
     *
     * <p>承办服崩溃/重启后，内存里的进行中的工会战与跨服镜像都会丢失，但数据库里仍可能
     * 留着非终态记录。不处理的话，这些记录会让 {@code /guild war history} 查不到结果（历史只认
     * ENDED），也会让清理任务一直跳过它们。这里把「承办服就是本服」的中断战斗标记为取消并
     * 广播终态，让其余子服同步清掉镜像。</p>
     */
    private void recoverInterruptedWars() {
        plugin.async(() -> {
            try {
                for (GuildWar w : plugin.repo().loadLiveWars()) {
                    if (w == null || w.id() == null || !plugin.serverId().equals(w.hostServer())) {
                        continue;
                    }
                    // 内存里正跑着的战斗不算「中断」，否则热重载/误触发会把进行中的战在库里结掉。
                    GuildWar running = current;
                    if (running != null && running.id().equals(w.id())) {
                        continue;
                    }
                    // endedAt 已写入说明已进入/完成结算阶段，按 ENDED 归档（进入历史）；
                    // 否则是准备/战斗中被打断，按 CANCELLED 处理。
                    boolean finished = w.endedAt() > 0;
                    w.state(finished ? WarState.ENDED : WarState.CANCELLED);
                    if (w.endedAt() <= 0) {
                        w.endedAt(System.currentTimeMillis());
                    }
                    plugin.repo().updateWar(w);
                    plugin.getLogger().warning("发现中断的工会战 " + w.id() + "（承办服=" + w.hostServer()
                            + "），已标记为 " + w.state().name() + "。");
                    if (plugin.bus().isReady()) {
                        plugin.bus().publishWarState(w.id(), w.state().name(), w.scoreA(), w.scoreB());
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().warning("工会战崩溃恢复扫描失败: " + e.getMessage());
            }
        });
    }

    @Override
    public void disable() {
        cancelTasks();
        // 关服/插件卸载：绝不结算奖励。取消进行中的工会战并还原玩家与竞技场。
        GuildWar war = current;
        if (war != null) {
            war.state(WarState.CANCELLED);
            try {
                plugin.repo().updateWar(war);
            } catch (Exception e) {
                plugin.getLogger().warning("取消工会战写库失败: " + e.getMessage());
            }
            cleanup(war, false);
        }
    }

    @Override
    public void reload() {
        // 配置热重载只重启定时任务，不结算、不取消正在进行的工会战，也不做崩溃恢复。
        cancelTasks();
        if (conf().enabled) {
            scheduleTasks();
        }
    }

    private void cancelTasks() {
        if (queueTask != null) {
            queueTask.cancel();
            queueTask = null;
        }
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        if (seasonTask != null) {
            seasonTask.cancel();
            seasonTask = null;
        }
    }

    public GuildWar currentWar() {
        return current;
    }

    /** 本服已知的全部进行中工会战（本服承办 + 跨服镜像）。 */
    public List<GuildWar> liveWars() {
        List<GuildWar> out = new ArrayList<>();
        if (current != null) {
            out.add(current);
        }
        long now = System.currentTimeMillis();
        for (GuildWar w : observed.values()) {
            if (w != current && w.state().isLive() && !isStaleMirror(w, now)) {
                out.add(w);
            }
        }
        return out;
    }

    public GuildWar warById(String id) {
        if (id == null) {
            return null;
        }
        if (current != null && current.id().equals(id)) {
            return current;
        }
        return observed.get(id);
    }

    /** 当前阶段剩余秒数（无进行中工会战返回 -1）。 */
    public long phaseSecondsLeft() {
        if (current == null) {
            return -1;
        }
        return TimeUtil.secondsLeft(phaseUntil);
    }

    /** 当前承办中的竞技场定义；无则返回默认场。 */
    public ArenaDef currentArena() {
        return currentArena == null ? plugin.settings().arena(mode(conf().defaultMode).arena) : currentArena;
    }

    public boolean isWarServer() {
        return conf().serverMode;
    }

    // =====================================================================
    // 查询
    // =====================================================================

    /** 找某个工会正在参与的工会战（本服承办优先，其次跨服镜像）。 */
    public GuildWar warOf(String guildId) {
        if (guildId == null) {
            return null;
        }
        GuildWar c = current;
        if (c != null && c.involves(guildId) && c.state().isLive()) {
            return c;
        }
        long now = System.currentTimeMillis();
        for (GuildWar w : observed.values()) {
            if (!w.involves(guildId)) {
                continue;
            }
            // 已是终态或已过期的跨服镜像直接清除，否则本服会一直被误判为「正在工会战中」。
            if (!w.state().isLive() || isStaleMirror(w, now)) {
                observed.remove(w.id());
                continue;
            }
            return w;
        }
        return null;
    }

    /**
     * 判定跨服镜像是否已过期。
     *
     * <p>承办服正常收尾时会广播终态，镜像随即移除；但承办服崩溃、
     * 消息丢失或 Redis 抖动时镜像会残留，导致本服玩家一直被判定为
     * 「正在工会战中」而无法匹配。这里按该模式的最长时长加安全余量
     * 兜底判定，超时即视为无效镜像并清除。</p>
     */
    private boolean isStaleMirror(GuildWar war, long now) {
        return now - war.createdAt() > maxMirrorAgeMillis(war);
    }

    private long maxMirrorAgeMillis(GuildWar war) {
        WarMode mode = modeOf(war);
        int prepare = mode == null ? 0 : Math.max(0, mode.prepareSeconds);
        int duration = mode == null ? 0 : Math.max(0, mode.durationSeconds);
        int settle = mode == null ? 0 : Math.max(0, mode.settleSeconds);
        return WarMath.mirrorMaxAgeMillis(prepare, duration, settle, WarMath.MIRROR_SAFETY_MARGIN_MILLIS);
    }

    public boolean isInWar(UUID uuid) {
        GuildWar c = current;
        return c != null && c.sideOf(uuid) != null;
    }

    /** 玩家是否处于工会战或旁观状态（禁止打开面板/菜单、禁止外部传送、禁止传送指令）。 */
    public boolean isWarRestricted(UUID uuid) {
        return uuid != null && (isInWar(uuid) || spectators.contains(uuid) || sideOf.containsKey(uuid));
    }

    public boolean isSpectator(UUID uuid) {
        return spectators.contains(uuid);
    }

    public boolean isQueued(String guildId) {
        if (plugin.bus().isReady()) {
            return plugin.bus().isQueued(guildId);
        }
        return localQueue.contains(guildId);
    }

    public void historyAsync(String guildId, int limit, Consumer<List<GuildWar>> callback) {
        plugin.async(() -> {
            List<GuildWar> list;
            try {
                list = plugin.repo().warHistory(guildId, limit);
            } catch (Exception e) {
                list = List.of();
            }
            final List<GuildWar> result = list;
            plugin.sync(() -> callback.accept(result));
        });
    }

    // =====================================================================
    // 匹配
    // =====================================================================

    public void queue(Player player, Guild guild) {
        queue(player, guild, conf().defaultMode);
    }

    /** 指定模式发起匹配。 */
    public void queue(Player player, Guild guild, String modeKey) {
        if (!player.hasPermission("liuguild.war")) {
            plugin.messages().send(player, "no-permission");
            return;
        }
        GuildMember actor = guild.member(player.getUniqueId());
        boolean leader = actor != null && actor.rank() == com.liu.liuguild.model.GuildRank.LEADER;
        boolean officer = actor != null && actor.rank() == com.liu.liuguild.model.GuildRank.OFFICER
                && plugin.settings().guild.officerDeclareWar;
        if (!leader && !officer) {
            plugin.messages().send(player, "no-permission-rank");
            return;
        }
        WarMode mode = mode(modeKey);
        if (!conf().enabled) {
            plugin.messages().send(player, "war-disabled");
            return;
        }
        if (!mode.enabled) {
            plugin.messages().send(player, "war-mode-disabled", "mode", mode.display);
            return;
        }
        if (warOf(guild.id()) != null) {
            plugin.messages().send(player, "war-in-progress");
            return;
        }
        if (guild.level() < mode.minLevel) {
            plugin.messages().send(player, "war-need-level", "level", mode.minLevel);
            return;
        }
        if (plugin.bus().isReady()) {
            // Redis 操作是阻塞 IO，放异步线程，避免卡主线程。
            plugin.async(() -> {
                try {
                    if (plugin.guilds().countNetworkOnline(guild) < mode.minMembers) {
                        plugin.sync(() -> plugin.messages().send(player, "war-need-members", "min", mode.minMembers));
                        return;
                    }
                    if (isQueued(guild.id())) {
                        plugin.sync(() -> plugin.messages().send(player, "war-already-queued"));
                        return;
                    }
                    plugin.bus().queueGuild(guild.id(), CrossServerBus.map(
                            "name", guild.name(), "tag", guild.tag(), "server", plugin.serverId(),
                            "mode", mode.key, "leader", player.getName()));
                    plugin.sync(() -> announceQueued(player, guild, mode));
                } catch (RuntimeException e) {
                    // Redis 握手中断时不能无声吞掉：否则玩家点了匹配以为已排队。
                    plugin.getLogger().warning("工会战入队失败: " + e.getMessage());
                    plugin.sync(() -> plugin.messages().send(player, "war-queue-error"));
                }
            });
        } else {
            if (plugin.guilds().onlineMembers(guild).size() < mode.minMembers) {
                plugin.messages().send(player, "war-need-members", "min", mode.minMembers);
                return;
            }
            if (isQueued(guild.id())) {
                plugin.messages().send(player, "war-already-queued");
                return;
            }
            localQueue.add(guild.id());
            localQueuedAt.put(guild.id(), System.currentTimeMillis());
            localMode.put(guild.id(), mode.key);
            announceQueued(player, guild, mode);
        }
    }

    /** 入队成功后的本地提示与广播（必须在主线程）。 */
    private void announceQueued(Player player, Guild guild, WarMode mode) {
        if (!player.isOnline()) {
            return;
        }
        plugin.messages().send(player, "war-queue-joined", "mode", mode.display);
        plugin.guilds().notifyGuild(guild.id(), plugin.messages().component("war-queue-joined", "mode", mode.display), player.getUniqueId());
        if (mode.broadcastQueue) {
            plugin.guilds().broadcastAll(plugin.messages().component("war-queue-broadcast",
                    "player", player.getName(), "guild", guild.name(), "mode", mode.display)
                    .append(queueJoinButton(mode)));
        }
    }

    /** 「点击参与匹配」可点击按钮（带具体模式）。 */
    private Component queueJoinButton(WarMode mode) {
        return Component.text(" ").append(plugin.messages().component("war-queue-join-button")
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/guild war queue " + mode.key))
                .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                        plugin.messages().component("war-queue-join-hover", "mode", mode.display))));
    }

    /** 「点击进入竞技场」可点击按钮。 */
    private Component arenaJoinButton() {
        return Component.text(" ").append(plugin.messages().component("war-arena-join-button")
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/guild war join"))
                .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(
                        plugin.messages().component("war-arena-join-hover"))));
    }

    public void leaveQueue(Player player, Guild guild) {
        if (plugin.bus().isReady()) {
            plugin.async(() -> {
                boolean queued = isQueued(guild.id());
                if (queued) {
                    plugin.bus().dequeueGuild(guild.id());
                }
                plugin.sync(() -> plugin.messages().send(player, queued ? "war-queue-left" : "war-not-queued"));
            });
            return;
        }
        boolean queued = localQueue.remove(guild.id());
        localQueuedAt.remove(guild.id());
        localMode.remove(guild.id());
        plugin.messages().send(player, queued ? "war-queue-left" : "war-not-queued");
    }

    /** 该工会当前排队选择的模式 key；未排队返回 null。 */
    public String queuedMode(String guildId) {
        if (plugin.bus().isReady()) {
            String mode = plugin.bus().queuedMode(guildId);
            return mode == null ? null : (mode.isBlank() ? conf().defaultMode : mode);
        }
        return localMode.get(guildId);
    }

    /** 当前匹配队列中的工会数量。 */
    public int queueSize() {
        if (plugin.bus().isReady()) {
            return plugin.bus().queueCount();
        }
        return localQueue.size();
    }

    // =====================================================================
    // 赛季时间表
    // =====================================================================

    /** 每秒检查各模式的开战时间表。 */
    private void seasonTick() {
        try {
            seasonTickInternal();
        } catch (Throwable t) {
            plugin.getLogger().warning("工会战赛季 tick 异常（任务继续运行）: " + t);
        }
    }

    private void seasonTickInternal() {
        if (!conf().enabled) {
            return;
        }
        long minute = System.currentTimeMillis() / 60000L;
        for (WarMode mode : conf().enabledModes()) {
            if (!mode.scheduled()) {
                continue;
            }
            for (String entry : mode.schedule) {
                if (!TimeUtil.matchesSchedule(entry)) {
                    continue;
                }
                String key = mode.key + "@" + entry;
                Long last = lastSeasonTrigger.get(key);
                if (last != null && last == minute) {
                    continue;
                }
                triggerSeasonOnce(mode, minute, key);
            }
        }
    }

    /**
     * 共享配置下的关键点：多台子服会同时到达开赛时间，
     * 必须用 Redis 原子抢锁保证全局只有一台子服发布开赛指令。
     * 抢锁失败/瞬时异常时不记录 lastSeasonTrigger，下一秒钟会自动重试。
     */
    private void triggerSeasonOnce(WarMode mode, long minute, String dedupeKey) {
        if (plugin.bus() == null || !plugin.bus().isReady()) {
            lastSeasonTrigger.put(dedupeKey, minute);
            startSeason(mode);
            return;
        }
        plugin.async(() -> {
            if (!plugin.bus().acquireOnce("season:" + mode.key + ":" + minute, 180)) {
                return;
            }
            plugin.sync(() -> {
                lastSeasonTrigger.put(dedupeKey, minute);
                startSeason(mode);
            });
        });
    }

    /**
     * 开启一场赛季：跨服广播给所有子服，每台子服各自公告并自动入队本地在线工会。
     * <p>必须只由一个子服触发（定时任务通过 Redis 抢锁，或管理员手动命令），
     * 否则共享配置下多台子服会重复开赛。</p>
     */
    public void startSeason(WarMode mode) {
        if (mode == null) {
            return;
        }
        if (plugin.bus() != null && plugin.bus().isReady()) {
            plugin.bus().publish("SEASON_OPEN", CrossServerBus.map("mode", mode.key));
        } else {
            applySeasonOpen(mode);
        }
    }

    /** 收到跨服开赛指令后在本服生效：公告 + 可选自动入队。 */
    private void applySeasonOpen(WarMode mode) {
        if (mode == null) {
            return;
        }
        plugin.guilds().broadcastAll(plugin.messages().component("war-season-open",
                "mode", mode.display, "minutes", mode.seasonWindowMinutes).append(queueJoinButton(mode)));
        if (mode.autoEnqueue) {
            for (Guild g : plugin.guilds().all()) {
                if (g.level() < mode.minLevel || warOf(g.id()) != null || isQueued(g.id())) {
                    continue;
                }
                if (plugin.guilds().onlineMembers(g).size() < mode.minMembers) {
                    continue;
                }
                enqueue(g.id(), g.name(), g.tag(), mode.key);
            }
        }
    }

    /** 管理员强制开启赛季。 */
    public void forceSeason(String modeKey) {
        startSeason(mode(modeKey));
    }

    private void enqueue(String guildId, String name, String tag, String modeKey) {
        if (plugin.bus().isReady()) {
            plugin.bus().queueGuild(guildId, CrossServerBus.map(
                    "name", name, "tag", tag, "server", plugin.serverId(), "mode", modeKey, "leader", "-"));
        } else {
            localQueue.add(guildId);
            localQueuedAt.put(guildId, System.currentTimeMillis());
            localMode.put(guildId, modeKey);
        }
    }

    private void matchTick() {
        if (!conf().enabled || current != null) {
            return;
        }
        try {
            if (plugin.bus().isReady()) {
                if (!plugin.bus().tryMatchLock()) {
                    return;
                }
                crossServerMatch();
            } else {
                localMatch();
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("工会战匹配异常: " + t.getMessage());
        }
    }

    private void crossServerMatch() {
        List<Map<String, String>> snapshot = plugin.bus().queueSnapshot();
        long now = System.currentTimeMillis();
        int timeout = conf().matchTimeoutSeconds;
        List<Map<String, String>> valid = new ArrayList<>();
        for (Map<String, String> meta : snapshot) {
            String id = meta.get("guildId");
            if (id == null) {
                continue;
            }
            Guild guild = plugin.guilds().guild(id);
            WarMode mode = mode(meta.getOrDefault("mode", conf().defaultMode));
            // 工会在排队后可能被解散、进入其它战斗、模式被关闭、或等级被调低，这里统一剔除。
            if (guild == null || warOf(id) != null) {
                plugin.bus().dequeueGuild(id);
                continue;
            }
            if (!mode.enabled) {
                plugin.bus().dequeueGuild(id);
                notifyQueueServer(meta, "war-queue-removed");
                continue;
            }
            if (guild.level() < mode.minLevel) {
                plugin.bus().dequeueGuild(id);
                notifyQueueServer(meta, "war-queue-removed");
                continue;
            }
            long queued = parseLong(meta.get("queuedAt"), now);
            if (timeout > 0 && now - queued > timeout * 1000L) {
                plugin.bus().dequeueGuild(id);
                notifyQueueServer(meta, "war-queue-timeout");
                continue;
            }
            valid.add(meta);
        }
        if (valid.size() < 2) {
            return;
        }
        // 必须找到「同一模式」的两个工会，否则继续等待（不能只看前两个）
        Map<String, String> a = null;
        Map<String, String> b = null;
        String chosenMode = null;
        outer:
        for (int i = 0; i < valid.size(); i++) {
            String mi = valid.get(i).getOrDefault("mode", conf().defaultMode);
            for (int j = i + 1; j < valid.size(); j++) {
                if (mi.equals(valid.get(j).getOrDefault("mode", conf().defaultMode))) {
                    a = valid.get(i);
                    b = valid.get(j);
                    chosenMode = mi;
                    break outer;
                }
            }
        }
        if (a == null || b == null) {
            return;
        }
        String idA = a.get("guildId");
        String idB = b.get("guildId");
        if (idA == null || idB == null || idA.equals(idB)) {
            return;
        }
        if (!plugin.bus().claimPair(idA, idB)) {
            return;
        }
        if (!createMatch(a, b, chosenMode)) {
            requeueFailedMatch(List.of(idA, idB), chosenMode);
        }
    }

    private void localMatch() {
        long now = System.currentTimeMillis();
        int timeout = conf().matchTimeoutSeconds;
        for (String id : new ArrayList<>(localQueue)) {
            long queued = localQueuedAt.getOrDefault(id, now);
            Guild g = plugin.guilds().guild(id);
            WarMode m = mode(localMode.getOrDefault(id, conf().defaultMode));
            boolean invalid = g == null || warOf(id) != null || !m.enabled || g.level() < m.minLevel;
            if (invalid) {
                localQueue.remove(id);
                localQueuedAt.remove(id);
                localMode.remove(id);
                if (g != null) {
                    final String key = (!m.enabled || g.level() < m.minLevel) ? "war-queue-removed" : "war-queue-timeout";
                    plugin.sync(() -> plugin.guilds().notifyGuild(id, plugin.messages().component(key), null));
                }
                continue;
            }
            if (timeout > 0 && now - queued > timeout * 1000L) {
                localQueue.remove(id);
                localQueuedAt.remove(id);
                localMode.remove(id);
                if (g != null) {
                    plugin.sync(() -> plugin.guilds().notifyGuild(id, plugin.messages().component("war-queue-timeout"), null));
                }
            }
        }
        // 按模式分组，同模式两两配对
        Map<String, List<String>> byMode = new LinkedHashMap<>();
        for (String id : localQueue) {
            byMode.computeIfAbsent(localMode.getOrDefault(id, conf().defaultMode), k -> new ArrayList<>()).add(id);
        }
        for (Map.Entry<String, List<String>> e : byMode.entrySet()) {
            List<String> ids = e.getValue();
            if (ids.size() < 2) {
                continue;
            }
            String idA = ids.remove(0);
            String idB = ids.remove(0);
            localQueue.remove(idA);
            localQueue.remove(idB);
            localQueuedAt.remove(idA);
            localQueuedAt.remove(idB);
            localMode.remove(idA);
            localMode.remove(idB);
            Map<String, String> a = CrossServerBus.map("guildId", idA, "server", plugin.serverId(), "mode", e.getKey());
            Map<String, String> b = CrossServerBus.map("guildId", idB, "server", plugin.serverId(), "mode", e.getKey());
            if (!createMatch(a, b, e.getKey())) {
                requeueFailedMatch(List.of(idA, idB), e.getKey());
            }
            return;
        }
    }

    /**
     * 创建一场工会战。
     *
     * @return true = 已写库并广播；false = 创建失败（工会消失 / 写库异常）
     */
    private boolean createMatch(Map<String, String> a, Map<String, String> b, String modeKey) {
        Guild ga = plugin.guilds().guild(a.get("guildId"));
        Guild gb = plugin.guilds().guild(b.get("guildId"));
        if (ga == null || gb == null) {
            return false;
        }
        WarMode mode = mode(modeKey);
        ArenaDef arenaDef = plugin.settings().arena(mode.arena);
        String host = mode.hostServer(arenaDef.server, conf().dedicatedServer, plugin.serverId());
        GuildWar war = new GuildWar(UUID.randomUUID().toString(), ga.id(), gb.id());
        war.mode(mode.key);
        war.nameA(ga.name());
        war.nameB(gb.name());
        war.tagA(ga.tag());
        war.tagB(gb.tag());
        war.state(WarState.MATCHED);
        war.hostServer(host);
        try {
            plugin.repo().insertWar(war);
        } catch (Exception e) {
            plugin.getLogger().warning("创建工会战写库失败: " + e.getMessage());
            return false;
        }
        Map<String, String> data = CrossServerBus.map(
                "war", war.id(), "host", host, "mode", mode.key,
                "a", ga.id(), "b", gb.id(),
                "nameA", ga.name(), "nameB", gb.name(),
                "tagA", ga.tag(), "tagB", gb.tag(),
                "server", plugin.serverId());
        if (plugin.bus().isReady()) {
            plugin.bus().publish("WAR_MATCHED", data);
        } else {
            handleWarMatched(data);
        }
        return true;
    }

    /**
     * 匹配创建失败时把双方重新放回队列并通知。
     *
     * <p>否则 {@code claimPair} 已经把两队移出队列，但它们永远不会被匹配，
     * 玩家侧表现为“点了匹配没反应、也收不到任何提示”。</p>
     */
    private void requeueFailedMatch(List<String> guildIds, String modeKey) {
        for (String id : guildIds) {
            Guild g = plugin.guilds().guild(id);
            if (g == null) {
                continue;
            }
            enqueue(id, g.name(), g.tag(), modeKey);
            plugin.sync(() -> plugin.guilds().notifyGuild(id,
                    plugin.messages().component("war-requeue-failed"), null));
        }
    }

    // =====================================================================
    // 跨服消息
    // =====================================================================

    @Override
    public void onCrossServer(String type, Map<String, String> data) {
        switch (type) {
            case "WAR_MATCHED" -> handleWarMatched(data);
            case "WAR_STATE" -> handleWarState(data);
            case "WAR_AUTOJOIN" -> {
                GuildWar w = data.get("war") == null ? null : observed.get(data.get("war"));
                if (w != null && !w.hostServer().equals(plugin.serverId())) {
                    plugin.sync(() -> autoEnterLocal(w));
                }
            }
            case "SEASON_OPEN" -> {
                WarMode mode = plugin.settings().mode(data.get("mode"));
                plugin.sync(() -> applySeasonOpen(mode));
            }
            case "WAR_QUEUE_TIMEOUT" -> {
                String guildId = data.get("guild");
                if (guildId != null) {
                    // key 由发起服指定并做白名单，避免旧版/恶意消息渲染任意 key；
                    // 缺省回退为超时文案（兼容只发 guild 的旧节点）。
                    String key = data.get("key");
                    if (key == null || !QUEUE_NOTIFY_KEYS.contains(key)) {
                        key = "war-queue-timeout";
                    }
                    String messageKey = key;
                    plugin.sync(() -> plugin.guilds().notifyGuild(guildId,
                            plugin.messages().component(messageKey), null));
                }
            }
            default -> {
            }
        }
    }

    private void handleWarMatched(Map<String, String> data) {
        String warId = data.get("war");
        String host = data.get("host");
        if (warId == null || host == null) {
            return;
        }
        GuildWar war = new GuildWar(warId, data.get("a"), data.get("b"));
        war.mode(data.get("mode"));
        war.nameA(data.get("nameA"));
        war.nameB(data.get("nameB"));
        war.tagA(data.get("tagA"));
        war.tagB(data.get("tagB"));
        war.hostServer(host);
        observed.put(warId, war);
        plugin.sync(() -> {
            if (host.equals(plugin.serverId())) {
                adoptWar(war);
            }
            // 非承办服不再本地通知：承办服 prepare 后会广播「匹配成功」，避免重复。
        });
    }

    private void handleWarState(Map<String, String> data) {
        String warId = data.get("war");
        GuildWar war = observed.get(warId);
        if (war == null) {
            return;
        }
        // 本服是承办方时状态/比分以本服为准，不让回环或乱序消息倒推状态机。
        GuildWar self = current;
        if (self != null && self.id().equals(warId)) {
            return;
        }
        war.state(WarState.parse(data.get("state")));
        war.scoreA(parseInt(data.get("sa"), war.scoreA()));
        war.scoreB(parseInt(data.get("sb"), war.scoreB()));
        if (!war.state().isLive()) {
            observed.remove(warId);
        }
    }

    // =====================================================================
    // 承办服：开战生命周期
    // =====================================================================

    private void adoptWar(GuildWar war) {
        if (current != null) {
            return; // 本服已在承办另一场
        }
        current = war;
        observed.put(war.id(), war);
        livesRemaining.clear();
        eliminated.clear();
        prepareArena(war);
    }

    private void prepareArena(GuildWar war) {
        WarMode mode = modeOf(war);
        ArenaDef def = plugin.settings().arena(mode.arena);
        currentArena = def;
        fallbackWorld = null;
        builtinWorld = null;
        if (def.tempWorld.builtin && !def.hasTemplate()) {
            World world = createBuiltinWorld(def);
            if (world == null) {
                cancelWar(war, "war-arena-failed");
                return;
            }
            fallbackWorld = world;
            applyWorldSettings(def, world);
            war.arenaWorld(world.getName());
            beginPrepare(war);
            return;
        }
        if (plugin.dungeon() != null && plugin.dungeon().available() && def.hasTemplate()
                && plugin.dungeon().hasTemplate(def.template)) {
            Set<ChunkPosition> chunks = plugin.settings().arenas.pregenerate ? computeChunks(def) : new HashSet<>();
            plugin.dungeon().create(def.template, chunks, Duration.ofSeconds(plugin.settings().arenas.createTimeoutSeconds))
                    .whenComplete((world, error) -> {
                        plugin.sync(() -> {
                            if (current != war || war.state() == WarState.CANCELLED) {
                                if (world != null) plugin.dungeon().destroy(world);
                                return;
                            }
                            if (error != null || world == null || world.world() == null) {
                                plugin.getLogger().warning("竞技场准备失败，工会战取消: " + (error == null ? "未返回可用世界" : error.getMessage()));
                                cancelWar(war, "war-arena-failed");
                                return;
                            }
                            arena = world;
                            war.arenaWorld(world.worldName());
                            applyWorldSettings(def, world.world());
                            beginPrepare(war);
                        });
                    });
        } else {
            World world = def.world != null && !def.world.isBlank() ? Bukkit.getWorld(def.world) : null;
            if (world == null && (def.world == null || def.world.isBlank()) && !Bukkit.getWorlds().isEmpty()) {
                world = Bukkit.getWorlds().get(0);
            }
            fallbackWorld = world;
            if (world == null) {
                plugin.getLogger().warning("未配置竞技场世界且无可加载世界，工会战取消。");
                cancelWar(war, "war-arena-failed");
                return;
            }
            if (def.hasTemplate()) {
                plugin.getLogger().warning("竞技场模板不可用，拒绝回退到普通世界: " + def.template);
                cancelWar(war, "war-arena-failed");
                return;
            }
            applyWorldSettings(def, world);
            war.arenaWorld(world.getName());
            beginPrepare(war);
        }
    }

    /** 创建 LiuGuild 自建临时世界（environment / name-format / world-type 均生效）。 */
    private World createBuiltinWorld(ArenaDef def) {
        try {
            String raw = def.tempWorld.nameFormat == null ? "guild_arena_{id}" : def.tempWorld.nameFormat;
            String name = raw.replace("{id}", def.id)
                    .replace("{time}", Long.toString(System.currentTimeMillis() % 1_000_000L))
                    .replaceAll("[^A-Za-z0-9_\\-]", "_");
            if (name.length() > 48) {
                name = name.substring(0, 48);
            }
            World existing = Bukkit.getWorld(name);
            if (existing != null) {
                builtinWorld = existing;
                return existing;
            }
            World.Environment env;
            try {
                env = World.Environment.valueOf(def.tempWorld.environment.toUpperCase(java.util.Locale.ROOT));
            } catch (Throwable t) {
                env = World.Environment.NORMAL;
            }
            WorldCreator wc = new WorldCreator(name).environment(env).generateStructures(false);
            wc.type("normal".equalsIgnoreCase(def.tempWorld.worldType) ? WorldType.NORMAL : WorldType.FLAT);
            World world = wc.createWorld();
            if (world != null) {
                builtinWorld = world;
                plugin.getLogger().info("已创建内置临时竞技场世界 " + name + "（" + env + "/" + wc.type() + "）");
            }
            return world;
        } catch (Throwable t) {
            plugin.getLogger().warning("创建内置临时世界失败: " + t.getMessage());
            return null;
        }
    }

    /** 卸载并异步删除内置临时世界。 */
    private void destroyBuiltinWorld() {
        World world = builtinWorld;
        builtinWorld = null;
        if (world == null) {
            return;
        }
        World fallback = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
        if (fallback != null && !fallback.equals(world)) {
            for (Player p : new ArrayList<>(world.getPlayers())) {
                warTeleport(p, fallback.getSpawnLocation());
            }
        }
        File folder = world.getWorldFolder();
        String name = world.getName();
        if (!Bukkit.unloadWorld(world, false)) {
            plugin.getLogger().warning("内置临时世界卸载失败: " + name);
            return;
        }
        plugin.async(() -> deleteFolder(folder));
    }

    private void deleteFolder(File dir) {
        if (dir == null || !dir.exists()) {
            return;
        }
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                deleteFolder(f);
            }
        }
        if (!dir.delete()) {
            plugin.getLogger().warning("内置临时世界目录删除失败: " + dir.getAbsolutePath());
        }
    }

    /**
     * 按名称查找 gamerule。新版本 Bukkit 的 {@code GameRule.getByName} 语义变为命名空间键，
     * 运行时可能返回 null；这里优先遍历枚举常量（跨版本稳定），再回退 getByName。
     */
    private static org.bukkit.GameRule<?> gameRule(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String target = name.trim();
        String path = target.toLowerCase(java.util.Locale.ROOT);
        if (path.startsWith("minecraft:")) {
            path = path.substring("minecraft:".length());
        }
        try {
            for (org.bukkit.GameRule<?> rule : org.bukkit.GameRule.values()) {
                if (rule.getName().equalsIgnoreCase(path)) {
                    return rule;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            return org.bukkit.GameRule.getByName(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 应用竞技场世界设置（gamerule / 时间 / 天气 / 难度），全部可自定义。 */
    @SuppressWarnings({"unchecked", "rawtypes", "deprecation"})
    private void applyWorldSettings(ArenaDef def, World world) {
        if (def == null || world == null) {
            return;
        }
        ArenaDef.TempWorld tw = def.tempWorld;
        try {
            for (Map.Entry<String, Boolean> e : tw.gamerules.entrySet()) {
                org.bukkit.GameRule rule = org.bukkit.GameRule.getByName(e.getKey());
                if (rule != null) {
                    world.setGameRule(rule, e.getValue());
                } else {
                    plugin.getLogger().warning("未知 gamerule: " + e.getKey() + "（竞技场 " + def.id + "）");
                }
            }
            if (tw.time >= 0) {
                world.setTime(tw.time);
            }
            if (tw.weather != null && !tw.weather.isBlank()) {
                switch (tw.weather.toLowerCase(java.util.Locale.ROOT)) {
                    case "clear" -> {
                        world.setStorm(false);
                        world.setThundering(false);
                    }
                    case "rain" -> {
                        world.setStorm(true);
                        world.setThundering(false);
                    }
                    case "thunder" -> {
                        world.setStorm(true);
                        world.setThundering(true);
                    }
                    default -> plugin.getLogger().warning("未知天气: " + tw.weather);
                }
            }
            if (tw.difficulty != null && !tw.difficulty.isBlank()) {
                world.setDifficulty(org.bukkit.Difficulty.valueOf(tw.difficulty.toUpperCase(java.util.Locale.ROOT)));
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("应用竞技场世界设置失败（" + def.id + "）: " + t.getMessage());
        }
    }

    private Set<ChunkPosition> computeChunks(ArenaDef def) {
        Set<ChunkPosition> chunks = new HashSet<>();
        int radius = def.radius;
        if (radius <= 0) {
            return chunks;
        }
        int cx = (int) ((def.spawnA.x() + def.spawnB.x()) / 2);
        int cz = (int) ((def.spawnA.z() + def.spawnB.z()) / 2);
        for (int x = cx - radius; x <= cx + radius; x += 16) {
            for (int z = cz - radius; z <= cz + radius; z += 16) {
                chunks.add(ChunkPosition.fromBlock(x, z));
                if (chunks.size() >= 1024) {
                    return chunks;
                }
            }
        }
        return chunks;
    }

    private void beginPrepare(GuildWar war) {
        WarMode mode = modeOf(war);
        war.state(WarState.PREPARING);
        phaseUntil = System.currentTimeMillis() + mode.prepareSeconds * 1000L;
        lastBroadcastSecond = -1;
        Bukkit.getPluginManager().callEvent(new GuildWarMatchEvent(war));
        boolean auto = plugin.settings().war.autoEnter;
        String key = auto ? "war-match-found-auto" : "war-match-found";
        Component msgA = plugin.messages().component(key, "enemy", war.nameB(), "seconds", mode.prepareSeconds);
        Component msgB = plugin.messages().component(key, "enemy", war.nameA(), "seconds", mode.prepareSeconds);
        if (!auto) {
            msgA = msgA.append(arenaJoinButton());
            msgB = msgB.append(arenaJoinButton());
        }
        plugin.guilds().announceGuild(war.guildA(), msgA, null);
        plugin.guilds().announceGuild(war.guildB(), msgB, null);
        plugin.getLogger().info("工会战 " + war.id() + " [" + mode.display + "] 进入准备阶段: "
                + war.nameA() + " vs " + war.nameB() + "，竞技场=" + arenaName());
        if (auto) {
            // 本服在线成员直接传送参战；其余服收到 WAR_AUTOJOIN 后把本服成员转移过来。
            autoEnterLocal(war);
            String wid = war.id();
            String host = war.hostServer();
            String ga = war.guildA();
            String gb = war.guildB();
            plugin.async(() -> plugin.bus().publish("WAR_AUTOJOIN",
                    CrossServerBus.map("war", wid, "host", host, "a", ga, "b", gb)));
        }
    }

    /** 匹配成功后自动把本服在线成员传送进竞技场（承办服直接加入，其余服转移过去）。 */
    private void autoEnterLocal(GuildWar war) {
        if (!plugin.settings().war.autoEnter || war == null) {
            return;
        }
        autoEnterGuild(war, war.guildA(), "A");
        autoEnterGuild(war, war.guildB(), "B");
    }

    private void autoEnterGuild(GuildWar war, String guildId, String side) {
        Guild guild = plugin.guilds().guild(guildId);
        if (guild == null) {
            return;
        }
        Set<UUID> roster = "A".equals(side) ? war.rosterA() : war.rosterB();
        WarMode mode = modeOf(war);
        boolean host = war.hostServer().equals(plugin.serverId());
        for (Player p : plugin.guilds().onlineMembers(guild)) {
            if (p == null || !p.isOnline()) {
                continue;
            }
            if (!p.hasPermission("liuguild.war")) {
                continue; // 无权限视为自愿不参战
            }
            if (roster.contains(p.getUniqueId()) || spectators.contains(p.getUniqueId())) {
                continue;
            }
            if (host) {
                if (roster.size() >= mode.teamSize) {
                    continue; // 名额已满，不强制观战
                }
                joinLocal(p, war);
            } else {
                transferToWar(p, war);
            }
        }
    }

    private void transferToWar(Player player, GuildWar war) {
        if (!plugin.bus().isReady() || !plugin.bus().transfer().available()) {
            return;
        }
        final UUID uuid = player.getUniqueId();
        final String host = war.hostServer();
        final String warId = war.id();
        // 先异步写「待入场」标记（Redis IO），成功后再回主线程发起代理传送，
        // 保证玩家抵达承办服时标记已就绪（主线程不做阻塞 Redis）。
        plugin.async(() -> {
            plugin.bus().setPendingWar(uuid, warId);
            plugin.sync(() -> {
                if (player.isOnline()) {
                    plugin.bus().transfer().connect(player, host);
                }
            });
        });
    }

    // =====================================================================
    // 准备阶段锁定（冻结）
    // =====================================================================

    /** 锁定一名参赛者：固定位置/视角、禁动、禁交互，确保开战瞬间站位一致。 */
    private void freezePlayer(Player player, GuildWar war, String side) {
        if (player == null || !player.isOnline()) {
            return;
        }
        WarMode mode = modeOf(war);
        if (mode == null || !mode.freezeOnPrepare) {
            return;
        }
        // 再次盯回出生点，避免传送偏差
        ArenaDef def = plugin.settings().arena(mode.arena);
        ArenaDef.Spawn spawn = def == null ? null : def.teamSpawn(side);
        if (spawn != null && arenaWorld() != null) {
            warTeleport(player, spawn.toLocation(arenaWorld()));
        }
        frozen.add(player.getUniqueId());
        player.setWalkSpeed(0f);
        player.setFlySpeed(0f);
        player.setAllowFlight(false);
        player.setFlying(false);
        player.setInvulnerable(true);
        player.setCollidable(false);
        player.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
        // 出生点下方垫一层屏障，从物理上禁止起跳（可关）
        placePreparePlatform(player, mode, def, side);
        if (!player.getGameMode().isInvulnerable()) {
            player.setGameMode(org.bukkit.GameMode.ADVENTURE);
        }
    }

    /**
     * 在出生点脚下垫一层 BARRIER，让客户端连「跳」这个动作都无法发起
     * （PacketEvents 取消 {@code PLAYER_INPUT} 拦不住已生效的跳跃）。
     */
    private void placePreparePlatform(Player player, WarMode mode, ArenaDef def, String side) {
        if (mode == null || !mode.preparePlatform || mode.allowJumpOnPrepare) {
            return;
        }
        ArenaDef.Spawn spawn = def == null ? null : def.teamSpawn(side);
        World world = arenaWorld();
        if (spawn == null || world == null) {
            return;
        }
        int x = (int) Math.floor(spawn.x());
        int y = (int) Math.floor(spawn.y()) - 1;
        int z = (int) Math.floor(spawn.z());
        // 铺 3x3，避免站在边缘时从旁边跳起
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                org.bukkit.block.Block block = world.getBlockAt(x + dx, y, z + dz);
                if (block.getType().isAir() || !block.getType().isSolid()) {
                    preparedPlatform.put(block.getLocation(), block.getType());
                    block.setType(org.bukkit.Material.BARRIER, false);
                }
            }
        }
    }

    /** 移除准备阶段垫的所有屏障，恢复原方块。 */
    private void removePreparePlatform() {
        if (preparedPlatform.isEmpty()) {
            return;
        }
        for (Map.Entry<Location, org.bukkit.Material> e : preparedPlatform.entrySet()) {
            try {
                org.bukkit.block.Block block = e.getKey().getBlock();
                if (block.getType() == org.bukkit.Material.BARRIER) {
                    block.setType(e.getValue(), false);
                }
            } catch (Throwable ignored) {
                // 世界可能已卸载，忽略
            }
        }
        preparedPlatform.clear();
    }

    /** 解除锁定：恢复行走速度与生存模式。 */
    private void unfreezePlayer(Player player) {
        if (player == null) {
            return;
        }
        if (!frozen.remove(player.getUniqueId())) {
            return;
        }
        player.setWalkSpeed(0.2f);
        player.setFlySpeed(0.1f);
        player.setInvulnerable(false);
        player.setCollidable(true);
        if (player.getGameMode() == org.bukkit.GameMode.ADVENTURE) {
            player.setGameMode(org.bukkit.GameMode.SURVIVAL);
        }
    }

    /** 解除当前战局所有参赛者的锁定（开战 / 结束时调用）。 */
    private void unfreezeAll(GuildWar war) {
        removePreparePlatform();
        if (frozen.isEmpty()) {
            return;
        }
        java.util.Set<UUID> targets = new java.util.HashSet<>(frozen);
        for (UUID u : targets) {
            Player p = plugin.player(u);
            if (p != null) {
                unfreezePlayer(p);
            } else {
                frozen.remove(u);
            }
        }
    }

    /** 准备阶段每秒调用：把被抓回出生点或被改速度的玩家重新锁好。 */
    private void enforceFreeze(GuildWar war) {
        if (frozen.isEmpty()) {
            return;
        }
        WarMode mode = modeOf(war);
        if (mode == null || !mode.freezeOnPrepare) {
            unfreezeAll(war);
            return;
        }
        for (UUID u : new java.util.ArrayList<>(frozen)) {
            Player p = plugin.player(u);
            if (p == null) {
                frozen.remove(u);
                continue;
            }
            String side = sideOf.get(u);
            ArenaDef def = plugin.settings().arena(mode.arena);
            ArenaDef.Spawn spawn = def == null || side == null ? null : def.teamSpawn(side);
            World world = arenaWorld();
            Location target = spawn != null && world != null ? spawn.toLocation(world) : null;
            if (target != null) {
                Location cur = p.getLocation();
                if (cur.getWorld() != world || cur.distanceSquared(target) > 1.0) {
                    warTeleport(p, target);
                }
            }
            if (p.getWalkSpeed() != 0f) {
                p.setWalkSpeed(0f);
            }
            if (!p.isInvulnerable()) {
                p.setInvulnerable(true);
            }
            // 跳跃兜底：PacketEvents 取消 PLAYER_INPUT 拦不住已生效的跳跃，
            // 这里一旦发现 Y 方向速度/高度偏离就立即清零（并拉回出生点）。
            if (!mode.allowJumpOnPrepare) {
                org.bukkit.util.Vector v = p.getVelocity();
                if (Math.abs(v.getY()) > 1.0E-4) {
                    p.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
                    if (target != null) {
                        warTeleport(p, target);
                    }
                }
            }
        }
    }

    private void beginActive(GuildWar war) {
        WarMode mode = modeOf(war);
        if (war.rosterA().isEmpty() || war.rosterB().isEmpty()) {
            cancelWar(war, "war-no-participants");
            return;
        }
        unfreezeAll(war);
        war.state(WarState.ACTIVE);
        initializeLives(war, modeOf(war));
        war.startedAt(System.currentTimeMillis());
        phaseUntil = System.currentTimeMillis() + mode.durationSeconds * 1000L;
        lastBroadcastSecond = -1;
        if (warBar == null && plugin.settings().war.bossBarEnabled) {
            warBar = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.RED, BossBar.Overlay.PROGRESS);
        }
        if (warBar != null) {
            for (UUID u : war.rosterA()) {
                showWarBar(u);
            }
            for (UUID u : war.rosterB()) {
                showWarBar(u);
            }
        }
        Bukkit.getPluginManager().callEvent(new GuildWarStartEvent(war));
        broadcastWar(war, plugin.messages().component("war-started", "guild_a", war.nameA(), "guild_b", war.nameB()));
        publishState(war);
    }

    /** 每秒主线程 tick：倒计时、结束判定、比分播报。 */
    private void warTick() {
        try {
            warTickInternal();
        } catch (Throwable t) {
            // 单次 tick 异常不能杀死重复任务，否则战斗永远无法结算/回收。
            plugin.getLogger().warning("工会战 tick 异常（任务继续运行）: " + t);
        }
    }

    private void warTickInternal() {
        long now = System.currentTimeMillis();
        pruneStaleMirrors(now);
        GuildWar war = current;
        if (war == null) {
            return;
        }
        long remain = Math.max(0, phaseUntil - now);
        long sec = (remain + 999) / 1000;
        if (warBar != null) {
            int duration = Math.max(1, modeOf(war).durationSeconds);
            float progress = war.state() == WarState.ACTIVE ? (float) remain / (duration * 1000f) : 0f;
            warBar.name(scoreLine(war).append(Component.text("  " + sec + "s")));
            warBar.progress(Math.max(0f, Math.min(1f, progress)));
        }
        switch (war.state()) {
            case PREPARING -> {
                enforceFreeze(war);
                if (sec != lastBroadcastSecond && (sec <= 10 || sec % 30 == 0)) {
                    lastBroadcastSecond = sec;
                    broadcastWar(war, plugin.messages().component("war-countdown", "seconds", sec));
                }
                if (remain <= 0) {
                    beginActive(war);
                }
            }
            case ACTIVE -> {
                if (sec != lastBroadcastSecond && sec % 60 == 0 && sec > 0) {
                    lastBroadcastSecond = sec;
                    broadcastWar(war, scoreLine(war));
                }
                if (remain <= 0) {
                    endWar(war);
                }
            }
            case SETTLING -> {
                settling = true;
                // 分帧发放奖励：每 tick 最多 settleBatchPerTick 人，避免大名单一次性阻塞。
                int batch = Math.max(1, conf().settleBatchPerTick);
                WarMath.drainBatch(settleQueue, batch, r -> r.run());
                if (remain <= 0 && settleQueue.isEmpty()) {
                    cleanup(war);
                }
            }
            default -> {
            }
        }
    }

    /** 周期性清理跨服镜像，避免承办服崩溃/消息丢失后本服一直被判定为进行中。 */
    private void pruneStaleMirrors(long now) {
        if (observed.isEmpty() || now - lastMirrorPruneAt < 30_000L) {
            return;
        }
        lastMirrorPruneAt = now;
        observed.values().removeIf(w -> w != current && (!w.state().isLive() || isStaleMirror(w, now)));
    }

    private void endWar(GuildWar war) {
        if (war.state() != WarState.ACTIVE && war.state() != WarState.PREPARING) {
            return;
        }
        war.state(WarState.SETTLING);
        war.endedAt(System.currentTimeMillis());
        String winner = !forcedWinner.isBlank() ? forcedWinner
                : (war.scoreA() == war.scoreB() ? "" : (war.scoreA() > war.scoreB() ? war.guildA() : war.guildB()));
        forcedWinner = "";
        war.winner(winner);
        try {
            plugin.repo().transition(war, WarState.ACTIVE, WarState.SETTLING);
            plugin.repo().updateWar(war);
        } catch (Exception e) {
            plugin.getLogger().warning("结算工会战写库失败: " + e.getMessage());
        }
        GuildWarEndEvent.Result result = winner.isEmpty()
                ? GuildWarEndEvent.Result.DRAW
                : (winner.equals(war.guildA()) ? GuildWarEndEvent.Result.A_WIN : GuildWarEndEvent.Result.B_WIN);
        Bukkit.getPluginManager().callEvent(new GuildWarEndEvent(war, result));
        if (winner.isEmpty()) {
            broadcastWar(war, plugin.messages().component("war-draw", "score_a", war.scoreA(), "score_b", war.scoreB()));
        } else {
            String winnerName = winner.equals(war.guildA()) ? war.nameA() : war.nameB();
            broadcastWar(war, plugin.messages().component("war-ended",
                    "winner", winnerName, "score_a", war.scoreA(), "score_b", war.scoreB()));
        }
        applyRewards(war, winner);
        publishState(war, true);
        phaseUntil = System.currentTimeMillis() + modeOf(war).settleSeconds * 1000L;
    }

    /**
     * 工会层奖励立即结算（内存 + 异步落库，开销很小）；
     * 玩家层奖励（金币/通行证/称号/消息）进入分帧队列，
     * 由 {@link #warTick} 在结算阶段每 tick 发放一批，避免一次性阻塞主线程。
     */
    private void applyRewards(GuildWar war, String winnerId) {
        WarMode w = modeOf(war);
        double mult = w.scheduled() ? Math.max(1.0, w.seasonPointsMultiplier) : 1.0;
        // 平局时两方均按参与奖励结算，不发胜利称号。
        boolean aWon = winnerId != null && winnerId.equals(war.guildA());
        boolean bWon = winnerId != null && winnerId.equals(war.guildB());
        long nominalA = wonGuildFunds(aWon, w, mult);
        long nominalB = wonGuildFunds(bWon, w, mult);
        long fundsA = rewardGuild(plugin.guilds().guild(war.guildA()), aWon, w, mult);
        long fundsB = rewardGuild(plugin.guilds().guild(war.guildB()), bWon, w, mult);
        for (UUID u : new ArrayList<>(war.rosterA())) {
            settleQueue.add(() -> rewardPlayer(u, aWon, w, mult, fundsA, nominalA));
        }
        for (UUID u : new ArrayList<>(war.rosterB())) {
            settleQueue.add(() -> rewardPlayer(u, bWon, w, mult, fundsB, nominalB));
        }
    }

    /**
     * 发放工会资金奖励（受该模式每日上限约束）。
     *
     * @return 实际（预计）发放的点数，用于结算消息展示
     */
    private long rewardGuild(Guild guild, boolean won, WarMode mode, double multiplier) {
        if (guild == null) {
            return 0;
        }
        long nominal = wonGuildFunds(won, mode, multiplier);
        long granted = plugin.guilds().grantWarFunds(guild, nominal, mode.dailyGuildFundsCap);
        if (won) {
            guild.wins(guild.wins() + 1);
        } else {
            guild.losses(guild.losses() + 1);
        }
        plugin.guilds().saveGuild(guild);
        return granted;
    }

    private void rewardPlayer(UUID uuid, boolean win, WarMode mode, double multiplier,
                              long guildFundsGranted, long guildFundsNominal) {
        double money = (win ? mode.winPlayerMoney : mode.losePlayerMoney) * multiplier;
        int passXp = (int) Math.min(Integer.MAX_VALUE,
                Math.round((win ? mode.winPassXp : mode.losePassXp) * multiplier));
        int titleId = win ? mode.winTitleId : 0;
        boolean hasTitle = titleId > 0 && plugin.playerTitle() != null;

        if (money > 0 && plugin.economy() != null && plugin.economy().available()) {
            plugin.economy().deposit(plugin.offline(uuid), money);
        }
        if (plugin.pass() != null && passXp > 0) {
            plugin.pass().addXp(uuid, mode.passId, passXp);
        }
        if (hasTitle) {
            plugin.playerTitle().grant(uuid, titleId);
        }

        Player player = plugin.player(uuid);
        if (player == null) {
            return;
        }
        // 分行展示：只列出「实际有值」的奖励，没有的项不占一行
        plugin.messages().sendRaw(player, win ? "war-reward-win-header" : "war-reward-lose-header");
        if (money > 0) {
            plugin.messages().sendRaw(player, "war-reward-line-money",
                    "money", plugin.economy().format(money));
        }
        if (guildFundsGranted > 0) {
            plugin.messages().sendRaw(player, "war-reward-line-funds", "funds", String.valueOf(guildFundsGranted));
        } else if (guildFundsNominal > 0) {
            plugin.messages().sendRaw(player, "war-reward-line-funds-capped",
                    "cap", String.valueOf(mode.dailyGuildFundsCap));
        }
        if (passXp > 0) {
            plugin.messages().sendRaw(player, "war-reward-line-pass",
                    "pass_xp", String.valueOf(passXp),
                    "pass", mode.passId == null || mode.passId.isBlank() ? "通行证" : mode.passId);
        }
        if (hasTitle) {
            plugin.messages().sendRaw(player, "war-reward-line-title", "title", String.valueOf(titleId));
        }
        runRewardCommands(player, win, mode);
        plugin.messages().sendRaw(player, "war-reward-footer");
    }

    /**
     * 执行自定义指令奖励，并把每条指令的「自定义名称」分行展示给玩家。
     * <p>{@code rewards.commands} 双方执行；{@code rewards.win-commands} 仅胜方；
     * {@code rewards.lose-commands} 仅负方（含平局）。</p>
     */
    private void runRewardCommands(Player player, boolean win, WarMode mode) {
        if (mode.extraRewards.isEmpty()) {
            return;
        }
        Guild guild = plugin.guilds().guildOf(player.getUniqueId());
        for (WarMode.CommandReward reward : mode.extraRewards) {
            if (!reward.appliesTo(win)) {
                continue;
            }
            boolean executed = false;
            for (String raw : reward.commands) {
                String cmd = applyRewardPlaceholders(raw, player, guild, mode);
                if (cmd.isBlank()) {
                    continue;
                }
                try {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                    executed = true;
                } catch (Throwable t) {
                    plugin.getLogger().warning("工会战指令奖励执行失败 [" + cmd + "]: " + t.getMessage());
                }
            }
            // 名称就是奖励展示文字；执行过的奖励才分行显示
            if (executed) {
                plugin.messages().sendRaw(player, "war-reward-line-command", "label", reward.name);
            }
        }
    }

    /** 替换指令奖励里的占位符。 */
    private String applyRewardPlaceholders(String cmd, Player player, Guild guild, WarMode mode) {
        GuildWar war = current;
        boolean sideA = war != null && guild != null && war.guildA().equals(guild.id());
        int mine = war == null ? 0 : (sideA ? war.scoreA() : war.scoreB());
        int theirs = war == null ? 0 : (sideA ? war.scoreB() : war.scoreA());
        return cmd.replace("{player}", player.getName())
                .replace("{uuid}", player.getUniqueId().toString())
                .replace("{guild}", guild == null ? "" : guild.name())
                .replace("{guild_id}", guild == null ? "" : guild.id())
                .replace("{mode}", mode.key)
                .replace("{score}", String.valueOf(mine))
                .replace("{enemy_score}", String.valueOf(theirs));
    }

    private long wonGuildFunds(boolean win, WarMode mode, double multiplier) {
        return Math.round((win ? mode.winGuildFunds : mode.loseGuildFunds) * multiplier);
    }

    public boolean forceEnd(String warId) {
        GuildWar war = current;
        if (war == null || (warId != null && !war.id().equals(warId))) {
            return false;
        }
        endWar(war);
        return true;
    }

    /**
     * 管理员强制开战：直接匹配两支工会。
     *
     * @return null 表示已开始；否则返回失败原因对应的消息键
     */
    public String forceStart(Guild a, Guild b, String modeKey) {
        if (a == null || b == null) {
            return "guild-not-found";
        }
        if (a.id().equals(b.id())) {
            return "same-guild";
        }
        if (warOf(a.id()) != null || warOf(b.id()) != null) {
            return "war-in-progress";
        }
        WarMode mode = mode(modeKey);
        if (!mode.enabled) {
            return "war-mode-disabled";
        }
        if (plugin.bus().isReady()) {
            plugin.bus().dequeueGuild(a.id());
            plugin.bus().dequeueGuild(b.id());
        }
        createMatch(
                CrossServerBus.map("guildId", a.id(), "server", plugin.serverId(), "mode", mode.key),
                CrossServerBus.map("guildId", b.id(), "server", plugin.serverId(), "mode", mode.key),
                mode.key);
        return null;
    }

    /** 管理员设置比分（a/b）。 */
    public boolean setScore(String warId, String side, int value) {
        GuildWar war = warById(warId);
        if (war == null) {
            return false;
        }
        if ("b".equalsIgnoreCase(side)) {
            war.scoreB(Math.max(0, value));
        } else {
            war.scoreA(Math.max(0, value));
        }
        publishState(war, true);
        return true;
    }

    /** 把管理员传送到当前竞技场（旁观点）；无进行中战斗时传到默认场地 A 点。 */
    public boolean teleportToArena(Player player) {
        GuildWar war = current;
        ArenaDef def = war != null ? plugin.settings().arena(modeOf(war).arena) : currentArena();
        World world = arenaWorld();
        if (world == null) {
            world = def.world != null && !def.world.isBlank() ? Bukkit.getWorld(def.world)
                    : (Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0));
        }
        if (world == null) {
            return false;
        }
        Location target = war != null ? def.spawnSpectator.toLocation(world) : def.spawnA.toLocation(world);
        player.teleport(target);
        return true;
    }

    /** 工会被解散时：若其正在进行工会战，则取消该场战斗。 */
    public void onGuildDisbanded(String guildId) {
        GuildWar war = current;
        if (war != null && (guildId.equals(war.guildA()) || guildId.equals(war.guildB()))) {
            cancelWar(war, "war-guild-disbanded");
        }
        localQueue.remove(guildId);
        localQueuedAt.remove(guildId);
        localMode.remove(guildId);
        if (plugin.bus().isReady()) {
            plugin.async(() -> plugin.bus().dequeueGuild(guildId));
        }
    }

    private void cancelWar(GuildWar war, String reasonKey) {
        war.state(WarState.CANCELLED);
        observed.remove(war.id());
        settleQueue.clear();
        clearWarBar();
        broadcastWar(war, plugin.messages().component(reasonKey));
        try {
            plugin.repo().updateWar(war);
        } catch (Exception ignored) {
        }
        cleanup(war);
    }

    private void cleanup(GuildWar war) {
        cleanup(war, true);
    }

    private void cleanup(GuildWar war, boolean publishTerminal) {
        // 传送所有参赛者/旁观者回原位并还原数据
        for (UUID u : new ArrayList<>(war.rosterA())) {
            leaveLocal(u);
        }
        for (UUID u : new ArrayList<>(war.rosterB())) {
            leaveLocal(u);
        }
        for (UUID u : new ArrayList<>(spectators)) {
            leaveSpectator(u);
        }
        war.rosterA().clear();
        war.rosterB().clear();
        ArenaDef activeDef = currentArena;
        boolean keepWorld = activeDef != null && activeDef.tempWorld.keepOnEnd;
        if (builtinWorld != null) {
            if (keepWorld) {
                plugin.getLogger().info("工会战 " + war.id() + " 已结束，按配置保留内置世界（" + builtinWorld.getName() + "）。");
                builtinWorld = null;
            } else {
                destroyBuiltinWorld();
            }
        } else if (plugin.dungeon() != null && arena != null && !keepWorld) {
            plugin.dungeon().destroy(arena);
        } else if (keepWorld && arena != null) {
            plugin.getLogger().info("工会战 " + war.id() + " 已结束，按配置保留动态世界（" + arena.worldName() + "）。");
        }
        arena = null;
        fallbackWorld = null;
        currentArena = null;
        livesRemaining.clear();
        eliminated.clear();
        handledLethal.clear();
        frozen.clear();
        removePreparePlatform();
        settling = false;
        settleQueue.clear();
        clearWarBar();
        observed.remove(war.id());
        // 正常结束记为 ENDED（写入库并进入历史记录/清理）；取消保持 CANCELLED。
        if (war.state() != WarState.CANCELLED && war.state() != WarState.ENDED) {
            war.state(WarState.ENDED);
        }
        if (war.endedAt() <= 0) {
            war.endedAt(System.currentTimeMillis());
        }
        try {
            plugin.repo().updateWar(war);
        } catch (Exception e) {
            plugin.getLogger().warning("工会战收尾写库失败: " + e.getMessage());
        }
        current = null;
        // 广播终态，让其它子服移除镜像；否则跨服玩家会一直被判定为「正在工会战中」。
        if (publishTerminal) {
            publishState(war, true);
        }
        plugin.getLogger().info("工会战 " + war.id() + " 已结束并回收竞技场。");
    }

    // =====================================================================
    // 参赛 / 旁观
    // =====================================================================

    public void join(Player player) {
        if (!player.hasPermission("liuguild.war")) {
            plugin.messages().send(player, "no-permission");
            return;
        }
        Guild guild = plugin.guilds().guildOf(player.getUniqueId());
        if (guild == null) {
            plugin.messages().send(player, "not-in-guild");
            return;
        }
        GuildWar war = warOf(guild.id());
        if (war == null) {
            plugin.messages().send(player, "war-not-in-war");
            return;
        }
        if (war.hostServer().equals(plugin.serverId())) {
            joinLocal(player, war);
        } else {
            if (!plugin.bus().isReady() || !plugin.bus().transfer().available()) {
                plugin.messages().send(player, "war-transfer-failed");
                return;
            }
            final UUID uuid = player.getUniqueId();
            final String host = war.hostServer();
            final String warId = war.id();
            // 跟加入提示保持同步：先异步写待入场标记，再回主线程发起传送。
            plugin.async(() -> {
                plugin.bus().setPendingWar(uuid, warId);
                plugin.sync(() -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    if (!plugin.bus().transfer().connect(player, host)) {
                        plugin.messages().send(player, "war-transfer-failed");
                    } else {
                        plugin.messages().send(player, "war-joined");
                    }
                });
            });
        }
    }

    /** 玩家进入承办服后由监听器调用，消费待处理工会战。 */
    public void onPlayerJoinServer(Player player) {
        final UUID uuid = player.getUniqueId();
        // 消费标记是 Redis IO，放异步；拿到后再回主线程入队，避免卡主线程。
        plugin.async(() -> {
            String warId = plugin.bus().consumePendingWar(uuid);
            if (warId == null) {
                return;
            }
            plugin.sync(() -> {
                if (!player.isOnline()) {
                    return;
                }
                GuildWar war = current;
                if (war != null && war.id().equals(warId)) {
                    joinLocal(player, war);
                }
            });
        });
    }

    private void joinLocal(Player player, GuildWar war) {
        if (player == null || !player.isOnline() || localSnap.containsKey(player.getUniqueId())
                || spectators.contains(player.getUniqueId()) || arenaWorld() == null
                || (war.state() != WarState.MATCHED && war.state() != WarState.PREPARING)) return;
        Guild guild = plugin.guilds().guildOf(player.getUniqueId());
        if (guild == null) {
            return;
        }
        String side = guild.id().equals(war.guildA()) ? "A" : (guild.id().equals(war.guildB()) ? "B" : null);
        String s = side;
        if (s == null) {
            return;
        }
        Set<UUID> roster = "A".equals(s) ? war.rosterA() : war.rosterB();
        WarMode mode = modeOf(war);
        if (roster.contains(player.getUniqueId())) {
            return; // 已在名单：幂等返回，避免重复提示“已满”
        }
        if (roster.size() >= mode.teamSize) {
            if (mode.allowSpectators) {
                if (spectateLocal(player, war)) {
                    plugin.messages().send(player, "war-spectate");
                } else {
                    plugin.messages().send(player, "join-full", "guild", guild.name());
                }
            } else {
                plugin.messages().send(player, "join-full", "guild", guild.name());
            }
            return;
        }
        if (war.state() == WarState.ACTIVE || war.state() == WarState.SETTLING) {
            plugin.messages().send(player, "war-join-locked");
            return;
        }
        roster.add(player.getUniqueId());
        sideOf.put(player.getUniqueId(), s);
        returnLoc.put(player.getUniqueId(), player.getLocation().clone());
        livesRemaining.putIfAbsent(player.getUniqueId(), mode.livesPerPlayer);
        // 入场前先关闭所有打开的菜单/仓库，防止战中操作外部容器
        player.closeInventory();
        captureSnapshot(player, war, () -> {
            if (!player.isOnline() || current != war || !roster.contains(player.getUniqueId())) {
                return;
            }
            // 公平血量：屏蔽 LuckyPeaches 蟠桃加成
            if (mode.fairHealth && plugin.luckyPeaches() != null && plugin.luckyPeaches().available()) {
                plugin.luckyPeaches().enter(player, plugin.luckyPeaches().supportsSuppress());
            }
            plugin.kits().apply(player, mode);
            teleportToSpawn(player, war, s);
            plugin.messages().send(player, "war-joined");
            // 传送后确保 game mode，并在准备阶段锁定（开战时自动解除）
            player.setGameMode(org.bukkit.GameMode.SURVIVAL);
            if (war.state() == WarState.PREPARING) {
                freezePlayer(player, war, s);
            }
            showWarBar(player.getUniqueId());
        });
    }

    public void spectate(Player player) {
        Guild guild = plugin.guilds().guildOf(player.getUniqueId());
        GuildWar war = guild == null ? current : warOf(guild.id());
        if (war == null || !war.hostServer().equals(plugin.serverId())) {
            plugin.messages().send(player, "war-not-in-war");
            return;
        }
        if (spectateLocal(player, war)) {
            plugin.messages().send(player, "war-spectate");
        } else {
            plugin.messages().send(player, "war-spectate-disabled");
        }
    }

    private boolean spectateLocal(Player player, GuildWar war) {
        if (player == null || !player.isOnline() || localSnap.containsKey(player.getUniqueId())
                || spectators.contains(player.getUniqueId()) || sideOf.containsKey(player.getUniqueId())
                || !modeOf(war).allowSpectators || !war.state().isLive()) return false;
        World world = arenaWorld();
        if (world == null) return false;
        spectators.add(player.getUniqueId());
        returnLoc.putIfAbsent(player.getUniqueId(), player.getLocation().clone());
        player.closeInventory();
        ArenaDef def = plugin.settings().arena(modeOf(war).arena);
        Location target = def.spawnSpectator.toLocation(world);
        // 旁观者同样需要快照：旁观会改写游戏模式与坐标，崩溃/掉线时必须能兜底还原，
        // 否则玩家会永久卡在旁观模式（此前无快照直接还原，SPECTATOR 不会恢复）。
        captureSnapshot(player, war, () -> {
            if (!player.isOnline() || current != war || !spectators.contains(player.getUniqueId())) {
                return;
            }
            player.setGameMode(org.bukkit.GameMode.SPECTATOR);
            warTeleport(player, target);
            showWarBar(player.getUniqueId());
        });
        return true;
    }

    private void leaveLocal(UUID uuid) {
        Player p = plugin.player(uuid);
        unfreezePlayer(p);
        restoreSnapshot(uuid, p, false, () -> {
            finishTeleport(uuid, plugin.player(uuid));
            returnLoc.remove(uuid);
        });
        if (plugin.luckyPeaches() != null && plugin.luckyPeaches().available()) {
            if (p != null) {
                plugin.luckyPeaches().exit(p);
            } else {
                plugin.luckyPeaches().clear(uuid);
            }
        }
        spectators.remove(uuid);
        sideOf.remove(uuid);
        livesRemaining.remove(uuid);
        eliminated.remove(uuid);
        if (p != null) {
            p.setGameMode(org.bukkit.GameMode.SURVIVAL);
            hideWarBar(p);
        }
    }

    private void leaveSpectator(UUID uuid) {
        spectators.remove(uuid);
        Player p = plugin.player(uuid);
        restoreSnapshot(uuid, p, false, () -> {
            finishTeleport(uuid, plugin.player(uuid));
            returnLoc.remove(uuid);
        });
        if (p != null && plugin.luckyPeaches() != null && plugin.luckyPeaches().available()) {
            plugin.luckyPeaches().exit(p);
        }
        hideWarBar(plugin.player(uuid));
    }

    /**
     * 把玩家传送回原位：优先统一集合点，其次按配置自动返回参赛前位置
     * （必须在竞技场世界销毁前执行）。
     */
    private void finishTeleport(UUID uuid, Player player) {
        if (player == null) {
            return;
        }
        ArenaDef def = currentArena != null ? currentArena
                : plugin.settings().arena(mode(conf().defaultMode).arena);
        ArenaDef.AfterWar aw = def.afterWar;

        Location destination = null;
        if (aw.endSpawnEnabled) {
            World world = aw.endSpawnWorld != null && !aw.endSpawnWorld.isBlank()
                    ? Bukkit.getWorld(aw.endSpawnWorld)
                    : arenaWorld();
            if (world == null) {
                world = arenaWorld();
            }
            if (world != null) {
                destination = aw.endSpawn.toLocation(world);
            }
        } else if (aw.autoReturn) {
            destination = returnLoc.get(uuid);
        }

        if (destination == null) {
            return; // 不返回也不设集合点：保留在原位（仅当世界被保留时安全）
        }
        final Location target = destination;
        // 仅当世界不会被销毁（固定世界）时才延迟，避免玩家滞留在被销毁的世界
        long delay = (arena == null && builtinWorld == null) ? Math.max(0, aw.returnDelaySeconds) * 20L : 0L;
        if (delay > 0) {
            plugin.syncDelayed(() -> {
                if (player.isOnline()) {
                    warTeleport(player, target);
                }
            }, delay);
        } else {
            warTeleport(player, target);
        }
    }

    private void teleportToSpawn(Player player, GuildWar war, String side) {
        World world = arenaWorld();
        if (world == null) {
            return;
        }
        ArenaDef def = plugin.settings().arena(modeOf(war).arena);
        ArenaDef.Spawn spawn = def.teamSpawn(side);
        Location loc = (spawn == null ? ("A".equals(side) ? def.spawnA : def.spawnB) : spawn).toLocation(world);
        warTeleport(player, loc);
    }

    private World arenaWorld() {
        ArenaWorld a = arena;
        if (a != null && a.world() != null) {
            return a.world();
        }
        return fallbackWorld;
    }

    private String arenaName() {
        ArenaWorld a = arena;
        if (a != null) {
            return a.worldName();
        }
        return fallbackWorld == null ? "?" : fallbackWorld.getName();
    }

    // =====================================================================
    // 数据还原（Sparrow Sync 优先 + 数据库持久化兜底 + 本地内存快照）
    // =====================================================================

    /**
     * 采集并持久化玩家入场/旁观前的装备与状态，采集完成后回调执行实际的入场操作
     * （发放统一装备、切换旁观模式、传送等）。
     *
     * <p>必须先完成内置托管写库，再采集 SparrowSync 并发放比赛装备。
     * 等待写库期间限制玩家背包操作，写库失败拒绝入场。</p>
     *
     * <p>为什么必须等采集启动后再清背包：
     * {@code SparrowSync.api().save(uuid)} 会在玩家线程同步读取当前背包（{@code CaptureMode.SYNC}）；
     * 若在同一 tick 紧接着调用 {@code kits().apply()} 把玩家背包替换成竞技场统一装备，
     * SparrowSync 采集到的可能就是竞技场套装而非玩家原有装备！</p>
     *
     * <p>为什么必须写进数据库表 {@code guild_war_escrow}：
     * 1) 服务器战中崩溃/重启时，内存 {@code localSnap}/{@code sparrowSnap} 全部丢失；
     * 2) 玩家战中掉线（{@code onPlayerQuit}）时，SparrowSync 会话已进入 {@code SAVING} 状态，
     *    此时在线 {@code restore} 必定返回 {@code OFFLINE} 失败，而此时 {@code player.isOnline()} 为 false，
     *    若不落库或不当场还原玩家背包，SparrowSync 下一 tick 的退出保存会把竞技场统一装备写进跨服档案，
     *    导致玩家原有装备永久被覆盖！</p>
     */
    private void captureSnapshot(Player player, GuildWar war, Runnable afterCaptured) {
        UUID uuid = player.getUniqueId();
        String playerName = player.getName();
        player.closeInventory();
        capturing.add(uuid);
        KitService.Snapshot snap = plugin.kits().snapshot(player);
        localSnap.put(uuid, snap);

        // 落库持久化托管快照：无论是否发放统一装备都要写，
        // 因为入场本身就会改写游戏模式/坐标，崩溃/重启后必须能兜底还原。
        String itemsData = plugin.kits().encodeSnapshotItems(snap);
        if (itemsData == null || itemsData.isBlank()) {
            capturing.remove(uuid);
            localSnap.remove(uuid, snap);
            sideOf.remove(uuid);
            spectators.remove(uuid);
            returnLoc.remove(uuid);
            livesRemaining.remove(uuid);
            if (war != null) {
                war.rosterA().remove(uuid);
                war.rosterB().remove(uuid);
            }
            plugin.getLogger().warning("工会战快照编码失败，拒绝入场: " + uuid);
            return;
        }
        Location loc = snap.location();
        KitService.PersistedEscrow escrow = new KitService.PersistedEscrow(
                uuid,
                plugin.serverId(),
                war == null ? "" : war.id(),
                null,
                itemsData,
                loc == null || loc.getWorld() == null ? "" : loc.getWorld().getName(),
                loc == null ? 0 : loc.getX(),
                loc == null ? 0 : loc.getY(),
                loc == null ? 0 : loc.getZ(),
                loc == null ? 0f : loc.getYaw(),
                loc == null ? 0f : loc.getPitch(),
                snap.gameMode() == null ? "SURVIVAL" : snap.gameMode().name(),
                snap.health(),
                snap.food(),
                snap.level(),
                snap.exp(),
                System.currentTimeMillis());
        plugin.async(() -> {
            try {
                plugin.repo().saveWarEscrow(escrow);
            } catch (Exception e) {
                plugin.getLogger().warning("保存工会战装备托管快照失败(" + playerName + "): " + e.getMessage());
                plugin.sync(() -> {
                    capturing.remove(uuid);
                    localSnap.remove(uuid, snap);
                    sideOf.remove(uuid);
                    spectators.remove(uuid);
                    returnLoc.remove(uuid);
                    livesRemaining.remove(uuid);
                    if (war != null) {
                        war.rosterA().remove(uuid);
                        war.rosterB().remove(uuid);
                    }
                });
                return;
            }
            plugin.sync(() -> {
                capturing.remove(uuid);
                if (!player.isOnline() || localSnap.get(uuid) != snap || current != war
                        || !war.state().isLive()) return;
                completeCapture(player, afterCaptured);
            });
        });
    }

    private void completeCapture(Player player, Runnable afterCaptured) {
        UUID uuid = player.getUniqueId();
        if (plugin.sparrow() != null && plugin.sparrow().available()) {
            // SparrowSync 在主线程调用 save(uuid) 时会同步完成玩家背包采集（CaptureMode.SYNC），
            // 然后异步编码写库；因此 invoke 返回后即可安全发放竞技场统一装备。
            CompletableFuture<UUID> future = plugin.sparrow().capture(uuid);
            future.thenAccept(id -> {
                if (id != null) {
                    // 若在异步写库完成前玩家已经退场/还原完毕（localSnap 已移除），则直接释放固定，不留残留
                    if (!localSnap.containsKey(uuid) && !sideOf.containsKey(uuid)) {
                        plugin.sparrow().unpin(id);
                        return;
                    }
                    sparrowSnap.put(uuid, id);
                    // 固定该快照，防止战斗期间因切世界/死亡触发超过 maxSnapshots(32) 而被自动清理
                    plugin.sparrow().pin(id);
                    plugin.async(() -> {
                        try {
                            plugin.repo().updateWarEscrowSparrowId(uuid, id);
                        } catch (Exception ignored) {
                        }
                    });
                }
            });
        }
        if (afterCaptured != null) {
            afterCaptured.run();
        }
    }

    /**
     * 还原参赛者状态与装备。
     *
     * @param quitting     true 表示在 {@code PlayerQuitEvent} 中调用（此时必须同步把本地快照写回 Player 对象，
     *                     让 SparrowSync 下一 tick 的退出采集拿到玩家原有装备，避免把竞技场装备存进跨服库）
     * @param afterRestore 还原完成（含坐标恢复）后的回调，用于执行 {@code finishTeleport} 覆盖坐标
     */
    private void restoreSnapshot(UUID uuid, Player player, boolean quitting, Runnable afterRestore) {
        capturing.remove(uuid);
        UUID sparrowId = sparrowSnap.remove(uuid);
        KitService.Snapshot snap = localSnap.remove(uuid);

        // 退出服务器路径：SparrowSync 在 PlayerQuitEvent(MONITOR) 已把会话置为 SAVING，
        // 并在 1 tick 后采集 Player 对象保存 DISCONNECT 快照。
        // 因此退服时必须【立即同步】用本地快照把 Player 的背包/装备/等级还原回原样！
        if (quitting) {
            if (snap != null && player != null) {
                // 不在此处传送：退服流程中来回复传送既可能被传送拦截拦截，也毫无意义；
                // 坐标由服务器保存的退出位置决定，装备/等级/游戏模式才是必须还原的关键。
                plugin.kits().restore(player, snap, true);
            }
            if (sparrowId != null && plugin.sparrow() != null && plugin.sparrow().available()) {
                plugin.sparrow().unpin(sparrowId);
            }
            if (snap != null && player != null) deleteEscrowAsync(uuid);
            if (afterRestore != null) {
                afterRestore.run();
            }
            return;
        }

        if (sparrowId != null && plugin.sparrow() != null && plugin.sparrow().available()) {
            plugin.sparrow().restore(uuid, sparrowId).whenComplete((ok, ex) -> {
                plugin.sparrow().unpin(sparrowId);
                plugin.sync(() -> {
                    Player p = player != null && player.isOnline() ? player : plugin.player(uuid);
                    if (!Boolean.TRUE.equals(ok)) {
                        // Sparrow Sync 还原失败时回退本地快照
                        if (snap != null && p != null) {
                            plugin.kits().restore(p, snap, true);
                        } else {
                            // 玩家已离线且 SparrowSync 失败：保留数据库托管记录，等下次上线兜底恢复
                            if (afterRestore != null) {
                                afterRestore.run();
                            }
                            return;
                        }
                    } else if (snap != null && p != null) {
                        // SparrowSync 默认 skip-online-restore-data 跳过 health 与 location，
                        // 用本地快照补齐血量，位置交由后续 finishTeleport 统一处理
                        com.liu.liuguild.util.ApiCompat.setHealthSafely(p, snap.health());
                    }
                    deleteEscrowAsync(uuid);
                    if (afterRestore != null) {
                        afterRestore.run();
                    }
                });
            });
            return;
        }
        if (snap != null && player != null) {
            plugin.kits().restore(player, snap, true);
            deleteEscrowAsync(uuid);
        }
        if (afterRestore != null) {
            afterRestore.run();
        }
    }

    private void deleteEscrowAsync(UUID uuid) {
        plugin.async(() -> {
            try {
                plugin.repo().deleteWarEscrow(uuid);
            } catch (Exception e) {
                plugin.getLogger().warning("清理工会战装备托管记录失败: " + e.getMessage());
            }
        });
    }

    /**
     * 玩家登录时检查是否有未归还的工会战托管装备（例如战中服务器崩溃/重启或异常断线残留）。
     * 若该玩家当前不在进行中的工会战内，则自动归还托管装备并清理记录。
     */
    public void recoverPendingEscrow(Player player) {
        if (player == null) {
            return;
        }
        UUID uuid = player.getUniqueId();
        if (isInWar(uuid)) {
            return;
        }
        plugin.async(() -> {
            KitService.PersistedEscrow escrow;
            try {
                escrow = plugin.repo().loadWarEscrow(uuid);
            } catch (Exception e) {
                plugin.getLogger().warning("查询工会战托管快照失败: " + e.getMessage());
                return;
            }
            if (escrow == null) {
                return;
            }
            plugin.sync(() -> {
                if (!player.isOnline() || isInWar(uuid)) {
                    return;
                }
                UUID sparrowId = escrow.sparrowSnapshotId();
                KitService.Snapshot fallback = escrow.toSnapshot();
                if (sparrowId != null && plugin.sparrow() != null && plugin.sparrow().available()) {
                    plugin.sparrow().restore(uuid, sparrowId).whenComplete((ok, ex) -> {
                        plugin.sparrow().unpin(sparrowId);
                        plugin.sync(() -> {
                            if (!player.isOnline() || isInWar(uuid)) return;
                            if (!Boolean.TRUE.equals(ok)) {
                                if (fallback != null) {
                                    plugin.kits().restore(player, fallback, false);
                                } else {
                                    plugin.getLogger().warning("工会战托管快照不可用，保留记录等待修复: " + uuid);
                                    return;
                                }
                            }
                            deleteEscrowAsync(uuid);
                            plugin.getLogger().info("已为玩家 " + player.getName() + " 恢复未归还的工会战托管装备（SparrowSync）。");
                        });
                    });
                } else if (fallback != null) {
                    plugin.kits().restore(player, fallback, false);
                    deleteEscrowAsync(uuid);
                    plugin.getLogger().info("已为玩家 " + player.getName() + " 恢复未归还的工会战托管装备（本地持久化快照）。");
                } else {
                    // 保留损坏数据供管理员修复，绝不以空背包替换或删除唯一恢复依据。
                    plugin.getLogger().warning("工会战托管快照损坏，已保留记录: " + player.getName());
                }
            });
        });
    }

    /**
     * 成员退会 / 被踢时的工会战清理。
     *
     * <p>不加处理的话，退会者会一直留在名单里：继续上 BossBar、继续被复活传送到出生点，
     * 结算时还会以一个“已不属于该工会的人”的身份领到参赛奖励。</p>
     */
    public void onMemberRemoved(UUID uuid, String guildId) {
        if (uuid == null) {
            return;
        }
        GuildWar war = current;
        if (war == null || guildId == null
                || !(guildId.equals(war.guildA()) || guildId.equals(war.guildB()))) {
            return;
        }
        boolean wasParticipant = war.rosterA().remove(uuid) | war.rosterB().remove(uuid);
        boolean wasSpectator = spectators.remove(uuid);
        if (!wasParticipant && !wasSpectator && !sideOf.containsKey(uuid)) {
            return;
        }
        String side = sideOf.remove(uuid);
        livesRemaining.remove(uuid);
        eliminated.remove(uuid);
        hideWarBar(plugin.player(uuid));
        // 交还统一装备并送回原位
        Player p = plugin.player(uuid);
        if (p != null) {
            // 必须解冻：否则 frozen 残留会让 WarListener 持续拦截该玩家的背包/移动/指令。
            unfreezePlayer(p);
            p.setWalkSpeed(0.2f);
            p.setFlySpeed(0.1f);
            p.setInvulnerable(false);
            p.setCollidable(true);
            if (p.getGameMode() != org.bukkit.GameMode.SURVIVAL) {
                p.setGameMode(org.bukkit.GameMode.SURVIVAL);
            }
            restoreSnapshot(uuid, p, false, () -> {
                Player online = plugin.player(uuid);
                if (online != null) {
                    finishTeleport(uuid, online);
                    plugin.messages().send(online, "war-member-left");
                }
                returnLoc.remove(uuid);
            });
        } else {
            restoreSnapshot(uuid, null, false, () -> returnLoc.remove(uuid));
        }
        if (plugin.luckyPeaches() != null && plugin.luckyPeaches().available()) {
            if (p != null) {
                plugin.luckyPeaches().exit(p);
            } else {
                plugin.luckyPeaches().clear(uuid);
            }
        }
        // 人都走了：该方若再无人，立即判定
        if (war.state() == WarState.ACTIVE && wasParticipant && side != null) {
            checkElimination(war, side);
        }
    }

    /** 玩家退出服务器（未结束时）也需还原，避免统一装备残留。 */
    public void onPlayerQuit(Player player) {
        UUID uuid = player.getUniqueId();
        unfreezePlayer(player);
        hideWarBar(player);
        GuildWar war = current;
        String side = sideOf.remove(uuid);
        if (war != null) {
            war.rosterA().remove(uuid);
            war.rosterB().remove(uuid);
        }
        if (side != null || sparrowSnap.containsKey(uuid) || localSnap.containsKey(uuid)) {
            restoreSnapshot(uuid, player, true, null);
            if (plugin.luckyPeaches() != null && plugin.luckyPeaches().available()) {
                plugin.luckyPeaches().clear(uuid);
            }
            spectators.remove(uuid);
            livesRemaining.remove(uuid);
            eliminated.remove(uuid);
            returnLoc.remove(uuid);
            if (war != null && war.state() == WarState.ACTIVE && side != null) {
                checkElimination(war, side);
            }
        }
    }

    // =====================================================================
    // 击杀 / 死亡
    // =====================================================================

    public void onKill(Player killer, Player victim) {
        GuildWar war = current;
        if (war == null || war.state() != WarState.ACTIVE) {
            return;
        }
        String ks = war.sideOf(killer.getUniqueId());
        String vs = war.sideOf(victim.getUniqueId());
        if (ks == null || vs == null || ks.equals(vs)) {
            return;
        }
        int points = modeOf(war).scoreKill;
        if ("A".equals(ks)) {
            war.scoreA(war.scoreA() + points);
        } else {
            war.scoreB(war.scoreB() + points);
        }
        int dp = modeOf(war).scoreDeath;
        if (dp != 0) {
            if ("A".equals(vs)) {
                war.scoreA(war.scoreA() + dp);
            } else {
                war.scoreB(war.scoreB() + dp);
            }
        }
        Guild killerGuild = plugin.guilds().guildOf(killer.getUniqueId());
        Guild victimGuild = plugin.guilds().guildOf(victim.getUniqueId());
        if (killerGuild != null) {
            GuildMember m = killerGuild.member(killer.getUniqueId());
            if (m != null) {
                m.addKill();
                plugin.guilds().saveMemberAsync(m);
            }
        }
        if (victimGuild != null) {
            GuildMember m = victimGuild.member(victim.getUniqueId());
            if (m != null) {
                m.addDeath();
                plugin.guilds().saveMemberAsync(m);
            }
        }
        Bukkit.getPluginManager().callEvent(new GuildKillEvent(war, killer, victim, points));
        // 只有击杀确实加分时才显示「(+N)」；0 / 负分不展示分数部分
        net.kyori.adventure.text.Component killMsg = points > 0
                ? plugin.messages().component("war-kill",
                        "killer", killer.getName(), "victim", victim.getName(), "points", points)
                : plugin.messages().component("war-kill-plain",
                        "killer", killer.getName(), "victim", victim.getName());
        broadcastWar(war, killMsg);
        publishState(war);
    }

    /** 比赛开始时锁定实际参赛者，并按较小队伍人数归一双方总生命池。 */
    private void initializeLives(GuildWar war, WarMode mode) {
        livesRemaining.clear();
        eliminated.clear();
        List<UUID> a = new ArrayList<>(war.rosterA());
        List<UUID> b = new ArrayList<>(war.rosterB());
        int[] pools = WarMath.teamLifePools(mode.balanceTeamLives, mode.livesPerPlayer, a.size(), b.size());
        livesRemaining.putAll(WarMath.distributeLives(a, pools[0]));
        livesRemaining.putAll(WarMath.distributeLives(b, pools[1]));
        if (a.size() != b.size() && mode.balanceTeamLives) {
            plugin.getLogger().info("工会战 " + war.id() + " 人数平衡生命：A " + a.size() + " 人/" + pools[0]
                    + " 命，B " + b.size() + " 人/" + pools[1] + " 命。");
        }
    }

    /** 致命伤害在原版死亡流程前拦截，扣生命并安排回点/淘汰。 */
    public boolean handleLethalDamage(Player victim, Player attacker) {
        GuildWar war = current;
        if (victim == null || war == null || war.state() != WarState.ACTIVE
                || war.sideOf(victim.getUniqueId()) == null || eliminated.contains(victim.getUniqueId())) {
            return false;
        }
        if (spectators.contains(victim.getUniqueId())) {
            return true;
        }
        if (attacker != null && !attacker.getUniqueId().equals(victim.getUniqueId())) {
            onKill(attacker, victim);
        }
        UUID uuid = victim.getUniqueId();
        if (!handledLethal.add(uuid)) {
            return true;
        }
        unfreezePlayer(victim); // 死亡时先解除锁定，避免观战/复活后仍被冻结
        int remaining = Math.max(0, livesRemaining.getOrDefault(uuid, modeOf(war).livesPerPlayer) - 1);
        livesRemaining.put(uuid, remaining);
        victim.setFireTicks(0);
        if (remaining <= 0) {
            eliminated.add(uuid);
            spectators.add(uuid);
            victim.setGameMode(org.bukkit.GameMode.SPECTATOR);
            plugin.messages().send(victim, "war-lives-eliminated");
            checkElimination(war, war.sideOf(uuid));
        } else {
            String side = war.sideOf(uuid);
            spectators.add(uuid);
            victim.setGameMode(org.bukkit.GameMode.SPECTATOR);
            plugin.messages().send(victim, "war-lives-remaining", "lives", remaining);
            WarMode mode = modeOf(war);
            plugin.syncDelayed(() -> {
                if (current != war || war.state() != WarState.ACTIVE || eliminated.contains(uuid)
                        || !victim.isOnline()) {
                    return;
                }
                spectators.remove(uuid);
                handledLethal.remove(uuid);
                victim.setGameMode(org.bukkit.GameMode.SURVIVAL);
                plugin.kits().apply(victim, mode);
                teleportToSpawn(victim, war, side);
                plugin.messages().send(victim, "war-respawned");
            }, mode.respawnDelaySeconds * 20L);
        }
        return true;
    }

    private void checkElimination(GuildWar war, String side) {
        Set<UUID> team = "A".equals(side) ? war.rosterA() : war.rosterB();
        boolean out = team.stream().allMatch(eliminated::contains);
        if (out) {
            forcedWinner = "A".equals(side) ? war.guildB() : war.guildA();
            endWar(war);
        }
    }

    /** Paper DeathEvent 兜底：直接 setHealth(0) 等不经过伤害事件的死亡也需接管。 */
    public boolean interceptDeath(org.bukkit.event.entity.PlayerDeathEvent event) {
        Player player = event.getEntity();
        GuildWar war = current;
        if (war == null || war.state() != WarState.ACTIVE || war.sideOf(player.getUniqueId()) == null) {
            return false;
        }
        event.setCancelled(true);
        event.setReviveHealth(1.0);
        event.deathMessage(null);
        Player killer = player.getKiller();
        plugin.syncDelayed(() -> {
            if (player.isOnline()) {
                handleLethalDamage(player, killer);
            }
        }, 1L);
        return true;
    }

    public int lives(UUID uuid) {
        return livesRemaining.getOrDefault(uuid, 0);
    }

    /** 结算阶段待发放的奖励人数（用于状态面板/排查）。 */
    public int pendingSettlements() {
        return settleQueue.size();
    }

    /** 参赛者死亡：重生时由监听器重新发放装备并传送回出生点。 */
    public void onRespawn(Player player) {
        GuildWar war = current;
        if (war == null) {
            return;
        }
        String side = war.sideOf(player.getUniqueId());
        if (side == null) {
            return;
        }
        plugin.kits().apply(player, modeOf(war));
        teleportToSpawn(player, war, side);
    }

    // =====================================================================
    // 广播
    // =====================================================================

    /** 战况上报节流：击杀密集时避免每次击杀都做一次阻塞 Redis 发布。 */
    private volatile long lastStatePublishAt;

    private void publishState(GuildWar war) {
        publishState(war, false);
    }

    private void publishState(GuildWar war, boolean force) {
        if (!plugin.bus().isReady()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!force && now - lastStatePublishAt < 500L) {
            return;
        }
        lastStatePublishAt = now;
        String id = war.id();
        String state = war.state().name();
        int scoreA = war.scoreA();
        int scoreB = war.scoreB();
        // Redis 发布是阻塞 IO，放异步线程
        plugin.async(() -> plugin.bus().publishWarState(id, state, scoreA, scoreB));
    }

    private void broadcastWar(GuildWar war, net.kyori.adventure.text.Component text) {
        // 避免重复：优先走全服（含跨服）公告，它已覆盖参赛者；失败/关闭时只投递工会成员。
        Player carrier = Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
        if (conf().announceServer && plugin.chat() != null && plugin.chat().available() && carrier != null
                && plugin.chat().broadcast(carrier, text)) {
            return;
        }
        plugin.guilds().announceGuild(war.guildA(), text, null);
        plugin.guilds().announceGuild(war.guildB(), text, null);
    }

    private void showWarBar(UUID uuid) {
        Player p = plugin.player(uuid);
        if (p != null && warBar != null) {
            p.showBossBar(warBar);
        }
    }

    private void hideWarBar(Player p) {
        if (p != null && warBar != null) {
            p.hideBossBar(warBar);
        }
    }

    private void clearWarBar() {
        BossBar bar = warBar;
        if (bar != null) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.hideBossBar(bar);
            }
        }
        warBar = null;
    }

    private net.kyori.adventure.text.Component scoreLine(GuildWar war) {
        return plugin.messages().component("war-score",
                "guild_a", war.nameA(), "score_a", war.scoreA(),
                "score_b", war.scoreB(), "guild_b", war.nameB());
    }

    private void notifyQueueServer(Map<String, String> meta, String key) {
        String server = meta.get("server");
        String guildId = meta.get("guildId");
        if (server == null || guildId == null) {
            return;
        }
        if (server.equals(plugin.serverId())) {
            plugin.sync(() -> plugin.guilds().notifyGuild(guildId, plugin.messages().component(key), null));
        } else {
            plugin.bus().publishTo(server, "WAR_QUEUE_TIMEOUT",
                    CrossServerBus.map("guild", guildId, "key", key, "server", plugin.serverId()));
        }
    }

    private static long parseLong(String s, long def) {
        try {
            return Long.parseLong(s);
        } catch (Exception e) {
            return def;
        }
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return def;
        }
    }
}
