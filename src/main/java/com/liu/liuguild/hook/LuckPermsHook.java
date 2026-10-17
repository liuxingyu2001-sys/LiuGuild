package com.liu.liuguild.hook;

import com.liu.liuguild.LiuGuild;
import com.liu.liuguild.model.Guild;
import com.liu.liuguild.model.GuildMember;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.context.ContextCalculator;
import net.luckperms.api.context.ContextConsumer;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * LuckPerms 上下文集成：给玩家提供 {@code liuguild:*} 上下文。
 *
 * <p>LuckPerms 缺失时整个类不会被实例化；计算器只在主线程读取内存缓存，
 * 不产生数据库/Redis IO。</p>
 *
 * <ul>
 *   <li>{@code liuguild:guild_id} 工会 UUID</li>
 *   <li>{@code liuguild:guild} 工会名称</li>
 *   <li>{@code liuguild:guild_rank} member / officer / leader</li>
 *   <li>{@code liuguild:in_war} 工会战或赛季混战参赛中</li>
 *   <li>{@code liuguild:spectator} 工会战旁观中</li>
 * </ul>
 */
public final class LuckPermsHook {

    private final LiuGuild plugin;
    private Object calculator;
    private boolean registered;

    public LuckPermsHook(LiuGuild plugin) {
        this.plugin = plugin;
    }

    public boolean register() {
        try {
            LuckPerms luckPerms = LuckPermsProvider.get();
            Calculator calc = new Calculator(plugin);
            luckPerms.getContextManager().registerCalculator(calc);
            this.calculator = calc;
            this.registered = true;
            plugin.getLogger().info("LuckPerms 上下文已注册（liuguild:guild_id / guild_rank / in_war / spectator）");
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("LuckPerms 上下文注册失败，已跳过: " + t.getMessage());
            return false;
        }
    }

    public void unregister() {
        if (!registered || calculator == null) {
            return;
        }
        try {
            LuckPermsProvider.get().getContextManager()
                    .unregisterCalculator((ContextCalculator<?>) calculator);
        } catch (Throwable ignored) {
            // 关服/重载期间 LuckPerms 可能已先卸载。
        }
        registered = false;
        calculator = null;
    }

    /** 战斗/工会身份变化后让 LuckPerms 立即重算该玩家上下文。 */
    public void refresh(Player player) {
        if (!registered || player == null) {
            return;
        }
        try {
            LuckPermsProvider.get().getContextManager().signalContextUpdate(player);
        } catch (Throwable ignored) {
            // 上下文刷新失败不能影响主流程。
        }
    }

    private static final class Calculator implements ContextCalculator<Player> {
        private final LiuGuild plugin;

        private Calculator(LiuGuild plugin) {
            this.plugin = plugin;
        }

        @Override
        public void calculate(Player target, ContextConsumer consumer) {
            UUID uuid = target.getUniqueId();
            Guild guild = plugin.guilds() == null ? null : plugin.guilds().guildOf(uuid);
            if (guild != null) {
                consumer.accept("liuguild:guild_id", guild.id());
                consumer.accept("liuguild:guild", guild.name());
                GuildMember member = guild.member(uuid);
                if (member != null) {
                    consumer.accept("liuguild:guild_rank",
                            member.rank().name().toLowerCase(java.util.Locale.ROOT));
                }
            }
            if (plugin.war() != null) {
                if (plugin.war().isFighting(uuid)) {
                    consumer.accept("liuguild:in_war", "true");
                }
                if (plugin.war().isSpectator(uuid)) {
                    consumer.accept("liuguild:spectator", "true");
                }
            }
        }
    }
}
