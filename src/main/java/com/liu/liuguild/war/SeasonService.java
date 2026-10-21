package com.liu.liuguild.war;

import com.liu.liuguild.LiuGuild;
import com.liu.liuguild.config.*;
import com.liu.liuguild.module.GuildModule;
import com.liu.liuguild.util.WorldResolver;
import org.bukkit.*;
import org.bukkit.boss.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.scheduler.BukkitTask;
import java.util.*;

/** 独立多工会混战控制器。主线程持有比赛与玩家状态，串行IO队列负责跨服协议和奖励事务。 */
public final class SeasonService implements GuildModule, Listener {
    private final LiuGuild plugin;
    private final SeasonRepository repo;
    private final java.util.concurrent.ExecutorService io = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final Set<String> seen = new HashSet<>();
    private final Map<UUID, KitService.Snapshot> snapshots = new HashMap<>();
    private final Map<UUID, SeasonRepository.Entry> participants = new LinkedHashMap<>();
    private final Set<UUID> capturedOnce = new HashSet<>();
    private final Set<UUID> capturing = new HashSet<>(), restoring = new HashSet<>(), waiting = new HashSet<>();
    private final Map<UUID, Long> arrivals = new HashMap<>();
    private final Map<UUID, Long> transferCooldown = new HashMap<>();
    /** 返程失败提示限流（UUID → 上次提示时间），避免 5 秒轮询把聊天刷爆。 */
    private final Map<UUID, Long> returnNoticeAt = new HashMap<>();
    private volatile SeasonRepository.Event current;
    private WarMode mode;
    private ArenaDef arena;
    private World world;
    private SeasonBattle battle;
    private BossBar bar;
    private BukkitTask task;
    private long prepareUntil;
    private volatile boolean running;
    private boolean polling, finishing, starting;
    private boolean worldLoading;
    /** 上次轮询数据库的时间；无赛事时退避到 5 秒，避免空转。 */
    private long lastPollAt;
    /** 上次续期「承办服忙碌」标记的时间。 */
    private long lastHostLeaseAt;
    private Map<Integer,Long> rewards = Map.of();

    public SeasonService(LiuGuild plugin) { this.plugin=plugin; repo=new SeasonRepository(plugin.db()); }
    public String id() { return "season-battle"; }
    public int order() { return 62; }
    public void enable(LiuGuild plugin) throws Exception {
        repo.init(); running=true;
        Bukkit.getPluginManager().registerEvents(this,plugin);
        task=Bukkit.getScheduler().runTaskTimer(plugin,this::tick,20,20);
    }
    public boolean isSeason(String key) {
        if (key == null) return false;
        WarMode configured=plugin.settings().war.modes.get(key.toLowerCase(Locale.ROOT));
        return configured!=null && configured.melee;
    }
    public boolean busy() { return current!=null; }
    public boolean restricted(UUID id) { return participants.containsKey(id) || capturing.contains(id) || restoring.contains(id); }
    /** 是否仍在赛事名单内（准备/战斗/等待复活/已淘汰待返程），用于屏蔽外部药水效果。 */
    public boolean isFighting(UUID id) { return id != null && participants.containsKey(id); }
    public boolean capturing(UUID id) { return capturing.contains(id) || restoring.contains(id); }
    public boolean frozen(UUID id) {
        if (!restricted(id)) return false;
        if (battle == null || battle.phase() != SeasonBattle.Phase.RUNNING) {
            return mode == null || mode.freezeOnPrepare;
        }
        return waiting.contains(id) || capturing.contains(id) || restoring.contains(id);
    }
    public void requestStart(org.bukkit.command.CommandSender sender, String key) {
        requestStart(key, message -> { if(running) plugin.sync(() -> sender.sendMessage(message)); });
    }
    private boolean requestStart(String key, java.util.function.Consumer<String> reply) {
        WarMode requested=plugin.settings().war.modes.get(key.toLowerCase(Locale.ROOT));
        if(requested==null || !requested.enabled || !plugin.settings().war.enabled) { reply.accept("§c工会战或指定模式不存在/未启用。"); return false; }
        ArenaDef def=plugin.settings().arenas.arenas.get(requested.arena.toLowerCase(Locale.ROOT));
        if(def==null) { reply.accept("§c模式的竞技场不存在。"); return false; }
        String host=def.server==null || def.server.isBlank()?plugin.serverId():def.server;
        if(!host.equals(plugin.serverId()) && (!plugin.settings().main.transferEnabled
                || plugin.bus()==null || !plugin.bus().isReady() || plugin.bus().transfer()==null || !plugin.bus().transfer().available())) {
            reply.accept("§c本赛季场地在 "+host+"，但跨服传送不可用（Redis/transfer 未就绪），无法把玩家送过去。");
            return false;
        }
        if(host.equals(plugin.serverId()) && requested.fairHealth && (plugin.luckyPeaches()==null || !plugin.luckyPeaches().available()))
            plugin.getLogger().warning("赛季模式 "+requested.key+" 开启了 fair-health，但未检测到 LuckyPeaches 的屏蔽接口，无法保证公平血量。");
        long now=System.currentTimeMillis();
        long window=Math.max(5,requested.seasonRegistrationSeconds)*1000L;
        SeasonRepository.Event event=new SeasonRepository.Event(UUID.randomUUID().toString(),requested.key,host,"REGISTERING",now+window,now);
        submit(() -> {
            try { repo.create(event); reply.accept("§a赛季混战报名已开启，正在等待承办服和各子服汇总名单。"); }
            catch(Exception e) { reply.accept("§c赛季未开启：承办服已被占用或数据库失败，详情见日志。"); plugin.getLogger().warning("赛季未开启: "+e.getMessage()); }
        });
        return true;
    }
    /** 当前赛季完整排名（主线程缓存）。 */
    public List<SeasonBattle.Standing> standings() { return battle==null ? List.of() : battle.standings(); }
    public boolean start(String key) { return requestStart(key, message -> plugin.getLogger().info(message)); }

    public void status(Player player) {
        if(current==null) { player.sendMessage("§e本服没有正在承办的赛季混战。"); return; }
        player.sendMessage("§6赛季混战 §f"+mode.display+" §7["+current.state()+"]");
        if(battle!=null) for(var row:battle.standings()) player.sendMessage("§e#"+row.rank()+" §f"+guildName(row.guildId())+" §a"+row.points()+"分 §7死亡"+row.deaths()+" 存活"+row.survivors());
    }
    public void stopAll() {
        stop();
        submit(() -> {
            try { for(var event:repo.events()) repo.finish(event.id(),List.of(),Map.of(),true); }
            catch(Exception error) { failure(error); }
        });
    }
    public boolean stop() { if(current==null) return false; finish(true); return true; }
    private String guildName(String id) { var g=plugin.guilds().guild(id); return g==null?id:g.name(); }
    private void submit(Runnable work) { if(!io.isShutdown()) io.submit(work); }
    private void failure(Exception e) { plugin.getLogger().warning("赛季混战IO失败: "+e.getMessage()); }
    private void tick() {
        if(!running) return;
        long pollInterval = current == null ? 5000L : 1000L;
        long nowMs = System.currentTimeMillis();
        // 赛季期间也占用承办服：普通匹配不要再把两工会战派到同一台机器。
        if(current != null && nowMs - lastHostLeaseAt >= 5000L) {
            lastHostLeaseAt = nowMs;
            String host = current.host();
            String leaseEvent = current.id();
            if(host != null && !host.isBlank()) {
                plugin.async(() -> {
                    if(current != null && leaseEvent.equals(current.id())) {
                        plugin.bus().markHostBusy(host, 60);
                    }
                });
            }
        }
        if(!polling && nowMs - lastPollAt >= pollInterval) {
            lastPollAt = nowMs;
            polling=true;
            String heartbeat=current==null?null:current.id();
            submit(() -> {
                try {
                    if(heartbeat!=null) repo.heartbeat(heartbeat);
                    var events=repo.events();
                    var hosted=heartbeat==null?null:repo.event(heartbeat);
                    Map<String,List<SeasonRepository.Entry>> entries=new HashMap<>();
                    for(var event:events) {
                        if(System.currentTimeMillis()-event.heartbeat()>90000) {
                            repo.finish(event.id(),List.of(),Map.of(),true); continue;
                        }
                        entries.put(event.id(),repo.entries(event.id()));
                    }
                    var returns=repo.pendingReturns();
                    if (!running) return;
                    plugin.sync(() -> {
                        polling=false;
                        if(!running) return;
                        if(current!=null && hosted!=null && current.id().equals(hosted.id())
                                && ("CANCELLED".equals(hosted.state()) || "FINISHED".equals(hosted.state()))) finish(true);
                        for(var event:events) if(entries.containsKey(event.id())) process(event,entries.get(event.id()));
                        for(var entry:returns) returnPlayer(entry);
                    });
                } catch(Exception e) { failure(e); plugin.sync(() -> polling=false); }
            });
        }
        if(current==null && !participants.isEmpty()) {
            for(var entry:new ArrayList<>(participants.values())) {
                Player p=Bukkit.getPlayer(entry.player());
                if(p!=null && !p.isDead()) restore(p,entry,false);
            }
        }
        if(current==null || finishing) return;
        if(battle!=null && battle.phase()==SeasonBattle.Phase.FINISHED) { finish(false); return; }
        long now=System.currentTimeMillis();
        if("PREPARING".equals(current.state()) && now>=prepareUntil && !starting) begin();
        if(battle!=null && battle.phase()==SeasonBattle.Phase.RUNNING) {
            if(battle.finishIfDue(now)) finish(false);
            else updateBoard();
        }
    }
    private void process(SeasonRepository.Event event,List<SeasonRepository.Entry> entries) {
        if("REGISTERING".equals(event.state()) && seen.add(event.id())) registerLocal(event);
        if(plugin.war().isHostServer(event.host())) {
            if(current==null && "REGISTERING".equals(event.state())) {
                if(plugin.war().currentWar()!=null) { cancelAsync(event.id()); return; }
                capturedOnce.clear();
                current=event;
                mode=plugin.settings().war.modes.get(event.mode());
                if(mode==null) { finish(true); return; }
                arena=plugin.settings().arenas.arenas.get(mode.arena.toLowerCase(Locale.ROOT));
                if(arena==null || arena.spawnPointNames().isEmpty()) { finish(true); return; }
                world=null; worldLoading=true;
                String identity=current.id();
                plugin.war().openSeasonWorld(arena,created -> {
                    if(current==null || !identity.equals(current.id()) || finishing) return;
                    worldLoading=false; world=created;
                    if(created==null) finish(true);
                });
                try { rewards=readRewards(event.mode()); }
                catch(Exception error) { plugin.getLogger().warning("赛季奖励配置错误: "+error.getMessage()); finish(true); return; }
            } else if(current==null) {
                // 承办服重启后不重新发套装或重复结算，取消残留赛事并驱动持久化返程。
                cancelAsync(event.id()); return;
            }
            if(!current.id().equals(event.id()) || finishing || worldLoading || world==null) return;
            if("REGISTERING".equals(event.state()) && System.currentTimeMillis()>=event.until()) {
                Set<String> guilds=new HashSet<>();
                entries.forEach(e -> guilds.add(e.guild()));
                if(guilds.size()<2) {
                    announceRegistered(entries,"§e[工会赛季] 报名工会不足 2 个，赛事取消。");
                    finish(true);
                    return;
                }
                prepareUntil=System.currentTimeMillis()+Math.max(1,mode.prepareSeconds)*1000L;
                current=new SeasonRepository.Event(event.id(),event.mode(),event.host(),"PREPARING",prepareUntil,System.currentTimeMillis());
                submit(() -> { try { repo.state(event.id(),"PREPARING",prepareUntil); } catch(Exception e) { failure(e); } });
                announceRegistered(entries,"§6[工会赛季] §e"+guilds.size()+" 个工会进入混战准备阶段。");
            }
            if("PREPARING".equals(current.state()) && !starting) {
                for(var entry:entries) {
                    Player player=Bukkit.getPlayer(entry.player());
                    if(player!=null && !participants.containsKey(entry.player()) && !capturing.contains(entry.player()) && !capturedOnce.contains(entry.player()) && "REGISTERED".equals(entry.state())) admit(player,entry,entries);
                }
            }
        }
        if("PREPARING".equals(event.state()) && !plugin.war().isHostServer(event.host())) {
            for(var entry:entries) {
                WarMode selected=plugin.settings().war.modes.get(event.mode());
                if(selected==null) continue;
                var team=entries.stream().filter(e -> e.guild().equals(entry.guild())).sorted(Comparator.comparing(SeasonRepository.Entry::player)).toList();
                if(team.size()<selected.minMembers || team.indexOf(entry)>=selected.teamSize) continue;
                Player p=Bukkit.getPlayer(entry.player());
                if(p!=null && plugin.war().isHostServer(entry.source()) && "REGISTERED".equals(entry.state()) && !plugin.war().isWarRestricted(p.getUniqueId())) transfer(p,event.host());
            }
        }
    }
    private Map<Integer,Long> readRewards(String key) {
        WarMode selected=plugin.settings().war.modes.get(key);
        return selected==null ? Map.of() : selected.rankRewards;
    }
    private void registerLocal(SeasonRepository.Event event) {
        WarMode selected=plugin.settings().war.modes.get(event.mode());
        if(selected==null || !selected.enabled) return;
        List<SeasonRepository.Entry> registrations=new ArrayList<>();
        for(var guild:plugin.guilds().all()) {
            if(guild.level()<selected.minLevel || plugin.war().warOf(guild.id())!=null) continue;
            List<Player> players=plugin.guilds().onlineMembers(guild).stream().filter(p -> p.hasPermission("liuguild.war") && !plugin.war().isWarRestricted(p.getUniqueId())).sorted(Comparator.comparing(Player::getUniqueId)).toList();
            // 最低人数在承办服汇总后检查，支持成员分散在多个子服。
            for(Player p:players) {
                Location loc=p.getLocation();
                boolean transientWorld=loc.getWorld()==null || WorldResolver.isTransientWorld(loc.getWorld().getName());
                // 身在副本/临时世界：那是"用完即删"的世界，记成返程点赛后必然失效。
                // 改记主城出生点；连主城都没有就跳过，别造一条注定失败的记录。
                if(transientWorld) {
                    Location spawn=WorldResolver.defaultSpawn();
                    if(spawn==null) continue;
                    loc=spawn;
                }
                registrations.add(new SeasonRepository.Entry(event.id(),p.getUniqueId(),guild.id(),plugin.serverId(),
                        loc.getWorld().getName(),loc.getX(),loc.getY(),loc.getZ(),loc.getYaw(),loc.getPitch(),"REGISTERED"));
                p.sendMessage("§6[工会赛季] §e已报名混战，正在等待参赛名单汇总。");
            }
        }
        submit(() -> {
            for(var entry:registrations) try {
                if(plugin.war().isQueued(entry.guild())) continue;
                repo.enter(entry);
            } catch(Exception e) { failure(e); }
        });
    }
    private void transfer(Player player,String host) {
        long now=System.currentTimeMillis();
        if(now-transferCooldown.getOrDefault(player.getUniqueId(),0L)<10000) return;
        transferCooldown.put(player.getUniqueId(),now);
        if(plugin.bus()!=null && plugin.bus().transfer()!=null) plugin.bus().transfer().connect(player,host);
    }
    private void admit(Player player,SeasonRepository.Entry entry,List<SeasonRepository.Entry> all) {
        if(plugin.war().isWarRestricted(player.getUniqueId())) return;
        if(System.currentTimeMillis()-arrivals.getOrDefault(player.getUniqueId(),0L)<3000) return;
        if(plugin.sparrow()!=null && !plugin.sparrow().playerReady(player.getUniqueId())) return;
        List<SeasonRepository.Entry> team=all.stream().filter(e -> e.guild().equals(entry.guild())).sorted(Comparator.comparing(SeasonRepository.Entry::player)).toList();
        if(team.size()<mode.minMembers || team.indexOf(entry)>=mode.teamSize) return;
        var guild=plugin.guilds().guildOf(player.getUniqueId());
        if(guild==null || !guild.id().equals(entry.guild()) || guild.level()<mode.minLevel) return;
        capturedOnce.add(entry.player());
        capturing.add(entry.player());
        plugin.gui().closeMenu(player);
        if(plugin.buffs()!=null) plugin.buffs().suspend(player);
        KitService.Snapshot snap=plugin.kits().snapshot(player);
        if (snap == null || snap.location() == null) {
            capturedOnce.remove(entry.player()); capturing.remove(entry.player());
            player.sendMessage("§c赛季托管快照不可用，已取消入场。");
            return;
        }
        String data=plugin.kits().encodeSnapshotItems(snap);
        Location loc=snap.location();
        if(data==null || data.isBlank() || loc==null || loc.getWorld()==null) {
            capturedOnce.remove(entry.player()); capturing.remove(entry.player());
            player.sendMessage("§c赛季托管快照编码失败，已取消入场（请联系管理员检查背包/物品组件）。");
            plugin.getLogger().warning("赛季托管快照编码失败，拒绝入场: "+entry.player());
            return;
        }
        var escrow=new KitService.PersistedEscrow(entry.player(),plugin.serverId(),entry.event(),null,data,loc.getWorld().getName(),loc.getX(),loc.getY(),loc.getZ(),loc.getYaw(),loc.getPitch(),snap.gameMode().name(),snap.health(),snap.food(),snap.level(),snap.exp(),System.currentTimeMillis());
        String eventId=current.id();
        submit(() -> {
            try {
                if(plugin.repo().loadWarEscrow(entry.player())!=null) throw new IllegalStateException("玩家仍有未恢复的装备托管");
                plugin.repo().saveWarEscrow(escrow);
                if(!running) return;
                plugin.sync(() -> {
                    capturing.remove(entry.player());
                    if(!player.isOnline() || current==null || finishing || !eventId.equals(current.id()) || starting) {
                        if(player.isOnline()) plugin.war().recoverPendingEscrow(player);
                        return;
                    }
                    snapshots.put(entry.player(),snap); participants.put(entry.player(),entry);
                    applyFairHealth(player);
                    if(!teleportSpawn(player,entry.guild(),all)) { restore(player,entry,false); return; }
                    try { plugin.kits().apply(player,mode); }
                    catch(Exception error) { plugin.getLogger().warning("赛季套装发放失败: "+error.getMessage()); restore(player,entry,false); return; }
                    player.setGameMode(GameMode.ADVENTURE);
                    player.setInvulnerable(true);
                    submit(() -> { try { repo.entryState(entry.event(),entry.player(),"ADMITTED"); } catch(Exception e) { failure(e); } });
                });
            } catch(Exception e) {
                failure(e);
                plugin.sync(() -> { capturing.remove(entry.player()); capturedOnce.remove(entry.player()); });
            }
        });
    }
    private boolean teleportSpawn(Player p,String guild,List<SeasonRepository.Entry> entries) {
        List<String> guilds=entries.stream().map(SeasonRepository.Entry::guild).distinct().sorted().toList();
        List<String> points=arena.spawnPointNames();
        int index=battle==null ? Math.max(0,guilds.indexOf(guild))%points.size() : battle.spawnIndex(guild,points.size());
        return plugin.war().warTeleport(p,arena.spawn(points.get(index)).toLocation(world));
    }
    private void begin() {
        starting=true;
        if(!capturing.isEmpty()) { starting=false; prepareUntil=System.currentTimeMillis()+1000; return; }
        SeasonBattle next=new SeasonBattle();
        Map<String,List<UUID>> teams=new TreeMap<>();
        for(var entry:participants.values()) if(Bukkit.getPlayer(entry.player())!=null) teams.computeIfAbsent(entry.guild(),k->new ArrayList<>()).add(entry.player());
        teams.entrySet().removeIf(e -> e.getValue().size()<mode.minMembers);
        if(teams.size()<2) { finish(true); return; }
        teams.forEach((id,players) -> next.register(id,players,Math.max(1,mode.livesPerPlayer)));
        next.start(System.currentTimeMillis(),mode.durationSeconds*1000L);
        battle=next;
        for(var entry:new ArrayList<>(participants.values())) {
            Player p=Bukkit.getPlayer(entry.player());
            if(p==null) continue;
            if(!teams.containsKey(entry.guild())) { restore(p,entry,false); continue; }
            teleportSpawn(p,entry.guild(),new ArrayList<>(participants.values()));
            p.setInvulnerable(false);
        }
        current=new SeasonRepository.Event(current.id(),current.mode(),current.host(),"RUNNING",System.currentTimeMillis()+mode.durationSeconds*1000L,System.currentTimeMillis());
        String id=current.id(); long until=current.until();
        submit(() -> { try { repo.state(id,"RUNNING",until); } catch(Exception e) { failure(e); } });
        bar=plugin.settings().war.bossBarEnabled?Bukkit.createBossBar("工会赛季混战",BarColor.YELLOW,BarStyle.SOLID):null;
        if(bar!=null) participants.keySet().forEach(uuid -> { Player p=Bukkit.getPlayer(uuid); if(p!=null) bar.addPlayer(p); });
        announceParticipants("§6[工会赛季] §c混战开始！击杀得分，同分按死亡次数排名。");
    }
    private void updateBoard() {
        if(bar==null) return;
        StringBuilder title=new StringBuilder("剩余 "+Math.max(0,(current.until()-System.currentTimeMillis())/1000)+"秒 ");
        battle.standings().stream().limit(5).forEach(row -> title.append(" #").append(row.rank()).append(' ').append(guildName(row.guildId())).append(':').append(row.points()));
        bar.setTitle(title.toString());
    }
    /** 赛季战况/结算只发给本场参赛者，不再向承办服全体玩家广播。 */
    private void announceParticipants(String message) {
        for (UUID id : new ArrayList<>(participants.keySet())) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && player.isOnline()) {
                player.sendMessage(message);
            }
        }
    }

    /** 准备阶段名单已汇总但尚未全部完成入场，直接发给本服已在线报名者。 */
    private void announceRegistered(List<SeasonRepository.Entry> entries, String message) {
        Set<UUID> sent = new HashSet<>();
        for (SeasonRepository.Entry entry : entries) {
            if (!sent.add(entry.player())) {
                continue;
            }
            Player player = Bukkit.getPlayer(entry.player());
            if (player != null && player.isOnline()) {
                player.sendMessage(message);
            }
        }
    }

    private void finish(boolean cancelled) {
        if(current==null || finishing) return;
        finishing=true;
        participants.keySet().forEach(id -> { Player p=Bukkit.getPlayer(id); if(p!=null) p.setInvulnerable(true); });
        String id=current.id();
        List<SeasonBattle.Standing> ranks=battle==null?List.of():battle.standings();
        Map<Integer,Long> prizes=rewards;
        ArenaDef.AfterWar after=arena==null?null:arena.afterWar;
        String host=current.host(); String arenaWorld=world==null?"":world.getName();
        String modeName=mode==null?"season":mode.display;
        submit(() -> {
            try {
                if(!cancelled && after!=null && after.endSpawnEnabled) {
                    var point=after.endSpawn;
                    repo.destination(id,after.endSpawnServer.isBlank()?host:after.endSpawnServer,
                            after.endSpawnWorld.isBlank()?arenaWorld:after.endSpawnWorld,
                            new SeasonRepository.ArenaDestination(point.x(),point.y(),point.z(),point.yaw(),point.pitch()));
                }
                repo.finish(id,ranks,prizes,cancelled);
                if(!running) return;
                plugin.sync(() -> {
                    if(current==null || !current.id().equals(id)) return;
                    if(!cancelled) for(var row:ranks) {
                        announceParticipants("§6[赛季结算] #"+row.rank()+" "+guildName(row.guildId())+"："+row.points()+"分，死亡"+row.deaths()+"次，公共资金奖励"+prizes.getOrDefault(row.rank(),0L));
                        plugin.guilds().reloadGuild(row.guildId());
                        plugin.broadcastGuildChange(row.guildId());
                    }
                    else announceParticipants("§e[工会赛季] 赛事取消（人数不足、场地不可用或管理员停止），不发放排名奖励。");
                    for(var entry:new ArrayList<>(participants.values())) {
                        Player p=Bukkit.getPlayer(entry.player());
                        if(p!=null && !p.isDead()) {
                            restore(p,entry,false);
                        } else {
                            // 离线/死亡玩家不能留在 participants，否则 restricted 会阻塞返程与 escrow 恢复。
                            participants.remove(entry.player()); snapshots.remove(entry.player()); waiting.remove(entry.player());
                        }
                    }
                    if(bar!=null) { bar.removeAll(); bar=null; }
                    if(!cancelled && plugin.webhook()!=null) {
                        java.util.List<String> lines=new java.util.ArrayList<>();
                        for(var row:ranks) lines.add("#"+row.rank()+" "+guildName(row.guildId())+"："+row.points()+"分");
                        plugin.webhook().seasonResult(modeName, host, lines);
                    }
                    plugin.war().closeSeasonWorld(arena);
                    if(host != null && !host.isBlank() && plugin.war().currentWar() == null) {
                        plugin.async(() -> plugin.bus().clearHostBusy(host));
                    }
                    current=null; battle=null; starting=false; finishing=false; waiting.clear();
                });
            } catch(Exception e) { failure(e); plugin.sync(() -> { finishing=false; if(running) plugin.syncDelayed(() -> finish(cancelled),100); }); }
        });
    }
    private void cancelAsync(String id) { submit(() -> { try { repo.finish(id,List.of(),Map.of(),true); } catch(Exception e) { failure(e); } }); }
    private boolean playerReady(UUID player) {
        return plugin.sparrow()==null || plugin.sparrow().playerReady(player);
    }

    /** 赛季公平血量：按模式开关屏蔽 LuckyPeaches 蟠桃加成，必须在发放套装前执行。 */
    private void applyFairHealth(Player player) {
        if(mode==null || !mode.fairHealth || plugin.luckyPeaches()==null || !plugin.luckyPeaches().available()) return;
        plugin.luckyPeaches().enter(player,plugin.luckyPeaches().supportsSuppress());
    }
    private void clearFairHealth(Player player,UUID id) {
        if(plugin.luckyPeaches()==null || !plugin.luckyPeaches().available()) return;
        if(player!=null && player.isOnline()) plugin.luckyPeaches().exit(player);
        else plugin.luckyPeaches().clear(id);
    }
    private void restore(Player p,SeasonRepository.Entry entry,boolean quitting) {
        if(!quitting && !playerReady(entry.player())) return;
        KitService.Snapshot snap=snapshots.remove(entry.player());
        participants.remove(entry.player()); waiting.remove(entry.player());
        // 先恢复蟠桃血量属性，再按快照还原血量，避免快照血量被压制后的上限截断。
        clearFairHealth(p,entry.player());
        if(snap==null) return;
        restoring.add(entry.player());
        try {
            plugin.kits().restore(p,snap,true);
            p.setInvulnerable(false);
            if(bar!=null) bar.removePlayer(p);
            submit(() -> {
                try {
                    plugin.repo().deleteWarEscrow(entry.player());
                    repo.entryState(entry.event(),entry.player(),"LEFT");
                    if(running) plugin.sync(() -> { restoring.remove(entry.player()); });
                } catch(Exception e) { failure(e); plugin.sync(() -> restoring.remove(entry.player())); }
            });
        } catch (Throwable t) {
            failure(new Exception("赛季恢复失败: " + entry.player(), t));
            restoring.remove(entry.player());
        }
    }
    private void returnPlayer(SeasonRepository.Entry entry) {
        Player p=Bukkit.getPlayer(entry.player());
        if(p==null || p.isDead() || restricted(entry.player()) || restoring.contains(entry.player()) || plugin.war().isWarRestricted(entry.player())) return;
        if(plugin.sparrow()!=null && !plugin.sparrow().playerReady(entry.player())) return;
        // 必须确认托管已归还，才允许跨服离开。
        if(System.currentTimeMillis()-transferCooldown.getOrDefault(entry.player(),0L)<3000) return;
        transferCooldown.put(entry.player(),System.currentTimeMillis());
        submit(() -> {
            try {
                var latest=repo.entry(entry.event(),entry.player());
                if(latest==null || "RETURNED".equals(latest.state())
                        || !Objects.equals(latest.source(),entry.source())
                        || !Objects.equals(latest.world(),entry.world())) return;
                if(plugin.repo().loadWarEscrow(entry.player())!=null) { plugin.sync(() -> {
                    plugin.war().recoverPendingEscrow(p);
                    // 恢复模块异步完成后才继续返程，恢复原始无敌状态由赛季清理兜底。
                    plugin.syncDelayed(() -> { if(p.isOnline() && !restricted(entry.player())) p.setInvulnerable(false); },20);
                }); return; }
                plugin.sync(() -> {
                    if(!p.isOnline() || restricted(entry.player())) return;
                    if(!plugin.war().isHostServer(entry.source())) {
                        transferCooldown.remove(entry.player()); transfer(p,entry.source()); return;
                    }
                    // 原世界还在 → 精确回原坐标；世界已被删（副本实例/临时竞技场）→ 退回主城出生点。
                    // 旧实现只发一句"保留返程记录"就 return，记录永远不清：每 5 秒刷屏、每次登录重来。
                    Location dest=returnDestination(latest);
                    if(dest==null) { noticeReturnFailed(p,latest.world()); return; }
                    boolean fallback=returnNeedsFallback(latest);
                    boolean ok;
                    try { ok=plugin.war().warTeleport(p,dest); }
                    catch (Throwable t) { plugin.getLogger().warning("赛季返程传送异常: "+t.getMessage()); ok=false; }
                    if(ok) {
                        if(fallback) noticeReturnFallback(p,latest.world());
                        submit(() -> { try { repo.entryState(entry.event(),entry.player(),"RETURNED"); } catch(Exception e) { failure(e); } });
                    } else {
                        noticeReturnFailed(p,latest.world());
                    }
                });
            } catch(Exception e) { failure(e); }
        });
    }

    /** 返程落点：世界有效就用原坐标，失效就回主城出生点（绝不把旧坐标套到别的主世界）。 */
    private Location returnDestination(SeasonRepository.Entry entry) {
        // 临时世界即使还没被卸载也不能作为返程点：它随时可能在本轮结束后删除。
        World target=returnNeedsFallback(entry) ? null : WorldResolver.resolve(entry.world());
        if(target!=null) return new Location(target,entry.x(),entry.y(),entry.z(),entry.yaw(),entry.pitch());
        Location fallback=WorldResolver.defaultSpawn();
        if(fallback==null) return null;
        plugin.getLogger().warning("赛季返程目标世界已失效（"+entry.world()+"），"+entry.player()+" 改送回 "
                +fallback.getWorld().getName()+" 出生点。");
        return fallback;
    }

    /** 记录里的世界已失效/是临时世界，必须改用主城出生点。 */
    private boolean returnNeedsFallback(SeasonRepository.Entry entry) {
        return WorldResolver.isTransientWorld(entry.world()) || WorldResolver.resolve(entry.world())==null;
    }

    /** 成功改送回主城出生点的一次性提示；与失败提示共用 60 秒限流。 */
    private void noticeReturnFallback(Player p,String world) {
        if(p==null || !p.isOnline()) return;
        long now=System.currentTimeMillis();
        Long last=returnNoticeAt.get(p.getUniqueId());
        if(last!=null && now-last<60_000L) return;
        returnNoticeAt.put(p.getUniqueId(),now);
        p.sendMessage("§e赛季返程目标世界 §f"+world+" §e已不存在，已将你送回主城出生点。");
    }

    /** 同一条失败提示 60 秒最多一次；成功兜底返程后记录会被标成 RETURNED，循环自然停止。 */
    private void noticeReturnFailed(Player p,String world) {
        if(p==null || !p.isOnline()) return;
        long now=System.currentTimeMillis();
        Long last=returnNoticeAt.get(p.getUniqueId());
        if(last!=null && now-last<60_000L) return;
        returnNoticeAt.put(p.getUniqueId(),now);
        p.sendMessage("§c赛季返程失败：目标世界 §f"+world+" §c已不存在，暂时无法送回主城出生点；请联系管理。");
    }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void damage(EntityDamageEvent event) {
        if(!(event.getEntity() instanceof Player victim)) return;
        Player killer=null;
        if(event instanceof EntityDamageByEntityEvent by) {
            if(by.getDamager() instanceof Player p) killer=p;
            else if(by.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player p) killer=p;
        }
        boolean v=participants.containsKey(victim.getUniqueId()), k=killer!=null && participants.containsKey(killer.getUniqueId());
        if(capturing(victim.getUniqueId()) || killer!=null && capturing(killer.getUniqueId())) { event.setCancelled(true); return; }
        if(!v && !k) return;
        if(!v || killer!=null && !k || frozen(victim.getUniqueId()) || k && frozen(killer.getUniqueId())) { event.setCancelled(true); return; }
        if(k && participants.get(victim.getUniqueId()).guild().equals(participants.get(killer.getUniqueId()).guild())) { event.setCancelled(true); return; }
        if(event.getFinalDamage()>=victim.getHealth()) {
            event.setCancelled(true);
            die(victim,killer);
        }
    }
    private void die(Player victim,Player killer) {
        if(battle==null || !battle.death(victim.getUniqueId(),killer==null?null:killer.getUniqueId(),Math.max(0,mode.scoreKill),System.currentTimeMillis())) return;
        UUID uuid=victim.getUniqueId(); waiting.add(uuid);
        if(!victim.isDead()) {
            victim.setHealth(Math.max(1,Math.min(20,victim.getMaxHealth())));
            victim.setGameMode(GameMode.SPECTATOR);
            plugin.war().warTeleport(victim,arena.spawnSpectator.toLocation(world));
        }
        victim.setFireTicks(0); victim.setInvulnerable(true);
        if(battle.phase()==SeasonBattle.Phase.FINISHED) { finish(false); return; }
        SeasonBattle identity=battle;
        plugin.syncDelayed(() -> {
            if(battle!=identity || finishing || !victim.isOnline() || !participants.containsKey(uuid) || victim.isDead()) return;
            if(!battle.respawn(uuid)) return;
            waiting.remove(uuid);
            applyFairHealth(victim);
            plugin.kits().apply(victim,mode); victim.setGameMode(GameMode.ADVENTURE); victim.setInvulnerable(false);
            teleportSpawn(victim,participants.get(uuid).guild(),new ArrayList<>(participants.values()));
        },Math.max(0,mode.respawnDelaySeconds)*20L);
    }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void death(PlayerDeathEvent event) {
        if(!participants.containsKey(event.getEntity().getUniqueId())) return;
        event.setKeepInventory(true); event.setKeepLevel(true); event.getDrops().clear(); event.setDroppedExp(0);
        die(event.getEntity(),event.getEntity().getKiller());
    }
    @EventHandler public void respawn(PlayerRespawnEvent event) {
        if(!participants.containsKey(event.getPlayer().getUniqueId()) || world==null) return;
        event.setRespawnLocation(arena.spawnSpectator.toLocation(world));
        Player player=event.getPlayer(); UUID uuid=player.getUniqueId(); SeasonBattle identity=battle;
        plugin.syncDelayed(() -> {
            if(battle!=identity || battle==null || finishing || !participants.containsKey(uuid)) return;
            if(battle.respawn(uuid)) {
                waiting.remove(uuid);
                applyFairHealth(player);
                plugin.kits().apply(player,mode); player.setGameMode(GameMode.ADVENTURE); player.setInvulnerable(false);
                teleportSpawn(player,participants.get(uuid).guild(),new ArrayList<>(participants.values()));
            } else { player.setGameMode(GameMode.SPECTATOR); player.setInvulnerable(true); }
        }, Math.max(0,mode.respawnDelaySeconds)*20L);
    }

    @EventHandler public void join(PlayerJoinEvent event) { arrivals.put(event.getPlayer().getUniqueId(),System.currentTimeMillis()); }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void quit(PlayerQuitEvent event) {
        UUID quitting=event.getPlayer().getUniqueId();
        arrivals.remove(quitting);
        returnNoticeAt.remove(quitting);
        var entry=participants.get(quitting);
        if(entry==null) return;
        if(battle!=null) battle.eliminate(entry.player(),System.currentTimeMillis());
        restore(event.getPlayer(),entry,true);
        if(battle!=null && battle.phase()==SeasonBattle.Phase.FINISHED) finish(false);
    }
    @EventHandler(ignoreCancelled=true) public void teleport(PlayerTeleportEvent e) {
        if(restricted(e.getPlayer().getUniqueId()) && !plugin.war().isAllowingWarTeleport(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }
    @EventHandler(ignoreCancelled=true) public void command(PlayerCommandPreprocessEvent e) {
        if(!restricted(e.getPlayer().getUniqueId())) return;
        String[] parts=e.getMessage().substring(1).toLowerCase(Locale.ROOT).split("\\s+"); String label=parts[0];
        if(label.contains(":")) label=label.substring(label.indexOf(':')+1);
        if(!com.liu.liuguild.listener.WarListener.isAllowedWarCommand(label,parts)) e.setCancelled(true);
    }
    @EventHandler(ignoreCancelled=true) public void interact(PlayerInteractEvent e) { if(frozen(e.getPlayer().getUniqueId())) e.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void portal(PlayerPortalEvent e) { if(restricted(e.getPlayer().getUniqueId())) e.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void consume(PlayerItemConsumeEvent e) { if(frozen(e.getPlayer().getUniqueId())) e.setCancelled(true); }
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void interactEntity(PlayerInteractEntityEvent e) { if(restricted(e.getPlayer().getUniqueId())) e.setCancelled(true); }
    @EventHandler(ignoreCancelled=true) public void move(PlayerMoveEvent e) {
        if(frozen(e.getPlayer().getUniqueId()) && e.hasChangedPosition()) e.setTo(e.getFrom());
    }
    @EventHandler(ignoreCancelled=true) public void drop(PlayerDropItemEvent e) { if(restricted(e.getPlayer().getUniqueId())) e.setCancelled(true); }
    @EventHandler(ignoreCancelled=true) public void pickup(EntityPickupItemEvent e) { if(restricted(e.getEntity().getUniqueId())) e.setCancelled(true); }
    @EventHandler(ignoreCancelled=true) public void block(org.bukkit.event.block.BlockBreakEvent e) { if(restricted(e.getPlayer().getUniqueId())) e.setCancelled(true); }
    @EventHandler(ignoreCancelled=true) public void place(org.bukkit.event.block.BlockPlaceEvent e) { if(restricted(e.getPlayer().getUniqueId())) e.setCancelled(true); }
    @EventHandler(ignoreCancelled=true) public void inventory(org.bukkit.event.inventory.InventoryOpenEvent e) { if(restricted(e.getPlayer().getUniqueId())) e.setCancelled(true); }
    @EventHandler(ignoreCancelled=true) public void click(org.bukkit.event.inventory.InventoryClickEvent e) { if(capturing.contains(e.getWhoClicked().getUniqueId()) || restoring.contains(e.getWhoClicked().getUniqueId())) e.setCancelled(true); }
    @EventHandler(ignoreCancelled=true) public void drag(org.bukkit.event.inventory.InventoryDragEvent e) { if(capturing.contains(e.getWhoClicked().getUniqueId()) || restoring.contains(e.getWhoClicked().getUniqueId())) e.setCancelled(true); }
    @EventHandler(ignoreCancelled=true) public void swap(PlayerSwapHandItemsEvent e) { if(capturing.contains(e.getPlayer().getUniqueId()) || restoring.contains(e.getPlayer().getUniqueId())) e.setCancelled(true); }
    public void disable() {
        running=false; if(task!=null) task.cancel();
        if(current!=null) cancelAsync(current.id());
        for(var entry:new ArrayList<>(participants.values())) { Player p=Bukkit.getPlayer(entry.player()); if(p!=null) restore(p,entry,true); }
        if(bar!=null) bar.removeAll();
        if(current!=null) plugin.war().closeSeasonWorld(arena);
        io.shutdown();
        try { if(!io.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS)) plugin.getLogger().warning("赛季IO队列关闭超时，保留数据库恢复记录"); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
