package com.liu.liuguild.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 模块化配置管理器。
 * <p>每个功能模块一个配置文件（见 {@link #MODULES}），统一从「共享配置目录」读取，
 * 支持首次复制默认值、mtime 指纹热重载、每服 server.yml 覆盖 server-id。</p>
 */
public final class ConfigManager {

    /** 模块文件清单。 */
    public static final String[] MODULES = {
            "config.yml", "database.yml", "cross-server.yml", "guild.yml", "war.yml",
            "war-modes.yml", "arena.yml", "kit.yml", "gui.yml", "icons.yml", "messages.yml", "tasks.yml"
    };

    private final Plugin plugin;
    private final File configRoot;
    private final Map<String, FileConfiguration> files = new LinkedHashMap<>();
    private final Messages messages;
    private volatile Settings settings;

    public ConfigManager(Plugin plugin) {
        this.plugin = plugin;
        this.configRoot = resolveConfigRoot();
        this.messages = new Messages(plugin, configRoot);
    }

    public File root() {
        return configRoot;
    }

    public Messages messages() {
        return messages;
    }

    public Settings settings() {
        return settings;
    }

    public FileConfiguration file(String name) {
        return files.get(name);
    }

    // =====================================================================
    // 目录解析
    // =====================================================================

    private File resolveConfigRoot() {
        String shared = plugin.getConfig().getString("settings.shared-config-path", "");
        if (shared == null || shared.isBlank()) {
            return plugin.getDataFolder();
        }
        File dir = new File(shared);
        if (dir.isDirectory()) {
            plugin.getLogger().info("使用共享配置目录: " + dir.getAbsolutePath());
            return dir;
        }
        if (dir.mkdirs()) {
            plugin.getLogger().info("已创建共享配置目录: " + dir.getAbsolutePath());
            return dir;
        }
        plugin.getLogger().warning("共享配置目录不可用（" + dir.getAbsolutePath() + "），回退插件目录");
        return plugin.getDataFolder();
    }

    // =====================================================================
    // 载入 / 复制默认
    // =====================================================================

    public void load() {
        copyDefaults();
        Map<String, FileConfiguration> loaded = new LinkedHashMap<>();
        for (String name : MODULES) {
            File f = new File(configRoot, name);
            loaded.put(name, YamlConfiguration.loadConfiguration(f));
        }
        files.clear();
        files.putAll(loaded);

        MainSettings main = MainSettings.parse(loaded.get("config.yml"));
        MainSettings.applyCrossServer(main, loaded.get("cross-server.yml"));
        applyServerOverride(main);
        GuildSettings guild = GuildSettings.parse(loaded.get("guild.yml"));
        WarSettings war = WarSettings.parse(loaded.get("war.yml"));
        WarModeSettings warModes = WarModeSettings.parse(loaded.get("war-modes.yml"), war);
        war.modes = warModes.modes;
        warModes.defaultMode = war.defaultMode;
        war.defaultMode = warModes.defaultMode;
        ArenaSettings arenas = ArenaSettings.parse(loaded.get("arena.yml"));
        KitSettings kit = KitSettings.parse(loaded.get("kit.yml"));
        GuiSettings gui = GuiSettings.parse(loaded.get("gui.yml"));
        IconSettings icons = IconSettings.parse(loaded.get("icons.yml"));
        // 原子替换
        this.settings = new Settings(main, guild, war, warModes, arenas, kit, gui, icons);

        messages.copyDefault();
        messages.reload(main.language);
    }

    /** 写入某个配置文件（用于 /guildadmin arena setspawn 等）。 */
    public boolean set(String fileName, String path, Object value) {
        FileConfiguration f = files.get(fileName);
        if (f == null) {
            return false;
        }
        f.set(path, value);
        try {
            f.save(new File(configRoot, fileName));
            return true;
        } catch (IOException e) {
            plugin.getLogger().warning("保存 " + fileName + " 失败: " + e.getMessage());
            return false;
        }
    }

    /** 每服独立覆盖：plugins/LiuGuild/server.yml（不随共享配置同步），仅覆盖 server-id。 */
    private void applyServerOverride(MainSettings main) {
        File f = new File(plugin.getDataFolder(), "server.yml");
        if (!f.exists()) {
            try (InputStream in = plugin.getResource("server.yml")) {
                if (in != null) {
                    plugin.getDataFolder().mkdirs();
                    Files.copy(in, f.toPath());
                }
            } catch (IOException e) {
                plugin.getLogger().warning("复制 server.yml 失败: " + e.getMessage());
            }
        }
        if (!f.exists()) {
            return;
        }
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(f);
        String sid = yml.getString("server-id", "").trim();
        if (!sid.isBlank()) {
            main.serverId = sid;
        }
    }

    private void copyDefaults() {
        for (String name : MODULES) {
            File target = new File(configRoot, name);
            if (target.exists()) {
                continue;
            }
            try (InputStream in = plugin.getResource(name)) {
                if (in == null) {
                    plugin.getLogger().warning("内置资源缺失: " + name);
                    continue;
                }
                configRoot.mkdirs();
                Files.copy(in, target.toPath());
                plugin.getLogger().info("已复制默认配置 " + target.getAbsolutePath());
            } catch (IOException e) {
                plugin.getLogger().warning("复制 " + name + " 失败: " + e.getMessage());
            }
        }
    }

    // =====================================================================
    // 热重载指纹
    // =====================================================================

    public long fingerprint() {
        long max = 0;
        for (String name : MODULES) {
            max = Math.max(max, new File(configRoot, name).lastModified());
        }
        max = Math.max(max, new File(plugin.getDataFolder(), "server.yml").lastModified());
        return max;
    }
}
