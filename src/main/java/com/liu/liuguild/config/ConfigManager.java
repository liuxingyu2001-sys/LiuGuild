package com.liu.liuguild.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模块化配置管理器。
 * <p>每个功能模块一个配置文件（见 {@link #MODULES}），统一从「共享配置目录」读取，
 * 支持首次复制默认值、mtime 指纹热重载、每服 server.yml 独立覆盖。</p>
 */
public final class ConfigManager {

    /** 这些配置损坏时不能静默重建，否则可能重置数据库/跨服/仓库等关键状态。 */
    private static final java.util.Set<String> CRITICAL_CONFIGS = java.util.Set.of(
            "config.yml", "server.yml", "cross-server.yml", "database.yml", "warehouse.yml", "buffs.yml");

    private static String redact(String path, Object value) {
        String p = path == null ? "" : path.toLowerCase(java.util.Locale.ROOT);
        if (p.contains("password") || p.contains("passwd") || p.contains("secret") || p.contains("token")
                || p.contains("credential") || p.contains("api-key") || p.contains("apikey")) {
            return "***";
        }
        return String.valueOf(value);
    }

    /** 模块文件清单。 */
    public static final String[] MODULES = {
            "config.yml", "database.yml", "cross-server.yml", "guild.yml", "warehouse.yml", "buffs.yml", "war.yml",
            "war-modes.yml", "arena.yml", "kit.yml", "gui.yml", "icons.yml", "messages.yml", "tasks.yml"
    };

    private final Plugin plugin;
    private final File configRoot;
    /** 仅用于迁移旧版本游戏内编辑文件。 */
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
        boolean migrateWarehouse = !new File(configRoot, "warehouse.yml").exists()
                && new File(configRoot, "guild.yml").exists();
        copyDefaults();
        migrateLegacyOverrides();
        Map<String, FileConfiguration> loaded = new LinkedHashMap<>();
        for (String name : MODULES) {
            loaded.put(name, loadModule(name));
        }
        // 迁移只读取基础 guild.yml，不能把每服覆盖写进共享文件。
        GuildSettings legacyWarehouse = migrateWarehouse ? GuildSettings.parse(loaded.get("guild.yml")) : null;
        // 应用 server.yml 的「每服独立」设置（如工会战承办服），只影响本服内存配置，
        // 不写回共享目录，避免多台子服因为同一份共享文件而互相冲突。
        applyServerOverrides(loaded, ensureServerFile());
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
        WarehouseSettings warehouse = WarehouseSettings.parse(migrateWarehouse
                ? new YamlConfiguration() : loaded.get("warehouse.yml"), migrateWarehouse ? legacyWarehouse : guild);
        if (migrateWarehouse) {
            FileConfiguration migrated = loaded.get("warehouse.yml");
            migrated.set("enabled", legacyWarehouse.warehouseEnabled);
            migrated.set("manage-rank", warehouse.manageRank.name());
            migrated.set("base-slots", warehouse.baseSlots);
            migrated.set("access.require-rent", warehouse.requireRent);
            migrated.set("access.max-days", warehouse.maxDays);
            migrated.set("access.options", java.util.Arrays.stream(warehouse.rentOptions).boxed().toList());
            migrated.set("access.price-per-day.amount", warehouse.rentPrice.amount());
            migrated.set("levels", null);
            migrated.createSection("levels");
            for (var level : warehouse.levels.values()) {
                String path = "levels." + level.number();
                migrated.set(path + ".slots", level.slots());
                migrated.set(path + ".price.currency", level.price().currency().name());
                migrated.set(path + ".price.amount", level.price().amount());
                migrated.set(path + ".duration-days", 0);
            }
            try { atomicSave((YamlConfiguration) migrated, new File(configRoot, "warehouse.yml")); }
            catch (IOException e) { throw new IllegalStateException("迁移 warehouse.yml 失败", e); }
            // 每服设置只改变本服运行配置。
            applyServerOverrides(loaded, ensureServerFile());
            warehouse = WarehouseSettings.parse(loaded.get("warehouse.yml"), guild);
        }
        // 旧 GUI/API 兼容投影；实际购买与容量计算使用 warehouse。
        guild.warehouseEnabled = warehouse.enabled;
        guild.warehouseRequireRent = warehouse.requireRent;
        guild.warehouseManageRank = warehouse.manageRank;
        guild.warehouseMaxLevel = warehouse.levels.size();
        guild.warehouseRentOptions = warehouse.rentOptions;
        guild.warehouseRentPricePerDay = warehouse.rentPrice.amount();
        guild.warehouseRentMaxDays = warehouse.maxDays;
        files.clear();
        files.putAll(loaded);
        BuffSettings buffs = BuffSettings.parse(loaded.get("buffs.yml"));
        Settings next = new Settings(main, guild, war, warModes, arenas, kit, gui, icons, warehouse);
        next.buffs = buffs;
        this.settings = next;

        messages.copyDefault();
        messages.reload(main.language);
        com.liu.liuguild.util.Text.invalidateCache();
    }

    /** 游戏内编辑直接保存到共享目录中的同名 YAML。 */
    public boolean set(String fileName, String path, Object value) {
        if (!java.util.Arrays.asList(MODULES).contains(fileName)) {
            return false;
        }
        try {
            updateFile(configRoot, fileName, path, value);
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("保存配置 " + fileName + " 失败，原文件保留: " + e.getMessage());
            return false;
        }
    }

    /**
     * 批量写入同一文件：一次加锁、一次读盘、一次原子写。
     * <p>GUI/指令一次改多个键（如设置出生点的 7 个字段）时避免 N 次整文件读改写。</p>
     */
    public boolean setAll(String fileName, java.util.Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return true;
        }
        if (!java.util.Arrays.asList(MODULES).contains(fileName)) {
            return false;
        }
        try {
            updateFile(configRoot, fileName, values);
            return true;
        } catch (Exception e) {
            plugin.getLogger().warning("保存配置 " + fileName + " 失败，原文件保留: " + e.getMessage());
            return false;
        }
    }

    /** 删除直接从配置文件移除节点。 */
    public boolean remove(String fileName, String path) {
        return set(fileName, path, null);
    }

    static void updateFile(File root, String name, java.util.Map<String, Object> values) throws Exception {
        withConfigLock(root, () -> {
            YamlConfiguration config = new YamlConfiguration();
            config.options().parseComments(true);
            config.load(new File(root, name));
            for (var e : values.entrySet()) {
                config.set(e.getKey(), e.getValue());
            }
            atomicSave(config, new File(root, name));
        });
    }

    /** 从磁盘重新读取，避免用本服内存配置覆盖其它服的修改或写入 server.yml 的本服值。 */
    static void updateFile(File root, String name, String path, Object value) throws Exception {
        withConfigLock(root, () -> {
            YamlConfiguration config = new YamlConfiguration();
            config.options().parseComments(true);
            config.load(new File(root, name));
            config.set(path, value);
            atomicSave(config, new File(root, name));
        });
    }

    @FunctionalInterface
    private interface FileOperation {
        void run() throws Exception;
    }

    /** 多服共同锁住读取—修改—保存，防止同时编辑不同键时相互覆盖。 */
    private static synchronized void withConfigLock(File root, FileOperation operation) throws Exception {
        try (var channel = java.nio.channels.FileChannel.open(new File(root, ".config-write.lock").toPath(),
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            operation.run();
        }
    }

    /** 旧覆盖层只迁移一次；成功保存并备份后才删除旧文件。 */
    private void migrateLegacyOverrides() {
        // 迁移只用于旧版本升级；overrides/ 不存在时不必为 14 个模块各抢一次文件锁。
        if (!overrideRoot.isDirectory()) {
            return;
        }
        for (String name : MODULES) {
            try {
                if (migrateLegacyFile(configRoot, name)) {
                    plugin.getLogger().info("已合并旧 overrides/" + name + " 到 " + name
                            + "，原覆盖文件备份在 migration-backups/。");
                }
            } catch (Exception e) {
                throw new IllegalStateException("迁移旧 overrides/" + name + " 失败，保留旧文件并停止加载", e);
            }
        }
        // 仅删除空目录，不触碰未知文件。
        overrideRoot.delete();
    }

    static boolean migrateLegacyFile(File root, String name) throws Exception {
        boolean[] migrated = {false};
        withConfigLock(root, () -> {
            File legacy = new File(new File(root, "overrides"), name);
            if (!legacy.isFile()) {
                return;
            }
            YamlConfiguration base = new YamlConfiguration();
            base.options().parseComments(true);
            base.load(new File(root, name));
            YamlConfiguration overlay = new YamlConfiguration();
            overlay.load(legacy); // 严格解析，损坏时不能静默丢失旧修改。
            File backups = new File(root, "migration-backups");
            Files.createDirectories(backups.toPath());
            File backup = File.createTempFile(name + "-", ".bak", backups);
            Files.copy(legacy.toPath(), backup.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            mergeOverrides(base, overlay);
            atomicSave(base, new File(root, name));
            Files.delete(legacy.toPath());
            migrated[0] = true;
        });
        return migrated[0];
    }

    /** 先写临时文件再原子替换，避免其它子服读到写了一半的文件。 */
    private static void atomicSave(YamlConfiguration yml, File target) throws IOException {
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

    /** 确保 plugins/LiuGuild/server.yml 存在（不存在则从内置默认复制），返回该文件。 */
    private File ensureServerFile() {
        File f = new File(plugin.getDataFolder(), "server.yml");
        if (f.exists()) {
            return f;
        }
        try (InputStream in = plugin.getResource("server.yml")) {
            if (in != null) {
                plugin.getDataFolder().mkdirs();
                Files.copy(in, f.toPath());
            }
        } catch (IOException e) {
            plugin.getLogger().warning("复制 server.yml 失败: " + e.getMessage());
        }
        return f.exists() ? f : null;
    }

    /**
     * 应用 server.yml 的「每服独立覆盖」：
     * <ul>
     *   <li>{@code overrides.<模块>: { 配置路径: 值 }}：任意模块的任意键，例如
     *       {@code overrides: {cross-server.yml: {transfer.enabled: false}}}。</li>
     * </ul>
     * 覆盖只作用于本服内存中的配置，不写回共享目录。
     */
    private void applyServerOverrides(Map<String, FileConfiguration> loaded, File serverFile) {
        if (serverFile == null) {
            return;
        }
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(serverFile);
        // 通用每服覆盖（任意模块 / 任意键）
        org.bukkit.configuration.ConfigurationSection ov = yml.getConfigurationSection("overrides");
        if (ov == null) {
            return;
        }
        for (String moduleKey : ov.getKeys(false)) {
            String module = moduleKey.endsWith(".yml") ? moduleKey : moduleKey + ".yml";
            FileConfiguration cfg = loaded.get(module);
            org.bukkit.configuration.ConfigurationSection mod = ov.getConfigurationSection(moduleKey);
            if (cfg == null || mod == null) {
                continue;
            }
            List<String> applied = new ArrayList<>();
            for (String path : mod.getKeys(true)) {
                if (mod.isConfigurationSection(path)) {
                    continue;
                }
                cfg.set(path, mod.get(path));
                applied.add(path + "=" + redact(path, mod.get(path)));
            }
            if (!applied.isEmpty()) {
                plugin.getLogger().info("server.yml 每服覆盖 " + module + ": " + String.join(", ", applied));
            }
        }
    }

    /** 每服独立覆盖：plugins/LiuGuild/server.yml（不随共享配置同步）。 */
    private void applyServerOverride(MainSettings main) {
        File f = ensureServerFile();
        if (f == null) {
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

    /**
     * 允许“新增默认键自动补齐”的模块（升级时新菜单/新按钮自动出现）。
     * 用户文件已有的值一律优先；仅补齐缺失的叶子键。
     */
    private static final java.util.Set<String> MERGE_DEFAULT_MODULES = java.util.Set.of("gui.yml");

    private FileConfiguration loadModule(String name) {
        File f = new File(configRoot, name);
        if (!f.isFile()) {
            YamlConfiguration fallback = new YamlConfiguration();
            readResourceInto(fallback, name);
            return fallback;
        }
        YamlConfiguration cfg = new YamlConfiguration();
        try {
            cfg.load(f);
        } catch (Exception e) {
            if (CRITICAL_CONFIGS.contains(name)) {
                // 数据库/跨服/经济等关键配置损坏时不能带默认值启动，否则可能连到空库或换服。
                throw new IllegalArgumentException(name + " 解析失败，保留原文件和现有运行配置", e);
            }
            // 其余模块：保留用户文件（绝不覆盖，坐标/价格/菜单都是人工成果），
            // 本次加载退回内置默认并复制一份带时间戳的备份供排查。
            plugin.getLogger().severe("配置 " + name + " 解析失败，本次使用内置默认值（原文件保持不变）: "
                    + e.getMessage());
            backupBroken(f);
            YamlConfiguration fresh = new YamlConfiguration();
            readResourceInto(fresh, name);
            return fresh;
        }
        // 升级兼容：新版本新增的 GUI 菜单/按钮自动补齐（不覆盖已有值）。
        if (MERGE_DEFAULT_MODULES.contains(name)) {
            YamlConfiguration defaults = new YamlConfiguration();
            readResourceInto(defaults, name);
            fillMissing(cfg, defaults);
        }
        return cfg;
    }

    /** 复制（而不是移动/覆盖）损坏配置，保留现场且不破坏上一次备份。 */
    private void backupBroken(File file) {
        try {
            File backups = new File(configRoot, "broken-backups");
            Files.createDirectories(backups.toPath());
            File target = new File(backups, file.getName() + "." + System.currentTimeMillis() + ".broken");
            Files.copy(file.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            plugin.getLogger().warning("已备份疑似损坏的配置到 " + target.getName() + "（未覆盖原文件）");
        } catch (Exception e) {
            plugin.getLogger().warning("备份疑似损坏的配置 " + file.getName() + " 失败: " + e.getMessage());
        }
    }

    /** 把 defaults 中缺失的叶子键补进 target（target 已有值不受影响）。 */
    static void fillMissing(FileConfiguration target, ConfigurationSection defaults) {
        for (String key : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key)) {
                continue;
            }
            if (!target.isSet(key)) {
                target.set(key, defaults.get(key));
            }
        }
    }

    private void readResourceInto(YamlConfiguration cfg, String name) {
        try (InputStream in = plugin.getResource(name)) {
            if (in == null) {
                return;
            }
            cfg.loadFromString(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            plugin.getLogger().warning("读取内置默认 " + name + " 失败: " + e.getMessage());
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
