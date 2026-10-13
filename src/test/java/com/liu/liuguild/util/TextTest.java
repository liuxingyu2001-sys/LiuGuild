package com.liu.liuguild.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Round-2 regression tests for the unified MiniMessage + legacy text pipeline. */
class TextTest {

    @Test
    void parsesAmpersandColors() {
        Component c = Text.mm("&aGreen");
        assertEquals("Green", Text.plain(c));
        assertEquals(NamedTextColor.GREEN, firstColor(c));
    }

    @Test
    void parsesSectionColors() {
        Component c = Text.mm("\u00a7bAqua");
        assertEquals("Aqua", Text.plain(c));
        assertEquals(NamedTextColor.AQUA, firstColor(c));
    }

    @Test
    void parsesRgbLegacyColors() {
        Component c = Text.mm("&#FF0000Red");
        assertEquals("Red", Text.plain(c));
        assertEquals(TextColor.color(0xFF0000), firstColor(c));
    }

    @Test
    void parsesMiniMessageAndMixedContent() {
        assertEquals("Hello", Text.plain(Text.mm("<red>Hello")));
        Component mixed = Text.mm("<gold>A</gold> &bB");
        assertEquals("A B", Text.plain(mixed));
    }

    @Test
    void placeholdersArePlainTextByDefault() {
        // A malicious value must not become a red clickable component.
        Component c = Text.fmt("<gray>{name}", "name", "<red><click:run_command:'/op me'>x");
        assertEquals("<red><click:run_command:'/op me'>x", Text.plain(c));
        assertTrue(noClickEvents(c), "unparsed placeholder must not inject click events");
    }

    @Test
    void richPlaceholdersRenderFormatting() {
        Component c = Text.fmtRich("<gray>{name}", "name", "&aGreen");
        assertEquals("Green", Text.plain(c));
        assertEquals(NamedTextColor.GREEN, firstColor(c));
    }

    @Test
    void selectiveRenderingEscapesPlainKeysButAllowsRichKeys() {
        Component c = Text.fmtSelective("<gray>{player}: {notice}",
                java.util.Set.of("notice"),
                "player", "<red>Bad", "notice", "&aHello");
        assertEquals("<red>Bad: Hello", Text.plain(c));
        assertTrue(noClickEvents(c));
    }

    @Test
    void sanitizeKeepsColorsAndStripsEvents() {
        String sanitized = Text.sanitizeToMini("&a<click:run_command:'/op me'>Click</click>&r");
        Component c = Text.mm(sanitized);
        assertEquals("Click", Text.plain(c));
        assertEquals(NamedTextColor.GREEN, firstColor(c));
        assertTrue(noClickEvents(c), "click events must be stripped from player-authored text");
    }

    @Test
    void cleanAndFormattingDetection() {
        assertEquals("Hi", Text.clean("&a<red>Hi"));
        assertTrue(Text.hasFormatting("&aHi"));
        assertTrue(Text.hasFormatting("<red>Hi"));
        assertFalse(Text.hasFormatting("PlainName"));
        assertTrue(Text.hasLegacyCode("&#00ff00x"));
        assertFalse(Text.hasLegacyCode("<red>x"));
    }

    private static TextColor firstColor(Component c) {
        TextColor color = c.color();
        if (color != null) {
            return color;
        }
        for (Component child : c.children()) {
            TextColor nested = firstColor(child);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    private static boolean noClickEvents(Component c) {
        if (c.clickEvent() != null || c.hoverEvent() != null) {
            return false;
        }
        for (Component child : c.children()) {
            if (!noClickEvents(child)) {
                return false;
            }
        }
        return true;
    }
}
