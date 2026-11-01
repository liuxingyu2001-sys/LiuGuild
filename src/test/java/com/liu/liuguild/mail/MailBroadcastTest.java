package com.liu.liuguild.mail;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 工会邮件群发的纯函数守卫：标题/正文校验与工会级冷却。
 *
 * <p>这部分逻辑同时被命令和 GUI 复用，校验或取整错一位就会出现
 * “提示可发却发不出”或“冷却永远不结束”，因此单独测。</p>
 */
class MailBroadcastTest {

    // =====================================================================
    // 标题校验（纯文本）
    // =====================================================================

    @Test
    void titleRejectsBlankAndFormattingOnly() {
        assertEquals(MailService.BroadcastResult.EMPTY_TITLE, MailService.validateTitle(null, 32));
        assertEquals(MailService.BroadcastResult.EMPTY_TITLE, MailService.validateTitle("   ", 32));
        // 纯格式标签清洗后为空，等同于空标题
        assertEquals(MailService.BroadcastResult.EMPTY_TITLE, MailService.validateTitle("<red>", 32));
    }

    @Test
    void titleRejectsTooLongButKeepsFormatting() {
        assertEquals(MailService.BroadcastResult.TITLE_TOO_LONG,
                MailService.validateTitle("a".repeat(33), 32));
        assertNull(MailService.validateTitle("a".repeat(32), 32));
        // 允许带颜色标签，长度按原始输入计算
        assertNull(MailService.validateTitle("<red>今晚集合</red>", 32));
    }

    // =====================================================================
    // 正文校验（允许 MiniMessage）
    // =====================================================================

    @Test
    void bodyRejectsBlank() {
        assertEquals(MailService.BroadcastResult.EMPTY_BODY, MailService.validateBody(null, 256));
        assertEquals(MailService.BroadcastResult.EMPTY_BODY, MailService.validateBody("  ", 256));
    }

    @Test
    void bodyAcceptsLenientMiniMessage() {
        // MiniMessage 默认宽松：未闭合/未知标签按字面量处理，不构成发送失败
        assertNull(MailService.validateBody("<red", 256));
        assertNull(MailService.validateBody("<notatag>今晚集合", 256));
    }

    @Test
    void bodyRejectsTooLong() {
        assertEquals(MailService.BroadcastResult.BODY_TOO_LONG,
                MailService.validateBody("b".repeat(257), 256));
        assertNull(MailService.validateBody("b".repeat(256), 256));
        assertNull(MailService.validateBody("<gold>今晚 8 点工会战</gold>", 256));
    }

    // =====================================================================
    // 冷却
    // =====================================================================

    @Test
    void cooldownIsZeroWhenDisabledOrNeverSent() {
        assertEquals(0, MailService.cooldownRemainingSeconds(0, 10_000, 60));
        assertEquals(0, MailService.cooldownRemainingSeconds(10_000, 10_000, 0));
        assertEquals(0, MailService.cooldownRemainingSeconds(10_000, 10_000, -3));
    }

    @Test
    void cooldownRoundsUpPartialSeconds() {
        long now = 1_000_000L;
        assertEquals(60, MailService.cooldownRemainingSeconds(now, now, 60));
        assertEquals(60, MailService.cooldownRemainingSeconds(now, now + 1, 60));
        assertEquals(30, MailService.cooldownRemainingSeconds(now, now + 30_000, 60));
        assertEquals(1, MailService.cooldownRemainingSeconds(now, now + 59_500, 60));
    }

    @Test
    void cooldownExpiresAndToleratesClockSkew() {
        long now = 1_000_000L;
        assertEquals(0, MailService.cooldownRemainingSeconds(now, now + 60_000, 60));
        assertEquals(0, MailService.cooldownRemainingSeconds(now, now + 120_000, 60));
        // 系统时间回拨时按完整冷却计算，不能因为负数差瞬间放行
        assertEquals(60, MailService.cooldownRemainingSeconds(now + 10_000, now, 60));
    }
}
