package com.liu.liuguild.war;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 战斗药水效果守卫：入场先清理，战斗期间拦截外部插件重新施加，套装自身效果通过。
 * <p>真实 Paper/PlayerTitle 行为仍需服务器联调，这里只锁定接线顺序。</p>
 */
class WarPotionEffectGuardTest {

    @Test
    void kitApplyClearsPotionEffectsBeforeKitEffects() throws Exception {
        String kit = Files.readString(Path.of("src/main/java/com/liu/liuguild/war/KitService.java"));
        int clear = kit.indexOf("clearPotionEffects(player);", kit.indexOf("private void fullState"));
        int add = kit.indexOf("player.addPotionEffect", kit.indexOf("private void fullState"));
        assertTrue(clear >= 0, "fullState 必须清理玩家原有药水效果");
        assertTrue(add > clear, "必须先清理再发放套装药水效果");
        assertTrue(kit.contains("effectBypass.add(player.getUniqueId())"));
        assertTrue(kit.contains("public boolean isApplyingEffects(UUID uuid)"));
    }

    @Test
    void restoreWritesSnapshotEffectsThroughBypass() throws Exception {
        String kit = Files.readString(Path.of("src/main/java/com/liu/liuguild/war/KitService.java"));
        int restore = kit.indexOf("public void restore(Player player, Snapshot snap, boolean skipTeleport)");
        int clear = kit.indexOf("clearPotionEffects(player);", restore);
        int bypass = kit.indexOf("effectBypass.add(player.getUniqueId());", restore);
        int add = kit.indexOf("player.addPotionEffect(e);", restore);
        assertTrue(clear >= 0 && bypass > clear && add > bypass,
                "恢复快照时也要先清理，再通过 bypass 还原原有效果");
    }

    @Test
    void listenerBlocksExternalEffectsForFightersButAllowsKit() throws Exception {
        String listener = Files.readString(Path.of("src/main/java/com/liu/liuguild/listener/WarListener.java"));
        assertTrue(listener.contains("EntityPotionEffectEvent"));
        assertTrue(listener.contains("EntityPotionEffectEvent.Action.ADDED"));
        assertTrue(listener.contains("plugin.war().isFighting(player.getUniqueId())"));
        assertTrue(listener.contains("plugin.kits().isApplyingEffects(player.getUniqueId())"));
        assertTrue(listener.contains("event.setCancelled(true)"));
    }

    @Test
    void fightingStateCoversNormalAndSeasonParticipants() throws Exception {
        String war = Files.readString(Path.of("src/main/java/com/liu/liuguild/war/WarManager.java"));
        String season = Files.readString(Path.of("src/main/java/com/liu/liuguild/war/SeasonService.java"));
        assertTrue(war.contains("public boolean isFighting(UUID uuid)"));
        assertTrue(war.contains("plugin.seasons() != null && plugin.seasons().isFighting(uuid)"));
        assertTrue(season.contains("public boolean isFighting(UUID id)"));
        assertTrue(season.contains("participants.containsKey(id)"));
    }
}
