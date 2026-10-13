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
