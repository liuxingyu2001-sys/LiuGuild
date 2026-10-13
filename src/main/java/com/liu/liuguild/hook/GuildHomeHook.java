package com.liu.liuguild.hook;

import com.google.gson.Gson;
import com.liu.liuguild.LiuGuild;
import com.liu.liuguild.model.Guild;
import com.liu.liuguild.model.GuildMember;
import com.liu.liuguild.model.GuildRank;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工会驻地：坐标由 LiuGuild 保存，传送交给 HuskHomes（支持计时与跨服传送）。
 *
 * <p>HuskHomes 为可选依赖，未安装时对应命令提示不可用，其余功能不受影响。
 * 所有 HuskHomes 调用均通过反射完成，避免硬依赖。</p>
 */
public final class GuildHomeHook {

    private final LiuGuild plugin;
    private final Gson gson = new Gson();
    private final Map<UUID, Long> cooldownUntil = new ConcurrentHashMap<>();

    private Object api;
    private Class<?> apiType;
    private Class<?> worldType;
    private Class<?> positionType;
    private Class<?> onlineUserType;
    private Class<?> targetType;

    /** 存储的驻地坐标（JSON）。 */
    private record Home(String server, String world, String worldUuid,
                        double x, double y, double z, float yaw, float pitch) { }

    public GuildHomeHook(LiuGuild plugin) {
        this.plugin = plugin;
        Plugin dependency = Bukkit.getPluginManager().getPlugin("HuskHomes");
        if (dependency == null || !dependency.isEnabled()) {
            return;
        }
        try {
            ClassLoader loader = dependency.getClass().getClassLoader();
            apiType = loader.loadClass("net.william278.huskhomes.api.HuskHomesAPI");
            worldType = loader.loadClass("net.william278.huskhomes.position.World");
            positionType = loader.loadClass("net.william278.huskhomes.position.Position");
            onlineUserType = loader.loadClass("net.william278.huskhomes.user.OnlineUser");
            targetType = loader.loadClass("net.william278.huskhomes.teleport.Target");
            api = apiType.getMethod("getInstance").invoke(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            api = null;
            plugin.getLogger().warning("HuskHomes 驻地接口不可用: " + e.getMessage());
        }
    }

    /** HuskHomes 是否可用且驻地功能已启用。 */
    public boolean available() {
        return api != null && plugin.settings().guild.homeEnabled;
    }

    public boolean huskHomesPresent() {
        return api != null;
    }

    // =====================================================================
    // 命令入口
    // =====================================================================

    public void command(Player player, String[] args) {
        String action = args.length >= 2 ? args[1].toLowerCase(java.util.Locale.ROOT) : "tp";
        String slotArg = args.length >= 3 ? args[2] : (args.length == 2 && isNumber(args[1]) ? args[1] : null);
        if (args.length == 2 && isNumber(args[1])) {
            action = "tp";
        }
        switch (action) {
            case "set" -> setHome(player, slotArg);
            case "del", "delete", "remove" -> deleteHome(player, slotArg);
            case "list" -> listHomes(player);
            default -> teleport(player, slotArg);
        }
    }

    private void teleport(Player player, String slotArg) {
        Guild guild = requireGuild(player);
        if (guild == null) {
            return;
        }
        if (!available()) {
            plugin.messages().send(player, "home-unavailable");
            return;
        }
        Integer slot = parseSlot(slotArg);
        if (slot == null) {
            plugin.messages().send(player, "home-slot-invalid", "slots", plugin.settings().guild.homeSlots(guild.level()));
            return;
        }
        if (plugin.settings().guild.homeDenyInWar && plugin.war() != null && plugin.war().isInWar(player.getUniqueId())) {
            plugin.messages().send(player, "home-war-denied");
            return;
        }
        long now = System.currentTimeMillis();
        Long until = cooldownUntil.get(player.getUniqueId());
        if (until != null && until > now) {
            plugin.messages().send(player, "home-cooldown", "seconds", (until - now) / 1000 + 1);
            return;
        }
        plugin.async(() -> {
            String json;
            try {
                json = plugin.repo().loadHomes(guild.id()).get(slot);
            } catch (Exception e) {
                plugin.getLogger().warning("读取工会驻地失败: " + e.getMessage());
                json = null;
            }
            String payload = json;
            plugin.sync(() -> {
                Guild current = plugin.guilds().guildOf(player.getUniqueId());
                if (!player.isOnline() || current == null || !current.id().equals(guild.id())) {
                    return;
                }
                if (payload == null) {
                    plugin.messages().send(player, "home-missing", "slot", slot);
                    return;
                }
                if (teleportTo(player, gson.fromJson(payload, Home.class))) {
                    int cd = plugin.settings().guild.homeCooldownSeconds;
                    if (cd > 0) {
                        cooldownUntil.put(player.getUniqueId(), System.currentTimeMillis() + cd * 1000L);
                    }
                    plugin.messages().send(player, "home-teleport", "slot", slot);
                } else {
                    plugin.messages().send(player, "home-failed");
                }
            });
        });
    }

    private void setHome(Player player, String slotArg) {
        Guild guild = requireGuild(player);
        if (guild == null) {
            return;
        }
        if (!available()) {
            plugin.messages().send(player, "home-unavailable");
            return;
        }
        if (!canManage(player, guild)) {
            plugin.messages().send(player, "no-permission-rank");
            return;
        }
        Integer slot = parseSlot(slotArg);
        if (slot == null || slot > plugin.settings().guild.homeSlots(guild.level())) {
            plugin.messages().send(player, "home-slot-invalid", "slots", plugin.settings().guild.homeSlots(guild.level()));
            return;
        }
        Location location = player.getLocation();
        Home home = new Home(serverName(), location.getWorld().getName(), location.getWorld().getUID().toString(),
                location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
        String json = gson.toJson(home);
        plugin.async(() -> {
            try {
                plugin.repo().saveHome(guild.id(), slot, json);
                plugin.sync(() -> plugin.messages().send(player, "home-saved", "slot", slot));
            } catch (Exception e) {
                plugin.getLogger().warning("保存工会驻地失败: " + e.getMessage());
                plugin.sync(() -> plugin.messages().send(player, "home-failed"));
            }
        });
    }

    private void deleteHome(Player player, String slotArg) {
        Guild guild = requireGuild(player);
        if (guild == null) {
            return;
        }
        if (!available()) {
            plugin.messages().send(player, "home-unavailable");
            return;
        }
        if (!canManage(player, guild)) {
            plugin.messages().send(player, "no-permission-rank");
            return;
        }
        Integer slot = parseSlot(slotArg);
        if (slot == null) {
            plugin.messages().send(player, "home-slot-invalid", "slots", plugin.settings().guild.homeSlots(guild.level()));
            return;
        }
        plugin.async(() -> {
            try {
                plugin.repo().deleteHome(guild.id(), slot);
                plugin.sync(() -> plugin.messages().send(player, "home-deleted", "slot", slot));
            } catch (Exception e) {
                plugin.getLogger().warning("删除工会驻地失败: " + e.getMessage());
                plugin.sync(() -> plugin.messages().send(player, "home-failed"));
            }
        });
    }

    private void listHomes(Player player) {
        Guild guild = requireGuild(player);
        if (guild == null) {
            return;
        }
        int slots = plugin.settings().guild.homeSlots(guild.level());
        plugin.async(() -> {
            List<Integer> existing = new ArrayList<>();
            try {
                existing.addAll(plugin.repo().loadHomes(guild.id()).keySet());
            } catch (Exception e) {
                plugin.getLogger().warning("读取工会驻地失败: " + e.getMessage());
            }
            List<Integer> found = existing;
            plugin.sync(() -> {
                plugin.messages().send(player, "home-list-header", "guild", guild.name(), "slots", slots);
                for (int i = 1; i <= slots; i++) {
                    plugin.messages().send(player, found.contains(i) ? "home-list-set" : "home-list-empty", "slot", i);
                }
            });
        });
    }

    // =====================================================================
    // HuskHomes 传送
    // =====================================================================

    private boolean teleportTo(Player player, Home home) {
        if (home == null || home.server() == null || home.world() == null) {
            return false;
        }
        try {
            UUID worldUuid = UUID.fromString(home.worldUuid());
            Object world = worldType.getMethod("from", String.class, UUID.class).invoke(null, home.world(), worldUuid);
            Object position = positionType.getMethod("at", double.class, double.class, double.class,
                            float.class, float.class, worldType, String.class)
                    .invoke(null, home.x(), home.y(), home.z(), home.yaw(), home.pitch(), world, home.server());
            Object user = apiType.getMethod("adaptUser", Player.class).invoke(api, player);
            Object builder = apiType.getMethod("teleportBuilder", onlineUserType).invoke(api, user);
            builder.getClass().getMethod("target", targetType).invoke(builder, position);
            boolean timed = plugin.settings().guild.homeWarmupSeconds > 0;
            Object result = builder.getClass().getMethod("buildAndComplete", boolean.class, String[].class)
                    .invoke(builder, timed, new String[0]);
            return Boolean.TRUE.equals(result);
        } catch (ReflectiveOperationException | RuntimeException e) {
            plugin.getLogger().warning("工会驻地传送失败: " + e.getMessage());
            return false;
        }
    }

    private String serverName() {
        try {
            Object value = apiType.getMethod("getServer").invoke(api);
            return value == null ? plugin.serverId() : value.toString();
        } catch (ReflectiveOperationException e) {
            return plugin.serverId();
        }
    }

    // =====================================================================
    // 工具
    // =====================================================================

    private Guild requireGuild(Player player) {
        Guild guild = plugin.guilds().guildOf(player.getUniqueId());
        if (guild == null) {
            plugin.messages().send(player, "not-in-guild");
        }
        return guild;
    }

    private boolean canManage(Player player, Guild guild) {
        GuildMember member = guild.member(player.getUniqueId());
        if (member == null) {
            return false;
        }
        return member.rank() == GuildRank.LEADER
                || (member.rank() == GuildRank.OFFICER && plugin.settings().guild.officerSetNotice);
    }

    private static boolean isNumber(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static Integer parseSlot(String raw) {
        if (raw == null || raw.isBlank()) {
            return 1;
        }
        try {
            int slot = Integer.parseInt(raw.trim());
            return slot >= 1 ? slot : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
