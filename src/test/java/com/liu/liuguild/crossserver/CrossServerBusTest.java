package com.liu.liuguild.crossserver;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Round-4 regression tests for cross-server payload encoding. */
class CrossServerBusTest {

    @Test
    void encodeParseRoundTrip() {
        Map<String, String> in = new LinkedHashMap<>();
        in.put("guild", "abc-123");
        in.put("server", "survival");
        in.put("name", "玩家Name");
        Map<String, String> out = CrossServerBus.parse(CrossServerBus.encode(in));
        assertEquals(in, out);
    }

    @Test
    void separatorCharactersInValuesAreSanitized() {
        Map<String, String> in = new LinkedHashMap<>();
        // Values containing the record/unit separators must not break parsing.
        in.put("text", "a\u001eb\u001fc");
        in.put("guild", "g1");
        Map<String, String> out = CrossServerBus.parse(CrossServerBus.encode(in));
        assertEquals("g1", out.get("guild"));
        assertTrue(out.containsKey("text"));
        // The injected separators were replaced by spaces, never treated as delimiters.
        assertEquals("a b c", out.get("text"));
    }

    @Test
    void emptyPayloadParsesToEmptyMap() {
        assertTrue(CrossServerBus.parse("").isEmpty());
        assertTrue(CrossServerBus.parse(null).isEmpty());
    }

    @Test
    void mapHelperSkipsNullValuesGracefully() {
        Map<String, String> m = CrossServerBus.map("a", null, "b", "x");
        assertEquals("", m.get("a"));
        assertEquals("x", m.get("b"));
    }
}
