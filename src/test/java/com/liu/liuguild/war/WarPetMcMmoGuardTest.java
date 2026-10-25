package com.liu.liuguild.war;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 工会战宠物屏蔽与 mcMMO 提示屏蔽的源码守卫（真实插件行为需服务端联调）。 */
class WarPetMcMmoGuardTest {

    private static String source(String path) throws Exception {
        return Files.readString(Path.of(path));
    }

    @Test
    void petHookBlocksSpawnUseAndRemovesExistingPets() throws Exception {
        String hook = source("src/main/java/com/liu/liuguild/hook/WarPetHook.java");
        assertTrue(hook.contains("fr.nocsy.mcpets.events.PetSpawnEvent"));
        assertTrue(hook.contains("fr.nocsy.mcpets.events.PetCastSkillEvent"));
        assertTrue(hook.contains("fr.nocsy.mcpets.events.PetOwnerInteractEvent"));
        assertTrue(hook.contains("fr.nocsy.mcpets.events.EntityMountPetEvent"));
        assertTrue(hook.contains("de.Keyle.MyPet.api.event.MyPetCallEvent"));
        assertTrue(hook.contains("de.Keyle.MyPet.api.event.MyPetActivatedEvent"));
        assertTrue(hook.contains("de.Keyle.MyPet.api.event.MyPetPlayerJoinEvent"));
        assertTrue(hook.contains("getActivePetsForOwner"));
        assertTrue(hook.contains("removePet"));
        assertTrue(hook.contains("enforceWarPlayers"));
        assertTrue(hook.contains("isWarRestricted"));
    }

    @Test
    void warLifecycleImmediatelyAndPeriodicallyEnforcesPetBlock() throws Exception {
        String war = source("src/main/java/com/liu/liuguild/war/WarManager.java");
        assertTrue(war.contains("plugin.warPets().enforce(player);"));
        assertTrue(war.contains("plugin.warPets().enforceWarPlayers();"));
        assertTrue(war.contains("plugin.warPets().enforce(p);"));
    }

    @Test
    void mcMmoNotificationEventIsCancelledForRestrictedPlayers() throws Exception {
        String hook = source("src/main/java/com/liu/liuguild/hook/McMmoHook.java");
        assertTrue(hook.contains("com.gmail.nossr50.events.skills.McMMOPlayerNotificationEvent"));
        assertTrue(hook.contains("suppressNotification"));
        assertTrue(hook.contains("isWarRestricted"));
    }

    @Test
    void configAndSoftDependDeclarePetAndNotificationHooks() throws Exception {
        String config = source("src/main/resources/config.yml");
        assertTrue(config.contains("block-in-war: true"));
        // mcMMO 只有一个开关，不再保留技能/提示/经验的拆分键。
        assertTrue(!config.contains("suppress-abilities-in-war"));
        assertTrue(!config.contains("suppress-notifications-in-war"));
        assertTrue(!config.contains("suppress-xp-in-war"));
        String plugin = source("src/main/resources/plugin.yml");
        assertTrue(plugin.contains("- LiuPets"));
        assertTrue(plugin.contains("- MCPets"));
        assertTrue(plugin.contains("- MyPet"));
    }
}
