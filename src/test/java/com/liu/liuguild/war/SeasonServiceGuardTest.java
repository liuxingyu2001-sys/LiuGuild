package com.liu.liuguild.war;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 赛季公平血量与匹配广播的接线守卫（Bukkit 行为本身需要真实服务器验证）。 */
class SeasonServiceGuardTest {

    @Test
    void seasonFairHealthNormalizesMaxHealthBeforeKitApply() throws Exception {
        String season = Files.readString(Path.of("src/main/java/com/liu/liuguild/war/SeasonService.java"));
        assertTrue(season.contains("fairHealth().beginFight(player,mode)"), "赛季入场必须走公平血量归一（含蟠桃屏蔽）");
        assertTrue(season.contains("clearFairHealth(p,entry.player())"), "离场必须先恢复公平血量/蟠桃");
        assertTrue(season.contains("fairHealth().endFight"), "赛季还原时必须解除公平血量补偿");
        assertTrue(season.contains("fairHealth().tick(p,mode)"), "赛季每秒 tick 必须兜底归一");
        int enter = season.indexOf("applyFairHealth(player);");
        int kit = season.indexOf("plugin.kits().apply(player,mode);");
        assertTrue(enter >= 0 && kit > enter, "公平血量必须在发放套装/回满血量之前生效");
    }

    @Test
    void matchWarNormalizesMaxHealthAndReleasesItOnExit() throws Exception {
        String war = Files.readString(Path.of("src/main/java/com/liu/liuguild/war/WarManager.java"));
        // 每秒兜底：称号/属性插件会把加成加回来，不能只在入场归一一次
        assertTrue(war.contains("enforceFairHealth(war);"));
        // 离场/退服统一收尾，并且必须在按快照还原血量之前解除补偿
        assertTrue(war.contains("releaseFightHealth(p, uuid);"));
        assertTrue(war.contains("releaseFightHealth(player, uuid);"));
        assertTrue(war.contains("fairHealth().endFight"));
        assertTrue(war.contains("fairHealth().endFightOffline"));

        String kit = Files.readString(Path.of("src/main/java/com/liu/liuguild/war/KitService.java"));
        assertTrue(kit.contains("fairHealth().beginFight(player, mode)"), "所有发套装/复活路径都要先归一最大生命");
        assertTrue(kit.contains("fairHealth().target(mode)"), "公平模式回满必须用公平值");
        assertFalse(kit.contains("setHealthSafely(player, com.liu.liuguild.util.ApiCompat.maxHealth(player))"),
                "不能再按“当前最大生命”回满：那会把胜利称号/第三方属性加成带进战斗");
    }

    @Test
    void seasonHonorsFreezeConfigAndRegistrationWindow() throws Exception {
        String season = Files.readString(Path.of("src/main/java/com/liu/liuguild/war/SeasonService.java"));
        assertTrue(season.contains("return mode == null || mode.freezeOnPrepare;"));
        assertTrue(season.contains("requested.seasonRegistrationSeconds"));
    }

    @Test
    void transientRegistrationWorldFallsBackToDefaultSpawn() throws Exception {
        String season = Files.readString(Path.of("src/main/java/com/liu/liuguild/war/SeasonService.java"));
        assertTrue(season.contains("WorldResolver.isTransientWorld(loc.getWorld().getName())"));
        assertTrue(season.contains("WorldResolver.defaultSpawn()"));
    }

    @Test
    void failedReturnUsesDefaultSpawnAndStopsPollingForever() throws Exception {
        String season = Files.readString(Path.of("src/main/java/com/liu/liuguild/war/SeasonService.java"));
        assertTrue(season.contains("private Location returnDestination"));
        assertTrue(season.contains("WorldResolver.isTransientWorld(entry.world())"));
        assertTrue(season.contains("noticeReturnFailed"));
        assertTrue(season.contains("noticeReturnFallback"));
        assertTrue(season.contains("returnNeedsFallback"));
        assertTrue(season.contains("returnNoticeAt"));
        assertTrue(season.contains("60_000L"));
        assertFalse(season.contains("赛季返程世界未加载："));
    }

    @Test
    void queueAnnouncementReachesOtherServersOnce() throws Exception {
        String war = Files.readString(Path.of("src/main/java/com/liu/liuguild/war/WarManager.java"));
        assertTrue(war.contains("publish(\"WAR_QUEUE_ANNOUNCE\""));
        assertTrue(war.contains("\"WAR_QUEUE_ANNOUNCE\".equals(type)"));
        assertTrue(war.contains("plugin.serverId().equals(data.get(\"server\"))"));
        assertTrue(war.contains("plugin.sync(() -> plugin.guilds().broadcastAll(text))"));
    }

    @Test
    void exhaustedTeamEndsEarlyInsteadOfWaitingForMatchEnd() throws Exception {
        String season = Files.readString(Path.of("src/main/java/com/liu/liuguild/war/SeasonService.java"));
        // 复活次数用尽 → 提前结束该队赛程，而不是留在场地旁观到整场结束
        assertTrue(season.contains("private void handleEliminatedTeams"));
        assertTrue(season.contains("已无复活次数，提前结束本队赛程"));
        assertTrue(season.contains("eliminateTeamMember"));
        // 死亡、断线与每秒轮询三条路径都要触发
        assertTrue(season.contains("handleEliminatedTeams();"));
        String repo = Files.readString(Path.of("src/main/java/com/liu/liuguild/war/SeasonRepository.java"));
        assertTrue(repo.contains("e.state='LEFT'"), "提前离场的 LEFT 记录必须进入返程队列");
        String battle = Files.readString(Path.of("src/main/java/com/liu/liuguild/war/SeasonBattle.java"));
        assertTrue(battle.contains("public boolean teamEliminated"));
    }

    @Test
    void returnWaitInvulnerabilityAlwaysHasBoundedCleanup() throws Exception {
        String season = Files.readString(Path.of("src/main/java/com/liu/liuguild/war/SeasonService.java"));
        // 返程等待期的临时无敌必须登记兜底期限，并在每秒 tick 中强制到期解除。
        assertTrue(season.contains("private void protectUntilReturn(Player p)"));
        assertTrue(season.contains("private void notePendingInvulnerability(Player p)"));
        assertTrue(season.contains("private void releaseInvulnerability(Player p)"));
        assertTrue(season.contains("private void sweepInvulnerability(long now)"));
        assertTrue(season.contains("INVULNERABLE_TIMEOUT_MS"));
        assertTrue(season.contains("sweepInvulnerability(nowMs);"));
        // 重启前遗留的无敌标记也要登记期限，不能只在本次赛程内记录。
        assertTrue(season.contains("notePendingInvulnerability(p);"));
        // 返程成功、退服、关服都要解除，否则标记会随玩家存档落库。
        assertTrue(season.contains("releaseInvulnerability(p);"));
        assertTrue(season.contains("boolean seasonProtected=returnProtections.remove(quitting)!=null;"));
        assertTrue(season.contains("returnProtections.clear();"));
        // 旧实现只把“恢复托管后解除”当作兜底，返程失败就会永久无敌；不能再出现这种裸调用写法。
        assertFalse(season.contains("plugin.syncDelayed(() -> { if(p.isOnline() && !restricted(entry.player())) p.setInvulnerable(false); },20);"));
    }
}
