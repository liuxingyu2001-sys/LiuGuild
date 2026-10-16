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
    private SeasonRepository.Event current;
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
        return "season".equalsIgnoreCase(key) || plugin.configs().file("war-modes.yml").getBoolean("modes."+key+".season.melee",false);
    }
    public boolean busy() { return current!=null; }
    public boolean restricted(UUID id) { return participants.containsKey(id) || capturing.contains(id) || restoring.contains(id); }
    public boolean capturing(UUID id) { return capturing.contains(id) || restoring.contains(id); }
    public boolean frozen(UUID id) { return restricted(id) && (battle==null || battle.phase()!=SeasonBattle.Phase.RUNNING || waiting.contains(id) || capturing.contains(id) || restoring.contains(id)); }
    public void requestStart(org.bukkit.command.CommandSender sender, String key) {
        requestStart(key, message -> { if(running) plugin.sync(() -> sender.sendMessage(message)); });
    }
    private boolean requestStart(String key, java.util.function.Consumer<String> reply) {
        WarMode requested=plugin.settings().war.modes.get(key.toLowerCase(Locale.ROOT));
        if(requested==null || !requested.enabled || !plugin.settings().war.enabled) { reply.accept("§c工会战或指定模式不存在/未启用。"); return false; }
        ArenaDef def=plugin.settings().arenas.arenas.get(requested.arena.toLowerCase(Locale.ROOT));
        if(def==null) { reply.accept("§c模式的竞技场不存在。"); return false; }
        String host=def.server==null || def.server.isBlank()?plugin.serverId():def.server;
        long now=System.currentTimeMillis();
        SeasonRepository.Event event=new SeasonRepository.Event(UUID.randomUUID().toString(),requested.key,host,"REGISTERING",now+15000,now);
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
        if(!polling) {
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
                if(guilds.size()<2) { finish(true); return; }
                prepareUntil=System.currentTimeMillis()+Math.max(15,mode.prepareSeconds)*1000L;
                current=new SeasonRepository.Event(event.id(),event.mode(),event.host(),"PREPARING",prepareUntil,System.currentTimeMillis());
                submit(() -> { try { repo.state(event.id(),"PREPARING",prepareUntil); } catch(Exception e) { failure(e); } });
                Bukkit.broadcastMessage("§6[工会赛季] §e"+guilds.size()+" 个工会进入混战准备阶段。");
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
        Map<Integer,Long> result=new HashMap<>(Map.of(1,5000L,2,3000L,3,1000L));
        var section=plugin.configs().file("war-modes.yml").getConfigurationSection("modes."+key+".season.rank-rewards");
        if(section!=null) { result.clear(); }
        if(section!=null) for(String rank:section.getKeys(false)) {
            int n=Integer.parseInt(rank); long value=section.getLong(rank);
            if(n<1 || value<0) throw new IllegalArgumentException("赛季排名奖励无效");
            result.put(n,value);
        }
        return Map.copyOf(result);
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
                registrations.add(new SeasonRepository.Entry(event.id(),p.getUniqueId(),guild.id(),plugin.serverId(),loc.getWorld().getName(),loc.getX(),loc.getY(),loc.getZ(),loc.getYaw(),loc.getPitch(),"REGISTERED"));
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
        String data=plugin.kits().encodeSnapshotItems(snap);
        Location loc=snap.location();
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
                    if(!teleportSpawn(player,entry.guild(),all)) { restore(player,entry,false); return; }
                    try { plugin.kits().apply(player,mode); }
                    catch(Exception error) { plugin.getLogger().warning("赛季套装发放失败: "+error.getMessage()); restore(player,entry,false); return; }
                    player.setGameMode(GameMode.ADVENTURE);
                    player.setInvulnerable(true);
                    submit(() -> { try { repo.entryState(entry.event(),entry.player(),"ADMITTED"); } catch(Exception e) { failure(e); } });
                });
            } catch(Exception e) { failure(e); plugin.sync(() -> capturing.remove(entry.player())); }
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
        bar=Bukkit.createBossBar("工会赛季混战",BarColor.YELLOW,BarStyle.SOLID);
        participants.keySet().forEach(uuid -> { Player p=Bukkit.getPlayer(uuid); if(p!=null) bar.addPlayer(p); });
        Bukkit.broadcastMessage("§6[工会赛季] §c混战开始！击杀得分，同分按死亡次数排名。");
    }
    private void updateBoard() {
        if(bar==null) return;
        StringBuilder title=new StringBuilder("剩余 "+Math.max(0,(current.until()-System.currentTimeMillis())/1000)+"秒 ");
        battle.standings().stream().limit(5).forEach(row -> title.append(" #").append(row.rank()).append(' ').append(guildName(row.guildId())).append(':').append(row.points()));
        bar.setTitle(title.toString());
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
                        Bukkit.broadcastMessage("§6[赛季结算] #"+row.rank()+" "+guildName(row.guildId())+"："+row.points()+"分，死亡"+row.deaths()+"次，公共资金奖励"+prizes.getOrDefault(row.rank(),0L));
                        plugin.guilds().reloadGuild(row.guildId());
                        plugin.broadcastGuildChange(row.guildId());
                    }
                    else Bukkit.broadcastMessage("§e[工会赛季] 赛事取消（人数不足、场地不可用或管理员停止），不发放排名奖励。");
                    for(var entry:new ArrayList<>(participants.values())) {
                        Player p=Bukkit.getPlayer(entry.player());
                        if(p!=null && p.isDead()) {
                            participants.remove(entry.player()); snapshots.remove(entry.player());
                        } else if(p!=null) restore(p,entry,false);
                    }
                    if(bar!=null) { bar.removeAll(); bar=null; }
                    plugin.war().closeSeasonWorld(arena);
                    current=null; battle=null; starting=false; finishing=false; waiting.clear();
                });
            } catch(Exception e) { failure(e); plugin.sync(() -> { finishing=false; if(running) plugin.syncDelayed(() -> finish(cancelled),100); }); }
        });
    }
    private void cancelAsync(String id) { submit(() -> { try { repo.finish(id,List.of(),Map.of(),true); } catch(Exception e) { failure(e); } }); }
    private boolean playerReady(UUID player) {
        return plugin.sparrow()==null || plugin.sparrow().playerReady(player);
    }

    private void restore(Player p,SeasonRepository.Entry entry,boolean quitting) {
        if(!quitting && !playerReady(entry.player())) return;
        KitService.Snapshot snap=snapshots.remove(entry.player());
        participants.remove(entry.player()); waiting.remove(entry.player());
        if(snap==null) return;
        restoring.add(entry.player());
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
                if(latest==null || "RETURNED".equals(latest.state()) || !latest.source().equals(entry.source()) || !latest.world().equals(entry.world())) return;
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
                    World target=WorldResolver.resolve(entry.world());
                    if(target==null) { p.sendMessage("§c赛季返程世界未加载："+entry.world()+"，保留返程记录。"); return; }
                    if(plugin.war().warTeleport(p,new Location(target,entry.x(),entry.y(),entry.z(),entry.yaw(),entry.pitch()))) {
                        submit(() -> { try { repo.entryState(entry.event(),entry.player(),"RETURNED"); } catch(Exception e) { failure(e); } });
                    }
                });
            } catch(Exception e) { failure(e); }
        });
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
            plugin.kits().apply(victim,mode); victim.setGameMode(GameMode.ADVENTURE); victim.setInvulnerable(false);
            teleportSpawn(victim,participants.get(uuid).guild(),new ArrayList<>(participants.values()));
        },Math.max(1,mode.respawnDelaySeconds)*20L);
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
                waiting.remove(uuid); plugin.kits().apply(player,mode); player.setGameMode(GameMode.ADVENTURE); player.setInvulnerable(false);
                teleportSpawn(player,participants.get(uuid).guild(),new ArrayList<>(participants.values()));
            } else { player.setGameMode(GameMode.SPECTATOR); player.setInvulnerable(true); }
        }, Math.max(1,mode.respawnDelaySeconds)*20L);
    }

    @EventHandler public void join(PlayerJoinEvent event) { arrivals.put(event.getPlayer().getUniqueId(),System.currentTimeMillis()); }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void quit(PlayerQuitEvent event) {
        arrivals.remove(event.getPlayer().getUniqueId());
        var entry=participants.get(event.getPlayer().getUniqueId());
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
