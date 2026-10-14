package com.liu.liuguild.gui;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 守卫测试：gui.yml 中出现的每个 {@code {占位符}} 都必须由代码提供，
 * 否则界面上会直接显示 {@code {xxx}}。之前 {history_count} 未提供就属此类问题。
 */
class GuiPlaceholderTest {

    /** 代码中确实会填充的占位符全集（见 GuiManager 各 openXxx）。 */
    private static final Set<String> KNOWN = Set.of(
            // placeholders(guild)
            "guild", "tag", "leader", "level", "max_level", "points", "members", "member_limit",
            "wins", "losses", "bank", "home_slots", "notice", "description", "icon", "open",
            // members
            "player", "rank", "contributed", "kills", "deaths", "joined",
            // list
            "count",
            // war 状态 / 模式
            "state", "enemy", "score", "enemy_score", "lives", "arena", "host", "history_count",
            "mode", "mode_key", "mode_state", "mode_min", "mode_team", "mode_fair", "mode_health",
            "mode_arena", "mode_schedule", "mode_duration",
            // settings / icons
            "icon_name", "icon_cost", "rename_cooldown", "rename_cost",
            // info 详情面板
            "online", "level_progress", "created", "win_rate", "total_wars",
            // admin
            "guild_count", "queue", "live_wars", "server", "storage", "cross",
            // checkin
            "checkin_streak", "checkin_state", "checkin_cost");

    private static final Pattern TOKEN = Pattern.compile("\\{([A-Za-z0-9_]+)}");

    @Test
    void allPlaceholdersInGuiYmlAreProvided() throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString(Files.readString(Paths.get("src/main/resources/gui.yml")));
        List<String> unknown = new ArrayList<>();
        scan(cfg.getValues(true), unknown);
        if (!unknown.isEmpty()) {
            fail("gui.yml 使用了未被提供的占位符: " + unknown);
        }
    }

    private void scan(java.util.Map<String, Object> values, List<String> unknown) {
        for (Object v : values.values()) {
            if (!(v instanceof String s)) {
                continue;
            }
            Matcher m = TOKEN.matcher(s);
            while (m.find()) {
                String key = m.group(1);
                if (!KNOWN.contains(key) && !unknown.contains(key)) {
                    unknown.add(key);
                }
            }
        }
        assertTrue(true);
    }
}
