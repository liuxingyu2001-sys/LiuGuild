package com.liu.liuguild.module;

import com.liu.liuguild.LiuGuild;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** 模块注册表：按 order 启停、统一重载与跨服消息分发。 */
public final class ModuleRegistry {

    private final LiuGuild plugin;
    private final List<GuildModule> modules = new ArrayList<>();

    public ModuleRegistry(LiuGuild plugin) {
        this.plugin = plugin;
    }

    public void register(GuildModule module) {
        modules.add(module);
        modules.sort(Comparator.comparingInt(GuildModule::order));
    }

    public void enableAll() {
        for (GuildModule m : modules) {
            try {
                m.enable(plugin);
            } catch (Throwable t) {
                plugin.getLogger().severe("模块 " + m.id() + " 启动失败: " + t);
                if (plugin.settings() != null && plugin.settings().main.debug) {
                    t.printStackTrace();
                }
            }
        }
    }

    public void disableAll() {
        // 逆序关闭
        for (int i = modules.size() - 1; i >= 0; i--) {
            try {
                modules.get(i).disable();
            } catch (Throwable ignored) {
            }
        }
    }

    public void reloadAll() {
        for (GuildModule m : modules) {
            try {
                m.reload();
            } catch (Throwable t) {
                plugin.getLogger().warning("模块 " + m.id() + " 重载失败: " + t.getMessage());
            }
        }
    }

    public void dispatchCrossServer(String type, Map<String, String> data) {
        for (GuildModule m : modules) {
            try {
                m.onCrossServer(type, data);
            } catch (Throwable t) {
                plugin.getLogger().warning("模块 " + m.id() + " 处理跨服消息失败(" + type + "): " + t.getMessage());
            }
        }
    }

    @SuppressWarnings("unchecked")
    public <T extends GuildModule> T get(Class<T> type) {
        for (GuildModule m : modules) {
            if (type.isInstance(m)) {
                return (T) m;
            }
        }
        return null;
    }

    /** 已注册模块 id 列表（按启动顺序）。 */
    public List<String> ids() {
        List<String> out = new ArrayList<>();
        for (GuildModule m : modules) {
            out.add(m.id());
        }
        return out;
    }
}
