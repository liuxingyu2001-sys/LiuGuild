package com.liu.liuguild.hook;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * PlayerTitle 钩子（软依赖，反射调用）。
 *
 * <h3>用途</h3>
 * <ul>
 *   <li>工会战胜利可按模式发放称号：{@link #grant(UUID, int)}；</li>
 *   <li>工会名展示称号（见 {@code GuildNameTitleModule}）：授予 / 佩戴 / 回收 / 刷新。</li>
 * </ul>
 *
 * <p>API：{@code cn.handyplus.title.api.PlayerTitleApi}。所有 Method 在构造时缓存，
 * 升级或重载 PlayerTitle 后建议重启服务器，避免旧类加载器上的反射句柄失效。</p>
 *
 * <h3>两个容易踩的坑</h3>
 * <ul>
 *   <li>{@code PlayerTitleApi.playerUseTitle(UUID, Integer)} 的第二个参数是
 *       <b>玩家称号记录 id</b>（{@code TitlePlayer#getId}），不是称号 id
 *       （{@code TitlePlayer#getTitleId}），所以佩戴前要先在
 *       {@code findPlayerTitle(uuid)} 里按称号 id 找到记录。</li>
 *   <li>该 API 内部用 {@code TitleUseTypeEnum.ALL} 且是 toggle 语义：会同时占用
 *       展示 / buff / 粒子三个槽位，把玩家原来的 buff 称号顶掉。工会名称号只想占
 *       <b>展示槽</b>，因此优先调用 {@code TitleUtil.playerTitleUse(uuid, recordId,
 *       TitleUseTypeEnum.SHOW, true)}（显式开启、只动展示槽）；旧版本缺这个重载时
 *       才降级回 {@code playerUseTitle}。</li>
 * </ul>
 */
public final class PlayerTitleHook {

    private static final String API_CLASS = "cn.handyplus.title.api.PlayerTitleApi";
    private static final String TITLE_PLAYER_CLASS = "cn.handyplus.title.entity.TitlePlayer";
    private static final String TITLE_UTIL_CLASS = "cn.handyplus.title.util.TitleUtil";
    private static final String USE_TYPE_CLASS = "cn.handyplus.title.constants.TitleUseTypeEnum";
    private static final String PLUGIN_CLASS = "cn.handyplus.title.PlayerTitle";

    private final Method setTitle;
    private final Method findPlayerTitles;
    private final Method playerUseTitle;
    private final Method playerUseTitleType;
    private final Object showUseType;
    private final Method removePlayerTitle;
    private final Method playerExistTitle;
    private final Method findPlayerShowTitle;
    private final Method existTitleId;
    private final Method titlePlayerId;
    private final Method titlePlayerTitleId;
    private final Method refreshPlayerTitle;
    /** 基础 API 可用（只够发称号，沿用工会上奖励的降级语义）。 */
    private final boolean available;
    /** 工会名称号所需的完整 API 可用（查 / 授 / 戴 / 卸 / 刷）。 */
    private final boolean guildTitleSupported;
    /** PlayerTitle 是否启用了 PlaceholderAPI 解析；读不到时按 true 处理（不拦老版本）。 */
    private final boolean papiParsing;

    public PlayerTitleHook() {
        Method set = null;
        Method find = null;
        Method use = null;
        Method useTyped = null;
        Object showType = null;
        Method remove = null;
        Method exist = null;
        Method show = null;
        Method titleExists = null;
        Method recordId = null;
        Method recordTitleId = null;
        Method refresh = null;
        boolean papi = true;
        try {
            Plugin p = Bukkit.getPluginManager().getPlugin("PlayerTitle");
            if (p != null) {
                Class<?> api = Class.forName(API_CLASS);
                set = api.getMethod("setPlayerTitle", UUID.class, int.class);
                find = api.getMethod("findPlayerTitle", UUID.class);
                use = api.getMethod("playerUseTitle", UUID.class, int.class);
                remove = api.getMethod("removePlayerTitle", UUID.class, int.class);
                exist = api.getMethod("playerExistTitleId", UUID.class, int.class);
                show = api.getMethod("findPlayerShowTitle", UUID.class);
                titleExists = api.getMethod("existTitleId", Integer.class);
                Class<?> titlePlayer = Class.forName(TITLE_PLAYER_CLASS);
                recordId = titlePlayer.getMethod("getId");
                recordTitleId = titlePlayer.getMethod("getTitleId");
                Class<?> util = Class.forName(TITLE_UTIL_CLASS);
                refresh = util.getMethod("refreshPlayerTitle", UUID.class);
                try {
                    Class<?> useType = Class.forName(USE_TYPE_CLASS);
                    showType = useType.getField("SHOW").get(null);
                    useTyped = util.getMethod("playerTitleUse",
                            UUID.class, Integer.class, useType, Boolean.class);
                } catch (Throwable ignored) {
                    // 旧版没有该重载：佩戴时降级为 playerUseTitle（会占用 ALL 槽位）。
                    useTyped = null;
                    showType = null;
                }
                try {
                    papi = Class.forName(PLUGIN_CLASS).getField("USE_PAPI").getBoolean(null);
                } catch (Throwable ignored) {
                    // 读不到就按 true 处理，由调用方再用 PAPI 插件是否存在兜底。
                    papi = true;
                }
            }
        } catch (Throwable ignored) {
            // 版本不匹配或插件未加载：整体降级，调用方通过 available() 判断。
        }
        this.setTitle = set;
        this.findPlayerTitles = find;
        this.playerUseTitle = use;
        this.playerUseTitleType = useTyped;
        this.showUseType = showType;
        this.removePlayerTitle = remove;
        this.playerExistTitle = exist;
        this.findPlayerShowTitle = show;
        this.existTitleId = titleExists;
        this.titlePlayerId = recordId;
        this.titlePlayerTitleId = recordTitleId;
        this.refreshPlayerTitle = refresh;
        this.papiParsing = papi;
        this.available = set != null;
        this.guildTitleSupported = available
                && find != null && remove != null && exist != null && show != null
                && recordId != null && recordTitleId != null
                && (useTyped != null || use != null);
    }

    /** 基础 API 是否可用（工会战奖励只依赖 setPlayerTitle）。 */
    public boolean available() {
        return available;
    }

    /** 工会名称号所需的完整 API 是否可用。 */
    public boolean guildTitleSupported() {
        return guildTitleSupported;
    }

    /** PlayerTitle 是否启用了 PlaceholderAPI 解析（称号名里的 %liuguild_name% 才会被解析）。 */
    public boolean papiParsing() {
        return papiParsing;
    }

    /** 称号 id 在 PlayerTitle 中是否存在（API 不支持时按 true 处理，不做误拦）。 */
    public boolean titleExists(int titleId) {
        if (existTitleId == null || titleId <= 0) {
            return true;
        }
        try {
            return Boolean.TRUE.equals(existTitleId.invoke(null, titleId));
        } catch (Throwable ignored) {
            return true;
        }
    }

    /** 只授予，不自动佩戴（工会战奖励沿用）。 */
    public void grant(UUID player, int titleId) {
        if (!available || titleId <= 0 || player == null) {
            return;
        }
        try {
            setTitle.invoke(null, player, titleId);
        } catch (Throwable ignored) {
        }
    }

    /** 玩家是否拥有该称号。 */
    public boolean owns(UUID player, int titleId) {
        if (!guildTitleSupported || titleId <= 0 || player == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(playerExistTitle.invoke(null, player, titleId));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 玩家当前是否正在展示该称号。 */
    public boolean showing(UUID player, int titleId) {
        if (!guildTitleSupported || player == null) {
            return false;
        }
        try {
            Optional<?> show = (Optional<?>) findPlayerShowTitle.invoke(null, player);
            return show != null && show.isPresent() && titleIdOf(show.get()) == titleId;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 玩家当前是否有任意展示称号。 */
    public boolean showingAny(UUID player) {
        if (!guildTitleSupported || player == null) {
            return false;
        }
        try {
            Optional<?> show = (Optional<?>) findPlayerShowTitle.invoke(null, player);
            return show != null && show.isPresent();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 授予并立刻佩戴到 <b>展示槽</b>（不碰玩家的 buff / 粒子称号）。重复调用安全。
     *
     * @return 是否已处于佩戴状态（失败返回 false，调用方可忽略并下次重试）
     */
    public boolean grantAndEquip(UUID player, int titleId) {
        if (!guildTitleSupported || titleId <= 0 || player == null) {
            return false;
        }
        try {
            if (showing(player, titleId)) {
                return true;
            }
            Integer recordId = findRecordId(player, titleId);
            if (recordId == null) {
                setTitle.invoke(null, player, titleId);
                recordId = findRecordId(player, titleId);
            }
            if (recordId == null) {
                return false;
            }
            if (playerUseTitleType != null && showUseType != null) {
                // 显式开启 + 只切展示槽：保留玩家自己的 buff / 粒子称号。
                playerUseTitleType.invoke(null, player, recordId, showUseType, Boolean.TRUE);
            } else {
                // 旧版降级：会占用 ALL 槽位。
                playerUseTitle.invoke(null, player, recordId);
            }
            refresh(player);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 回收玩家的该称号（PlayerTitle 内部会刷新在线玩家的展示）。 */
    public boolean revoke(UUID player, int titleId) {
        if (!guildTitleSupported || titleId <= 0 || player == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(removePlayerTitle.invoke(null, player, titleId));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 让 PlayerTitle 重新读取并刷新该玩家的展示称号（称号名改动 / Tab 前缀刷新用）。 */
    public void refresh(UUID player) {
        if (refreshPlayerTitle == null || player == null) {
            return;
        }
        try {
            refreshPlayerTitle.invoke(null, player);
        } catch (Throwable ignored) {
        }
    }

    private Integer findRecordId(UUID player, int titleId) throws Exception {
        for (Object titlePlayer : (List<?>) findPlayerTitles.invoke(null, player)) {
            if (titleIdOf(titlePlayer) == titleId) {
                return (Integer) titlePlayerId.invoke(titlePlayer);
            }
        }
        return null;
    }

    private int titleIdOf(Object titlePlayer) throws Exception {
        return (Integer) titlePlayerTitleId.invoke(titlePlayer);
    }
}
