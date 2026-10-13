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
        return s;
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
