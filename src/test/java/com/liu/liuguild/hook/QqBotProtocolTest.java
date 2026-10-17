package com.liu.liuguild.hook;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QqBotProtocolTest {

    @Test
    void identifyFrameContainsTokenIntentsAndShard() {
        JsonObject frame = JsonParser.parseString(QqBotProtocol.identify("abc", 33554432)).getAsJsonObject();
        assertEquals(2, frame.get("op").getAsInt());
        JsonObject d = frame.getAsJsonObject("d");
        assertEquals("QQBot abc", d.get("token").getAsString());
        assertEquals(33554432, d.get("intents").getAsInt());
        assertEquals(2, d.getAsJsonArray("shard").size());
    }

    @Test
    void resumeFrameKeepsSessionAndSeq() {
        JsonObject frame = JsonParser.parseString(QqBotProtocol.resume("abc", "sess", 42)).getAsJsonObject();
        assertEquals(6, frame.get("op").getAsInt());
        assertEquals("sess", frame.getAsJsonObject("d").get("session_id").getAsString());
        assertEquals(42, frame.getAsJsonObject("d").get("seq").getAsLong());
    }

    @Test
    void heartbeatCarriesLastSeq() {
        JsonObject frame = JsonParser.parseString(QqBotProtocol.heartbeat(9)).getAsJsonObject();
        assertEquals(1, frame.get("op").getAsInt());
        assertTrue(frame.get("d").getAsLong() == 9);
    }
}
