package com.liu.liuguild.hook;

import java.util.Locale;

/** Webhook JSON 编码（纯函数，便于测试）。 */
public final class WebhookPayload {

    private WebhookPayload() {
    }

    public static String build(String format, String username, String content) {
        String escaped = json(content == null ? "" : content);
        if (format != null && format.toLowerCase(Locale.ROOT).equals("discord")) {
            return "{\"content\":\"" + escaped + "\",\"username\":\"" + json(username == null ? "LiuGuild" : username) + "\"}";
        }
        return "{\"content\":\"" + escaped + "\"}";
    }

    public static String json(String s) {
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
