package com.liu.liuguild.war;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 公平血量补偿公式的纯计算测试（不依赖 Bukkit）。
 *
 * <p>背景：入场按“当前最大生命”回满，如果胜方拿到带“生命上限 +20”的胜利称号，
 * 下一场就会比别人多 20 血。补偿 modifier 要能把生效值精确压回公平值；
 * 由于不同版本对百分比/乘法 modifier 的结算顺序不同，斜率由现场实测传入。</p>
 */
class FairHealthMathTest {

    private static final double DELTA = 1.0E-9;

    @Test
    void cancelsFlatHealthBonusFromVictoryTitle() {
        // 当前生效 40（基础 20 + 称号“生命上限 +20”）、斜率 1 → 追加 -20 抵消
        assertEquals(-20.0, FairHealthService.compensation(20.0, 40.0, 1.0), DELTA);
    }

    @Test
    void dividesByMeasuredSlopeForPercentageModifiers() {
        // 其它插件带乘法/百分比 modifier 时，1 点 ADD_NUMBER 的收益也是斜率倍
        assertEquals(-20.0 / 1.1, FairHealthService.compensation(20.0, 40.0, 1.1), DELTA);
        // 公式自洽：追加后正好回到目标值
        assertEquals(20.0, 40.0 + (-20.0 / 1.1) * 1.1, DELTA);
    }

    @Test
    void raisesPlayersWhoseMaxIsBelowTarget() {
        // 目标 20、当前 10 → 追加 +10（公平值对所有人一致，低的也拉到 20）
        assertEquals(10.0, FairHealthService.compensation(20.0, 10.0, 1.0), DELTA);
    }

    @Test
    void noCompensationWhenAlreadyAtTarget() {
        assertEquals(0.0, FairHealthService.compensation(20.0, 20.0, 1.0), DELTA);
    }

    @Test
    void invalidInputsCannotBeCompensated() {
        assertTrue(Double.isNaN(FairHealthService.compensation(20.0, 40.0, 0.0)),
                "斜率为 0 时无法计算补偿，必须退化为夹血而不是写 NaN 进 modifier");
        assertTrue(Double.isNaN(FairHealthService.compensation(Double.NaN, 40.0, 1.0)));
        assertTrue(Double.isNaN(FairHealthService.compensation(20.0, Double.POSITIVE_INFINITY, 1.0)));
    }
}
