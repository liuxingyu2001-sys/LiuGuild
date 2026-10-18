package com.liu.liuguild.war;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跨服消息范围守卫：两工会战况不得走全服/LiuChat 公告，只发给本场参与者与旁观者。
 */
class WarBroadcastScopeGuardTest {

    private static String source(String path) throws Exception {
        return Files.readString(Path.of(path));
    }

    @Test
    void twoGuildWarMessagesStayInsideTheMatch() throws Exception {
        String war = source("src/main/java/com/liu/liuguild/war/WarManager.java");
        String body = war.substring(war.indexOf("private void broadcastWar("));
        body = body.substring(0, body.indexOf("\n    }", body.indexOf("private void broadcastWar(")));
        assertTrue(body.contains("war.allPlayers()"));
        assertTrue(body.contains("spectators"));
        assertFalse(body.contains("announceGuild"));
        assertFalse(body.contains("announceServer"));
        assertFalse(body.contains("plugin.chat()"));
    }

    @Test
    void globalWarAnnouncementConfigIsGone() throws Exception {
        String yml = source("src/main/resources/war.yml");
        String settings = source("src/main/java/com/liu/liuguild/config/WarSettings.java");
        assertFalse(yml.contains("announce-server"));
        assertFalse(settings.contains("announceServer"));
    }

    @Test
    void autoEnterUsesPrivateMatchMessageInsteadOfGuildAnnouncement() throws Exception {
        String war = source("src/main/java/com/liu/liuguild/war/WarManager.java");
        int auto = war.indexOf("private void autoEnterGuild(");
        int end = war.indexOf("private void transferToWar(", auto);
        String body = war.substring(auto, end);
        assertTrue(body.contains("war-match-found-auto"));
        assertFalse(body.contains("announceGuild"));
    }

    @Test
    void seasonCombatMessagesGoToParticipantsOnly() throws Exception {
        String season = source("src/main/java/com/liu/liuguild/war/SeasonService.java");
        assertFalse(season.contains("Bukkit.broadcastMessage"));
        assertTrue(season.contains("private void announceParticipants"));
        assertTrue(season.contains("announceParticipants(\"§6[工会赛季]"));
    }
}
