package com.liu.liuguild.config;

import com.liu.liuguild.model.GuildRank;
import org.bukkit.configuration.file.FileConfiguration;

/** 工会玩法模块（guild.yml）。 */
public final class GuildSettings {

    public int nameMin = 2;
    public int nameMax = 16;
    public int tagMin = 2;
    public int tagMax = 5;
    public double createCost = 10000.0;
    public int maxMembers = 50;
    public int maxMembersPerLevel = 5;
    public int inviteExpireSeconds = 300;
    public int inviteCooldownSeconds = 30;
    public boolean bankEnabled = true;
    public double bankDepositTaxPercent = 0.0;
    public long baseRequiredPoints = 1000L;
    public double requiredGrowth = 1.5;
    public int maxLevel = 20;
    public boolean friendlyFire = false;
    public boolean officerInvite = true;
    public boolean officerKick = true;
    public boolean officerDeclareWar = true;
    public boolean officerSetNotice = true;
    public String defaultIcon = "default";
    public double iconChangeCost = 5000.0;

    // ---- 工会驻地（HuskHomes 软依赖） ----
    public boolean homeEnabled = true;
    public int homeMaxSlots = 3;
    /** 每多少级多开放 1 个驻地。 */
    public int homeLevelsPerSlot = 5;
    public int homeCooldownSeconds = 30;
    public int homeWarmupSeconds = 3;
    public boolean homeDenyInWar = true;

    // ---- 僵尸工会清理 ----
    /** 自动定期清理开关。 */
    public boolean cleanupEnabled = false;
    /** 多久无活动判定为不活跃（天）。 */
    public int cleanupInactiveDays = 30;
    /** 自动检查间隔（分钟）。 */
    public int cleanupIntervalMinutes = 360;
    /** 仅清理成员数不超过该值的工会（0 = 不限）。 */
    public int cleanupMaxMembers = 0;
    /** 试运行：只记录不删除。 */
    public boolean cleanupDryRun = false;
    /** 删除时是否全服广播。 */
    public boolean cleanupBroadcast = true;

    // ---- 工会签到 ----
    public boolean checkinEnabled = true;
    /** 每日签到奖励的工会资金（点数）。 */
    public long checkinPoints = 10; 
    /** 每日签到奖励的个人金币（需要经济插件）。 */
    public double checkinMoney = 0.0;
    /** 每连续签到 1 天的额外工会资金。 */
    public long checkinStreakBonus = 5;
    /** 连续奖励上限。 */
    public long checkinMaxStreakBonus = 50;
    /** 是否全服广播签到。 */
    public boolean checkinBroadcast = true;
    /** 每日签到消耗的个人金币（Vault，0 = 不收费）。 */
    public double checkinCost = 0.0;
    /** 每日签到消耗的工会资金（点数，0 = 不扣）。 */
    public long checkinGuildCost = 0;

    // ---- 在线活跃发工会资金 ----
    /** 是否启用「玩家在线活跃 → 工会资金」。 */
    public boolean activityEnabled = true;
    /** 结算间隔（秒），至少 30。 */
    public int activityIntervalSeconds = 300;
    /** 每个活跃在线成员每次结算贡献的工会资金。 */
    public long activityPointsPerPlayer = 2;
    /** 每个工会每天最多通过在线活跃获得的工会资金（0 = 不限）。 */
    public long activityDailyCap = 1000;
    /** 多久没有任何操作（移动/交互/战斗）算挂机（秒，0 = 不检测，纯在线即算）。 */
    public int activityAfkSeconds = 180;
    /** 至少需要多少名活跃成员才结算（0 = 有 1 名即可）。 */
    public int activityMinOnline = 1;

    // ---- 改名字 ----
    /** 是否允许工会改名字（/guild rename <新名>）。 */
    public boolean renameEnabled = true;
    /** 改名字冷却（秒）；0 = 不限。 */
    public int renameCooldownSeconds = 3600;
    /** 改名字费用（Vault 金币）；0 = 免费。 */
    public double renameCost = 0;
    /** 是否允许工会改名字（/guild rename <新名> <新标签>）。 */
    public boolean renameAllowTag = true;
    /** 是否启用 Residence 领地共享（需安装 Residence）。 */
    public boolean residenceEnabled = true;

    // ---- 工会仓库 ----
    /** 是否启用工会仓库（跨服共享，同一时刻仅一人可打开）。 */
    public boolean warehouseEnabled = true;
    /** 是否必须购买使用时间才能打开仓库；false = 永久可用（旧行为，不消耗点数）。 */
    public boolean warehouseRequireRent = true;
    /** 仓库基础槽位数（至少 9，向上取整到 9 的倍数由代码保证）。 */
    public int warehouseBaseSlots = 45;
    /** 每购买 1 次「仓库升级」增加的槽位数。 */
    public int warehouseSlotsPerLevel = 45;
    /** 仓库槽位上限。 */
    public int warehouseMaxSlots = 450;
    /** 每 24 小时使用时间消耗的工会点数（工会资金）。 */
    public long warehouseRentPricePerDay = 200;
    /** 一次可购买的天数选项（GUI / 命令）。 */
    public int[] warehouseRentOptions = {1, 7, 30};
    /** 累计最多可持有的天数（0 = 不限）。 */
    public int warehouseRentMaxDays = 90;
    /** 仓库最大可升级次数（0 = 不可升级）。 */
    public int warehouseMaxLevel = 9;
    /** 0 → 1 升级消耗的工会点数。 */
    public long warehouseUpgradeBaseCost = 1000;
    /** 每级升级成本的倍率。 */
    public double warehouseUpgradeCostGrowth = 2.0;
    /** 谁能购买使用时间 / 升级仓库。 */
    public GuildRank warehouseManageRank = GuildRank.OFFICER;

    // ---- 工会头衔 ----
    /** 是否启用工会自定义头衔。 */
    public boolean titlesEnabled = true;
    /** 每个工会最多可创建的头衔数。 */
    public int titlesMax = 12;
    /** 创建/删除头衔所需的最低职位。 */
    public GuildRank titlesManageRank = GuildRank.OFFICER;

    // ---- 工会商店 ----
    /** 是否启用工会商店。 */
    public boolean shopEnabled = true;
    /** 每个工会最多上架多少条（各工会单独计算）。 */
    public int shopMaxListings = 10;
    /** 单条上架最多多少件（防止一条 listing 塞几万件）。 */
    public int shopMaxStackPerListing = 2304;
    /** 单次购买上限。 */
    public int shopMaxPerPurchase = 2304;
    /** 单次向工会供货上限。 */
    public int shopMaxSupply = 2304;
    /** 交易税（%），从货款里扣。 */
    public double shopTaxPercent = 5.0;
    /** 谁能上架/改价/下架。 */
    public GuildRank shopManageRank = GuildRank.OFFICER;

    public static GuildSettings parse(FileConfiguration c) {
        GuildSettings s = new GuildSettings();
        s.nameMin = Math.max(1, c.getInt("guild.name.min-length", s.nameMin));
        s.nameMax = Math.max(s.nameMin, c.getInt("guild.name.max-length", s.nameMax));
        s.tagMin = Math.max(1, c.getInt("guild.tag.min-length", s.tagMin));
        s.tagMax = Math.max(s.tagMin, c.getInt("guild.tag.max-length", s.tagMax));
        s.createCost = c.getDouble("guild.create-cost", s.createCost);
        s.maxMembers = Math.max(1, c.getInt("guild.max-members", s.maxMembers));
        s.maxMembersPerLevel = Math.max(0, c.getInt("guild.max-members-per-level", s.maxMembersPerLevel));
        s.inviteExpireSeconds = Math.max(10, c.getInt("guild.invite.expire-seconds", s.inviteExpireSeconds));
        s.inviteCooldownSeconds = Math.max(0, c.getInt("guild.invite.cooldown-seconds", s.inviteCooldownSeconds));
        s.bankEnabled = c.getBoolean("guild.bank.enabled", s.bankEnabled);
        s.bankDepositTaxPercent = clamp(c.getDouble("guild.bank.deposit-tax-percent", s.bankDepositTaxPercent), 0, 100);
        s.baseRequiredPoints = Math.max(1, c.getLong("guild.level.base-required-points", s.baseRequiredPoints));
        s.requiredGrowth = Math.max(1.0, c.getDouble("guild.level.required-growth", s.requiredGrowth));
        s.maxLevel = Math.max(1, c.getInt("guild.level.max-level", s.maxLevel));
        s.friendlyFire = c.getBoolean("guild.friendly-fire", s.friendlyFire);
        s.officerInvite = c.getBoolean("guild.officer.invite", s.officerInvite);
        s.officerKick = c.getBoolean("guild.officer.kick", s.officerKick);
        s.officerDeclareWar = c.getBoolean("guild.officer.declare-war", s.officerDeclareWar);
        s.officerSetNotice = c.getBoolean("guild.officer.set-notice", s.officerSetNotice);
        s.defaultIcon = c.getString("guild.icon.default", s.defaultIcon);
        s.iconChangeCost = c.getDouble("guild.icon.change-cost", s.iconChangeCost);
        s.homeEnabled = c.getBoolean("guild.home.enabled", s.homeEnabled);
        s.homeMaxSlots = Math.max(1, Math.min(50, c.getInt("guild.home.max-slots", s.homeMaxSlots)));
        s.homeLevelsPerSlot = Math.max(1, c.getInt("guild.home.levels-per-slot", s.homeLevelsPerSlot));
        s.homeCooldownSeconds = Math.max(0, c.getInt("guild.home.cooldown-seconds", s.homeCooldownSeconds));
        s.homeWarmupSeconds = Math.max(0, c.getInt("guild.home.warmup-seconds", s.homeWarmupSeconds));
        s.homeDenyInWar = c.getBoolean("guild.home.deny-in-war", s.homeDenyInWar);
        s.cleanupEnabled = c.getBoolean("guild.cleanup.enabled", s.cleanupEnabled);
        s.cleanupInactiveDays = Math.max(1, c.getInt("guild.cleanup.inactive-days", s.cleanupInactiveDays));
        s.cleanupIntervalMinutes = Math.max(1, c.getInt("guild.cleanup.interval-minutes", s.cleanupIntervalMinutes));
        s.cleanupMaxMembers = Math.max(0, c.getInt("guild.cleanup.max-members", s.cleanupMaxMembers));
        s.cleanupDryRun = c.getBoolean("guild.cleanup.dry-run", s.cleanupDryRun);
        s.cleanupBroadcast = c.getBoolean("guild.cleanup.broadcast", s.cleanupBroadcast);
        s.checkinEnabled = c.getBoolean("guild.checkin.enabled", s.checkinEnabled);
        s.checkinPoints = Math.max(0, c.getLong("guild.checkin.points", s.checkinPoints));
        s.checkinMoney = Math.max(0, c.getDouble("guild.checkin.money", s.checkinMoney));
        s.checkinStreakBonus = Math.max(0, c.getLong("guild.checkin.streak-bonus", s.checkinStreakBonus));
        s.checkinMaxStreakBonus = Math.max(0, c.getLong("guild.checkin.max-streak-bonus", s.checkinMaxStreakBonus));
        s.checkinBroadcast = c.getBoolean("guild.checkin.broadcast", s.checkinBroadcast);
        s.checkinCost = Math.max(0, c.getDouble("guild.checkin.cost", s.checkinCost));
        s.checkinGuildCost = Math.max(0, c.getLong("guild.checkin.guild-cost", s.checkinGuildCost));
        s.activityEnabled = c.getBoolean("guild.activity.enabled", s.activityEnabled);
        s.activityIntervalSeconds = Math.max(30, c.getInt("guild.activity.interval-seconds", s.activityIntervalSeconds));
        s.activityPointsPerPlayer = Math.max(0, c.getLong("guild.activity.points-per-player", s.activityPointsPerPlayer));
        s.activityDailyCap = Math.max(0, c.getLong("guild.activity.daily-cap", s.activityDailyCap));
        s.activityAfkSeconds = Math.max(0, c.getInt("guild.activity.afk-seconds", s.activityAfkSeconds));
        s.activityMinOnline = Math.max(0, c.getInt("guild.activity.min-online", s.activityMinOnline));
        s.renameEnabled = c.getBoolean("guild.rename.enabled", s.renameEnabled);
        s.renameCooldownSeconds = Math.max(0, c.getInt("guild.rename.cooldown-seconds", s.renameCooldownSeconds));
        s.renameCost = Math.max(0, c.getDouble("guild.rename.cost", s.renameCost));
        s.renameAllowTag = c.getBoolean("guild.rename.allow-tag", s.renameAllowTag);
        s.residenceEnabled = c.getBoolean("guild.residence.enabled", s.residenceEnabled);
        s.warehouseEnabled = c.getBoolean("guild.warehouse.enabled", s.warehouseEnabled);
        s.warehouseRequireRent = c.getBoolean("guild.warehouse.require-rent", s.warehouseRequireRent);
        s.warehouseBaseSlots = warehouseCapacity(c.getInt("guild.warehouse.base-slots", s.warehouseBaseSlots));
        s.warehouseSlotsPerLevel = Math.max(0, c.getInt("guild.warehouse.slots-per-level", s.warehouseSlotsPerLevel));
        s.warehouseMaxSlots = warehouseCapacity(c.getInt("guild.warehouse.max-slots", s.warehouseMaxSlots));
        if (s.warehouseBaseSlots > s.warehouseMaxSlots) {
            s.warehouseBaseSlots = s.warehouseMaxSlots;
        }
        s.warehouseRentPricePerDay = Math.max(0, c.getLong("guild.warehouse.rent.price-per-day", s.warehouseRentPricePerDay));
        s.warehouseRentMaxDays = Math.max(0, c.getInt("guild.warehouse.rent.max-days", s.warehouseRentMaxDays));
        s.warehouseRentOptions = parseDayOptions(c.getIntegerList("guild.warehouse.rent.options"), s.warehouseRentOptions);
        s.warehouseMaxLevel = Math.max(0, Math.min(32, c.getInt("guild.warehouse.upgrade.max-level", s.warehouseMaxLevel)));
        s.warehouseUpgradeBaseCost = Math.max(0, c.getLong("guild.warehouse.upgrade.base-cost", s.warehouseUpgradeBaseCost));
        s.warehouseUpgradeCostGrowth = Math.max(1.0, c.getDouble("guild.warehouse.upgrade.cost-growth", s.warehouseUpgradeCostGrowth));
        s.warehouseManageRank = GuildRank.parse(c.getString("guild.warehouse.manage-rank", s.warehouseManageRank.name()));
        s.shopEnabled = c.getBoolean("guild.shop.enabled", s.shopEnabled);
        s.shopMaxListings = Math.max(1, Math.min(54, c.getInt("guild.shop.max-listings", s.shopMaxListings)));
        s.shopMaxStackPerListing = Math.max(1, c.getInt("guild.shop.max-stack-per-listing", s.shopMaxStackPerListing));
        s.shopMaxPerPurchase = Math.max(1, c.getInt("guild.shop.max-per-purchase", s.shopMaxPerPurchase));
        s.shopMaxSupply = Math.max(1, c.getInt("guild.shop.max-supply", s.shopMaxSupply));
        s.shopTaxPercent = clamp(c.getDouble("guild.shop.tax-percent", s.shopTaxPercent), 0, 100);
        s.shopManageRank = GuildRank.parse(c.getString("guild.shop.manage-rank", s.shopManageRank.name()));
        s.titlesEnabled = c.getBoolean("guild.titles.enabled", s.titlesEnabled);
        s.titlesMax = Math.max(1, Math.min(64, c.getInt("guild.titles.max", s.titlesMax)));
        s.titlesManageRank = GuildRank.parse(c.getString("guild.titles.manage-rank", s.titlesManageRank.name()));
        return s;
    }

    /**
     * 纯函数：根据“今天”与“上次签到”计算签到后的连续天数。
     *
     * @param today      今天的 epoch day（本地时区）
     * @param lastDay    上次签到 epoch day（0 = 从未签到）
     * @param lastStreak 上次的连续天数
     * @return 新的连续天数
     */
    public static int nextStreak(long today, long lastDay, int lastStreak) {
        if (lastDay <= 0) {
            return 1;
        }
        if (today == lastDay) {
            return Math.max(1, lastStreak);
        }
        if (today == lastDay + 1) {
            return Math.max(1, lastStreak) + 1;
        }
        return 1;
    }

    /** 纯函数：今天是否已经签到。 */
    public static boolean alreadyCheckedIn(long today, long lastDay) {
        return lastDay > 0 && lastDay == today;
    }

    /**
     * 纯函数：一次在线活跃结算应发放的工会资金 = 活跃人数 × 每人点数；
     * 活跃人数不足 {@code minOnline}（至少按 1 计）时返回 0。
     */
    public static long activityReward(int activeMembers, int minOnline, long pointsPerPlayer) {
        if (activeMembers <= 0 || pointsPerPlayer <= 0) {
            return 0;
        }
        if (activeMembers < Math.max(1, minOnline)) {
            return 0;
        }
        try {
            return Math.multiplyExact((long) activeMembers, pointsPerPlayer);
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    /**
     * 纯函数：计算签到可获得的工会资金（含连续奖励，封顶）。
     */
    public static long checkinReward(long basePoints, long streakBonus, long maxStreakBonus, int streak) {
        long bonus = Math.min(Math.max(0, maxStreakBonus), Math.max(0, streakBonus) * Math.max(0, streak - 1));
        return Math.max(0, basePoints) + bonus;
    }

    /**
     * 纯函数：判定工会是否为“不活跃”，可用于清理僵尸工会。
     *
     * @param now          当前时间
     * @param lastActiveAt 最后一次活跃时间
     * @param inactiveDays 多少天无活动算不活跃
     * @param memberCount  成员数
     * @param maxMembers   仅清理不超过该成员数的工会（0 = 不限）
     * @param onlineCount  当前在线成员数
     */
    public static boolean isInactive(long now, long lastActiveAt, int inactiveDays,
                                     int memberCount, int maxMembers, int onlineCount) {
        if (onlineCount > 0) {
            return false;
        }
        if (maxMembers > 0 && memberCount > maxMembers) {
            return false;
        }
        long cutoff = now - Math.max(1, inactiveDays) * 86_400_000L;
        long since = lastActiveAt > 0 ? lastActiveAt : 0;
        return since < cutoff;
    }

    /** 当前等级可用的驻地数量。 */
    public int homeSlots(int level) {
        return homeSlots(homeMaxSlots, homeLevelsPerSlot, level);
    }

    /** 纯函数：按最高驻地数、每几级一驻地、等级计算可用驻地数量（至少 1）。 */
    public static int homeSlots(int maxSlots, int levelsPerSlot, int level) {
        int per = Math.max(1, levelsPerSlot);
        int cap = Math.max(1, maxSlots);
        int lv = Math.max(1, level);
        return Math.min(cap, 1 + (lv - 1) / per);
    }

    /** 成员上限 = 基础 + (等级-1) * 每级增量。 */
    public int memberLimit(int level) {
        return maxMembers + Math.max(0, level - 1) * maxMembersPerLevel;
    }

    /** 从 level 升到 level+1 所需点数。 */
    public long requiredPoints(int level) {
        return Math.max(1L, Math.round(baseRequiredPoints * Math.pow(requiredGrowth, Math.max(0, level - 1))));
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    /**
     * 工会仓库槽位数计算（纯函数，便于测试）。
     *
     * @param base     基础槽位
     * @param perLevel 每级增加
     * @param max      上限
     * @param level    当前等级
     * @return 9 的倍数，且在 9..4500 之间（逻辑容量，不是 Bukkit 库存尺寸）
     */
    public static int warehouseSlots(int base, int perLevel, int max, int level) {
        int cap = warehouseCapacity(max);
        int floor = Math.min(warehouseCapacity(base), cap);
        long extra = (long) Math.max(0, level - 1) * Math.max(0, perLevel);
        long total = Math.min(cap, floor + extra);
        return warehouseCapacity((int) Math.max(9, total));
    }

    /** 逻辑容量与 Bukkit 容器尺寸解耦；最多 100 页，仍按 9 向上取整。 */
    public static int warehouseCapacity(int slots) {
        int value = Math.max(9, Math.min(4500, slots));
        return ((value + 8) / 9) * 9;
    }

    /**
     * 把槽位数规整到 Minecraft 容器允许的范围：向上取整到 9 的倍数，并限制在 9~54。
     * （Bukkit 只接受 9 的倍数作为容器尺寸，配错的数字会让 createInventory 抛异常）
     */
    public static int clamp9(int slots) {
        int v = Math.max(9, Math.min(54, slots));
        return ((v + 8) / 9) * 9;
    }

    /** 纯函数：购买使用时间的价格；溢出饱和到 {@code Long.MAX_VALUE} 而不是回绕成负数。 */
    public static long warehouseRentCost(long pricePerDay, int days) {
        if (pricePerDay <= 0 || days <= 0) {
            return 0;
        }
        try {
            return Math.multiplyExact(pricePerDay, (long) days);
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    /** 纯函数：从 {@code currentLevel} 升到下一级的价格，按倍率指数增长。 */
    public static long warehouseUpgradeCost(long baseCost, double growth, int currentLevel) {
        if (baseCost <= 0) {
            return 0;
        }
        double cost = baseCost * Math.pow(Math.max(1.0, growth), Math.max(0, currentLevel));
        if (cost >= Long.MAX_VALUE || Double.isNaN(cost) || Double.isInfinite(cost)) {
            return Long.MAX_VALUE;
        }
        return Math.max(0, Math.round(cost));
    }

    /** 纯函数：把配置里的天数选项规整为正整数数组（去重、升序、限制在 1~3650）。 */
    public static int[] parseDayOptions(java.util.List<Integer> raw, int[] fallback) {
        java.util.TreeSet<Integer> set = new java.util.TreeSet<>();
        if (raw != null) {
            for (Integer v : raw) {
                if (v != null && v > 0) {
                    set.add(Math.min(v, 3650));
                }
            }
        }
        if (set.isEmpty()) {
            return fallback == null || fallback.length == 0 ? new int[]{1} : fallback.clone();
        }
        int[] out = new int[set.size()];
        int i = 0;
        for (int v : set) {
            out[i++] = v;
        }
        return out;
    }
}
