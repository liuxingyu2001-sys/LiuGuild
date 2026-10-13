# 软依赖钩子

所有钩子均通过反射访问，缺失时对应功能自动降级，不影响插件启动。
`/guildadmin status` 会显示每个钩子的可用状态。

## liudungeon（动态竞技场世界）

- 用于按 `arena.yml` 的 `template` 动态创建独立竞技场世界。
- 模板不存在或未安装时回退到 `world` + 坐标的固定场地。
- 结束后自动销毁动态世界。

## LiuChat（跨服公告）

- `broadcastAnnouncement(Player carrier, BaseComponent...)`：工会战公告借其跨服链路广播。
- `deliverPrivate(...)`：私聊投递。
- 仅在主线程调用；未安装时回退到 Bukkit 广播。

## liupass（通行证经验）

- `addXp(UUID, int)` / `addXp(UUID, String passId, int)`：工会战胜利/失败按模式配置发放通行证经验。
- 由 `war-modes.yml` 的 `rewards.win-pass-xp` / `lose-pass-xp` / `pass-id` 控制。

## Sparrow Sync（玩家快照）

- 入战时 `capture(UUID)`（`CompletableFuture`），出战时 `restore(UUID, snapshotId)`。
- 用于在统一装备 / 公平血量后完整还原玩家真实数据。
- 未安装时回退本地内存快照（`KitService.Snapshot`）。

## LuckyPeaches（蟠桃血量屏蔽）

对应版本 **2.3.x**，API：`com.luckypeaches.PeachIntegrationAPI`。

工会战入战（`fair-health: true`）：

```java
PeachIntegrationAPI.setPlayerInBattle(player);            // 死亡豁免
PeachIntegrationAPI.setPeachBonusSuppressed(player, true); // 屏蔽蟠桃血量，保留待恢复值
```

出战时优先使用 **2.3.1+** 的 `restoreSuppressedBonusKeepingHealth(player)`，只恢复属性、
保留真实当前血量（避免把「满血」写库）；旧版本回退 `setPeachBonusSuppressed(player, false)`，
随后 `setPlayerNotInBattle(player)`；玩家离线则 `clearBattleStatus(uuid)`。

参考：`hook/LuckyPeachesHook.java`。需要公平血量的模式若检测不到屏蔽 API，会跳过血量屏蔽
（`supportsSuppress()` 为 false），不会把仅死亡豁免当作屏蔽成功。

## PlayerTitle（称号奖励）

- 工会战胜利时按模式 `rewards.win-title-id` 发放 PlayerTitle 称号：
  `PlayerTitleApi.setPlayerTitle(UUID, int)`。
- `win-title-id: 0` 表示不发放。

## Vault（经济）

- 工会创建费、银行存取、图标更换费、工会战金币奖励。
- 未安装时经济相关操作按 0 处理并给出提示。

## PlaceholderAPI

- 注册标识符 `liuguild`，详见 [placeholders.md](placeholders.md)。

## CraftEngine

- 工会图标与统一装备支持 `CE:namespace:id`，详见 [craftengine.md](craftengine.md)。
