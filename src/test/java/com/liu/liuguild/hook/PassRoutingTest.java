package com.liu.liuguild.hook;

import com.liu.liuguild.config.MainSettings;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 工会事件 → liupass 扩展任务类型进度的纯函数映射。 */
class PassRoutingTest {

    private static MainSettings settings() {
        return new MainSettings();
    }

    @Test
    void allTypesAreValidExtensionKeysAndUnique() {
        // 与 liupass TaskConfig.EXTENSION_TYPE 的正则保持一致
        var pattern = java.util.regex.Pattern.compile("[a-z0-9_]+:[a-z0-9_./-]+");
        assertEquals(PassRouting.ALL_TYPES.size(), new HashSet<>(PassRouting.ALL_TYPES).size());
        for (String type : PassRouting.ALL_TYPES) {
            assertTrue(pattern.matcher(type).matches(), "非法任务类型: " + type);
            assertTrue(type.startsWith("liuguild:"), "应使用 liuguild 命名空间: " + type);
        }
    }

    @Test
    void disabledIntegrationEmitsNothing() {
        MainSettings s = settings();
        s.passEnabled = false;
        UUID u = UUID.randomUUID();
        assertTrue(PassRouting.join(u, s).isEmpty());
        assertTrue(PassRouting.create(u, s).isEmpty());
        assertTrue(PassRouting.war(u, true, "match", s).isEmpty());
        assertTrue(PassRouting.warKill(u, "match", s).isEmpty());
        assertTrue(PassRouting.checkin(u, s).isEmpty());
        assertTrue(PassRouting.donate(u, 100, s).isEmpty());
    }

    @Test
    void warEmitsParticipationAndWinOnly() {
        MainSettings s = settings();
        UUID u = UUID.randomUUID();
        List<PassRouting.Progress> win = PassRouting.war(u, true, "season", s);
        assertEquals(2, win.size());
        assertEquals(PassRouting.TYPE_WAR, win.get(0).type());
        assertEquals("season", win.get(0).target());
        assertEquals(1, win.get(0).amount());
        assertEquals(PassRouting.TYPE_WAR_WIN, win.get(1).type());
        assertEquals("season", win.get(1).target());

        List<PassRouting.Progress> lose = PassRouting.war(u, false, "season", s);
        assertEquals(1, lose.size());
        assertEquals(PassRouting.TYPE_WAR, lose.get(0).type());
    }

    @Test
    void warTogglesAreIndependent() {
        MainSettings s = settings();
        UUID u = UUID.randomUUID();
        s.passWar = false;
        assertEquals(1, PassRouting.war(u, true, "match", s).size());
        assertEquals(PassRouting.TYPE_WAR_WIN, PassRouting.war(u, true, "match", s).get(0).type());

        s.passWar = true;
        s.passWarWin = false;
        assertEquals(PassRouting.TYPE_WAR, PassRouting.war(u, true, "match", s).get(0).type());
        assertEquals(1, PassRouting.war(u, true, "match", s).size());
    }

    @Test
    void amountsAreClampedAndNonPositiveIgnored() {
        assertEquals(0, PassRouting.clamp(-5));
        assertEquals(0, PassRouting.clamp(0));
        assertEquals(Integer.MAX_VALUE, PassRouting.clamp(Long.MAX_VALUE));
        assertEquals(50, PassRouting.clamp(50));

        MainSettings s = settings();
        UUID u = UUID.randomUUID();
        assertTrue(PassRouting.donate(u, 0, s).isEmpty());
        assertTrue(PassRouting.donate(u, -1, s).isEmpty());
        assertEquals(50, PassRouting.donate(u, 50, s).get(0).amount());
        assertEquals(Integer.MAX_VALUE, PassRouting.donate(u, Long.MAX_VALUE, s).get(0).amount());
    }

    @Test
    void bankDepositIsOffByDefault() {
        MainSettings s = settings();
        UUID u = UUID.randomUUID();
        assertTrue(PassRouting.bankDeposit(u, 100, s).isEmpty());
        s.passBankDeposit = true;
        List<PassRouting.Progress> on = PassRouting.bankDeposit(u, 100, s);
        assertEquals(1, on.size());
        assertEquals(PassRouting.TYPE_BANK_DEPOSIT, on.get(0).type());
        assertEquals(100, on.get(0).amount());
    }

    @Test
    void nullPlayerIsIgnored() {
        MainSettings s = settings();
        assertTrue(PassRouting.join(null, s).isEmpty());
        assertTrue(PassRouting.war(null, true, "match", s).isEmpty());
        assertTrue(PassRouting.checkin(null, s).isEmpty());
    }
}
