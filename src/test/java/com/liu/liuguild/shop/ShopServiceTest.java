package com.liu.liuguild.shop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工会商店的金额计算。
 *
 * <p>这两块是纯函数，也是**资金安全边界**：算出负数就等于给玩家发钱，溢出回绕同理。
 * 库存/背包相关的逻辑需要运行中的服务器，由实机验证。</p>
 */
class ShopServiceTest {

    // =====================================================================
    // 安全乘法
    // =====================================================================

    @Test
    void multipliesNormally() {
        assertEquals(0, ShopService.safeMul(0, 100));
        assertEquals(0, ShopService.safeMul(100, 0));
        assertEquals(100, ShopService.safeMul(100, 1));
        assertEquals(6400, ShopService.safeMul(100, 64));
        assertEquals(230_400, ShopService.safeMul(100, 2304));
    }

    @Test
    void neverWrapsToNegativeOnOverflow() {
        // 溢出必须封顶，绝不能回绕成负数（负数 = 反过来给玩家发钱）
        long big = Long.MAX_VALUE;
        assertEquals(Long.MAX_VALUE, ShopService.safeMul(big, 2));
        assertEquals(Long.MAX_VALUE, ShopService.safeMul(big, big));
        assertTrue(ShopService.safeMul(Long.MAX_VALUE / 2 + 1, 2) > 0);
        assertTrue(ShopService.safeMul(1_000_000_000L, 1_000_000_000L) > 0);
    }

    @Test
    void handlesNegativeInputsWithoutSurprises() {
        // 价格负值在入口就被拒了，但乘法本身也不该崩
        assertEquals(-100, ShopService.safeMul(-100, 1));
        assertEquals(0, ShopService.safeMul(-100, 0));
    }

    // =====================================================================
    // 交易税
    // =====================================================================

    @Test
    void zeroTaxKeepsEverything() {
        assertEquals(10_000, ShopService.taxOf(10_000, 0));
        assertEquals(10_000, ShopService.taxOf(10_000, -5));
    }

    @Test
    void taxIsTakenOffTheTop() {
        assertEquals(9_500, ShopService.taxOf(10_000, 5));
        assertEquals(9_000, ShopService.taxOf(10_000, 10));
        assertEquals(5_000, ShopService.taxOf(10_000, 50));
    }

    @Test
    void hundredPercentTaxLeavesNothingForTheGuild() {
        assertEquals(0, ShopService.taxOf(10_000, 100));
        // 超过 100% 也不会变成负数
        assertEquals(0, ShopService.taxOf(10_000, 500));
    }

    @Test
    void taxNeverExceedsTheOriginalAmount() {
        // 任何配置都不能让「工会实收」超过「买家实付」
        long[] amounts = {0, 1, 7, 99, 1000, 123_456_789, Long.MAX_VALUE};
        double[] pcts = {-10, 0, 0.5, 5, 33.3, 99.9, 100, 250};
        for (long amount : amounts) {
            for (double pct : pcts) {
                long net = ShopService.taxOf(amount, pct);
                assertTrue(net >= 0, "抽税后不能为负: amount=" + amount + " pct=" + pct + " -> " + net);
                assertTrue(net <= Math.max(0, amount),
                        "抽税后不能超过原额: amount=" + amount + " pct=" + pct + " -> " + net);
            }
        }
    }

    @Test
    void zeroOrNegativeAmountYieldsZero() {
        assertEquals(0, ShopService.taxOf(0, 5));
        assertEquals(0, ShopService.taxOf(-100, 5));
    }

    @Test
    void roundingIsAtMostOneUnitOff() {
        // 1 块钱抽 5% → 四舍五入到 1 或 0，不能算出别的
        long net = ShopService.taxOf(1, 5);
        assertTrue(net == 0 || net == 1, "1 元抽 5% 结果应为 0 或 1，实际=" + net);
    }

    @Test
    void shopIsVisibleOnlyWithinTheSameGuild() {
        // 工会隔离：同会可见，跨会 / 空值一律不可见
        assertTrue(ShopService.shopVisibleTo("guild-a", "guild-a"));
        assertTrue(!ShopService.shopVisibleTo("guild-a", "guild-b"));
        assertTrue(!ShopService.shopVisibleTo(null, "guild-a"));
        assertTrue(!ShopService.shopVisibleTo("guild-a", null));
    }
}
