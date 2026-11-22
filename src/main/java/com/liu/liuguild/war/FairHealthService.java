package com.liu.liuguild.war;

import com.liu.liuguild.LiuGuild;
import com.liu.liuguild.config.WarMode;
import com.liu.liuguild.util.ApiCompat;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 公平血量：战斗期间把参赛者的“最大生命值”统一压到模式配置的公平值，离场后自动解除。
 *
 * <h3>为什么要做最大生命归一</h3>
 * <p>入场/复活时 {@link KitService#fullState} 按“玩家当前最大生命值”回满，因此任何外部
 * 最大生命加成都会 1:1 变成入场血量：上一场胜方拿到带“生命上限 +20”的奖励称号
 * （PlayerTitle / LiuTitle 的属性类 buff、AttributePlus / MythicLib / AttributeSystem 等），
 * 或者蟠桃血量没被屏蔽成功时，下一场开战就会是 40 血，而其他玩家只有 20 血。</p>
 *
 * <p>以前 {@code fair-health} 只做一件事：调用 LuckyPeaches 的屏蔽接口移除蟠桃 modifier。
 * 属性类加成不是药水效果，{@link com.liu.liuguild.listener.WarListener} 也拦不住，于是漏进竞技场。</p>
 *
 * <h3>实现方式</h3>
 * <p>不动玩家的基础值、也不移除其他插件的 modifier，而是给 {@code max_health} 追加一个
 * <b>ADD_NUMBER 补偿 modifier</b>，让“生效值”恰好等于公平值。</p>
 *
 * <p>不同版本/不同插件对 {@code ADD_SCALAR}、{@code MULTIPLY_SCALAR_1} 的结算顺序不完全一致，
 * 因此这里不写死公式：先量一次当前生效值，再追加一个单位 ADD_NUMBER 量出“斜率”
 * （d生效值 / dADD_NUMBER），用 {@code (公平值 − 当前值) / 斜率} 得到精确补偿量，最后再验证一次。
 * 这样无论第三方加成是加法、百分比还是乘法，都能把总生命压回公平值。</p>
 *
 * <p>补偿 modifier 用 transient 方式添加（不写进玩家存档），因此关服/崩溃不会留下持久改动；
 * 其他插件的加成原样保留，只是被抵消。战斗中每秒重新计算一次，第三方插件在切世界、复活、
 * 重新佩戴称号时把加成加回来，也会在 1 秒内被重新压回公平值。</p>
 */
public final class FairHealthService {

    /** 默认公平最大生命值（原版满血 20）。 */
    public static final double DEFAULT_FAIR_BASE_HEALTH = 20.0;
    /** 允许配置的最大公平生命值。 */
    private static final double MAX_FAIR_BASE_HEALTH = 1024.0;
    private static final double EPSILON = 0.01;

    private final LiuGuild plugin;
    /** 补偿 modifier 的 key；transient 添加，不写入玩家存档。 */
    private final NamespacedKey modifierKey;
    /** 正在战斗中、已登记公平血量的玩家：UUID → 目标最大生命值。 */
    private final Map<UUID, Double> active = new ConcurrentHashMap<>();
    /** 已经告警过的降级原因（每服只刷一次，避免战斗中刷屏）。 */
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    public FairHealthService(LiuGuild plugin) {
        this.plugin = plugin;
        this.modifierKey = new NamespacedKey(plugin, "fair_health");
    }

    /** 该模式是否启用公平血量。 */
    public boolean enabled(WarMode mode) {
        return mode != null && mode.fairHealth;
    }

    /** 目标最大生命值（模式配置，非法值回退 20）。 */
    public double target(WarMode mode) {
        double v = mode == null ? DEFAULT_FAIR_BASE_HEALTH : mode.fairBaseHealth;
        if (!Double.isFinite(v) || v < 1.0) {
            return DEFAULT_FAIR_BASE_HEALTH;
        }
        return Math.min(v, MAX_FAIR_BASE_HEALTH);
    }

    /** 当前有多少名玩家被纳入公平血量。 */
    public int activeCount() {
        return active.size();
    }

    /**
     * 进入战斗 / 复活：屏蔽蟠桃加成（若 LuckyPeaches 可用），并把最大生命压到公平值。
     * <p>必须在发放套装、回满血量之前调用。主线程调用。</p>
     */
    public void beginFight(Player player, WarMode mode) {
        if (player == null || !enabled(mode)) {
            return;
        }
        active.put(player.getUniqueId(), target(mode));
        // 蟠桃仍走 LuckyPeaches 自己的屏蔽接口：它同时负责“战斗中死亡不扣蟠桃”。
        // 即使插件缺失或版本过旧，下面的补偿 modifier 也会把蟠桃加成一起压掉。
        if (plugin.luckyPeaches() != null && plugin.luckyPeaches().available()) {
            if (!plugin.luckyPeaches().supportsSuppress()) {
                warnOnce("suppress", "LuckyPeaches 缺少 setPeachBonusSuppressed（旧版本）：蟠桃血量改由 LiuGuild 公平血量补偿压制，"
                        + "死亡扣罚豁免仍按战斗中标记生效。");
            }
            plugin.luckyPeaches().enter(player, plugin.luckyPeaches().supportsSuppress());
        }
        enforce(player, mode);
    }

    /** 每秒兜底：把战斗中玩家的最大生命重新压到公平值，并夹住当前血量。主线程调用。 */
    public void tick(Player player, WarMode mode) {
        if (player == null || !enabled(mode)) {
            return;
        }
        if (!active.containsKey(player.getUniqueId())) {
            return;
        }
        enforce(player, mode);
    }

    /**
     * 现场执行一次归一：追加/刷新补偿 modifier，让最大生命值等于公平值。
     * <p>不依赖 {@link #beginFight} 的登记，入场、复活、tick 都可以直接调用。</p>
     */
    public void enforce(Player player, WarMode mode) {
        if (player == null || !enabled(mode) || player.isDead()) {
            return;
        }
        double target = target(mode);
        AttributeInstance attr = ApiCompat.maxHealthAttribute(player);
        if (attr == null) {
            warnOnce("missing", "无法获取玩家的最大生命属性，公平血量无法生效（服务端版本：" + plugin.getServer().getVersion() + "）。");
            return;
        }
        // 先去掉上一次的补偿，量出“其它加成下的当前生效值”，再按实测斜率补偿。
        removeModifier(attr);
        double baseline = attr.getValue();
        if (Math.abs(baseline - target) <= EPSILON) {
            clampHealth(player, target);
            return;
        }
        // 第一次猜测按斜率 1 追加；实际斜率=0（被钳制/被其它插件锁死）时只能退化为夹血。
        double slope = 1.0;
        double amount = compensation(target, baseline, slope);
        for (int attempt = 0; attempt < 3; attempt++) {
            if (!applyModifier(attr, amount)) {
                break;
            }
            double value = attr.getValue();
            double error = target - value;
            if (Math.abs(error) <= 0.01) {
                clampHealth(player, target);
                return;
            }
            if (attempt == 0) {
                // 两点法量斜率：d(生效值) / d(ADD_NUMBER) 在本 tick 内是常数。
                slope = (value - baseline) / amount;
                if (!Double.isFinite(slope) || Math.abs(slope) < 1.0E-6) {
                    warnOnce("clamp", "玩家最大生命在当前状态下无法调整（斜率异常），本场退化为只夹取当前血量。");
                    break;
                }
            }
            amount = compensation(target, baseline, slope);
        }
        clampHealth(player, target);
        if (Math.abs(attr.getValue() - target) > 0.5) {
            warnOnce("clamp", "公平血量未能完全生效（目标 " + format(target) + "，实际 "
                    + format(attr.getValue()) + "），可能被其它插件钳制。");
        }
    }

    /**
     * 离场：移除补偿 modifier。
     * <p>基础值与其他插件的 modifier 从未被改动，因此无需“恢复”，第三方插件会按自己的
     * 逻辑（登录/切世界/佩戴）重新生效。</p>
     */
    public void endFight(Player player) {
        if (player == null) {
            return;
        }
        if (active.remove(player.getUniqueId()) == null) {
            return;
        }
        AttributeInstance attr = ApiCompat.maxHealthAttribute(player);
        if (attr == null) {
            return;
        }
        removeModifier(attr);
        // 移除补偿后上限可能变小（例如之前被补到公平值的高血量玩家），夹一下避免“超血”状态。
        if (!player.isDead() && player.getHealth() > attr.getValue()) {
            ApiCompat.setHealthSafely(player, attr.getValue());
        }
    }

    /** 离线清理：补偿是 transient 的，不会写进存档，重登时天然不存在，只需清掉登记。 */
    public void endFightOffline(UUID uuid) {
        if (uuid != null) {
            active.remove(uuid);
        }
    }

    /** 插件关闭/重载兜底：清掉所有在线玩家身上的补偿 modifier。 */
    public void endAll() {
        for (UUID uuid : new java.util.ArrayList<>(active.keySet())) {
            Player player = plugin.player(uuid);
            if (player != null) {
                endFight(player);
            } else {
                active.remove(uuid);
            }
        }
    }

    /** 状态页展示：生效人数 + 各模式的公平值 + 蟠桃屏蔽情况。 */
    public String summary(java.util.Collection<WarMode> modes) {
        StringBuilder out = new StringBuilder("生效 ").append(active.size()).append(" 人");
        // war.yml 的同级模式与 war-modes.yml 可能重复出现同一个 key，去重后再展示。
        Set<String> rules = new java.util.LinkedHashSet<>();
        if (modes != null) {
            for (WarMode mode : modes) {
                if (mode != null && mode.fairHealth) {
                    rules.add(mode.key + "=" + format(target(mode)));
                }
            }
        }
        out.append(rules.isEmpty() ? "  模式=无" : "  模式=" + String.join(",", rules));
        if (plugin.luckyPeaches() == null || !plugin.luckyPeaches().available()) {
            out.append("  蟠桃=未安装");
        } else {
            out.append("  蟠桃=").append(plugin.luckyPeaches().supportsSuppress() ? "已屏蔽" : "旧版(靠补偿)");
        }
        return out.toString();
    }

    /**
     * 纯计算：为了让“生效最大生命值”等于 {@code target}，需要追加多少 ADD_NUMBER。
     *
     * @param effective 不含本插件补偿时的当前生效最大生命值
     * @param slope     实测斜率：追加 1 点 ADD_NUMBER 会让生效值变化多少
     * @return 需要追加的 ADD_NUMBER；参数非法或斜率不可用时返回 {@link Double#NaN}
     */
    public static double compensation(double target, double effective, double slope) {
        if (!Double.isFinite(target) || !Double.isFinite(effective) || !Double.isFinite(slope)) {
            return Double.NaN;
        }
        if (Math.abs(slope) < 1.0E-6) {
            return Double.NaN;
        }
        double amount = (target - effective) / slope;
        return Double.isFinite(amount) ? amount : Double.NaN;
    }

    /** 写入补偿 modifier：先移除旧的再加新值；新值接近 0 时只移除。 */
    private boolean applyModifier(AttributeInstance attr, double amount) {
        removeModifier(attr);
        if (!Double.isFinite(amount)) {
            return false;
        }
        if (Math.abs(amount) <= EPSILON) {
            return true;
        }
        try {
            attr.addTransientModifier(new AttributeModifier(modifierKey, amount,
                    AttributeModifier.Operation.ADD_NUMBER));
            return true;
        } catch (Throwable t) {
            warnOnce("add", "添加公平血量 modifier 失败：" + t.getMessage());
            return false;
        }
    }

    /** 移除本插件自己的补偿 modifier（按 key 匹配，避免误删其他插件的加成）。 */
    private void removeModifier(AttributeInstance attr) {
        try {
            for (AttributeModifier modifier : new java.util.ArrayList<>(attr.getModifiers())) {
                if (modifierKey.equals(modifier.getKey())) {
                    attr.removeModifier(modifier);
                }
            }
        } catch (Throwable ignored) {
            // 极端情况下属性已被移除：交给下一次 tick 重试。
        }
    }

    /** 把当前血量夹到公平值以内（死亡/旁观状态不处理）。 */
    private static void clampHealth(Player player, double target) {
        if (player.isDead() || player.getHealth() <= 0.0) {
            return;
        }
        if (player.getHealth() > target) {
            ApiCompat.setHealthSafely(player, target);
        }
    }

    private void warnOnce(String key, String message) {
        if (key == null || !warned.add(key)) {
            return;
        }
        plugin.getLogger().warning("[LiuGuild] " + message);
    }

    private static String format(double value) {
        if (value == Math.rint(value)) {
            return String.valueOf((long) value);
        }
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }
}
