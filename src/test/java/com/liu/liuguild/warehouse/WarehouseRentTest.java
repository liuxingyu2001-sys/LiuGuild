package com.liu.liuguild.warehouse;

import com.liu.liuguild.model.Guild;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工会仓库「使用时间」的纯逻辑：到期判定与中文时长格式化。
 *
 * <p>时间/费用相关的边界最容易出错 —— 0 与负数、刚好到期、超长时长都应有确定行为。</p>
 */
class WarehouseRentTest {

    private static final long DAY = 86_400_000L;

    @Test
    void guildWarehouseIsInactiveUntilPurchased() {
        Guild g = new Guild("g");
        long now = 1_000_000L;
        // 默认到期时间 0 = 从未购买 → 未开通
        assertFalse(g.warehouseActive(now));
    }

    @Test
    void guildWarehouseActiveOnlyBeforeExpiry() {
        Guild g = new Guild("g");
        long now = 1_000_000L;
        g.warehouseExpiresAt(now + 1);
        assertTrue(g.warehouseActive(now));
        // 恰好到期（expires == now）视为已过期
        g.warehouseExpiresAt(now);
        assertFalse(g.warehouseActive(now));
        g.warehouseExpiresAt(now - 1);
        assertFalse(g.warehouseActive(now));
    }

    @Test
    void warehouseLevelNeverNegative() {
        Guild g = new Guild("g");
        g.warehouseLevel(3);
        assertEquals(3, g.warehouseLevel());
        g.warehouseLevel(-5);
        assertEquals(0, g.warehouseLevel());
    }

    @Test
    void formatDurationCoversAllBuckets() {
        assertEquals("永久", WarehouseService.formatDuration(Long.MAX_VALUE));
        assertEquals("已过期", WarehouseService.formatDuration(0));
        assertEquals("已过期", WarehouseService.formatDuration(-1));
        assertEquals("不足1分钟", WarehouseService.formatDuration(30_000L));
        assertEquals("1分钟", WarehouseService.formatDuration(60_000L));
        assertEquals("30分钟", WarehouseService.formatDuration(30 * 60_000L));
        assertEquals("1天", WarehouseService.formatDuration(DAY));
        assertEquals("1天2小时", WarehouseService.formatDuration(DAY + 2 * 3_600_000L));
        assertEquals("2天", WarehouseService.formatDuration(2 * DAY + 30_000L));
    }
}
