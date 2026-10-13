package com.liu.liuguild.listener;

import com.liu.liuguild.LiuGuild;
import com.liu.liuguild.module.GuildModule;

import java.util.Map;
import java.util.UUID;

/** 跨服工会数据同步：接收其它子服的工会变更、公告与邀请。 */
public final class GuildSyncModule implements GuildModule {

    private final LiuGuild plugin;

    public GuildSyncModule(LiuGuild plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "guild-sync";
    }

    @Override
    public int order() {
        return 50;
    }

    @Override
    public void enable(LiuGuild plugin) {
    }

    @Override
    public void onCrossServer(String type, Map<String, String> data) {
        switch (type) {
            case "GUILD_CHANGED" -> {
                String guildId = data.get("guild");
                String from = data.get("server");
                if (from != null && from.equals(plugin.serverId())) {
                    return; // 自己发的，忽略
                }
                if (guildId != null) {
                    plugin.guilds().reloadGuild(guildId);
                }
            }
            case "MEMBER_CHANGED" -> {
                String from = data.get("server");
                if (from != null && from.equals(plugin.serverId())) {
                    return;
                }
                String guildId = data.get("guild");
                if (guildId != null && !guildId.isBlank()) {
                    plugin.guilds().reloadGuild(guildId);
                } else {
                    plugin.guilds().reloadAll();
                }
            }
            case "GUILD_ANNOUNCE" -> {
                String from = data.get("server");
                if (from != null && from.equals(plugin.serverId())) {
                    return;
                }
                plugin.guilds().deliverAnnounce(data.get("guild"), data.get("text"), data.get("except"));
            }
            case "GUILD_CHAT" -> {
                if (plugin.guildChat() != null) plugin.guildChat().receive(data);
            }
            case "GUILD_INVITE" -> {
                String target = data.get("target");
                if (target != null) {
                    try {
                        plugin.guilds().receiveRemoteInvite(UUID.fromString(target), data.get("guild"), data.get("name"));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
            default -> {
            }
        }
    }
}
