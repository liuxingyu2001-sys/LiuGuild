package com.liu.liuguild.api.event;

import com.liu.liuguild.model.GuildWar;

/** 两个工会匹配成功。 */
public class GuildWarMatchEvent extends GuildWarEvent {

    public GuildWarMatchEvent(GuildWar war) {
        super(war);
    }
}
