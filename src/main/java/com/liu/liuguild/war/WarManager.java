package com.liu.liuguild.war;

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
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
    /** 当前使用的竞技场定义。 */
    private volatile ArenaDef currentArena;

    private final Map<UUID, Location> returnLoc = new ConcurrentHashMap<>();
    private final Map<UUID, String> sideOf = new ConcurrentHashMap<>();
    private final Map<UUID, KitService.Snapshot> localSnap = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> sparrowSnap = new ConcurrentHashMap<>();
    private final Set<UUID> spectators = ConcurrentHashMap.newKeySet();

    /** 所有服务器共享的「进行中工会战」镜像，用于战况展示。 */
    private final Map<String, GuildWar> observed = new ConcurrentHashMap<>();
    /** 无 Redis 时的本服匹配队列。 */
    private final List<String> localQueue = new CopyOnWriteArrayList<>();
    private final Map<String, Long> localQueuedAt = new ConcurrentHashMap<>();
    /** 本服队列中每个工会选择的模式。 */
    private final Map<String, String> localMode = new ConcurrentHashMap<>();

    private BukkitTask queueTask;
    private BukkitTask tickTask;
    private BukkitTask seasonTask;
    private final Map<String, Long> lastSeasonTrigger = new ConcurrentHashMap<>();
    private volatile long phaseUntil;
    private volatile long lastBroadcastSecond = -1;
    private volatile boolean settling;

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
        int interval = Math.max(1, conf().queueIntervalSeconds);
        queueTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::matchTick,
                20L * interval, 20L * interval);
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::warTick, 20L, 20L);
        seasonTask = Bukkit.getScheduler().runTaskTimer(plugin, this::seasonTick, 40L, 20L);
        plugin.getLogger().info("工会战模块已启用（server-mode=" + conf().serverMode
                + "，承办服=" + (conf().dedicated() ? conf().dedicatedServer : "本服")
                + "，模式=" + conf().modes.keySet() + "）。");
    }

    @Override
    public void disable() {
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
        if (current != null) {
            forceEnd(current.id());
        }
    }

    @Override
    public void reload() {
        disable();
        enable(plugin);
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
        for (GuildWar w : observed.values()) {
            if (w != current && w.state().isLive()) {
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
        for (GuildWar w : observed.values()) {
            if (w.involves(guildId) && w.state().isLive()) {
                return w;
            }
        }
        return null;
    }

    public boolean isInWar(UUID uuid) {
        GuildWar c = current;
        return c != null && c.sideOf(uuid) != null;
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
        if (plugin.guilds().onlineMembers(guild).size() < mode.minMembers) {
            plugin.messages().send(player, "war-need-members", "min", mode.minMembers);
            return;
        }
        if (isQueued(guild.id())) {
            plugin.messages().send(player, "war-already-queued");
            return;
        }
        if (plugin.bus().isReady()) {
            plugin.bus().queueGuild(guild.id(), CrossServerBus.map(
                    "name", guild.name(), "tag", guild.tag(), "server", plugin.serverId(),
                    "mode", mode.key, "leader", player.getName()));
        } else {
            localQueue.add(guild.id());
            localQueuedAt.put(guild.id(), System.currentTimeMillis());
            localMode.put(guild.id(), mode.key);
        }
        plugin.messages().send(player, "war-queue-joined", "mode", mode.display);
        plugin.guilds().notifyGuild(guild.id(), plugin.messages().component("war-queue-joined", "mode", mode.display), player.getUniqueId());
        // 全服广播提示：让其他玩家看到有人在匹配
        if (mode.broadcastQueue) {
            plugin.guilds().broadcastAll(plugin.messages().component("war-queue-broadcast",
                    "player", player.getName(), "guild", guild.name(), "mode", mode.display));
        }
    }

    public void leaveQueue(Player player, Guild guild) {
        if (!isQueued(guild.id())) {
            plugin.messages().send(player, "war-not-queued");
            return;
        }
        if (plugin.bus().isReady()) {
            plugin.bus().dequeueGuild(guild.id());
        } else {
            localQueue.remove(guild.id());
            localQueuedAt.remove(guild.id());
            localMode.remove(guild.id());
        }
        plugin.messages().send(player, "war-queue-left");
    }

    /** 该工会当前排队选择的模式 key；未排队返回 null。 */
    public String queuedMode(String guildId) {
        if (plugin.bus().isReady()) {
            for (Map<String, String> meta : plugin.bus().queueSnapshot()) {
                if (guildId.equals(meta.get("guildId"))) {
                    return meta.getOrDefault("mode", conf().defaultMode);
                }
            }
            return null;
        }
        return localMode.get(guildId);
    }

    /** 当前匹配队列中的工会数量。 */
    public int queueSize() {
        if (plugin.bus().isReady()) {
            return plugin.bus().queueSnapshot().size();
        }
        return localQueue.size();
    }

    // =====================================================================
    // 赛季时间表
    // =====================================================================

    /** 每秒检查各模式的开战时间表。 */
    private void seasonTick() {
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
                lastSeasonTrigger.put(key, minute);
                startSeason(mode);
            }
        }
    }

    /** 开启一场赛季（公告 + 可选自动入队）。 */
    public void startSeason(WarMode mode) {
        if (mode == null) {
            return;
        }
        plugin.guilds().broadcastAll(plugin.messages().component("war-season-open",
                "mode", mode.display, "minutes", mode.seasonWindowMinutes));
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
            if (id == null || plugin.guilds().guild(id) == null || warOf(id) != null) {
                plugin.bus().dequeueGuild(id);
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
        Map<String, String> a = valid.get(0);
        Map<String, String> b = valid.get(1);
        String idA = a.get("guildId");
        String idB = b.get("guildId");
        if (idA == null || idB == null || idA.equals(idB)) {
            return;
        }
        String modeA = a.getOrDefault("mode", conf().defaultMode);
        String modeB = b.getOrDefault("mode", conf().defaultMode);
        if (!modeA.equals(modeB)) {
            return;
        }
        if (!plugin.bus().claimPair(idA, idB)) {
            return;
        }
        createMatch(a, b, modeA);
    }

    private void localMatch() {
        long now = System.currentTimeMillis();
        int timeout = conf().matchTimeoutSeconds;
        for (String id : new ArrayList<>(localQueue)) {
            long queued = localQueuedAt.getOrDefault(id, now);
            if (timeout > 0 && now - queued > timeout * 1000L) {
                localQueue.remove(id);
                localQueuedAt.remove(id);
                localMode.remove(id);
                Guild g = plugin.guilds().guild(id);
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
            createMatch(a, b, e.getKey());
            return;
        }
    }

    private void createMatch(Map<String, String> a, Map<String, String> b, String modeKey) {
        Guild ga = plugin.guilds().guild(a.get("guildId"));
        Guild gb = plugin.guilds().guild(b.get("guildId"));
        if (ga == null || gb == null) {
            return;
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
            return;
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
    }

    // =====================================================================
    // 跨服消息
    // =====================================================================

    @Override
    public void onCrossServer(String type, Map<String, String> data) {
        switch (type) {
            case "WAR_MATCHED" -> handleWarMatched(data);
            case "WAR_STATE" -> handleWarState(data);
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
            } else {
                // 非承办服：通知本地参赛成员去 /guild war join
                plugin.guilds().notifyGuild(war.guildA(), plugin.messages().component("war-match-found",
                        "enemy", war.nameB(), "seconds", modeOf(war).prepareSeconds), null);
                plugin.guilds().notifyGuild(war.guildB(), plugin.messages().component("war-match-found",
                        "enemy", war.nameA(), "seconds", modeOf(war).prepareSeconds), null);
            }
        });
    }

    private void handleWarState(Map<String, String> data) {
        String warId = data.get("war");
        GuildWar war = observed.get(warId);
        if (war == null) {
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
        prepareArena(war);
    }

    private void prepareArena(GuildWar war) {
        WarMode mode = modeOf(war);
        ArenaDef def = plugin.settings().arena(mode.arena);
        currentArena = def;
        fallbackWorld = null;
        if (plugin.dungeon() != null && plugin.dungeon().available() && def.hasTemplate()
                && plugin.dungeon().hasTemplate(def.template)) {
            Set<ChunkPosition> chunks = plugin.settings().arenas.pregenerate ? computeChunks(def) : new HashSet<>();
            plugin.dungeon().create(def.template, chunks, Duration.ofSeconds(plugin.settings().arenas.createTimeoutSeconds))
                    .whenComplete((world, error) -> {
                        plugin.sync(() -> {
                            if (error != null) {
                                plugin.getLogger().warning("竞技场准备失败，工会战取消: " + error.getMessage());
                                cancelWar(war, "war-arena-failed");
                                return;
                            }
                            arena = world;
                            war.arenaWorld(world.worldName());
                            beginPrepare(war);
                        });
                    });
        } else {
            World world = def.world != null && !def.world.isBlank() ? Bukkit.getWorld(def.world) : null;
            if (world == null && !Bukkit.getWorlds().isEmpty()) {
                world = Bukkit.getWorlds().get(0);
            }
            fallbackWorld = world;
            if (world == null) {
                plugin.getLogger().warning("未配置竞技场世界且无可加载世界，工会战取消。");
                cancelWar(war, "war-arena-failed");
                return;
            }
            war.arenaWorld(world.getName());
            beginPrepare(war);
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
        broadcastWar(war, plugin.messages().component("war-match-found",
                "enemy", war.nameB(), "seconds", mode.prepareSeconds));
        plugin.getLogger().info("工会战 " + war.id() + " [" + mode.display + "] 进入准备阶段: "
                + war.nameA() + " vs " + war.nameB() + "，竞技场=" + arenaName());
    }

    private void beginActive(GuildWar war) {
        WarMode mode = modeOf(war);
        war.state(WarState.ACTIVE);
        war.startedAt(System.currentTimeMillis());
        phaseUntil = System.currentTimeMillis() + mode.durationSeconds * 1000L;
        lastBroadcastSecond = -1;
        Bukkit.getPluginManager().callEvent(new GuildWarStartEvent(war));
        broadcastWar(war, plugin.messages().component("war-started", "guild_a", war.nameA(), "guild_b", war.nameB()));
        publishState(war);
    }

    /** 每秒主线程 tick：倒计时、结束判定、比分播报。 */
    private void warTick() {
        GuildWar war = current;
        if (war == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long remain = Math.max(0, phaseUntil - now);
        long sec = (remain + 999) / 1000;
        switch (war.state()) {
            case PREPARING -> {
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
                if (!settling) {
                    settling = true;
                }
                if (remain <= 0) {
                    cleanup(war);
                }
            }
            default -> {
            }
        }
    }

    private void endWar(GuildWar war) {
        if (war.state() != WarState.ACTIVE && war.state() != WarState.PREPARING) {
            return;
        }
        war.state(WarState.SETTLING);
        war.endedAt(System.currentTimeMillis());
        String winner = war.scoreA() == war.scoreB() ? "" : (war.scoreA() > war.scoreB() ? war.guildA() : war.guildB());
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
        publishState(war);
        phaseUntil = System.currentTimeMillis() + modeOf(war).settleSeconds * 1000L;
    }

    private void applyRewards(GuildWar war, String winnerId) {
        Guild winner = winnerId == null || winnerId.isBlank() ? null : plugin.guilds().guild(winnerId);
        Guild loser = winner == null ? null
                : plugin.guilds().guild(winner.id().equals(war.guildA()) ? war.guildB() : war.guildA());
        WarMode w = modeOf(war);
        double mult = w.scheduled() ? Math.max(1.0, w.seasonPointsMultiplier) : 1.0;
        if (winner != null) {
            winner.wins(winner.wins() + 1);
            winner.bank(winner.bank() + (long) w.winMoney);
            plugin.guilds().addPoints(winner, (long) (w.winPoints * mult));
        }
        if (loser != null) {
            loser.losses(loser.losses() + 1);
            loser.bank(loser.bank() + (long) w.loseMoney);
            plugin.guilds().addPoints(loser, (long) (w.losePoints * mult));
        }
        for (UUID u : war.rosterA()) {
            rewardPlayer(u, winner != null && winner.id().equals(war.guildA()), w);
        }
        for (UUID u : war.rosterB()) {
            rewardPlayer(u, winner != null && winner.id().equals(war.guildB()), w);
        }
    }

    private void rewardPlayer(UUID uuid, boolean win, WarMode w) {
        plugin.pass().addXp(uuid, w.passId, win ? w.winPassXp : w.losePassXp);
        if (win && plugin.playerTitle() != null) {
            plugin.playerTitle().grant(uuid, w.winTitleId);
        }
        Player p = plugin.player(uuid);
        if (p != null) {
            if (win) {
                plugin.messages().send(p, "war-reward-win", "money", w.winMoney, "points", w.winPoints);
            } else {
                plugin.messages().send(p, "war-reward-lose", "money", w.loseMoney, "points", w.losePoints);
            }
        }
    }

    public boolean forceEnd(String warId) {
        GuildWar war = current;
        if (war == null || (warId != null && !war.id().equals(warId))) {
            return false;
        }
        endWar(war);
        return true;
    }

    private void cancelWar(GuildWar war, String reasonKey) {
        war.state(WarState.CANCELLED);
        observed.remove(war.id());
        broadcastWar(war, plugin.messages().component(reasonKey));
        try {
            plugin.repo().updateWar(war);
        } catch (Exception ignored) {
        }
        cleanup(war);
    }

    private void cleanup(GuildWar war) {
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
        if (plugin.dungeon() != null && arena != null) {
            plugin.dungeon().destroy(arena);
        }
        arena = null;
        fallbackWorld = null;
        currentArena = null;
        settling = false;
        observed.remove(war.id());
        current = null;
        plugin.getLogger().info("工会战 " + war.id() + " 已结束并回收竞技场。");
    }

    // =====================================================================
    // 参赛 / 旁观
    // =====================================================================

    public void join(Player player) {
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
            plugin.bus().setPendingWar(player.getUniqueId(), war.id());
            if (!plugin.bus().transfer().connect(player, war.hostServer())) {
                plugin.messages().send(player, "war-transfer-failed");
            } else {
                plugin.messages().send(player, "war-joined");
            }
        }
    }

    /** 玩家进入承办服后由监听器调用，消费待处理工会战。 */
    public void onPlayerJoinServer(Player player) {
        String warId = plugin.bus().consumePendingWar(player.getUniqueId());
        GuildWar war = current;
        if (warId != null && war != null && war.id().equals(warId)) {
            joinLocal(player, war);
        }
    }

    private void joinLocal(Player player, GuildWar war) {
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
        if (roster.size() >= mode.teamSize) {
            if (mode.allowSpectators) {
                spectateLocal(player, war);
                plugin.messages().send(player, "war-spectate");
            } else {
                plugin.messages().send(player, "join-full", "guild", guild.name());
            }
            return;
        }
        if (roster.contains(player.getUniqueId())) {
            return;
        }
        roster.add(player.getUniqueId());
        sideOf.put(player.getUniqueId(), s);
        returnLoc.put(player.getUniqueId(), player.getLocation().clone());
        captureSnapshot(player);
        // 公平血量：屏蔽 LuckyPeaches 蟠桃加成
        if (mode.fairHealth && plugin.luckyPeaches() != null && plugin.luckyPeaches().available()) {
            plugin.luckyPeaches().enter(player, plugin.luckyPeaches().supportsSuppress());
        }
        plugin.kits().apply(player, mode);
        teleportToSpawn(player, war, s);
        plugin.messages().send(player, "war-joined");
        // 传送后确保 game mode
        player.setGameMode(org.bukkit.GameMode.SURVIVAL);
    }

    public void spectate(Player player) {
        Guild guild = plugin.guilds().guildOf(player.getUniqueId());
        GuildWar war = guild == null ? current : warOf(guild.id());
        if (war == null || !war.hostServer().equals(plugin.serverId())) {
            plugin.messages().send(player, "war-not-in-war");
            return;
        }
        spectateLocal(player, war);
        plugin.messages().send(player, "war-spectate");
    }

    private void spectateLocal(Player player, GuildWar war) {
        spectators.add(player.getUniqueId());
        returnLoc.putIfAbsent(player.getUniqueId(), player.getLocation().clone());
        World world = arenaWorld();
        if (world == null) {
            return;
        }
        player.setGameMode(org.bukkit.GameMode.SPECTATOR);
        ArenaDef def = plugin.settings().arena(modeOf(war).arena);
        player.teleport(def.spawnSpectator.toLocation(world));
    }

    private void leaveLocal(UUID uuid) {
        Player p = plugin.player(uuid);
        restoreSnapshot(uuid, p);
        if (plugin.luckyPeaches() != null && plugin.luckyPeaches().available()) {
            plugin.luckyPeaches().exit(p);
        }
        spectators.remove(uuid);
        sideOf.remove(uuid);
        returnLoc.remove(uuid);
        if (p != null) {
            p.setGameMode(org.bukkit.GameMode.SURVIVAL);
        }
    }

    private void leaveSpectator(UUID uuid) {
        spectators.remove(uuid);
        Player p = plugin.player(uuid);
        if (p != null) {
            p.setGameMode(org.bukkit.GameMode.SURVIVAL);
        }
        restoreSnapshot(uuid, p);
        if (p != null && plugin.luckyPeaches() != null && plugin.luckyPeaches().available()) {
            plugin.luckyPeaches().exit(p);
        }
        returnLoc.remove(uuid);
    }

    private void teleportToSpawn(Player player, GuildWar war, String side) {
        World world = arenaWorld();
        if (world == null) {
            return;
        }
        ArenaDef def = plugin.settings().arena(modeOf(war).arena);
        Location loc = "A".equals(side) ? def.spawnA.toLocation(world) : def.spawnB.toLocation(world);
        player.teleport(loc);
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
    // 数据还原（Sparrow Sync 优先，回退本地快照）
    // =====================================================================

    private void captureSnapshot(Player player) {
        localSnap.put(player.getUniqueId(), plugin.kits().snapshot(player));
        if (plugin.sparrow() != null && plugin.sparrow().available()) {
            UUID uuid = player.getUniqueId();
            plugin.sparrow().capture(uuid).thenAccept(id -> {
                if (id != null) {
                    sparrowSnap.put(uuid, id);
                }
            });
        }
    }

    private void restoreSnapshot(UUID uuid, Player player) {
        UUID sparrowId = sparrowSnap.remove(uuid);
        if (sparrowId != null && plugin.sparrow() != null && plugin.sparrow().available()) {
            plugin.sparrow().restore(uuid, sparrowId);
            localSnap.remove(uuid);
            return;
        }
        KitService.Snapshot snap = localSnap.remove(uuid);
        if (snap != null && player != null) {
            plugin.kits().restore(player, snap);
        }
    }

    /** 玩家退出服务器（未结束时）也需还原，避免统一装备残留。 */
    public void onPlayerQuit(Player player) {
        UUID uuid = player.getUniqueId();
        GuildWar war = current;
        if (war != null) {
            war.rosterA().remove(uuid);
            war.rosterB().remove(uuid);
        }
        if (sideOf.containsKey(uuid) || sparrowSnap.containsKey(uuid) || localSnap.containsKey(uuid)) {
            restoreSnapshot(uuid, null);
            if (plugin.luckyPeaches() != null && plugin.luckyPeaches().available()) {
                plugin.luckyPeaches().clear(uuid);
            }
            sideOf.remove(uuid);
            spectators.remove(uuid);
            returnLoc.remove(uuid);
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
        broadcastWar(war, plugin.messages().component("war-kill",
                "killer", killer.getName(), "victim", victim.getName(), "points", points));
        publishState(war);
    }

    /** 参赛者死亡：广播比分；重生时由监听器重新发放装备并传送回出生点。 */
    public void onDeath(Player player) {
        GuildWar war = current;
        if (war == null || war.sideOf(player.getUniqueId()) == null) {
            return;
        }
        // 重生处理交给 PlayerRespawnEvent 监听器
    }

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

    private void publishState(GuildWar war) {
        if (plugin.bus().isReady()) {
            plugin.bus().publishWarState(war.id(), war.state().name(), war.scoreA(), war.scoreB());
        }
    }

    private void broadcastWar(GuildWar war, net.kyori.adventure.text.Component text) {
        plugin.guilds().notifyGuild(war.guildA(), text, null);
        plugin.guilds().notifyGuild(war.guildB(), text, null);
        plugin.guilds().guildBroadcast(war.guildA(), text, null);
        plugin.guilds().guildBroadcast(war.guildB(), text, null);
        // 全服公告（有 LiuChat 时借其跨服链路）
        Player carrier = Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
        if (plugin.chat() != null && plugin.chat().available() && carrier != null) {
            plugin.chat().broadcast(carrier, text);
        }
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
                    CrossServerBus.map("guild", guildId, "server", plugin.serverId()));
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
