package com.liu.liuguild.listener;

import com.liu.liuguild.LiuGuild;
import com.liu.liuguild.api.event.*;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.configuration.ConfigurationSection;
import java.util.UUID;

/** Each binding targets a separately configured liupass CUSTOM task. */
public final class GuildTaskListener implements Listener {
    private final LiuGuild plugin;
    public GuildTaskListener(LiuGuild plugin) { this.plugin = plugin; }
    public void start() { Bukkit.getPluginManager().registerEvents(this, plugin); }

    private void progress(UUID uuid, String action, long units) {
        if (units <= 0 || plugin.pass() == null || !plugin.pass().available()) return;
        var config = plugin.configManager().file("tasks.yml");
        if (config == null || !config.getBoolean("enabled", true)) return;
        ConfigurationSection bindings = config.getConfigurationSection("bindings");
        if (bindings == null) return;
        for (String key : bindings.getKeys(false)) {
            ConfigurationSection rule = bindings.getConfigurationSection(key);
            if (rule == null || !rule.getBoolean("enabled", true)
                    || !action.equalsIgnoreCase(rule.getString("event", ""))) continue;
            String taskId = rule.getString("task-id", "");
            if (taskId.isBlank()) continue;
            long amount = rule.getBoolean("count-amount", false) ? units : 1;
            plugin.pass().addTaskProgress(uuid, rule.getString("pass-id", ""), taskId,
                    (int) Math.min(Integer.MAX_VALUE, amount));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(GuildJoinEvent event) { progress(event.player().getUniqueId(), "JOIN", 1); }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onCreate(GuildCreateEvent event) { progress(event.creator().getUniqueId(), "CREATE", 1); }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onBank(GuildBankEvent event) {
        if (event.action() == GuildBankEvent.Action.DEPOSIT) progress(event.player().getUniqueId(), "BANK_DEPOSIT", event.amount());
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWarEnd(GuildWarEndEvent event) {
        if (event.result() == GuildWarEndEvent.Result.CANCELLED) return;
        for (UUID uuid : event.war().rosterA()) {
            progress(uuid, "WAR_PLAY", 1);
            if (event.war().guildA().equals(event.winnerGuildId())) progress(uuid, "WAR_WIN", 1);
        }
        for (UUID uuid : event.war().rosterB()) {
            progress(uuid, "WAR_PLAY", 1);
            if (event.war().guildB().equals(event.winnerGuildId())) progress(uuid, "WAR_WIN", 1);
        }
    }
}
