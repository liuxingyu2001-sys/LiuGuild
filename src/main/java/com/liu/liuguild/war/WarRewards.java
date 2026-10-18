package com.liu.liuguild.war;

import com.liu.liuguild.LiuGuild;
import com.liu.liuguild.db.GuildRepository;
import com.liu.liuguild.model.Guild;
import com.liu.liuguild.model.GuildWar;
import com.liu.liuguild.config.WarMode;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 工会战奖励：outbox 行构建、玩家/工会发放、失败退回与补发。
 * <p>发放在主线程分帧执行（由 WarManager 的 settleQueue 驱动），outbox 状态写入走异步。</p>
 */
final class WarRewards {

    private final LiuGuild plugin;

    WarRewards(LiuGuild plugin) {
        this.plugin = plugin;
    }

    /** 结算前把玩家奖励整理成 outbox 行（纯内存，不写库）。 */
    List<GuildRepository.WarReward> buildRows(GuildWar war, String winnerId, WarMode mode) {
        if (mode == null) {
            return List.of();
        }
        double mult = mode.scheduled() ? Math.max(1.0, mode.seasonPointsMultiplier) : 1.0;
        List<GuildRepository.WarReward> rows = new ArrayList<>();
        for (GuildWar.Party party : war.parties()) {
            boolean won = winnerId != null && winnerId.equals(party.guildId());
            for (UUID u : new ArrayList<>(party.roster())) {
                rows.add(new GuildRepository.WarReward(war.id(), u, party.guildId(), won,
                        mode.key, (won ? mode.winPlayerMoney : mode.losePlayerMoney) * mult,
                        (int) Math.min(Integer.MAX_VALUE, Math.round((won ? mode.winPassXp : mode.losePassXp) * mult)),
                        won ? mode.winTitleId : 0));
            }
        }
        return rows;
    }

    /**
     * 发放工会资金奖励（受该模式每日上限约束）。
     *
     * @return 实际（预计）发放的点数，用于结算消息展示
     */
    long grantGuild(Guild guild, boolean won, boolean draw, WarMode mode, double multiplier) {
        if (guild == null) {
            return 0;
        }
        long nominal = wonGuildFunds(won, mode, multiplier);
        long granted = plugin.guilds().grantWarFunds(guild, nominal, mode.dailyGuildFundsCap);
        // 平局不计入胜/负，避免双方损失数被平局虚增；胜负记录只在有明确胜者时更新。
        if (!draw) {
            if (won) {
                guild.wins(guild.wins() + 1);
            } else {
                guild.losses(guild.losses() + 1);
            }
        }
        plugin.guilds().saveGuild(guild);
        return granted;
    }

    /**
     * 发放单个玩家的已领取奖励（结算与补发共用）。
     * <p>失败会把 outbox 行退回 PENDING 等下一轮重试；成功才标记 DONE。</p>
     */
    void grantPlayer(GuildWar war, GuildRepository.WarReward row, WarMode mode,
                     long guildFundsGranted, long guildFundsNominal) {
        UUID uuid = row.player();
        boolean win = row.win();
        double money = row.money();
        int passXp = row.passXp();
        int titleId = row.titleId();
        boolean hasTitle = titleId > 0 && plugin.playerTitle() != null;

        if (!pay(row, mode)) {
            return;
        }
        markDone(row);

        Player player = plugin.player(uuid);
        if (player == null) {
            return;
        }
        // 分行展示：只列出「实际有值」的奖励，没有的项不占一行
        plugin.messages().sendRaw(player, win ? "war-reward-win-header" : "war-reward-lose-header");
        if (money > 0) {
            plugin.messages().sendRaw(player, "war-reward-line-money",
                    "money", plugin.economy().format(money));
        }
        if (guildFundsGranted > 0) {
            plugin.messages().sendRaw(player, "war-reward-line-funds", "funds", String.valueOf(guildFundsGranted));
        } else if (guildFundsNominal > 0) {
            plugin.messages().sendRaw(player, "war-reward-line-funds-capped",
                    "cap", String.valueOf(mode.dailyGuildFundsCap));
        }
        if (passXp > 0) {
            plugin.messages().sendRaw(player, "war-reward-line-pass",
                    "pass_xp", String.valueOf(passXp),
                    "pass", mode.passId == null || mode.passId.isBlank() ? "通行证" : mode.passId);
        }
        if (hasTitle) {
            plugin.messages().sendRaw(player, "war-reward-line-title", "title", String.valueOf(titleId));
        }
        runCommands(player, win, mode, war);
        plugin.messages().sendRaw(player, "war-reward-footer");
    }

    /** 补发已领取的历史奖励（不发消息、不执行指令奖励）。 */
    void grantRecovered(GuildRepository.WarReward row, WarMode mode) {
        if (pay(row, mode)) {
            markDone(row);
        }
    }

    /** @return true = 已成功发放；false = 已退回 PENDING/FAILED 等重试 */
    private boolean pay(GuildRepository.WarReward row, WarMode mode) {
        try {
            if (row.money() > 0 && plugin.economy() != null && plugin.economy().available()) {
                plugin.economy().deposit(plugin.offline(row.player()), row.money());
            }
            if (plugin.pass() != null && row.passXp() > 0) {
                plugin.pass().addXp(row.player(), mode.passId, row.passXp());
            }
            if (row.titleId() > 0 && plugin.playerTitle() != null) {
                plugin.playerTitle().grant(row.player(), row.titleId());
            }
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("工会战玩家奖励发放失败（保留 outbox 待重试）: " + t);
            release(row);
            return false;
        }
    }

    void markDone(GuildRepository.WarReward row) {
        plugin.async(() -> {
            try {
                plugin.repo().markWarRewardDone(row.warId(), row.player());
            } catch (Exception e) {
                plugin.getLogger().warning("标记工会战奖励完成失败: " + e.getMessage());
            }
        });
    }

    /** 发放失败时把行退回 PENDING（超过次数上限则 FAILED，等待人工）。 */
    private void release(GuildRepository.WarReward row) {
        plugin.async(() -> {
            try {
                plugin.repo().releaseWarReward(row.warId(), row.player());
            } catch (Exception e) {
                plugin.getLogger().warning("回退工会战奖励状态失败: " + e.getMessage());
            }
        });
    }

    /**
     * 执行自定义指令奖励，并把每条指令的「自定义名称」分行展示给玩家。
     * <p>{@code rewards.commands} 双方执行；{@code rewards.win-commands} 仅胜方；
     * {@code rewards.lose-commands} 仅负方（含平局）。</p>
     */
    private void runCommands(Player player, boolean win, WarMode mode, GuildWar war) {
        if (mode.extraRewards.isEmpty()) {
            return;
        }
        Guild guild = plugin.guilds().guildOf(player.getUniqueId());
        for (WarMode.CommandReward reward : mode.extraRewards) {
            if (!reward.appliesTo(win)) {
                continue;
            }
            boolean executed = false;
            for (String raw : reward.commands) {
                String cmd = applyPlaceholders(raw, player, guild, mode, war);
                if (cmd.isBlank()) {
                    continue;
                }
                try {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                    executed = true;
                } catch (Throwable t) {
                    plugin.getLogger().warning("工会战指令奖励执行失败 [" + cmd + "]: " + t.getMessage());
                }
            }
            // 名称就是奖励展示文字；执行过的奖励才分行显示
            if (executed) {
                plugin.messages().sendRaw(player, "war-reward-line-command", "label", reward.name);
            }
        }
    }

    /** 替换指令奖励里的占位符。 */
    private String applyPlaceholders(String cmd, Player player, Guild guild, WarMode mode, GuildWar war) {
        String side = war == null || guild == null ? null : war.sideOfGuild(guild.id());
        int mine = war == null || side == null ? 0 : war.scoreOfSide(side);
        int theirs = war == null ? 0 : war.parties().stream()
                .filter(party -> guild == null || !party.guildId().equals(guild.id()))
                .mapToInt(GuildWar.Party::score).max().orElse(0);
        return cmd.replace("{player}", player.getName())
                .replace("{uuid}", player.getUniqueId().toString())
                .replace("{guild}", guild == null ? "" : guild.name())
                .replace("{guild_id}", guild == null ? "" : guild.id())
                .replace("{mode}", mode.key)
                .replace("{score}", String.valueOf(mine))
                .replace("{enemy_score}", String.valueOf(theirs));
    }

    long wonGuildFunds(boolean win, WarMode mode, double multiplier) {
        return Math.round((win ? mode.winGuildFunds : mode.loseGuildFunds) * multiplier);
    }
}
