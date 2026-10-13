package com.liu.liuguild.model;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 工会。 */
public final class Guild {

    private final String id;
    private volatile String name;
    private volatile String tag;
    private volatile UUID leaderUuid;
    private volatile String leaderName;
    private volatile String description;
    private volatile String notice;
    /** 工会图标 id（见 icons.yml）。 */
    private volatile String icon;
    /** 自定义图标数据（旧插件导入的序列化 ItemStack，可空）。 */
    private volatile String iconData;
    private volatile boolean open;
    private volatile long createdAt;
    private volatile long updatedAt;
    private volatile long points;
    private volatile int level;
    private volatile int wins;
    private volatile int losses;
    private volatile long bank;
    /** 最近一次所在的服务器（用于匹配/竞技场选址）。 */
    private volatile String homeServer;
    /** 反规范化成员数：成员未加载时用于列表展示。 */
    private volatile int memberCount;
    /** 最后一次活跃时间（成员登录或工会数据变更），用于清理僵尸工会。 */
    private volatile long lastActiveAt = System.currentTimeMillis();

    private final Map<UUID, GuildMember> members = new ConcurrentHashMap<>();

    public Guild(String id) {
        this.id = id;
    }

    public Guild(String id, String name, String tag, UUID leaderUuid, String leaderName,
                 String description, String notice, boolean open,
                 long createdAt, long updatedAt, long points, int level,
                 int wins, int losses, long bank, String homeServer, int memberCount,
                 String icon) {
        this.id = id;
        this.name = name;
        this.tag = tag;
        this.leaderUuid = leaderUuid;
        this.leaderName = leaderName;
        this.description = description;
        this.notice = notice;
        this.open = open;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.points = points;
        this.level = level;
        this.wins = wins;
        this.losses = losses;
        this.bank = bank;
        this.homeServer = homeServer;
        this.memberCount = memberCount;
        this.icon = icon;
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public void name(String name) {
        this.name = name;
    }

    public String tag() {
        return tag;
    }

    public void tag(String tag) {
        this.tag = tag;
    }

    public UUID leaderUuid() {
        return leaderUuid;
    }

    public void leaderUuid(UUID leaderUuid) {
        this.leaderUuid = leaderUuid;
    }

    public String leaderName() {
        return leaderName;
    }

    public void leaderName(String leaderName) {
        this.leaderName = leaderName;
    }

    public String description() {
        return description == null ? "" : description;
    }

    public void description(String description) {
        this.description = description;
    }

    public String notice() {
        return notice == null ? "" : notice;
    }

    public void notice(String notice) {
        this.notice = notice;
    }

    public String icon() {
        return icon == null || icon.isBlank() ? "default" : icon;
    }

    public void icon(String icon) {
        this.icon = icon;
    }

    public String iconData() {
        return iconData == null ? "" : iconData;
    }

    public void iconData(String iconData) {
        this.iconData = iconData;
    }

    public boolean open() {
        return open;
    }

    public void open(boolean open) {
        this.open = open;
    }

    public long createdAt() {
        return createdAt;
    }

    public void createdAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long updatedAt() {
        return updatedAt;
    }

    public void touch() {
        this.updatedAt = System.currentTimeMillis();
    }

    public long lastActiveAt() {
        return lastActiveAt;
    }

    public void lastActiveAt(long lastActiveAt) {
        this.lastActiveAt = lastActiveAt;
    }

    /** 标记为最近活跃（仅前进，不回退）。 */
    public void touchActive() {
        long now = System.currentTimeMillis();
        if (now > lastActiveAt) {
            this.lastActiveAt = now;
        }
    }

    public long points() {
        return points;
    }

    public void points(long points) {
        this.points = points;
    }

    public int level() {
        return level;
    }

    public void level(int level) {
        this.level = level;
    }

    public int wins() {
        return wins;
    }

    public void wins(int wins) {
        this.wins = wins;
    }

    public int losses() {
        return losses;
    }

    public void losses(int losses) {
        this.losses = losses;
    }

    public long bank() {
        return bank;
    }

    public void bank(long bank) {
        this.bank = bank;
    }

    public String homeServer() {
        return homeServer;
    }

    public void homeServer(String homeServer) {
        this.homeServer = homeServer;
    }

    public int memberCount() {
        return members.isEmpty() ? memberCount : members.size();
    }

    public void memberCount(int count) {
        this.memberCount = count;
    }

    public Collection<GuildMember> members() {
        return members.values();
    }

    public Map<UUID, GuildMember> memberMap() {
        return members;
    }

    public void putMember(GuildMember m) {
        members.put(m.uuid(), m);
        memberCount = members.size();
    }

    public void removeMember(UUID uuid) {
        members.remove(uuid);
        memberCount = members.size();
    }

    public GuildMember member(UUID uuid) {
        return members.get(uuid);
    }

    public boolean hasMember(UUID uuid) {
        return members.containsKey(uuid);
    }

    public GuildMember leader() {
        return leaderUuid == null ? null : members.get(leaderUuid);
    }

    public int onlineCount(Iterable<UUID> online) {
        int n = 0;
        for (UUID u : online) {
            if (members.containsKey(u)) {
                n++;
            }
        }
        return n;
    }
}
