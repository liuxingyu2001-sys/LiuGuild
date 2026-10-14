package com.liu.liuguild.war;

import com.liu.liuguild.listener.WarListener;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工会战期间的指令白名单（{@link WarListener#isAllowedWarCommand}）。
 *
 * <p>这段判断直接决定战斗中的玩家能用哪些指令：放行过宽会让玩家用 {@code /home}、{@code /tpa}、
 * {@code /guild gui} 把竞技场统一装备带出场，或把外部物品带进来；放行过窄又会把工会聊天、
 * 战况查询甚至退场都堵死，让玩家无法沟通或退出。这里逐类钉死行为。</p>
 */
class WarCommandFilterTest {

    private static boolean allowed(String raw) {
        String trimmed = raw.startsWith("/") ? raw.substring(1).trim() : raw.trim();
        String[] parts = trimmed.isEmpty() ? new String[0] : trimmed.split("\\s+");
        String label = parts.length == 0 ? "" : parts[0].toLowerCase(java.util.Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0 && colon + 1 < label.length()) {
            label = label.substring(colon + 1);
        }
        return WarListener.isAllowedWarCommand(label, parts);
    }

    @Test
    void chatCommandsAreAllowed() {
        assertTrue(allowed("/msg Notch hi"));
        assertTrue(allowed("/tell Notch hi"));
        assertTrue(allowed("/w Notch hi"));
        assertTrue(allowed("/whisper Notch hi"));
        assertTrue(allowed("/r ok"));
        assertTrue(allowed("/reply ok"));
        assertTrue(allowed("/me waves"));
    }

    @Test
    void guildChatAndHelpAreAllowed() {
        assertTrue(allowed("/guild chat hello"));
        assertTrue(allowed("/guild c hello"));
        assertTrue(allowed("/guild help"));
        assertTrue(allowed("/guild info"));
        assertTrue(allowed("/g c hello"));
        assertTrue(allowed("/gh help"));
        assertTrue(allowed("/gonghui info"));
    }

    @Test
    void warStatusAndLeaveAreAllowed() {
        assertTrue(allowed("/guild war"));
        assertTrue(allowed("/guild war status"));
        assertTrue(allowed("/guild war history"));
        assertTrue(allowed("/guild war leave"));
        assertTrue(allowed("/guild war STATUS"));
    }

    @Test
    void bareGuildCommandIsBlockedBecauseItOpensMenu() {
        assertFalse(allowed("/guild"));
        assertFalse(allowed("/g"));
        assertFalse(allowed("/gh"));
        assertFalse(allowed("/gonghui"));
    }

    @Test
    void menuAndWorldCommandsAreBlocked() {
        assertFalse(allowed("/guild gui"));
        assertFalse(allowed("/guild bank"));
        assertFalse(allowed("/guild home"));
        assertFalse(allowed("/guild shop"));
        assertFalse(allowed("/guild warehouse"));
        assertFalse(allowed("/guild war queue"));
        assertFalse(allowed("/guild war join"));
        assertFalse(allowed("/home"));
        assertFalse(allowed("/spawn"));
        assertFalse(allowed("/tpa Notch"));
        assertFalse(allowed("/warp arena"));
        assertFalse(allowed("/kit"));
    }

    @Test
    void namespacedLabelsAreStrippedBeforeMatching() {
        assertTrue(allowed("/liuguild:guild war status"));
        assertTrue(allowed("/minecraft:me hi"));
        assertFalse(allowed("/liuguild:guild gui"));
    }

    @Test
    void malformedLabelsAreBlockedSafely() {
        assertFalse(WarListener.isAllowedWarCommand(null, new String[0]));
        assertFalse(WarListener.isAllowedWarCommand("", new String[]{"guild"}));
        assertFalse(allowed("/   "));
    }
}
