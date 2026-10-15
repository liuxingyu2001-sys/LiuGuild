package com.liu.liuguild.hook;

import com.liu.liuguild.LiuGuild;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * liupass 战令钩子（软依赖，反射访问）。
 *
 * <p>支持两代对接方式：</p>
 * <ul>
 *   <li><b>扩展任务类型（Liupass 1.6.3+）</b>：{@code PassAPI.registerTaskType("liuguild:xxx")}，
 *       服务器在 Liupass 任务里用 {@code type: liuguild:xxx} 定义即可，无需再建 CUSTOM 任务；
 *       进度见 {@link PassRouting}，本类只负责注册与上报。</li>
 *   <li><b>旧版任务 ID</b>：{@code PassAPI.addTaskProgress(uuid, passId, taskId, amount)}，
 *       由 {@code tasks.yml} 绑定，保留兼容。</li>
 * </ul>
 * <p>工会战通行证经验走 {@link #addXp(UUID, String, int)}（{@code war-modes.yml} 配置），
 * 与任务进度相互独立。</p>
 */
public final class PassHook implements Listener {

    private final LiuGuild plugin;

    private Method addXpWithPass;
    private Method addXpDefault;
    private Method addTaskProgress;
    private Method getDefaultPassId;

    private Method registerTaskType;
    private Method handleProgress;
    private Method handleClose;
    private Method handleIsActive;

    /** type key -> TaskTypeRegistration 句柄。 */
    private final Map<String, Object> handles = new LinkedHashMap<>();

    private boolean apiAvailable;
    private boolean taskTypesAvailable;
    private boolean warned;

    public PassHook(LiuGuild plugin) {
        this.plugin = plugin;
        detect();
    }

    /** 重新探测 Liupass（插件热加载/重载后重新接入）。 */
    private void detect() {
        apiAvailable = false;
        taskTypesAvailable = false;
        addXpWithPass = null;
        addXpDefault = null;
        addTaskProgress = null;
        getDefaultPassId = null;
        registerTaskType = null;
        handleProgress = null;
        handleClose = null;
        handleIsActive = null;
        try {
            Plugin p = Bukkit.getPluginManager().getPlugin("Liupass");
            if (p == null || !p.isEnabled()) {
                return;
            }
            Class<?> api = p.getClass().getClassLoader().loadClass("com.liupass.core.api.PassAPI");
            addXpWithPass = optional(api, "addXp", UUID.class, String.class, int.class);
            addXpDefault = optional(api, "addXp", UUID.class, int.class);
            addTaskProgress = optional(api, "addTaskProgress", UUID.class, String.class, String.class, int.class);
            getDefaultPassId = optional(api, "getDefaultPassId", UUID.class);
            apiAvailable = addXpWithPass != null;

            registerTaskType = optional(api, "registerTaskType", String.class);
            if (registerTaskType != null) {
                Class<?> handle = registerTaskType.getReturnType();
                handleProgress = optional(handle, "progress", UUID.class, String.class, int.class);
                handleClose = optional(handle, "close");
                handleIsActive = optional(handle, "isActive");
                taskTypesAvailable = handleProgress != null && handleClose != null;
            }
            if (apiAvailable) {
                ensureRegistered();
                plugin.getLogger().info("liupass 钩子已接入"
                        + (!plugin.settings().main.passEnabled
                        ? "（任务进度已关闭）"
                        : taskTypesAvailable
                        ? "（扩展任务类型 " + handles.size() + " 个）"
                        : "（旧版 API：仅经验/任务 ID）"));
            }
        } catch (Throwable t) {
            // 保持降级：缺 Liupass 或版本不兼容都不影响 LiuGuild 启动
        }
    }

    @EventHandler
    public void onPluginEnable(PluginEnableEvent event) {
        if (event.getPlugin().getName().equalsIgnoreCase("Liupass")) {
            close();
            detect();
        }
    }

    public boolean available() {
        return apiAvailable;
    }

    /** Liupass 1.6.3+ 扩展任务类型是否可用。 */
    public boolean taskTypesAvailable() {
        return taskTypesAvailable;
    }

    /** 当前已注册的扩展任务类型数量。 */
    public int registeredTypes() {
        return handles.size();
    }

    /**
     * 确保扩展任务类型全部注册；Liupass 关停后失效的句柄会在下次调用时重新注册。
     */
    public void ensureRegistered() {
        if (!taskTypesAvailable || !plugin.settings().main.passEnabled) {
            return;
        }
        handles.values().removeIf(handle -> !isActive(handle));
        boolean anyRegistered = false;
        for (String type : PassRouting.ALL_TYPES) {
            if (handles.containsKey(type)) {
                continue;
            }
            try {
                handles.put(type, registerTaskType.invoke(null, type));
                anyRegistered = true;
            } catch (InvocationTargetException e) {
                if (e.getCause() instanceof IllegalArgumentException) {
                    // 该 key 已被注册；未记入句柄，本次不报错
                    continue;
                }
                warnOnce("liupass 扩展任务类型注册失败（事件触发时会重试）: " + brief(e));
            } catch (Throwable t) {
                warnOnce("liupass 扩展任务类型注册失败: " + brief(t));
            }
        }
        if (anyRegistered) {
            warned = false;
        }
    }

    /** 上报一次扩展任务进度；未安装 Liupass 或对应类型未注册时静默跳过。 */
    public void emit(String type, UUID uuid, String target, int amount) {
        if (!apiAvailable || !taskTypesAvailable || uuid == null || amount <= 0 || type == null) {
            return;
        }
        ensureRegistered();
        Object handle = handles.get(type);
        if (handle == null) {
            return;
        }
        try {
            handleProgress.invoke(handle, uuid, target, amount);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof IllegalStateException) {
                // Liupass 关停/重载导致句柄失效：移除，下次调用自动重注册
                handles.remove(type);
            } else {
                warnOnce("liupass 任务进度上报失败: " + brief(e));
            }
        } catch (Throwable t) {
            warnOnce("liupass 任务进度上报失败: " + brief(t));
        }
    }

    /**
     * 发放通行证经验；{@code passId} 留空时给玩家默认通行证。
     * 工会战奖励走这里，与任务进度互不影响。
     */
    public void addXp(UUID uuid, String passId, int amount) {
        if (!apiAvailable || uuid == null || amount <= 0) {
            return;
        }
        try {
            if (passId != null && !passId.isBlank()) {
                addXpWithPass.invoke(null, uuid, passId.trim(), amount);
            } else if (addXpDefault != null) {
                addXpDefault.invoke(null, uuid, amount);
            }
        } catch (Throwable t) {
            warnOnce("liupass 加经验失败: " + brief(t));
        }
    }

    /**
     * 旧版对接：按 Liupass 任务 ID 推进 CUSTOM 任务（{@code tasks.yml}）。
     * {@code passId} 留空时解析玩家默认通行证。
     */
    public boolean addTaskProgress(UUID uuid, String passId, String taskId, int amount) {
        if (!apiAvailable || addTaskProgress == null || uuid == null || amount <= 0
                || taskId == null || taskId.isBlank()) {
            return false;
        }
        try {
            String pass = passId == null || passId.isBlank() ? defaultPass(uuid) : passId.trim();
            if (pass == null || pass.isBlank()) {
                return false;
            }
            addTaskProgress.invoke(null, uuid, pass, taskId, amount);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private String defaultPass(UUID uuid) {
        if (getDefaultPassId == null) {
            return null;
        }
        try {
            return (String) getDefaultPassId.invoke(null, uuid);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 关闭全部任务类型句柄（LiuGuild 卸载/重载时调用）。 */
    public void close() {
        for (Object handle : handles.values()) {
            if (handleClose != null) {
                try {
                    handleClose.invoke(handle);
                } catch (Throwable ignored) {
                }
            }
        }
        handles.clear();
        warned = false;
    }

    private boolean isActive(Object handle) {
        if (handleIsActive == null) {
            return true;
        }
        try {
            return Boolean.TRUE.equals(handleIsActive.invoke(handle));
        } catch (Throwable t) {
            return false;
        }
    }

    private void warnOnce(String message) {
        if (!warned) {
            warned = true;
            plugin.getLogger().warning(message);
        }
    }

    private static Method optional(Class<?> owner, String name, Class<?>... params) {
        try {
            return owner.getMethod(name, params);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String brief(Throwable t) {
        Throwable cause = t instanceof InvocationTargetException ite && ite.getCause() != null ? ite.getCause() : t;
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
