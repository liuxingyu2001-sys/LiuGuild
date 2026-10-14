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

    // =====================================================================
    // 跨服锁的原子性（源码守卫）
    // =====================================================================

    /**
     * 释放/续期锁必须是「比对 + 动作」的**单条原子命令**（Lua）。
     *
     * <p>如果拆成 {@code GET} 然后 {@code DEL}/{@code EXPIRE}，中间有一个窗口：
     * 我们的锁恰好过期 → 别人抢到 → 我们把<b>别人的锁</b>删掉/续期。
     * 结果就是两个玩家同时以为自己在独占工会仓库。</p>
     *
     * <p>这个测试扫源码，防止以后有人把它「优化」回两次调用。</p>
     */
    @Test
    void lockReleaseAndRefreshUseAtomicLuaScripts() throws Exception {
        String src = java.nio.file.Files.readString(java.nio.file.Paths.get(
                "src/main/java/com/liu/liuguild/crossserver/CrossServerBus.java"));

        int release = src.indexOf("public boolean releaseLock(");
        int refresh = src.indexOf("public boolean refreshLock(");
        assertTrue(release > 0, "找不到 releaseLock");
        assertTrue(refresh > 0, "找不到 refreshLock");

        String releaseBody = src.substring(release, src.indexOf("public String lockOwner"));
        String refreshBody = src.substring(refresh, release);

        for (String body : new String[]{releaseBody, refreshBody}) {
            assertTrue(body.contains("jedis.eval("), "锁操作必须走 jedis.eval 的 Lua 脚本");
            assertTrue(body.contains("COMPARE_"), "Lua 脚本必须是比对型的");
        }
        // 绝不允许在锁操作里出现裸的 GET + DEL/EXPIRE 组合
        assertTrue(!releaseBody.contains("jedis.del("),
                "releaseLock 不能直接 del —— 必须先比对令牌（原子）");
        assertTrue(!refreshBody.contains("jedis.expire("),
                "refreshLock 不能直接 expire —— 必须先比对令牌（原子）");
    }

    @Test
    void luaScriptsCompareThenAct() {
        String src;
        try {
            src = java.nio.file.Files.readString(java.nio.file.Paths.get(
                    "src/main/java/com/liu/liuguild/crossserver/CrossServerBus.java"));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        // 两个脚本都必须先 get 比对 ARGV[1]，再执行写操作
        int del = src.indexOf("COMPARE_DEL_SCRIPT =");
        int exp = src.indexOf("COMPARE_EXPIRE_SCRIPT =");
        assertTrue(del > 0 && exp > 0, "两个 Lua 脚本常量都要存在");

        String delScript = src.substring(del, src.indexOf(";", del));
        String expScript = src.substring(exp, src.indexOf(";", exp));
        for (String script : new String[]{delScript, expScript}) {
            assertTrue(script.contains("redis.call('get'"), "脚本要先读取当前令牌");
            assertTrue(script.contains("ARGV[1]"), "脚本要拿调用方令牌比对");
            assertTrue(script.contains("then"), "脚本必须是条件执行");
        }
        assertTrue(delScript.contains("redis.call('del'"));
        assertTrue(expScript.contains("redis.call('expire'"));
    }
}
