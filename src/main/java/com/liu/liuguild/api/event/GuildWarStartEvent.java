package com.liu.liuguild.api.event;

import com.liu.liuguild.model.GuildWar;

/** 工会战正式开打（进入 ACTIVE）。 */
public class GuildWarStartEvent extends GuildWarEvent {

    public GuildWarStartEvent(GuildWar war) {
        super(war);
    }
}
