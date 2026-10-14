package com.liu.liuguild.war;

import com.liu.liuguild.config.WarMode;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 工会战结算：分行奖励 / 自定义指令奖励（name + scope + commands）。 */
class WarRewardTest {

    private static WarMode parse(String yaml) {
        YamlConfiguration c = new YamlConfiguration();
        try {
            c.loadFromString(yaml);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return WarMode.parse("match", c, new WarMode());
    }

    @Test
    void parsesNamedCommandRewards() {
        WarMode m = parse("""
                rewards:
                  extra:
                    - name: 金币 x1000
                      commands:
                        - "money give {player} 1000"
                    - name: 3 颗钻石
                      commands:
                        - "/give {player} diamond 3"
                """);
        assertEquals(2, m.extraRewards.size());
        assertEquals("金币 x1000", m.extraRewards.get(0).name);
        assertEquals(1, m.extraRewards.get(0).commands.size());
        assertEquals("money give {player} 1000", m.extraRewards.get(0).commands.get(0));
        assertEquals("both", m.extraRewards.get(0).scope);
        // 前导 / 会被去掉（控制台指令不需要）
        assertEquals("give {player} diamond 3", m.extraRewards.get(1).commands.get(0));
    }

    @Test
    void scopeControlsWhichSideReceives() {
        WarMode m = parse("""
                rewards:
                  extra:
                    - name: 参与奖
                      commands: ["give {player} bread 1"]
                    - name: 胜利礼包
                      scope: win
                      commands: ["give {player} diamond 3"]
                    - name: 安慰奖
                      scope: lose
                      commands: ["give {player} stone 1"]
                """);
        WarMode.CommandReward both = m.extraRewards.get(0);
        WarMode.CommandReward win = m.extraRewards.get(1);
        WarMode.CommandReward lose = m.extraRewards.get(2);

        assertTrue(both.appliesTo(true));
        assertTrue(both.appliesTo(false));

        assertTrue(win.appliesTo(true), "胜方应拿到 scope=win 的奖励");
        assertFalse(win.appliesTo(false), "负方不应拿到 scope=win 的奖励");

        assertFalse(lose.appliesTo(true), "胜方不应拿到 scope=lose 的奖励");
        assertTrue(lose.appliesTo(false), "负方（含平局）应拿到 scope=lose 的奖励");
    }

    @Test
    void rewardWithoutNameFallsBackToFirstCommandAndEmptyCommandsAreSkipped() {
        WarMode m = parse("""
                rewards:
                  extra:
                    - commands: ["say hello {player}"]
                    - name: 无效项
                    - name: 单条字符串也行
                      commands: "give {player} apple 1"
                """);
        assertEquals(2, m.extraRewards.size(), "没有指令的项应被跳过");
        assertEquals("say hello {player}", m.extraRewards.get(0).name, "缺省名称回退为第一条指令");
        assertEquals("give {player} apple 1", m.extraRewards.get(1).commands.get(0),
                "commands 写成单个字符串也要支持");
    }

    @Test
    void copyIsDeepSoModesDoNotShareRewardState() {
        WarMode base = parse("""
                rewards:
                  extra:
                    - name: 金币
                      commands: ["money give {player} 1000"]
                """);
        WarMode copy = base.copy();
        assertTrue(copy.extraRewards != base.extraRewards);
        copy.extraRewards.get(0).commands.add("say extra");
        assertEquals(1, base.extraRewards.get(0).commands.size(),
                "修改副本不应污染原模式（war.defaults 会被多个模式继承）");
    }

    @Test
    void defaultsToEmptyWhenUnset() {
        WarMode m = parse("rewards: {win-player-money: 10.0}");
        assertTrue(m.extraRewards.isEmpty());
    }
}
