package com.liu.liuguild.hook;

import com.liu.liuguild.LiuGuild;
import com.liu.liuguild.model.Guild;
import com.liu.liuguild.model.GuildWar;
import org.bukkit.Bukkit;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.function.Function;

/**
 * CustomNameplates 软依赖：注册 %liuguild_war_guild% 占位符，供铭牌/无限标签在头顶显示工会名。
 *
 * <p>全部走反射，未安装 CustomNameplates 时不影响启动；不直接引用其类，
 * 避免软依赖缺失导致 NoClassDefFoundError。</p>
 */
public final class CustomNameplatesHook {

    private static final int MAX_ATTEMPTS = 20;

    private final LiuGuild plugin;
    private Object placeholderManager;
    private Method unregister;
    private boolean registered;
    private boolean stopped;
    private int attempts;

    public CustomNameplatesHook(LiuGuild plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (Bukkit.getPluginManager().getPlugin("CustomNameplates") == null) {
            return;
        }
        stopped = false;
        attempts = 0;
        attempt();
    }

    public void stop() {
        stopped = true;
        if (!registered || placeholderManager == null || unregister == null) {
            return;
        }
        try {
            unregister.invoke(placeholderManager, "liuguild_war_guild");
        } catch (Throwable ignored) {
        }
        registered = false;
    }

    public boolean available() {
        return registered;
    }

    /**
     * CustomNameplates 的 API 可能在其 onEnable 的下一 tick 才初始化；
     * 首次注册失败时延迟重试，避免启动顺序导致永久失效。
     */
    private void attempt() {
        if (stopped || registered) {
            return;
        }
        attempts++;
        try {
            register();
        } catch (Throwable raw) {
            Throwable error = raw instanceof InvocationTargetException && raw.getCause() != null
                    ? raw.getCause() : raw;
            if (attempts >= MAX_ATTEMPTS) {
                plugin.getLogger().warning("CustomNameplates 接入失败（不影响启动）: "
                        + error.getClass().getSimpleName()
                        + (error.getMessage() == null ? "" : " - " + error.getMessage()));
            } else {
                long delay = Math.min(100L, 20L * attempts);
                if (!stopped) {
                    Bukkit.getScheduler().runTaskLater(plugin, this::attempt, delay);
                }
            }
        }
    }

    private void register() throws Exception {
        Class<?> apiClass = Class.forName("net.momirealms.customnameplates.api.CustomNameplatesAPI");
        Object api = apiClass.getMethod("getInstance").invoke(null);
        if (api == null) {
            throw new IllegalStateException("CustomNameplatesAPI 尚未初始化");
        }
        Object cnPlugin = apiClass.getMethod("plugin").invoke(api);
        if (cnPlugin == null) {
            throw new IllegalStateException("CustomNameplates plugin() 返回 null");
        }
        placeholderManager = cnPlugin.getClass().getMethod("getPlaceholderManager").invoke(cnPlugin);
        if (placeholderManager == null) {
            throw new IllegalStateException("PlaceholderManager 尚未初始化");
        }
        Class<?> managerInterface = Class.forName(
                "net.momirealms.customnameplates.api.placeholder.PlaceholderManager");
        Method register = managerInterface.getMethod(
                "registerPlayerPlaceholder", String.class, int.class, Function.class);
        register.invoke(placeholderManager, "liuguild_war_guild", 20,
                (Function<Object, String>) this::placeholder);
        unregister = managerInterface.getMethod("unregisterPlaceholder", String.class);
        registered = true;
        plugin.getLogger().info("CustomNameplates 已接入：%liuguild_war_guild%");
    }

    /** 由 CustomNameplates 的占位符线程调用；只读内存数据，不操作 Bukkit 实体。 */
    private String placeholder(Object cnPlayer) {
        var warSettings = plugin.settings().war;
        if (!warSettings.nameplateEnabled || cnPlayer == null) {
            return "";
        }
        try {
            UUID uuid = (UUID) cnPlayer.getClass().getMethod("uuid").invoke(cnPlayer);
            Guild guild = plugin.guilds().guildOf(uuid);
            if (guild == null || plugin.war() == null) {
                return "";
            }
            GuildWar war = plugin.war().warOf(guild.id());
            if (war == null || !war.state().isLive()) {
                return "";
            }
            String side = war.sideOf(uuid);
            if (side == null) {
                return "";
            }
            String playerName = (String) cnPlayer.getClass().getMethod("name").invoke(cnPlayer);
            String format = "A".equals(side) ? warSettings.nameplateFormatA : warSettings.nameplateFormatB;
            return format == null ? "" : format.replace("{guild}", guild.name())
                    .replace("{player}", playerName == null ? "" : playerName);
        } catch (Throwable ignored) {
            return "";
        }
    }
}
