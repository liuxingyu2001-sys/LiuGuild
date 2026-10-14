package com.liu.liuguild.hook;

import com.liu.liuguild.LiuGuild;
import com.liu.liuguild.model.Guild;
import com.liu.liuguild.model.GuildMember;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Residence（领地）软依赖：把工会成员的领地授权给整个工会共享。
 *
 * <p>Residence 未安装时所有方法安全返回，功能自动隐藏。</p>
 *
 * <p><b>实现方式</b>：全部通过反射调用 Residence 的公开 API
 * （{@code ResidenceApi.getResidenceManager()} → {@code ClaimedResidence}），
 * 因此不需要在 pom 里硬依赖，也不受其版本升级影响（找不到方法时静默降级）。</p>
 *
 * <p><b>共享语义</b>：对目标领地逐位成员设置 {@code build/destroy/container/use/pvp}
 * 等标志为 allow，并在退会/踢人时回收。默认只对<b>会长本人拥有的领地</b>生效，
 * 避免误改他人领地。</p>
 */
public final class ResidenceHook {

    /** 共享时授予的标志（Residence flag 名）。 */
    private static final String[] SHARE_FLAGS = {
            "build", "destroy", "place", "container", "use", "bucket", "bucketfill",
            "ignite", "pvp", "animalkilling", "mobkilling", "shear", "leash",
            "ride", "trade", "door", "button", "lever", "pressure", "table", "enchant",
    };
    /** 收回时至少要把这些改回 deny（其余的用 remove 更干净，但 API 只暴露 set）。 */
    private static final String[] REVOKE_FLAGS = SHARE_FLAGS;

    private final LiuGuild plugin;
    private boolean available;
    private Object residenceManager;

    public ResidenceHook(LiuGuild plugin) {
        this.plugin = plugin;
    }

    /** 初始化：抓取 ResidenceApi.getResidenceManager()。 */
    public void init() {
        try {
            Plugin res = Bukkit.getPluginManager().getPlugin("Residence");
            if (res == null || !res.isEnabled()) {
                return;
            }
            Class<?> apiClass = Class.forName("com.bekvon.bukkit.residence.api.ResidenceApi");
            Method getManager = apiClass.getMethod("getResidenceManager");
            residenceManager = getManager.invoke(null);
            if (residenceManager == null) {
                return;
            }
            available = true;
            plugin.getLogger().info("Residence 领地共享已启用。");
        } catch (Throwable t) {
            available = false;
            plugin.getLogger().warning("Residence 不可用，领地共享功能关闭: " + t);
        }
    }

    public boolean available() {
        return available;
    }

    // =====================================================================
    // 查询
    // =====================================================================

    /** 按名字取领地。 */
    public Object residence(String name) {
        if (!available || name == null || name.isBlank()) {
            return null;
        }
        try {
            Method m = residenceManager.getClass().getMethod("getByName", String.class);
            return m.invoke(residenceManager, name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 取某个坐标所属的领地。 */
    public Object residenceAt(Location loc) {
        if (!available || loc == null) {
            return null;
        }
        try {
            Method m = residenceManager.getClass().getMethod("getByLoc", Location.class);
            return m.invoke(residenceManager, loc);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 领地名字；非领地返回 null。 */
    public String nameOf(Object residence) {
        if (residence == null) {
            return null;
        }
        try {
            Method m = residence.getClass().getMethod("getName");
            return (String) m.invoke(residence);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 领地拥有者 UUID。 */
    public UUID ownerOf(Object residence) {
        if (residence == null) {
            return null;
        }
        try {
            Method m = residence.getClass().getMethod("getOwnerUUID");
            return (UUID) m.invoke(residence);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 列出某玩家拥有的全部领地名。 */
    public List<String> residencesOf(Player player) {
        return player == null ? new ArrayList<>() : residencesOf(player.getUniqueId());
    }

    /** 列出某玩家（按 UUID）拥有的全部领地名。 */
    public List<String> residencesOf(UUID uuid) {
        List<String> out = new ArrayList<>();
        if (!available || uuid == null) {
            return out;
        }
        try {
            Class<?> apiClass = Class.forName("com.bekvon.bukkit.residence.api.ResidenceApi");
            Object playerManager = apiClass.getMethod("getPlayerManager").invoke(null);
            if (playerManager == null) {
                return out;
            }
            Method getList = playerManager.getClass().getMethod("getResidenceList", UUID.class);
            Object res = getList.invoke(playerManager, uuid);
            if (res instanceof Iterable<?> it) {
                for (Object one : it) {
                    if (one != null) {
                        out.add(String.valueOf(one));
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    // =====================================================================
    // 共享 / 收回
    // =====================================================================

    /**
     * 把某个领地共享给全工会成员。
     *
     * @return 实际授予权限的成员数；领地为 null 时返回 0
     */
    public int share(Object residence, Guild guild) {
        if (!available || residence == null || guild == null) {
            return 0;
        }
        int count = 0;
        for (GuildMember m : guild.memberMap().values()) {
            if (setFlags(residence, m.uuid(), SHARE_FLAGS, true)) {
                count++;
            }
        }
        return count;
    }

    /** 收回某个领地上全工会成员的共享权限。 */
    public int revoke(Object residence, Guild guild) {
        if (!available || residence == null || guild == null) {
            return 0;
        }
        int count = 0;
        for (GuildMember m : guild.memberMap().values()) {
            if (setFlags(residence, m.uuid(), REVOKE_FLAGS, false)) {
                count++;
            }
        }
        return count;
    }

    /** 单个成员加入工会时授予其共享权限。 */
    public boolean shareFor(Object residence, UUID member) {
        return setFlags(residence, member, SHARE_FLAGS, true);
    }

    /** 单个成员退会被踢时收回权限。 */
    public boolean revokeFor(Object residence, UUID member) {
        return setFlags(residence, member, REVOKE_FLAGS, false);
    }

    /**
     * 给某玩家在领地上批量设置标志。
     *
     * <p>调用链：{@code residence.getPermissions().setPlayerFlag(sender, uuid, flag, value, save, resadmin)}。
     * 用 UUID 重载，避免玩家改名后权限残留；Reflection 会按真实的参数签名选重载
     * （Residence 6.x 是 6 参数，早期版本可能是 5 或 7 参数）——选不到时打警告，
     * <b>而不是静默失败</b>。</p>
     */
    private boolean setFlags(Object residence, UUID uuid, String[] flags, boolean value) {
        if (uuid == null) {
            return false;
        }
        try {
            Object perms = residence.getClass().getMethod("getPermissions").invoke(residence);
            if (perms == null) {
                return false;
            }
            Method setFlag = findSetPlayerFlag(perms.getClass());
            if (setFlag == null) {
                if (warnedNoFlagMethod.compareAndSet(false, true)) {
                    plugin.getLogger().warning("当前 Residence 版本没有可用的 setPlayerFlag(UUID, String, String) 重载，"
                            + "领地共享无法下发权限。请反馈该版本的 Residence 版本号。");
                }
                return false;
            }
            Object sender = Bukkit.getConsoleSender();
            Object[] callArgs = buildFlagArgs(setFlag, sender, uuid, value);
            boolean any = false;
            for (String flag : flags) {
                try {
                    callArgs[flagIndex(setFlag)] = flag;
                    setFlag.invoke(perms, callArgs.clone());
                    any = true;
                } catch (Throwable ignored) {
                    // 单个 flag 不被当前 Residence 版本支持时跳过，不影响其它 flag
                }
            }
            return any;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 只警告一次的标记，避免刷屏。 */
    private final java.util.concurrent.atomic.AtomicBoolean warnedNoFlagMethod =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * 在 ResidencePermissions 里找一个能用的 {@code setPlayerFlag} 重载：
     * 要求前 4 个参数依次为 {@code (CommandSender, UUID, String, String)}。
     */
    static Method findSetPlayerFlag(Class<?> permsClass) {
        Method best = null;
        for (Method cand : permsClass.getMethods()) {
            if (!cand.getName().equals("setPlayerFlag")) {
                continue;
            }
            Class<?>[] p = cand.getParameterTypes();
            if (p.length < 5
                    || !org.bukkit.command.CommandSender.class.isAssignableFrom(p[0])
                    || p[1] != UUID.class
                    || p[2] != String.class
                    || p[3] != String.class
                    || p[4] != boolean.class) {
                continue;
            }
            // 优先参数更少（更稳定）的重载
            if (best == null || p.length < best.getParameterTypes().length) {
                best = cand;
            }
        }
        return best;
    }

    /** flag 名在参数列表中的下标（固定为 2）。 */
    private static int flagIndex(Method m) {
        return 2;
    }

    /**
     * 构造调用参数：{@code (sender, uuid, flag, value, save, resadmin...)}。
     * 多余的布尔位统一填 {@code true}（既是保存，也是控制台以管理员身份绕过权限检查）。
     */
    static Object[] buildFlagArgs(Method m, Object sender, UUID uuid, boolean value) {
        int n = m.getParameterTypes().length;
        Object[] args = new Object[n];
        args[0] = sender;
        args[1] = uuid;
        args[2] = "";
        args[3] = value ? "allow" : "deny";
        for (int i = 4; i < n; i++) {
            // boolean 位：save / resadmin
            args[i] = true;
        }
        return args;
    }

    /** 玩家是否是该领地的拥有者。 */
    public boolean isOwner(Object residence, Player player) {
        if (residence == null || player == null) {
            return false;
        }
        try {
            Method m = residence.getClass().getMethod("isOwner", Player.class);
            return Boolean.TRUE.equals(m.invoke(residence, player));
        } catch (Throwable ignored) {
            return false;
        }
    }
}
