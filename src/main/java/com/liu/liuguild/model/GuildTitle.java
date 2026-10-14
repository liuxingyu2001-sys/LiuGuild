package com.liu.liuguild.model;

/** 工会自定义头衔（如「传说」「战力天花板」）。 */
public final class GuildTitle {

    /** 头衔标识（工会内唯一，用于指令与存储）。 */
    private final String id;
    /** 显示文本（支持 & / MiniMessage 颜色）。 */
    private volatile String display;
    /** 最低可用职位：达到该职位的成员才能装备它。 */
    private volatile GuildRank minRank;

    public GuildTitle(String id, String display, GuildRank minRank) {
        this.id = id == null ? "" : id;
        this.display = display == null ? "" : display;
        this.minRank = minRank == null ? GuildRank.MEMBER : minRank;
    }

    public String id() {
        return id;
    }

    public String display() {
        return display == null ? "" : display;
    }

    public void display(String display) {
        this.display = display == null ? "" : display;
    }

    public GuildRank minRank() {
        return minRank;
    }

    public void minRank(GuildRank minRank) {
        this.minRank = minRank == null ? GuildRank.MEMBER : minRank;
    }

    /** 该职位的成员是否够格使用此头衔。 */
    public boolean usableBy(GuildRank rank) {
        return rank != null && rank.atLeast(minRank);
    }
}
