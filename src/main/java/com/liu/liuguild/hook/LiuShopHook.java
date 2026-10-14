package com.liu.liuguild.hook;

import com.liu.liuguild.LiuGuild;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Method;

/**
 * LiuShop 软依赖钩子（反射，不引入编译期依赖）。
 *
 * <h3>用途</h3>
 * <p>工会商店需要三件事，全部由 LiuShop 提供，避免自造一套价格体系：</p>
 * <ol>
 *   <li><b>物品规范化 ID</b> —— {@code idOf(ItemStack)}，与全服商店识别完全一致
 *       （CraftEngine 优先、原版兜底）。没有它就无法保证「同一件物品」在两边被认成同一个。</li>
 *   <li><b>参考价</b> —— 上架时给出建议价（玩家在 LiuShop 买要多少 / 卖能得多少），
 *       让工会定价有依据，而不是瞎填。</li>
 *   <li><b>黑名单</b> —— LiuShop 禁售的物品，工会商店也必须拒绝上架，
 *       否则黑名单形同虚设（这是经济漏洞）。</li>
 * </ol>
 *
 * <h3>为什么用反射</h3>
 * <p>LiuShop 只装在部分子服（pvp 就没装），且版本会演进（1.5.3 → 1.6.3）。
 * 反射让本插件在 LiuShop 缺失或方法改名时安全降级，而不是启动即崩。</p>
 */
public final class LiuShopHook {

    private final LiuGuild plugin;
    private boolean available;
    private boolean warned;

    private Method apiGet;
    private Method idOf;
    private Method getBuyPrice;
    private Method getSellPrice;
    private Method isBlacklisted;
    private Method isShopItem;

    public LiuShopHook(LiuGuild plugin) {
        this.plugin = plugin;
    }

    public void init() {
        available = false;
        if (Bukkit.getPluginManager() == null || !Bukkit.getPluginManager().isPluginEnabled("LiuShop")) {
            return;
        }
        try {
            Class<?> apiClass = Class.forName("com.liushop.api.LiuShopAPI");
            apiGet = apiClass.getMethod("get");
            idOf = apiClass.getMethod("idOf", ItemStack.class);
            getBuyPrice = apiClass.getMethod("getBuyPrice", String.class);
            getSellPrice = apiClass.getMethod("getSellPrice", String.class);
            isBlacklisted = apiClass.getMethod("isBlacklisted", String.class);
            isShopItem = apiClass.getMethod("isShopItem", String.class);
            // get() 返回 null 说明 LiuShop 还没启用 —— 视为不可用，但不算错
            available = apiGet.invoke(null) != null;
        } catch (Throwable t) {
            warnOnce("LiuShop 钩子初始化失败: " + t);
        }
    }

    /** LiuShop 是否可用（已安装、已启用、API 可用）。 */
    public boolean available() {
        if (!available) {
            return false;
        }
        try {
            return apiGet.invoke(null) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private Object api() {
        try {
            return apiGet == null ? null : apiGet.invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 物品 → LiuShop 规范化 ID；无法识别或 LiuShop 不可用返回 null。 */
    public String idOf(ItemStack stack) {
        Object a = api();
        if (a == null || stack == null) {
            return null;
        }
        try {
            return (String) idOf.invoke(a, stack);
        } catch (Throwable t) {
            warnOnce("LiuShop idOf 调用失败: " + t);
            return null;
        }
    }

    /** 玩家在 LiuShop 购买该物品的单价；未知/未开启返回 -1。 */
    public double buyPrice(String itemId) {
        Object a = api();
        if (a == null || itemId == null) {
            return -1;
        }
        try {
            return ((Number) getBuyPrice.invoke(a, itemId)).doubleValue();
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 玩家把该物品卖给 LiuShop 的单价；未知/未开启返回 -1。 */
    public double sellPrice(String itemId) {
        Object a = api();
        if (a == null || itemId == null) {
            return -1;
        }
        try {
            return ((Number) getSellPrice.invoke(a, itemId)).doubleValue();
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 该物品是否在 LiuShop 商店目录中。 */
    public boolean isShopItem(String itemId) {
        Object a = api();
        if (a == null || itemId == null) {
            return false;
        }
        try {
            return (Boolean) isShopItem.invoke(a, itemId);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 该物品是否被 LiuShop 禁售。 */
    public boolean isBlacklisted(String itemId) {
        Object a = api();
        if (a == null || itemId == null) {
            return false;
        }
        try {
            return (Boolean) isBlacklisted.invoke(a, itemId);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 建议售价：优先取 LiuShop 的「玩家购买价」，没有则取「玩家出售价」的
     * {@code sellMarkup} 倍（工会总不能按收购价卖，否则永远亏）。
     *
     * @return 建议单价；无法给出建议时返回 {@code fallback}
     */
    public double suggestPrice(ItemStack stack, double sellMarkup, double fallback) {
        String id = idOf(stack);
        if (id == null) {
            return fallback;
        }
        double buy = buyPrice(id);
        if (buy > 0) {
            return buy;
        }
        double sell = sellPrice(id);
        if (sell > 0) {
            return sell * Math.max(1.0, sellMarkup);
        }
        return fallback;
    }

    private void warnOnce(String msg) {
        if (!warned) {
            warned = true;
            plugin.getLogger().warning(msg);
        }
    }
}
