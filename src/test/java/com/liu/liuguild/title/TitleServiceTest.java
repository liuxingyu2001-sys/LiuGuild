package com.liu.liuguild.title;

import com.liu.liuguild.model.GuildRank;
import com.liu.liuguild.model.GuildTitle;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工会头衔的输入校验与职位门槛。
 *
 * <p>这两块都是纯函数，不需要运行中的服务器 —— 而它们恰好是安全边界：
 * 标识会拼进指令与数据库键，显示文本会直接进 MiniMessage 渲染。</p>
 */
class TitleServiceTest {

    // =====================================================================
    // 标识校验
    // =====================================================================

    @Test
    void acceptsReasonableIds() {
        assertNull(TitleService.validateId("legend"));
        assertNull(TitleService.validateId("war_god"));
        assertNull(TitleService.validateId("top-1"));
        assertNull(TitleService.validateId("a"));
        assertNull(TitleService.validateId("1234567890123456")); // 16 位边界
    }

    @Test
    void rejectsIdsthatCouldBreakCommandsOrKeys() {
        // 空格 / 大写 / 特殊字符 / 空 / 超长 —— 全都要拒绝
        assertNotNull(TitleService.validateId("has space"));
        assertNotNull(TitleService.validateId("UPPER"));
        assertNotNull(TitleService.validateId("中文"));
        assertNotNull(TitleService.validateId("semi;colon"));
        assertNotNull(TitleService.validateId("quote'"));
        assertNotNull(TitleService.validateId("back\\slash"));
        assertNotNull(TitleService.validateId(""));
        assertNotNull(TitleService.validateId(null));
        assertNotNull(TitleService.validateId("12345678901234567")); // 17 位超长
    }

    @Test
    void rejectsColonWhichWouldBreakPlaceholderSyntax() {
        // ":" 会破坏 %liuguild:title% 之类的解析
        assertNotNull(TitleService.validateId("a:b"));
        assertNotNull(TitleService.validateId(":"));
    }

    // =====================================================================
    // 显示文本校验
    // =====================================================================

    @Test
    void acceptsPlainDisplay() {
        assertNull(TitleService.validateDisplay("传说"));
        assertNull(TitleService.validateDisplay("War God"));
    }

    @Test
    void acceptsColouredDisplayButCountsOnlyVisibleChars() {
        // 颜色标签不算长度，否则一个 <red> 就能顶掉 5 个字符
        assertNull(TitleService.validateDisplay("<red>传说"));
        assertNull(TitleService.validateDisplay("<gradient:#ff0000:#00ff00>战力天花板"));
        assertNull(TitleService.validateDisplay("&c红色头衔"));
    }

    @Test
    void rejectsEmptyOrInvisibleDisplay() {
        assertNotNull(TitleService.validateDisplay(""));
        assertNotNull(TitleService.validateDisplay("   "));
        assertNotNull(TitleService.validateDisplay(null));
        // 只有颜色标签、没有可见文字 —— 等于空头衔
        assertNotNull(TitleService.validateDisplay("<red>"));
        assertNotNull(TitleService.validateDisplay("<bold></bold>"));
    }

    @Test
    void rejectsOverlongDisplay() {
        int max = TitleService.maxDisplayLength();
        // 刚好合法
        assertNull(TitleService.validateDisplay("a".repeat(max)));
        // 多一个字符就拒绝
        assertNotNull(TitleService.validateDisplay("a".repeat(max + 1)));
        // 用颜色标签堆长度不算数：可见字符仍然超限才拒绝
        assertNotNull(TitleService.validateDisplay("<red>" + "a".repeat(max + 1)));
        assertNull(TitleService.validateDisplay("<red>" + "a".repeat(max)));
    }

    // =====================================================================
    // 职位门槛
    // =====================================================================

    @Test
    void rankGateWorksAsExpected() {
        GuildTitle memberTitle = new GuildTitle("t1", "见习", GuildRank.MEMBER);
        GuildTitle officerTitle = new GuildTitle("t2", "精英", GuildRank.OFFICER);
        GuildTitle leaderTitle = new GuildTitle("t3", "王", GuildRank.LEADER);

        // 普通成员只能拿 MEMBER 门槛的
        assertTrue(memberTitle.usableBy(GuildRank.MEMBER));
        assertFalse(officerTitle.usableBy(GuildRank.MEMBER));
        assertFalse(leaderTitle.usableBy(GuildRank.MEMBER));

        // 副会长拿 MEMBER + OFFICER
        assertTrue(memberTitle.usableBy(GuildRank.OFFICER));
        assertTrue(officerTitle.usableBy(GuildRank.OFFICER));
        assertFalse(leaderTitle.usableBy(GuildRank.OFFICER));

        // 会长全都能拿
        assertTrue(memberTitle.usableBy(GuildRank.LEADER));
        assertTrue(officerTitle.usableBy(GuildRank.LEADER));
        assertTrue(leaderTitle.usableBy(GuildRank.LEADER));
    }

    @Test
    void nullRankOrNullMinRankDoesNotThrow() {
        GuildTitle t = new GuildTitle("t", "x", GuildRank.MEMBER);
        assertFalse(t.usableBy(null));
        t.minRank(null);
        assertEquals(GuildRank.MEMBER, t.minRank(), "null 职位应回退到 MEMBER");
        assertTrue(t.usableBy(GuildRank.MEMBER));
    }

    @Test
    void titleDisplayDefaultsAreSafe() {
        GuildTitle t = new GuildTitle(null, null, null);
        assertEquals("", t.id());
        assertEquals("", t.display());
        assertEquals(GuildRank.MEMBER, t.minRank());
        // 变更 null 也不会抛
        t.display(null);
        assertEquals("", t.display());
    }
}
