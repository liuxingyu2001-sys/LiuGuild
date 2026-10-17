package com.liu.liuguild.gui;

import org.bukkit.event.inventory.ClickType;

/** GUI 按钮 id → 动作、动作 → 管理权限、有效点击类型。集中在一处便于与命令层权限对齐。 */
final class GuiActions {

    private GuiActions() {
    }

    /** 只有左键/右键及其 Shift 变体才算有效操作。 */
    static boolean isActionClick(ClickType type) {
        return type == ClickType.LEFT
                || type == ClickType.RIGHT
                || type == ClickType.SHIFT_LEFT
                || type == ClickType.SHIFT_RIGHT;
    }

    /**
     * GUI 管理动作对应的命令层权限节点（与 GuildAdminCommand.SUB_PERMISSIONS 对齐）。
     * 返回 null 表示普通玩家动作，不需要管理权限。
     */
    static String requiredAdminPermission(String action) {
        if (action == null) {
            return null;
        }
        if (action.startsWith("guild_disband:")) {
            return "liuguild.admin.disband";
        }
        if (action.startsWith("guild_rename:")) {
            return "liuguild.admin.rename";
        }
        if (action.startsWith("guild_input:")) {
            String field = action.substring("guild_input:".length());
            return switch (field) {
                case "level" -> "liuguild.admin.setlevel";
                case "points" -> "liuguild.admin.setpoints";
                case "bank" -> "liuguild.admin.setbank";
                default -> "liuguild.admin.guilds";
            };
        }
        if (action.startsWith("guild_") || action.startsWith("admin:guilds")
                || action.startsWith("page_guilds")) {
            return "liuguild.admin.guilds";
        }
        // 注意：war_history / war_status / war_leave 是普通玩家动作，不能归到管理权限。
        if (action.startsWith("war_edit:") || action.startsWith("war_stop:") || action.startsWith("war_scoreinput:")
                || action.startsWith("war_tp:") || action.startsWith("admin:war")) {
            return "liuguild.admin.war";
        }
        if (action.startsWith("arena_") || action.startsWith("admin:arenas") || action.startsWith("page_arenas")) {
            return "liuguild.admin.arena";
        }
        if (action.startsWith("kit_") || action.startsWith("admin:kits")) {
            return "liuguild.admin.kit";
        }
        if (action.startsWith("mode_") || action.startsWith("admin:modes")) {
            return "liuguild.admin.mode";
        }
        if (action.startsWith("admin:")) {
            return switch (action) {
                case "admin:reload" -> "liuguild.admin.reload";
                case "admin:status" -> "liuguild.admin.status";
                case "admin:verify" -> "liuguild.admin.verify";
                case "admin:cleanup" -> "liuguild.admin.cleanup";
                default -> "liuguild.admin.gui";
            };
        }
        return null;
    }

    static String actionId(String buttonId) {
        return switch (buttonId) {
            case "info" -> "info:open";
            case "card" -> "noop";
            case "info_notice" -> "info:notice";
            case "info_desc" -> "info:desc";
            case "info_members" -> "info:members";
            case "info_history" -> "info:history";
            case "info_back" -> "info:back";
            case "leave" -> "leave";
            case "warehouse" -> "guild:warehouse";
            case "warehouse_manage" -> "warehouse:manage";
            case "top" -> "top:open";
            case "shop" -> "shop:open";
            case "invites" -> "invites:open";
            case "chat" -> "chat:toggle";
            case "buffs" -> "buffs:open";
            case "titles" -> "title:open";
            case "toggle_open" -> "set:open";
            case "toggle_pvp" -> "set:pvp";
            case "notice" -> "set:notice";
            case "desc" -> "set:desc";
            case "icon" -> "set:icon";
            case "rename" -> "set:rename";
            case "disband" -> "guild:disband";
            case "back" -> "back:main";
            case "history" -> "war_history";
            case "leave_queue" -> "war_leave";
            case "rejoin" -> "war_rejoin";
            case "stats" -> "war_stats";
            case "status" -> "war_status";
            case "spectate" -> "war_spectate";
            case "mail" -> "mail:open";
            case "log" -> "log:open";
            case "apps" -> "apps:open";
            case "admin_status" -> "admin:status";
            case "admin_modes" -> "admin:modes";
            case "admin_arena" -> "admin:arenas";
            case "admin_guilds" -> "admin:guilds";
            case "admin_wars" -> "admin:wars";
            case "admin_kits" -> "admin:kits";
            case "admin_cleanup" -> "admin:cleanup";
            case "admin_verify" -> "admin:verify";
            case "admin_reload" -> "admin:reload";
            case "funds" -> "funds";
            case "close" -> "close";
            case "help" -> "join_help";
            case "create" -> "list:create";
            default -> buttonId;
        };
    }
}
