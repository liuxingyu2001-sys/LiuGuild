package com.liu.liuguild.config;

/**
 * 模块化配置聚合根。
 * 每个模块由各自的文件解析而来，互不耦合：
 * <pre>
 *   config.yml        → main（含 cross-server.yml 覆盖）
 *   database.yml      → database（由主类单独读取）
 *   guild.yml         → guild
 *   war.yml           → war（全局 + 默认值）
 *   war-modes.yml     → warModes（各模式）
 *   arena.yml         → arenas（场地）
 *   kit.yml           → kit
 *   gui.yml           → gui
 *   icons.yml         → icons
 * </pre>
 * 整体不可变（每次 reload 生成新的 Settings 并原子替换）。
 */
public final class Settings {

    public BuffSettings buffs = new BuffSettings();
    public final MainSettings main;
    public final GuildSettings guild;
    public final WarehouseSettings warehouse;
    public final WarSettings war;
    public final WarModeSettings warModes;
    public final ArenaSettings arenas;
    public final KitSettings kit;
    public final GuiSettings gui;
    public final IconSettings icons;

    public Settings(MainSettings main, GuildSettings guild, WarSettings war, WarModeSettings warModes,
                    ArenaSettings arenas, KitSettings kit, GuiSettings gui, IconSettings icons, WarehouseSettings warehouse) {
        this.warehouse = warehouse;
        this.main = main;
        this.guild = guild;
        this.war = war;
        this.warModes = warModes;
        this.arenas = arenas;
        this.kit = kit;
        this.gui = gui;
        this.icons = icons;
    }

    /** 便捷取某工会战模式。 */
    public WarMode mode(String key) {
        return warModes.mode(key);
    }

    /** 便捷取某场地。 */
    public ArenaDef arena(String id) {
        return arenas.arena(id);
    }
}
