package com.liu.liuguild.listener;

import com.liu.liuguild.LiuGuild;
import com.liu.liuguild.api.event.*;
import com.liu.liuguild.config.MainSettings;
import com.liu.liuguild.hook.PassHook;
import com.liu.liuguild.hook.PassRouting;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.configuration.ConfigurationSection;
import java.util.List;
import java.util.UUID;

/**
 * 把工会事件推送给 liupass。
 *
 * <p>默认走 Liupass 1.6.3+ 的扩展任务类型（{@code type: liuguild:xxx}）；
 * 同时保留旧版按任务 ID 绑定的 {@code tasks.yml}（可用 {@code hooks.liupass.legacy-task-ids} 关闭）。
 * 两者可同时存在：扩展类型用 {@code liuguild:xxx} 定义任务，旧绑定用同 ID 的 CUSTOM 任务。</p>
 */
public final class GuildTaskListener implements Listener {

    private final LiuGuild plugin;

    public GuildTaskListener(LiuGuild plugin) {
        this.plugin = plugin;
    }

    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    private MainSettings main() {
        return plugin.settings().main;
    }

    /** 上报扩展任务类型进度。 */
    private void emit(List<PassRouting.Progress> progress) {
        if (progress.isEmpty()) {
            return;
        }
        PassHook pass = plugin.pass();
        if (pass == null || !pass.available()) {
            return;
        }
        for (PassRouting.Progress p : progress) {
            pass.emit(p.type(), p.player(), p.target(), p.amount());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(GuildJoinEvent event) {
        emit(PassRouting.join(event.player().getUniqueId(), main()));
        legacy(event.player().getUniqueId(), "JOIN", 1);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCreate(GuildCreateEvent event) {
        emit(PassRouting.create(event.creator().getUniqueId(), main()));
        legacy(event.creator().getUniqueId(), "CREATE", 1);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBank(GuildBankEvent event) {
        if (event.action() != GuildBankEvent.Action.DEPOSIT) {
            return;
        }
        emit(PassRouting.bankDeposit(event.player().getUniqueId(), event.amount(), main()));
        legacy(event.player().getUniqueId(), "BANK_DEPOSIT", event.amount());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCheckin(GuildCheckinEvent event) {
        emit(PassRouting.checkin(event.player().getUniqueId(), main()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onFunds(GuildFundsEvent event) {
        if (event.action() != GuildFundsEvent.Action.DONATE) {
            return;
        }
        emit(PassRouting.donate(event.player().getUniqueId(), event.amount(), main()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWarEnd(GuildWarEndEvent event) {
        if (event.result() == GuildWarEndEvent.Result.CANCELLED) {
            return;
        }
        String mode = event.war().mode();
        for (UUID uuid : event.war().rosterA()) {
            boolean win = event.war().guildA().equals(event.winnerGuildId());
            emit(PassRouting.war(uuid, win, mode, main()));
            legacy(uuid, "WAR_PLAY", 1);
            if (win) {
                legacy(uuid, "WAR_WIN", 1);
            }
        }
        for (UUID uuid : event.war().rosterB()) {
            boolean win = event.war().guildB().equals(event.winnerGuildId());
            emit(PassRouting.war(uuid, win, mode, main()));
            legacy(uuid, "WAR_PLAY", 1);
            if (win) {
                legacy(uuid, "WAR_WIN", 1);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKill(GuildKillEvent event) {
        if (event.killer() == null) {
            return;
        }
        emit(PassRouting.warKill(event.killer().getUniqueId(), event.war().mode(), main()));
    }

    /**
     * 旧版对接：按 {@code tasks.yml} 的 event → task-id 绑定调用 addTaskProgress。
     * {@code hooks.liupass.legacy-task-ids: false} 可完全关闭。
     */
    private void legacy(UUID uuid, String action, long units) {
        if (uuid == null || units <= 0 || !main().passLegacyTaskIds) {
            return;
        }
        PassHook pass = plugin.pass();
        if (pass == null || !pass.available()) {
            return;
        }
        var config = plugin.configManager().file("tasks.yml");
        if (config == null || !config.getBoolean("enabled", true)) {
            return;
        }
        ConfigurationSection bindings = config.getConfigurationSection("bindings");
        if (bindings == null) {
            return;
        }
        for (String key : bindings.getKeys(false)) {
            ConfigurationSection rule = bindings.getConfigurationSection(key);
            if (rule == null || !rule.getBoolean("enabled", true)
                    || !action.equalsIgnoreCase(rule.getString("event", ""))) {
                continue;
            }
            String taskId = rule.getString("task-id", "");
            if (taskId.isBlank()) {
                continue;
            }
            long amount = rule.getBoolean("count-amount", false) ? units : 1;
            pass.addTaskProgress(uuid, rule.getString("pass-id", ""), taskId,
                    (int) Math.min(Integer.MAX_VALUE, amount));
        }
    }
}
