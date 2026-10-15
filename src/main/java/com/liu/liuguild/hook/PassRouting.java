package com.liu.liuguild.hook;

import com.liu.liuguild.config.MainSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 纯函数：把工会事件映射成 Liupass「扩展任务类型」进度。
 *
 * <p>LiuGuild 通过 {@code PassAPI.registerTaskType} 注册下列 {@code namespace:type}，
 * 服务器只需在 Liupass 任务里写 {@code type: liuguild:xxx} 与可选 {@code target}，
 * 不再需要创建同 ID 的 CUSTOM 任务。本类不做任何 IO，也不依赖 Bukkit，便于单测。</p>
 */
public final class PassRouting {

    public static final String TYPE_JOIN = "liuguild:join";
    public static final String TYPE_CREATE = "liuguild:create";
    public static final String TYPE_WAR = "liuguild:war";
    public static final String TYPE_WAR_WIN = "liuguild:war-win";
    public static final String TYPE_WAR_KILL = "liuguild:war-kill";
    public static final String TYPE_CHECKIN = "liuguild:checkin";
    public static final String TYPE_DONATE = "liuguild:donate";
    public static final String TYPE_BANK_DEPOSIT = "liuguild:bank-deposit";

    /** 需要向 Liupass 注册的全部任务类型。 */
    public static final List<String> ALL_TYPES = List.of(
            TYPE_JOIN, TYPE_CREATE, TYPE_WAR, TYPE_WAR_WIN, TYPE_WAR_KILL,
            TYPE_CHECKIN, TYPE_DONATE, TYPE_BANK_DEPOSIT);

    /** 一次任务进度上报：target 为 null 表示该任务不应配置 target。 */
    public record Progress(UUID player, String type, String target, int amount) {}

    private PassRouting() {
    }

    public static List<Progress> join(UUID uuid, MainSettings s) {
        return one(uuid, s.passEnabled && s.passJoin, TYPE_JOIN, 1);
    }

    public static List<Progress> create(UUID uuid, MainSettings s) {
        return one(uuid, s.passEnabled && s.passCreate, TYPE_CREATE, 1);
    }

    /** 参与工会战；胜利时额外推进 war-win。target 为模式 ID（无 target 的任务同样匹配）。 */
    public static List<Progress> war(UUID uuid, boolean win, String mode, MainSettings s) {
        if (uuid == null) {
            return List.of();
        }
        List<Progress> out = new ArrayList<>(2);
        if (s.passEnabled && s.passWar) {
            out.add(new Progress(uuid, TYPE_WAR, mode, 1));
        }
        if (win && s.passEnabled && s.passWarWin) {
            out.add(new Progress(uuid, TYPE_WAR_WIN, mode, 1));
        }
        return out;
    }

    public static List<Progress> warKill(UUID uuid, String mode, MainSettings s) {
        return one(uuid, s.passEnabled && s.passWarKill, TYPE_WAR_KILL, 1);
    }

    public static List<Progress> checkin(UUID uuid, MainSettings s) {
        return one(uuid, s.passEnabled && s.passCheckin, TYPE_CHECKIN, 1);
    }

    /** 捐献个人工会资金，进度为金额。 */
    public static List<Progress> donate(UUID uuid, long amount, MainSettings s) {
        return amount(uuid, s.passEnabled && s.passDonate, TYPE_DONATE, amount);
    }

    /** 向工会银行存入金币，进度为金额。 */
    public static List<Progress> bankDeposit(UUID uuid, long amount, MainSettings s) {
        return amount(uuid, s.passEnabled && s.passBankDeposit, TYPE_BANK_DEPOSIT, amount);
    }

    /** 进度金额上限：超过 int 时截断，避免反射调用溢出。 */
    public static int clamp(long amount) {
        if (amount <= 0) {
            return 0;
        }
        return (int) Math.min(Integer.MAX_VALUE, amount);
    }

    private static List<Progress> one(UUID uuid, boolean enabled, String type, int amount) {
        if (uuid == null || !enabled || amount <= 0) {
            return List.of();
        }
        return List.of(new Progress(uuid, type, null, amount));
    }

    private static List<Progress> amount(UUID uuid, boolean enabled, String type, long amount) {
        return one(uuid, enabled, type, clamp(amount));
    }
}
