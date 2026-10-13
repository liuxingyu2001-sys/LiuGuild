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
        return s;
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
