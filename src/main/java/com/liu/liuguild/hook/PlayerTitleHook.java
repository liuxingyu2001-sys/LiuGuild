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
 * <p>注意：{@code PlayerTitleApi.playerUseTitle(UUID, Integer)} 的第二个参数是
 * <b>玩家称号记录 id</b>（{@code TitlePlayer#getId}），不是称号 id（{@code TitlePlayer#getTitleId}），
 * 所以佩戴前要先在 {@code findPlayerTitle(uuid)} 里按称号 id 找到记录。</p>
 */
public final class PlayerTitleHook {

    private static final String API_CLASS = "cn.handyplus.title.api.PlayerTitleApi";
    private static final String TITLE_PLAYER_CLASS = "cn.handyplus.title.entity.TitlePlayer";
    private static final String TITLE_UTIL_CLASS = "cn.handyplus.title.util.TitleUtil";

    private final Method setTitle;
    private final Method findPlayerTitles;
    private final Method playerUseTitle;
    private final Method removePlayerTitle;
    private final Method playerExistTitle;
    private final Method findPlayerShowTitle;
    private final Method titlePlayerId;
    private final Method titlePlayerTitleId;
    private final Method refreshPlayerTitle;
    private final boolean available;

    public PlayerTitleHook() {
        Method set = null;
        Method find = null;
        Method use = null;
        Method remove = null;
        Method exist = null;
        Method show = null;
        Method recordId = null;
        Method recordTitleId = null;
        Method refresh = null;
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
                Class<?> titlePlayer = Class.forName(TITLE_PLAYER_CLASS);
                recordId = titlePlayer.getMethod("getId");
                recordTitleId = titlePlayer.getMethod("getTitleId");
                refresh = Class.forName(TITLE_UTIL_CLASS).getMethod("refreshPlayerTitle", UUID.class);
            }
        } catch (Throwable ignored) {
            // 版本不匹配或插件未加载：整体降级，调用方通过 available() 判断。
        }
        this.setTitle = set;
        this.findPlayerTitles = find;
        this.playerUseTitle = use;
        this.removePlayerTitle = remove;
        this.playerExistTitle = exist;
        this.findPlayerShowTitle = show;
        this.titlePlayerId = recordId;
        this.titlePlayerTitleId = recordTitleId;
        this.refreshPlayerTitle = refresh;
        this.available = set != null && find != null && use != null && remove != null
                && exist != null && show != null && recordId != null && recordTitleId != null;
    }

    public boolean available() {
        return available;
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
        if (!available || titleId <= 0 || player == null) {
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
        if (!available || player == null) {
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
        if (!available || player == null) {
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
     * 授予并立刻佩戴。重复调用安全：已拥有只会续期，已佩戴则只刷新显示。
     *
     * @return 是否已处于佩戴状态（失败返回 false，调用方可忽略并下次重试）
     */
    public boolean grantAndEquip(UUID player, int titleId) {
        if (!available || titleId <= 0 || player == null) {
            return false;
        }
        try {
            if (!showing(player, titleId)) {
                Integer recordId = findRecordId(player, titleId);
                if (recordId == null) {
                    setTitle.invoke(null, player, titleId);
                    recordId = findRecordId(player, titleId);
                }
                if (recordId == null) {
                    return false;
                }
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
        if (!available || titleId <= 0 || player == null) {
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
