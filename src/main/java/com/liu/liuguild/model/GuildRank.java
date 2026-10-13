package com.liu.liuguild.model;

/** 工会职位，weight 越大权限越高。 */
public enum GuildRank {
    MEMBER(0, "成员"),
    OFFICER(1, "副会长"),
    LEADER(2, "会长");

    private final int weight;
    private final String display;

    GuildRank(int weight, String display) {
        this.weight = weight;
        this.display = display;
    }

    public int weight() {
        return weight;
    }

    public String display() {
        return display;
    }

    public boolean atLeast(GuildRank other) {
        return this.weight >= other.weight;
    }

    public static GuildRank parse(String s) {
        if (s == null) {
            return MEMBER;
        }
        for (GuildRank r : values()) {
            if (r.name().equalsIgnoreCase(s)) {
                return r;
            }
        }
        return MEMBER;
    }
}
