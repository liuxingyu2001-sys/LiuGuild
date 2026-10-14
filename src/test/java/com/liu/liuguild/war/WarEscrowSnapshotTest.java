package com.liu.liuguild.war;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link KitService.PersistedEscrow#toSnapshot()} 的防御性行为。
 *
 * <p>这是崩溃后归还装备的最后一道防线，最危险的失败模式不是「还原失败」，而是
 * 「把损坏/空的物品数据当成一个全 null 的合法快照」——那会在恢复时直接把玩家背包清空。
 * 因此任何不完整的物品数据都必须返回 {@code null}（调用方跳过还原），绝不能返回快照。</p>
 */
class WarEscrowSnapshotTest {

    private static KitService.PersistedEscrow escrow(String itemsData) {
        return new KitService.PersistedEscrow(
                UUID.randomUUID(), "server", "war", null, itemsData,
                "", 0, 0, 0, 0f, 0f, "SURVIVAL", 20, 20, 0, 0f, 0L);
    }

    @Test
    void emptyItemsDataYieldsNoSnapshot() {
        assertNull(escrow("").toSnapshot());
        assertNull(escrow("   ").toSnapshot());
        assertNull(escrow(null).toSnapshot());
    }

    @Test
    void garbageItemsDataYieldsNoSnapshot() {
        assertNull(escrow("这不是 base64!!").toSnapshot());
        assertNull(escrow("YWJjZGVmZ2hpamtsbW5vcA==").toSnapshot());
        // 头部像 V2 magic 但内容被截断
        assertNull(escrow("TEtWMg==").toSnapshot());
    }

    @Test
    void partialItemDataIsRejected() {
        // 即便能解出物品，只要不足 41 槽（36 背包 + 4 护甲 + 1 副手）就视为损坏
        assertNull(escrow("AAAAAAAAAAA=").toSnapshot());
    }

    @Test
    void escrowRecordExposesItsFields() {
        KitService.PersistedEscrow e = escrow("");
        assertNotNull(e.playerUuid());
        assertEquals("server", e.serverId());
        assertEquals("war", e.warId());
        assertEquals("", e.itemsData());
    }
}
