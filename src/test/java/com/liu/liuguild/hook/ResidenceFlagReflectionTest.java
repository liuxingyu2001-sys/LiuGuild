package com.liu.liuguild.hook;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Residence 领地共享的反射适配测试。
 *
 * <p>真实踩过的坑：Residence 6.0.3.3 的 {@code setPlayerFlag} 是 **6 参数**
 * （{@code CommandSender, UUID, String, String, boolean, boolean}），
 * 而最初按 5 参数去找，结果一个重载都匹配不到 → 功能**静默失效**。
 * 这里用签名完全一致的假类把各版本情况都覆盖上。</p>
 */
class ResidenceFlagReflectionTest {

    /** 模拟 Residence 6.x 的重载（6 参数，主版本）。 */
    public static class PermsV6 {
        public boolean setPlayerFlag(CommandSender sender, UUID uuid, String flag, String value, boolean save, boolean resadmin) {
            return true;
        }
    }

    /** 模拟 7 参数版本。 */
    public static class PermsV7 {
        public boolean setPlayerFlag(CommandSender sender, UUID uuid, String flag, String value, boolean a, boolean b, boolean c) {
            return true;
        }
    }

    /** 模拟早期 5 参数版本。 */
    public static class PermsV5 {
        public boolean setPlayerFlag(CommandSender sender, UUID uuid, String flag, String value, boolean save) {
            return true;
        }
    }

    /** 只有按名字（String）的重载，没有 UUID 版本 —— 应当找不到。 */
    public static class PermsNoUuid {
        public boolean setPlayerFlag(CommandSender sender, String name, String flag, String value, boolean save, boolean resadmin) {
            return true;
        }
    }

    /** 完全没有 setPlayerFlag。 */
    public static class PermsNone {
        public void somethingElse() {
        }
    }

    @Test
    void findsSixArgOverloadUsedByResidence6() {
        Method m = ResidenceHook.findSetPlayerFlag(PermsV6.class);
        assertNotNull(m, "必须能找到 Residence 6.x 的 6 参数重载");
        assertEquals(6, m.getParameterTypes().length);
    }

    @Test
    void findsSevenAndFiveArgOverloads() {
        Method v7 = ResidenceHook.findSetPlayerFlag(PermsV7.class);
        assertNotNull(v7, "7 参数重载也应能找到");
        assertEquals(7, v7.getParameterTypes().length);

        Method v5 = ResidenceHook.findSetPlayerFlag(PermsV5.class);
        assertNotNull(v5, "5 参数重载也应能找到");
        assertEquals(5, v5.getParameterTypes().length);
    }

    @Test
    void prefersFewerParametersWhenMultipleExist() {
        // 同时存在 5/6/7 时，选最少的（最稳定）
        class Multi {
            public boolean setPlayerFlag(CommandSender s, UUID u, String f, String v, boolean a) {
                return true;
            }

            public boolean setPlayerFlag(CommandSender s, UUID u, String f, String v, boolean a, boolean b) {
                return true;
            }

            public boolean setPlayerFlag(CommandSender s, UUID u, String f, String v, boolean a, boolean b, boolean c) {
                return true;
            }
        }
        Method m = ResidenceHook.findSetPlayerFlag(Multi.class);
        assertNotNull(m);
        assertEquals(5, m.getParameterTypes().length);
    }

    @Test
    void rejectsOverloadsWithoutUuidTarget() {
        assertNull(ResidenceHook.findSetPlayerFlag(PermsNoUuid.class),
                "没有 UUID 目标的重载不能用（改名会残留权限）");
        assertNull(ResidenceHook.findSetPlayerFlag(PermsNone.class));
    }

    @Test
    void buildsArgumentsInTheRightPositions() {
        Method m = ResidenceHook.findSetPlayerFlag(PermsV6.class);
        assertNotNull(m);
        Object sender = new Object() {
            @Override
            public String toString() {
                return "console";
            }
        };
        UUID uuid = UUID.randomUUID();

        Object[] allow = ResidenceHook.buildFlagArgs(m, sender, uuid, true);
        assertEquals(6, allow.length);
        assertEquals(sender, allow[0], "第 1 位是 CommandSender");
        assertEquals(uuid, allow[1], "第 2 位是 UUID");
        assertEquals("", allow[2], "第 3 位是 flag 名（调用前填充）");
        assertEquals("allow", allow[3], "第 4 位是值");
        assertTrue((Boolean) allow[4], "save 应为 true");
        assertTrue((Boolean) allow[5], "resadmin 应为 true");

        Object[] deny = ResidenceHook.buildFlagArgs(m, sender, uuid, false);
        assertEquals("deny", deny[3]);
    }

    @Test
    void argumentsAreActuallyAcceptedByTheSixArgSignature() throws Exception {
        // 端到端：把构造好的参数真的喂给 6 参数方法，确认不会 IllegalArgumentException
        Method m = ResidenceHook.findSetPlayerFlag(PermsV6.class);
        assertNotNull(m);
        PermsV6 perms = new PermsV6();
        Object[] args = ResidenceHook.buildFlagArgs(m, dummySender(), UUID.randomUUID(), true);
        args[2] = "build";
        Object result = m.invoke(perms, args);
        assertTrue((Boolean) result);
    }

    /** 用动态代理生成一个 CommandSender，避免实现接口的全部方法。 */
    private static CommandSender dummySender() {
        return (CommandSender) java.lang.reflect.Proxy.newProxyInstance(
                ResidenceFlagReflectionTest.class.getClassLoader(),
                new Class<?>[]{CommandSender.class},
                (proxy, method, args) -> {
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) {
                        return true;
                    }
                    if (rt == int.class) {
                        return 0;
                    }
                    if (rt == long.class) {
                        return 0L;
                    }
                    if (rt == String.class) {
                        return "console";
                    }
                    return null;
                });
    }
}
