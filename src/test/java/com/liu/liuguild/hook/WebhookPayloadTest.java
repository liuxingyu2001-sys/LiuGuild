package com.liu.liuguild.hook;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class WebhookPayloadTest {

    @Test
    void escapesJsonSpecialCharacters() {
        assertEquals("a\\\"b\\nc\\\\d", WebhookPayload.json("a\"b\nc\\d"));
        assertEquals("\\u0001", WebhookPayload.json("\u0001"));
    }

    @Test
    void buildsDiscordAndGenericPayloads() {
        assertEquals("{\"content\":\"hi\",\"username\":\"Liu\"}",
                WebhookPayload.build("discord", "Liu", "hi"));
        assertEquals("{\"content\":\"hi\"}", WebhookPayload.build("generic", "Liu", "hi"));
        assertFalse(WebhookPayload.build("generic", "Liu", "hi").contains("username"));
    }
}
