package com.liu.liuguild.war;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 赛季公平血量与匹配广播的接线守卫（Bukkit 行为本身需要真实服务器验证）。 */
class SeasonServiceGuardTest {

    @Test
    void seasonFairHealthSuppressesPeachesBeforeKitApply() throws Exception {
        String season = Files.readString(Path.of("src/main/java/com/liu/liuguild/war/SeasonService.java"));
        assertTrue(season.contains("!mode.fairHealth"));
        assertTrue(season.contains("luckyPeaches().enter(player,plugin.luckyPeaches().supportsSuppress())"));
        assertTrue(season.contains("clearFairHealth(p,entry.player())"));
        int enter = season.indexOf("applyFairHealth(player);");
        int kit = season.indexOf("plugin.kits().apply(player,mode);");
        assertTrue(enter >= 0 && kit > enter, "公平血量必须在发放套装/回满血量之前生效");
    }

    @Test
    void seasonHonorsFreezeConfigAndRegistrationWindow() throws Exception {
        String season = Files.readString(Path.of("src/main/java/com/liu/liuguild/war/SeasonService.java"));
        assertTrue(season.contains("return mode == null || mode.freezeOnPrepare;"));
        assertTrue(season.contains("requested.seasonRegistrationSeconds"));
    }

    @Test
    void queueAnnouncementReachesOtherServersOnce() throws Exception {
        String war = Files.readString(Path.of("src/main/java/com/liu/liuguild/war/WarManager.java"));
        assertTrue(war.contains("publish(\"WAR_QUEUE_ANNOUNCE\""));
        assertTrue(war.contains("\"WAR_QUEUE_ANNOUNCE\".equals(type)"));
        assertTrue(war.contains("plugin.serverId().equals(data.get(\"server\"))"));
        assertTrue(war.contains("plugin.sync(() -> plugin.guilds().broadcastAll(text))"));
    }
}
