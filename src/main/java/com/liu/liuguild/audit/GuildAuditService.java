package com.liu.liuguild.audit;

import com.liu.liuguild.LiuGuild;
import com.liu.liuguild.db.AuditEntry;
import com.liu.liuguild.db.GuildAuditRepository;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 工会审计日志：业务成功后异步写入，查询时再回主线程。
 *
 * <p>审计写入失败只记 warning，绝不影响银行/商店/仓库主流程。</p>
 */
public final class GuildAuditService {

    private final LiuGuild plugin;
    private final GuildAuditRepository repo;

    public GuildAuditService(LiuGuild plugin) {
        this.plugin = plugin;
        this.repo = new GuildAuditRepository(plugin.db());
    }

    /** 记录一条审计；guildId 为空时忽略。 */
    public void record(String guildId, UUID actorUuid, String actorName,
                       String action, String target, long amount, String detail) {
        if (guildId == null || guildId.isBlank() || action == null || action.isBlank()) {
            return;
        }
        AuditEntry entry = new AuditEntry(UUID.randomUUID().toString(), guildId, actorUuid,
                trim(actorName, 32), action, trim(target, 128), amount, trim(detail, 255),
                System.currentTimeMillis());
        plugin.async(() -> {
            try {
                repo.insert(entry);
            } catch (Exception e) {
                plugin.getLogger().warning("写入工会审计失败: " + e.getMessage());
            }
        });
    }

    public void recentAsync(String guildId, int limit, Consumer<List<AuditEntry>> callback) {
        plugin.async(() -> {
            List<AuditEntry> list;
            try {
                list = repo.recent(guildId, limit);
            } catch (Exception e) {
                plugin.getLogger().warning("读取工会审计失败: " + e.getMessage());
                list = List.of();
            }
            final List<AuditEntry> result = list;
            plugin.sync(() -> callback.accept(result));
        });
    }

    public void recentAllAsync(int limit, Consumer<List<AuditEntry>> callback) {
        plugin.async(() -> {
            List<AuditEntry> list;
            try {
                list = repo.recentAll(limit);
            } catch (Exception e) {
                plugin.getLogger().warning("读取全部审计失败: " + e.getMessage());
                list = List.of();
            }
            final List<AuditEntry> result = list;
            plugin.sync(() -> callback.accept(result));
        });
    }

    public void purgeAsync(long beforeMillis) {
        plugin.async(() -> {
            try {
                int removed = repo.purgeBefore(beforeMillis);
                if (removed > 0) {
                    plugin.getLogger().info("已清理过期工会审计 " + removed + " 条");
                }
            } catch (Exception e) {
                plugin.getLogger().warning("清理工会审计失败: " + e.getMessage());
            }
        });
    }

    /** 审计动作的中文显示；未知动作原样返回。 */
    public static String displayAction(String action) {
        if (action == null) {
            return "";
        }
        return switch (action) {
            case "bank.deposit" -> "存入银行";
            case "bank.withdraw" -> "取出银行";
            case "shop.buy" -> "购买商品";
            case "shop.list" -> "上架商品";
            case "shop.remove" -> "下架商品";
            case "warehouse.deposit" -> "仓库存入";
            case "warehouse.withdraw" -> "仓库取出";
            case "admin.disband" -> "管理员解散";
            case "admin.setlevel" -> "管理员改等级";
            case "admin.setpoints" -> "管理员改资金";
            case "admin.setbank" -> "管理员改银行";
            case "admin.rename" -> "管理员改名";
            case "admin.purge.points" -> "管理员清空资金";
            case "admin.purge.bank" -> "管理员清空银行";
            case "admin.purge.activity" -> "管理员清空活跃度";
            case "admin.purge.warehouse" -> "管理员清空仓库租期";
            case "admin.purge.shop" -> "管理员清空商店";
            case "admin.purge.stats" -> "管理员清空战绩";
            case "guild.rename" -> "工会改名";
            default -> action;
        };
    }

    private static String trim(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
