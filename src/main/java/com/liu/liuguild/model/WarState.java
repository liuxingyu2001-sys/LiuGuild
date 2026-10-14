package com.liu.liuguild.model;

/** 工会战状态机。 */
public enum WarState {
    /** 已匹配，等待准备。 */
    MATCHED,
    /** 准备阶段（倒计时）。 */
    PREPARING,
    /** 正式战斗。 */
    ACTIVE,
    /** 结算展示。 */
    SETTLING,
    /** 已结束（已写库）。 */
    ENDED,
    /** 已取消。 */
    CANCELLED;

    public boolean isLive() {
        return this == MATCHED || this == PREPARING || this == ACTIVE || this == SETTLING;
    }

    /** 中文显示名（避免在 GUI / 占位符里直接暴露英文枚举）。 */
    public String display() {
        return switch (this) {
            case MATCHED -> "已匹配";
            case PREPARING -> "准备中";
            case ACTIVE -> "战斗中";
            case SETTLING -> "结算中";
            case ENDED -> "已结束";
            case CANCELLED -> "已取消";
        };
    }

    public static WarState parse(String s) {
        if (s == null) {
            return ENDED;
        }
        for (WarState v : values()) {
            if (v.name().equalsIgnoreCase(s)) {
                return v;
            }
        }
        return ENDED;
    }
}
