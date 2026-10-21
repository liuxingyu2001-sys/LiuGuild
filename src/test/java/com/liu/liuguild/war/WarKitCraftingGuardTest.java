package com.liu.liuguild.war;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 统一装备不得通过玩家 2x2 合成栏绕过 41 格快照。
 *
 * <p>合成栏不在 PlayerInventory 快照里，若套件留在其中，归还快照后关闭界面会
 * 被原版退回背包，造成复制。这里守卫四条接线：入场采集前 flush、发装备/还原时
 * clear、战斗中拦截点击与拖拽。真实 Bukkit 行为仍需服务器验证。</p>
 */
class WarKitCraftingGuardTest {

    @Test
    void kitApplyAndRestoreClearCraftingGrid() throws Exception {
        String kit = Files.readString(Path.of("src/main/java/com/liu/liuguild/war/KitService.java"));
        assertTrue(kit.contains("public void clearCraftingGrid(Player player)"));
        int restore = kit.indexOf("public void restore(Player player, Snapshot snap, boolean skipTeleport)");
        int apply = kit.indexOf("public void apply(Player player, WarMode mode)");
        assertTrue(restore >= 0 && apply > restore);
        String restoreBody = kit.substring(restore, kit.indexOf("private static ItemStack[] deepCopy", restore));
        String applyBody = kit.substring(apply, kit.indexOf("/** 应用直接保存的装备快照。 */", apply));
        assertTrue(restoreBody.contains("clearCraftingGrid(player)"));
        assertTrue(applyBody.contains("clearCraftingGrid(player)"));
    }

    @Test
    void warSnapshotsFlushCraftingGridFirst() throws Exception {
        String war = Files.readString(Path.of("src/main/java/com/liu/liuguild/war/WarManager.java"));
        int join = war.indexOf("plugin.kits().flushCraftingGrid(player);");
        int joinCapture = war.indexOf("captureSnapshot(player, war, () -> {", join);
        assertTrue(join >= 0 && joinCapture > join, "入场套件采集前必须先 flush 合成栏");
        int spectate = war.indexOf("plugin.kits().flushCraftingGrid(player);", join + 1);
        int spectateCapture = war.indexOf("captureSnapshot(player, war, () -> {", spectate);
        assertTrue(spectate > join && spectateCapture > spectate, "旁观快照前也必须 flush 合成栏");
        // SparrowSync 分支不经过 KitService.restore，必须在 restoreSnapshot 统一清一次。
        assertTrue(war.contains("private void restoreSnapshot(UUID uuid, Player player, boolean quitting, Runnable afterRestore)"));
        assertTrue(war.contains("plugin.kits().clearCraftingGrid(p);"));
    }

    @Test
    void restrictedPlayersCannotClickOrDragIntoPlayerCrafting() throws Exception {
        String listener = Files.readString(Path.of("src/main/java/com/liu/liuguild/listener/WarListener.java"));
        assertTrue(listener.contains("onWarCraftClick"));
        assertTrue(listener.contains("onWarCraftDrag"));
        assertTrue(listener.contains("InventoryView"));
        assertTrue(listener.contains("InventoryAction.MOVE_TO_OTHER_INVENTORY"));
        assertTrue(listener.contains("InventoryType.CRAFTING"));
    }
}
