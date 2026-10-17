package com.liu.liuguild.hook;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** QQ 官方机器人 WebSocket 帧构造（纯函数，便于测试）。 */
public final class QqBotProtocol {

    private QqBotProtocol() {
    }

    public static String identify(String accessToken, int intents) {
        JsonObject d = new JsonObject();
        d.addProperty("token", "QQBot " + accessToken);
        d.addProperty("intents", intents);
        JsonArray shard = new JsonArray();
        shard.add(0);
        shard.add(1);
        d.add("shard", shard);
        JsonObject props = new JsonObject();
        props.addProperty("$os", "linux");
        props.addProperty("$browser", "liuguild");
        props.addProperty("$device", "liuguild");
        d.add("properties", props);
        JsonObject frame = new JsonObject();
        frame.addProperty("op", 2);
        frame.add("d", d);
        return frame.toString();
    }

    public static String resume(String accessToken, String sessionId, long seq) {
        JsonObject d = new JsonObject();
        d.addProperty("token", "QQBot " + accessToken);
        d.addProperty("session_id", sessionId);
        d.addProperty("seq", seq);
        JsonObject frame = new JsonObject();
        frame.addProperty("op", 6);
        frame.add("d", d);
        return frame.toString();
    }

    public static String heartbeat(long lastSeq) {
        JsonObject frame = new JsonObject();
        frame.addProperty("op", 1);
        frame.add("d", new com.google.gson.JsonPrimitive(lastSeq));
        return frame.toString();
    }
}
