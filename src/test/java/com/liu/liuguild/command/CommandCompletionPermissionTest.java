package com.liu.liuguild.command;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tab 补全也要遵守权限，不能把管理指令或受限子命令暴露给普通玩家。
 */
class CommandCompletionPermissionTest {

    @Test
    void guildAdminCompletionOnlyShowsPermittedSubcommands() {
        GuildAdminCommand command = new GuildAdminCommand(null);

        List<String> none = command.onTabComplete(player(Set.of()), null, "guildadmin", new String[]{""});
        assertTrue(none.contains("help"));
        assertFalse(none.contains("cleanup"));
        assertFalse(none.contains("reload"));
        assertFalse(none.contains("arena"));

        List<String> reload = command.onTabComplete(player(Set.of("liuguild.admin.reload")),
                null, "guildadmin", new String[]{""});
        assertTrue(reload.contains("reload"));
        assertFalse(reload.contains("cleanup"));

        List<String> parent = command.onTabComplete(player(Set.of("liuguild.admin")),
                null, "guildadmin", new String[]{""});
        assertTrue(parent.contains("cleanup"));
        assertTrue(parent.contains("arena"));
    }

    @Test
    void guildAdminDoesNotCompleteArgumentsOfForbiddenSubcommands() {
        GuildAdminCommand command = new GuildAdminCommand(null);
        assertTrue(command.onTabComplete(player(Set.of()), null, "guildadmin",
                new String[]{"arena", ""}).isEmpty());
        assertTrue(command.onTabComplete(player(Set.of("liuguild.admin.reload")), null, "guildadmin",
                new String[]{"arena", ""}).isEmpty());
        assertTrue(command.onTabComplete(player(Set.of()), null, "guildadmin",
                new String[]{"cleanup", ""}).isEmpty());
        assertTrue(command.onTabComplete(player(Set.of("liuguild.admin")), null, "guildadmin",
                new String[]{"cleanup", ""}).containsAll(List.of("dry", "confirm")));
    }

    @Test
    void guildCompletionHidesPermissionGatedSubcommands() {
        GuildCommand command = new GuildCommand(null);
        List<String> base = command.onTabComplete(player(Set.of("liuguild.use")), null, "guild", new String[]{""});
        assertTrue(base.contains("help"));
        assertFalse(base.contains("create"));
        assertFalse(base.contains("war"));
        assertFalse(base.contains("warehouse"));

        List<String> allowed = command.onTabComplete(player(Set.of("liuguild.use", "liuguild.create",
                "liuguild.war", "liuguild.warehouse")), null, "guild", new String[]{""});
        assertTrue(allowed.contains("create"));
        assertTrue(allowed.contains("war"));
        assertTrue(allowed.contains("warehouse"));
    }

    @Test
    void guildCompletionRequiresUsePermission() {
        GuildCommand command = new GuildCommand(null);
        assertTrue(command.onTabComplete(player(Set.of()), null, "guild", new String[]{""}).isEmpty());
        assertTrue(command.onTabComplete(player(Set.of("liuguild.use")), null, "guild",
                new String[]{"warehouse", ""}).isEmpty());
    }

    private static Player player(Set<String> permissions) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hasPermission" -> permissions.contains((String) args[0]);
                    case "getName" -> "Tester";
                    case "isOnline" -> true;
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0D;
        if (type == float.class) return 0F;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return '\0';
        return null;
    }
}
