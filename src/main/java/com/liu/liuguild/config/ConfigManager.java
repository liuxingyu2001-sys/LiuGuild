package com.liu.liuguild.config;

import org.bukkit.configuration.ConfigurationSection;
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
    /** 游戏内编辑（GUI / 指令）写入的覆盖层；不修改带注释的基础文件。 */
    private final File overrideRoot;
    private final Map<String, FileConfiguration> files = new LinkedHashMap<>();
    private final Messages messages;
    private volatile Settings settings;

    /** 覆盖层中的删除标记。 */
    static final String DELETED = "__liuguild_deleted__";

    public ConfigManager(Plugin plugin) {
        this.plugin = plugin;
        this.configRoot = resolveConfigRoot();
        this.overrideRoot = new File(configRoot, "overrides");
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
        // 应用游戏内编辑覆盖层：基础文件（带注释、可手改）不被破坏，
        // 且共享目录下多服编辑不会互相整文件覆盖。
        applyOverrides(loaded);
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

    /**
     * 写入某个配置覆盖项（用于 /guildadmin arena setspawn 等）。
     * <p>只写入 {@code overrides/<file>}，不触碰带注释的基础文件；
     * 重复写同一路径的最后一个值生效，不会覆盖其它子服对其它键的修改。</p>
     */
    public boolean set(String fileName, String path, Object value) {
        return writeOverride(fileName, path, value);
    }

    /** 删除某个配置节点（如删除竞技场）。 */
    public boolean remove(String fileName, String path) {
        return writeOverride(fileName, path, DELETED);
    }

    private boolean writeOverride(String fileName, String path, Object value) {
        if (!overrideRoot.exists() && !overrideRoot.mkdirs()) {
            plugin.getLogger().warning("无法创建覆盖配置目录: " + overrideRoot.getAbsolutePath());
            return false;
        }
        File file = new File(overrideRoot, fileName);
        YamlConfiguration ov = file.exists()
                ? YamlConfiguration.loadConfiguration(file)
                : new YamlConfiguration();
        ov.set(path, value);
        try {
            atomicSave(ov, file);
            return true;
        } catch (IOException e) {
            plugin.getLogger().warning("保存覆盖配置 " + fileName + " 失败: " + e.getMessage());
            return false;
        }
    }

    /** 先写临时文件再原子替换，避免其它子服读到写了一半的文件。 */
    private void atomicSave(YamlConfiguration yml, File target) throws IOException {
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        yml.save(tmp);
        try {
            Files.move(tmp.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 把 overrides/<file> 的值合并进基础配置（标记为删除的键会被移除）。 */
    private void applyOverrides(Map<String, FileConfiguration> loaded) {
        for (String name : MODULES) {
            File ov = new File(overrideRoot, name);
            if (!ov.isFile()) {
                continue;
            }
            FileConfiguration base = loaded.get(name);
            if (base == null) {
                continue;
            }
            YamlConfiguration o = YamlConfiguration.loadConfiguration(ov);
            mergeOverrides(base, o);
        }
    }

    /** 把覆盖层合并进基础配置（标记为删除的键会被移除）。可单测。 */
    static void mergeOverrides(FileConfiguration base, ConfigurationSection overrides) {
        java.util.List<String> deletions = new java.util.ArrayList<>();
        for (String key : overrides.getKeys(true)) {
            if (overrides.isConfigurationSection(key)) {
                continue;
            }
            Object v = overrides.get(key);
            if (DELETED.equals(v)) {
                deletions.add(key);
            } else {
                base.set(key, v);
            }
        }
        // 删除放在最后：删除父节点会一并清掉由覆盖层写入的子键，避免“复活”。
        for (String key : deletions) {
            base.set(key, null);
        }
    }

    /** 清空某个模块（或全部）的游戏内编辑覆盖层；返回被删除的覆盖文件数。 */
    public int clearOverrides(String fileName) {
        if ("all".equalsIgnoreCase(fileName)) {
            int n = 0;
            for (String name : MODULES) {
                if (clearOverrides(name) > 0) {
                    n++;
                }
            }
            return n;
        }
        File file = new File(overrideRoot, fileName);
        return file.isFile() && file.delete() ? 1 : 0;
    }

    /** 已存在的覆盖层文件名。 */
    public java.util.List<String> overrideFiles() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String name : MODULES) {
            if (new File(overrideRoot, name).isFile()) {
                out.add(name);
            }
        }
        return out;
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
        if (sid.isBlank()) {
            // 未设置则生成一个稳定且各服不同的 id 并写回，避免多服全部为空白导致串服。
            sid = "server-" + java.util.UUID.randomUUID().toString().substring(0, 8);
            yml.set("server-id", sid);
            try {
                yml.save(f);
            } catch (IOException e) {
                plugin.getLogger().warning("写入自动生成的 server-id 失败: " + e.getMessage());
            }
            plugin.getLogger().warning("server.yml 未设置 server-id，已自动生成 &f" + sid
                    + " &e并写入 " + f.getAbsolutePath() + "（多服请确认每台不同）。");
        }
        main.serverId = sid;
        String lang = yml.getString("language", "").trim();
        if (!lang.isBlank()) {
            main.language = lang;
        }
        if (yml.isSet("debug")) {
            main.debug = yml.getBoolean("debug", main.debug);
        }
        if (yml.isSet("auto-reload-config")) {
            main.autoReload = yml.getBoolean("auto-reload-config", main.autoReload);
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
            max = Math.max(max, new File(overrideRoot, name).lastModified());
        }
        // 覆盖层目录的 mtime 会因新建/删除文件而变化，从而让其它子服也能感知删除。
        max = Math.max(max, overrideRoot.lastModified());
        max = Math.max(max, new File(plugin.getDataFolder(), "server.yml").lastModified());
        return max;
    }
}
