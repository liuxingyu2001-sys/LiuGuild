package com.liu.liuguild.config;

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
        s.renameEnabled = c.getBoolean("guild.rename.enabled", s.renameEnabled);
        s.renameCooldownSeconds = Math.max(0, c.getInt("guild.rename.cooldown-seconds", s.renameCooldownSeconds));
        s.renameCost = Math.max(0, c.getDouble("guild.rename.cost", s.renameCost));
        s.renameAllowTag = c.getBoolean("guild.rename.allow-tag", s.renameAllowTag);
        s.residenceEnabled = c.getBoolean("guild.residence.enabled", s.residenceEnabled);
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
}
