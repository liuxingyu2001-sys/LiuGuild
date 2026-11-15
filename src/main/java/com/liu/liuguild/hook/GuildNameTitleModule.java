package com.liu.liuguild.hook;

import com.liu.liuguild.LiuGuild;
import com.liu.liuguild.api.event.GuildCreateEvent;
import com.liu.liuguild.api.event.GuildDisbandEvent;
import com.liu.liuguild.api.event.GuildJoinEvent;
import com.liu.liuguild.api.event.GuildLeaveEvent;
import com.liu.liuguild.config.MainSettings;
import com.liu.liuguild.model.Guild;
import com.liu.liuguild.module.GuildModule;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;

/**
 * 用 PlayerTitle 把「工会名」显示成玩家称号。
 *
 * <h3>为什么不需要给每个工会建称号</h3>
 * <p>PlayerTitle 的称号名支持 PlaceholderAPI 变量：聊天前缀与 Tab 前缀显示时都会调用
 * {@code PlaceholderApiUtil.set(player, titleName)}。而 LiuGuild 已经注册了
 * {@code %liuguild_name%}，所以管理员只需要在 PlayerTitle 里创建 <b>一个</b> 称号名为
 * {@code %liuguild_name%} 的称号，成员戴上后显示的就是各自工会名；工会改名也会在下次
 * 渲染时实时生效（无需重建称号，也不受 PlayerTitle 免费版 100 个称号的上限影响）。</p>
 *
 * <h3>同步策略</h3>
 * <ul>
 *   <li>进服 / 入会 / 创建工会：补授并佩戴；</li>
 *   <li>退会 / 被踢 / 解散：回收，回落到 PlayerTitle 的默认称号；</li>
 *   <li>按 {@code recheck-seconds} 对在线玩家定时对账，兜底手动换称号、跨服入退会与漏事件。</li>
 * </ul>
 *
 * <p>PlayerTitle 只有一个展示称号槽：{@code force: true}（默认）会顶掉玩家自选 / 战利品称号；
 * 想保留玩家自选称号可设 {@code force: false}，仅在玩家没有展示称号时佩戴。</p>
 */
public final class GuildNameTitleModule implements GuildModule, Listener {

    private final LiuGuild plugin;
    private BukkitTask recheckTask;
    private boolean registered;

    public GuildNameTitleModule(LiuGuild plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "guild-name-title";
    }

    @Override
    public int order() {
        // 排在 TitleService（56）之后，保证工会自己的固定头衔先加载完。
        return 57;
    }

    @Override
    public void enable(LiuGuild plugin) {
        apply();
    }

    @Override
    public void reload() {
        apply();
    }

    @Override
    public void disable() {
        stop();
    }

    /** 模块是否处于生效状态（供状态命令展示）。 */
    public boolean active() {
        PlayerTitleHook hook = plugin.playerTitle();
        return registered && enabled() && hook != null && hook.available();
    }

    // =====================================================================
    // 生命周期
    // =====================================================================

    private void apply() {
        if (!enabled()) {
            stop();
            return;
        }
        PlayerTitleHook hook = plugin.playerTitle();
        if (hook == null || !hook.available()) {
            plugin.getLogger().warning("已开启 hooks.player-title.guild-title，"
                    + "但 PlayerTitle 不可用（未安装 / 版本不符），工会名称号已跳过。");
            stop();
            return;
        }
        if (plugin.papi() == null || !plugin.papi().available()) {
            plugin.getLogger().warning("工会名称号依赖 PlaceholderAPI："
                    + "缺少 PAPI 时 %liuguild_name% 不会被解析，称号会显示成字面量。");
        }
        if (!registered) {
            Bukkit.getPluginManager().registerEvents(this, plugin);
            registered = true;
        }
        scheduleRecheck();
        // 配置热重载 / 插件重载后，立即校准一次在线玩家（启动时工会数据可能还没加载，sync 会自行跳过）。
        for (Player player : Bukkit.getOnlinePlayers()) {
            sync(player, force());
        }
    }

    private void stop() {
        if (recheckTask != null) {
            recheckTask.cancel();
            recheckTask = null;
        }
        if (registered) {
            HandlerList.unregisterAll(this);
            registered = false;
        }
    }

    private void scheduleRecheck() {
        if (recheckTask != null) {
            recheckTask.cancel();
            recheckTask = null;
        }
        int seconds = plugin.settings().main.playerTitleGuildTitleRecheckSeconds;
        if (seconds <= 0) {
            return;
        }
        long ticks = seconds * 20L;
        // 主线程执行：PlayerTitle API 内部会同步写库并触发 Bukkit 事件，不能放到异步线程。
        recheckTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                sync(player, force());
            }
        }, ticks, ticks);
    }

    // =====================================================================
    // 事件
    // =====================================================================

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // PlayerTitle 的 loginDelay 默认 1 秒，延迟 2 秒等它把玩家称号缓存建好。
        plugin.syncDelayed(() -> {
            if (registered) {
                sync(player, force());
            }
        }, 40L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGuildCreate(GuildCreateEvent event) {
        if (event.creator() != null) {
            sync(event.creator(), force());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGuildJoin(GuildJoinEvent event) {
        if (event.player() != null) {
            sync(event.player(), force());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGuildLeave(GuildLeaveEvent event) {
        if (event.player() != null) {
            clear(event.player().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGuildDisband(GuildDisbandEvent event) {
        Guild guild = event.guild();
        if (guild == null) {
            return;
        }
        // 解散不会再给每个成员单独发 GuildLeaveEvent，这里按成员列表统一回收。
        for (UUID uuid : guild.memberMap().keySet()) {
            clear(uuid);
        }
    }

    /**
     * 工会改名后刷新在线成员的展示（聊天每次渲染都会重新解析 PAPI，
     * 但 Tab 前缀由 PlayerScoreboard 缓存，需要主动 refresh 才会变）。
     */
    public void onGuildRenamed(Guild guild) {
        if (!active() || guild == null) {
            return;
        }
        for (UUID uuid : guild.memberMap().keySet()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) {
                continue;
            }
            PlayerTitleHook hook = plugin.playerTitle();
            if (hook != null && hook.available()) {
                hook.refresh(uuid);
            }
            sync(player, force());
        }
    }

    @Override
    public void onCrossServer(String type, Map<String, String> data) {
        if (!registered || !"GUILD_CHANGED".equals(type)) {
            return;
        }
        String guildId = data.get("guild");
        if (guildId == null || guildId.isBlank()) {
            return;
        }
        Guild guild = plugin.guilds().guild(guildId);
        if (guild == null) {
            return;
        }
        // 跨服总线回调可能在异步线程：刷新展示要回主线程。
        plugin.sync(() -> onGuildRenamed(guild));
    }

    // =====================================================================
    // 同步
    // =====================================================================

    private void sync(Player player, boolean force) {
        sync(player, force, 0);
    }

    /**
     * @param retry 工会数据尚未载入时的重试次数。启动初期 {@code guildOf} 会短暂返回 null，
     *              此时既不能授予也不能回收，延后重试而不是把称号误删。
     */
    private void sync(Player player, boolean force, int retry) {
        if (player == null || !player.isOnline()) {
            return;
        }
        PlayerTitleHook hook = plugin.playerTitle();
        int titleId = titleId();
        if (hook == null || !hook.available() || titleId <= 0) {
            return;
        }
        UUID uuid = player.getUniqueId();
        Guild guild = plugin.guilds().guildOf(uuid);
        if (guild == null) {
            if (!plugin.guilds().loaded()) {
                // 全量载入通常几秒内结束，最多重试 6 次（约 30 秒）。
                if (retry < 6) {
                    plugin.syncDelayed(() -> {
                        if (registered) {
                            sync(player, force, retry + 1);
                        }
                    }, 100L);
                }
                return;
            }
            clear(uuid);
            return;
        }
        if (hook.showing(uuid, titleId)) {
            return;
        }
        if (!force && hook.showingAny(uuid)) {
            return;
        }
        hook.grantAndEquip(uuid, titleId);
    }

    private void clear(UUID uuid) {
        PlayerTitleHook hook = plugin.playerTitle();
        int titleId = titleId();
        if (hook == null || !hook.available() || titleId <= 0 || uuid == null) {
            return;
        }
        if (hook.owns(uuid, titleId)) {
            hook.revoke(uuid, titleId);
        }
    }

    private boolean enabled() {
        MainSettings main = plugin.settings().main;
        return main.playerTitleGuildTitleEnabled && main.playerTitleGuildTitleId > 0;
    }

    private int titleId() {
        return plugin.settings().main.playerTitleGuildTitleId;
    }

    private boolean force() {
        return plugin.settings().main.playerTitleGuildTitleForce;
    }
}
