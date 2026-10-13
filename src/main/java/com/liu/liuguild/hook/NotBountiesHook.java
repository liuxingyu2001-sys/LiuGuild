package com.liu.liuguild.hook;

import com.liu.liuguild.LiuGuild;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventException;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;

/** Optional NotBounties integration: suppress bounty payouts for controlled guild-war kills. */
public final class NotBountiesHook implements Listener {

    private final LiuGuild plugin;
    private volatile boolean available;

    public NotBountiesHook(LiuGuild plugin) {
        this.plugin = plugin;
        Plugin dependency = Bukkit.getPluginManager().getPlugin("NotBounties");
        if (dependency == null) {
            return;
        }
        try {
            Class<?> raw = dependency.getClass().getClassLoader()
                    .loadClass("me.jadenp.notbounties.bounty_events.BountyClaimEvent");
            if (!Event.class.isAssignableFrom(raw)) {
                return;
            }
            @SuppressWarnings("unchecked")
            Class<? extends Event> eventClass = (Class<? extends Event>) raw;
            Method getter = eventClass.getMethod("getKiller");
            Method cancel = eventClass.getMethod("setCancelled", boolean.class);
            EventExecutor executor = (listener, event) -> handle(event, getter, cancel);
            Bukkit.getPluginManager().registerEvent(eventClass, this, EventPriority.HIGH,
                    executor, plugin, true);
            available = true;
        } catch (Throwable t) {
            plugin.getLogger().warning("NotBounties 工会战悬赏保护不可用: " + t.getMessage());
        }
    }

    public boolean available() {
        return available;
    }

    private void handle(Event event, Method getter, Method cancel) throws EventException {
        try {
            Object value = getter.invoke(event);
            if (!(value instanceof Player killer) || !plugin.war().isInWar(killer.getUniqueId())) {
                return;
            }
            cancel.invoke(event, true);
            plugin.messages().send(killer, "war-bounty-blocked");
        } catch (ReflectiveOperationException e) {
            throw new EventException(e);
        }
    }
}
