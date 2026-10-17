package com.liu.liuguild.title;

import com.liu.liuguild.LiuGuild;
import com.liu.liuguild.model.Guild;
import com.liu.liuguild.model.GuildMember;
import com.liu.liuguild.model.GuildRank;
import com.liu.liuguild.model.GuildTitle;
import com.liu.liuguild.module.GuildModule;
import com.liu.liuguild.util.Text;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 工会头衔。
 *
 * <h3>模型</h3>
 * <ul>
 *   <li>头衔由管理员在 guild.yml 配置，全服统一，玩家不能创建或删除。</li>
 *   <li>成员从自己够格的头衔里选一个装备，也可随时卸下（每人同时最多一个）。</li>
 *   <li>显示位置：聊天（工会频道）、PAPI {@code %liuguild_title%}、成员列表。</li>
 * </ul>
 *
 * <h3>缓存策略</h3>
 * <p>与 {@code GuildManager} 一致：启动时异步全量加载进内存，之后所有读取都是同步的
 * （占位符/聊天格式被高频调用，不能每次都查库）。写操作改内存 + 异步落库。</p>
 */
public final class TitleService implements GuildModule {

    /** 头衔 id 允许的字符：小写字母/数字/下划线/连字符，1~16 位。 */
    private static final Pattern ID = Pattern.compile("^[a-z0-9_-]{1,16}$");
    /** 显示文本长度上限（去掉颜色标签后的可见长度）。 */
    private static final int MAX_DISPLAY = 16;

    private final LiuGuild plugin;
    private final TitleRepository repo;

    /** playerUuid → titleId（已装备）。 */
    private final Map<UUID, String> equipped = new ConcurrentHashMap<>();

    public TitleService(LiuGuild plugin) {
        this.plugin = plugin;
        this.repo = new TitleRepository(plugin.db());
    }

    @Override
    public String id() {
        return "titles";
    }

    @Override
    public int order() {
        return 56;
    }

    @Override
    public void enable(LiuGuild plugin) {
        loadAllAsync();
    }

    @Override
    public void disable() {
        // 头衔是即时写入的，无需关闭时落库
    }

    @Override
    public void onCrossServer(String type, Map<String, String> data) {
        if (!"GUILD_TITLES".equals(type)) {
            return;
        }
        // 回音：本服自己发的广播也会回来。此时本地内存已是最新，
        // 若去重载反而可能读在异步写入之前，把刚创建的头衔冲掉。
        String from = data.get("server");
        if (from != null && from.equals(plugin.serverId())) {
            return;
        }
        String guildId = data.get("guild");
        if (guildId != null) {
            reloadGuild(guildId);
        }
    }

    @Override
    public void reload() {
        loadAllAsync();
    }

    // =====================================================================
    // 加载
    // =====================================================================

    /** 全量异步加载（启动 / reload）。
     *
     * <p>直接从数据库全量读，<b>不依赖已加载的工会列表</b> —— 模块 enable 早于
     * {@code GuildManager.reloadAll()}，按工会逐个查会在启动时读到空列表。</p>
     */
    public void loadAllAsync() {
        if (plugin.settings() != null && !plugin.settings().guild.titlesEnabled) {
            return;
        }
        plugin.async(() -> {
            try {
                Map<UUID, String> eq = repo.loadAllEquipped();
                equipped.clear();
                equipped.putAll(eq);
            } catch (Exception e) {
                plugin.getLogger().warning("加载工会头衔失败: " + e.getMessage());
            }
        });
    }

    /** 单工会异步重载（跨服同步 / 解散后）。 */
    public void reloadGuild(String guildId) {
        plugin.async(() -> {
            try {
                // 固定头衔只从配置读取，数据库仅同步成员选择。
                Map<UUID, String> eq = repo.loadEquipped(guildId);
                equipped.keySet().removeIf(u -> {
                    Guild g = plugin.guilds().guildOf(u);
                    return g != null && g.id().equals(guildId);
                });
                equipped.putAll(eq);
            } catch (Exception e) {
                plugin.getLogger().warning("重载工会头衔失败(" + guildId + "): " + e.getMessage());
            }
        });
    }

    // =====================================================================
    // 读取（同步，全部走内存）
    // =====================================================================

    /** 全服固定头衔（按配置顺序）。 */
    public List<GuildTitle> titlesOf(String guildId) {
        return plugin.settings().guild.titlesEnabled && guildId != null
                ? List.copyOf(plugin.settings().guild.fixedTitles.values()) : List.of();
    }

    public GuildTitle title(String guildId, String titleId) {
        if (guildId == null || titleId == null) {
            return null;
        }
        return plugin.settings().guild.titlesEnabled ? plugin.settings().guild.fixedTitles.get(titleId) : null;
    }

    /** 玩家当前装备的头衔 id（未装备返回 null）。 */
    public String equippedId(UUID player) {
        return player == null ? null : equipped.get(player);
    }

    /** 玩家当前装备的头衔对象（未装备 / 头衔已被删返回 null）。 */
    public GuildTitle equipped(UUID player) {
        if (!plugin.settings().guild.titlesEnabled) {
            return null;
        }
        String id = equippedId(player);
        if (id == null) {
            return null;
        }
        Guild g = plugin.guilds().guildOf(player);
        if (g == null) {
            return null;
        }
        GuildTitle t = title(g.id(), id);
        if (t == null) {
            // 头衔被删了：顺手卸下，避免残留脏数据
            equipped.remove(player);
            plugin.async(() -> {
                try {
                    repo.setEquipped(player, g.id(), null);
                } catch (Exception ignored) {
                }
            });
            return null;
        }
        // 头衔最低职位要求不满足时（例如被降职）不再显示
        GuildMember m = g.member(player);
        if (m == null || !t.usableBy(m.rank())) {
            return null;
        }
        return t;
    }

    /** 玩家头衔渲染后的显示文本（未装备返回空串）。 */
    public String display(UUID player) {
        GuildTitle t = equipped(player);
        return t == null ? "" : t.display();
    }

        /** 玩家够格装备的头衔列表。 */
    public List<GuildTitle> usableBy(String guildId, GuildRank rank) {
        List<GuildTitle> out = new ArrayList<>();
        for (GuildTitle t : titlesOf(guildId)) {
            if (t.usableBy(rank)) {
                out.add(t);
            }
        }
        return Collections.unmodifiableList(out);
    }

    // =====================================================================
    // 写入
    // =====================================================================

    /** 校验头衔 id。返回 null 表示合法，否则返回错误消息 key。 */
    public static String validateId(String id) {
        if (id == null || !ID.matcher(id).matches()) {
            return "title-id-invalid";
        }
        return null;
    }

    /** 校验显示文本。返回 null 表示合法，否则返回错误消息 key。 */
    public static String validateDisplay(String display) {
        if (display == null || display.isBlank()) {
            return "title-display-invalid";
        }
        // 去掉 MiniMessage / 颜色标签后再算可见长度，防止用颜色标签撑长度
        String visible = Text.clean(display);
        if (visible.isBlank()) {
            return "title-display-invalid";
        }
        if (visible.length() > MAX_DISPLAY) {
            return "title-display-too-long";
        }
        return null;
    }

    public static int maxDisplayLength() {
        return MAX_DISPLAY;
    }

    /** 兼容旧调用：固定头衔不允许通过运行时接口创建或更新。 */
    @Deprecated
    public String create(String guildId, String titleId, String display, GuildRank minRank) {
        return "title-fixed-only";
    }

    /** 兼容旧调用：固定头衔不能通过运行时接口删除。 */
    @Deprecated
    public boolean delete(String guildId, String titleId) {
        return false;
    }

    /**
     * 装备头衔（只能选自己够格的）。
     *
     * @return 错误消息 key，null = 成功
     */
    public String equip(Guild g, GuildMember member, String titleId) {
        if (g == null || member == null) {
            return "not-in-guild";
        }
        if (!plugin.settings().guild.titlesEnabled) {
            return "title-disabled";
        }
        GuildTitle t = title(g.id(), titleId);
        if (t == null) {
            return "title-not-found";
        }
        if (!t.usableBy(member.rank())) {
            return "title-rank-too-low";
        }
        equipped.put(member.uuid(), titleId);
        plugin.async(() -> {
            try {
                repo.setEquipped(member.uuid(), g.id(), titleId);
            } catch (Exception e) {
                plugin.getLogger().warning("保存头衔装备失败: " + e.getMessage());
            }
        });
        broadcastCrossServer(g.id());
        return null;
    }

    /** 卸下头衔。 */
    public void unequip(Guild g, UUID player) {
        if (player == null || equipped.remove(player) == null) {
            return;
        }
        String guildId = g == null ? null : g.id();
        plugin.async(() -> {
            try {
                repo.setEquipped(player, guildId, null);
            } catch (Exception e) {
                plugin.getLogger().warning("卸下头衔失败: " + e.getMessage());
            }
        });
        if (guildId != null) broadcastCrossServer(guildId);
    }

    /** 成员离会 / 被踢：卸下头衔。 */
    public void onMemberRemoved(UUID player) {
        if (player == null) {
            return;
        }
        equipped.remove(player);
        plugin.async(() -> {
            try {
                repo.setEquipped(player, null, null);
            } catch (Exception e) {
                plugin.getLogger().warning("清理成员头衔失败: " + e.getMessage());
            }
        });
    }

    /** 工会解散：清空该工会的头衔与装备记录。 */
    public void onGuildDisbanded(String guildId) {
        equipped.keySet().removeIf(u -> {
            Guild g = plugin.guilds().guildOf(u);
            return g == null || g.id().equals(guildId);
        });
        plugin.async(() -> {
            try {
                repo.deleteAll(guildId);
            } catch (Exception e) {
                plugin.getLogger().warning("清空工会头衔失败: " + e.getMessage());
            }
        });
    }

    /** 清空全部内存缓存（测试 / 关闭用）。 */
    public void clearCache() {
        equipped.clear();
    }

    private void broadcastCrossServer(String guildId) {
        if (guildId == null || plugin.bus() == null) return;
        plugin.publishAsync("GUILD_TITLES", Map.of("guild", guildId, "server", plugin.serverId()));
    }
}
