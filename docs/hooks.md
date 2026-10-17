# 软依赖钩子

所有钩子均通过反射/软依赖访问，缺失时对应功能自动降级，不影响插件启动。
`/guildadmin status` 会显示每个钩子的可用状态。

## PacketEvents（包级拦截，工会战准备阶段锁定）

用于在**网络包层**锁定准备阶段的参赛者，开销低：

- 移动/转视角包（`PLAYER_POSITION` / `PLAYER_POSITION_AND_ROTATION` / `PLAYER_ROTATION` / `PLAYER_FLYING`）
  以及挖掘 / 放置 / 使用物品 / 攻击 / 交互 / 丢物 / 快捷栏 / 载具等包**直接取消**。
  > 只取消、**绝不改写字节**：曾因改写包 buffer 导致 `accept_teleportation` 解析失败而踢人。
- 未安装 PacketEvents 时自动回退 Bukkit 事件拦截，功能等价。
- 跳跃单独处理（见 `war.md`）：`allow-jump-on-prepare` + Y 速度清零 + `prepare-platform` 屏障地盘。
- 开关：各模式的 `freeze-on-prepare`（默认 `true`）；`prepare-seconds` 为锁定持续时长。
- 软依赖：`plugin.yml` 的 `softdepend: packetevents`，未安装不影响启动。

## liudungeon（动态竞技场世界）

- 用于按 `arena.yml` 的 `template` 动态创建独立竞技场世界。
- 模板不存在或未安装时回退到 `world` + 坐标的固定场地。
- 结束后自动销毁动态世界。

## LiuChat（工会频道）

- `/guild chat` 切换工会频道，再执行返回原频道；`/guild chat <消息>` 单次发送。
- 每个工会注册独立 MEMBERS 频道，接收/发送权限由成员身份自动同步；退会与踢人即时取消订阅。
- 禁言、冷却、重复、过滤均由 LiuChat 原生审核。
- 更新后的 LiuChat `ChannelDeliveredEvent` 才可触发跨服转发，通过 LiuGuild Redis 总线发送成品组件，远端 `deliverChannelRemote` 再检查订阅、权限与忽略列表；旧版仅本服频道。
- 所有子服需要同时更新 LiuChat 与 LiuGuild。
- 工会战战斗消息只发给本场参赛者与承办服旁观者，不再借 LiuChat 做全服/跨服公告。

## mcMMO（技能静默屏蔽）

对应 mcMMO 2.2.x，仅使用其公开事件，不修改 mcMMO 本体。

- 工会战期间对**参赛者**静默屏蔽 mcMMO 技能/能力：取消 `McMMOPlayerAbilityActivateEvent`。
- 可选屏蔽技能经验：将 `McMMOPlayerPreXpGainEvent` 的经验置 0（默认关闭）。
- 配置在 `config.yml` 的 `hooks.mcmmo`：
  - `enabled`（默认 `true`）总开关；
  - `suppress-abilities-in-war`（默认 `true`）；
  - `suppress-xp-in-war`（默认 `false`）。
- 旁观者与未参赛玩家不受影响；未安装 mcMMO 时完全跳过。

## HuskHomes（工会驻地传送）

- 坐标由 LiuGuild 保存（表 `guild_homes`），传送由 HuskHomes 执行。
- `/guild home [槽位]` / `set` / `del` / `list`，按等级开放槽位数量。
- 支持跨服传送（保存服务器名 + 世界 UUID）。
- 未安装时相关命令提示不可用；工会战期间可禁用（`guild.home.deny-in-war`）。
- 详见 [home.md](home.md)。

## liupass（通行证经验 / 工会任务）

- **通行证经验**：`PassAPI.addXp(UUID, int)` / `addXp(UUID, String passId, int)`。工会战胜利/失败按模式配置发放，由 `war-modes.yml` 的 `rewards.win-pass-xp` / `lose-pass-xp` / `pass-id` 控制；`pass-id` 留空使用玩家默认通行证。
- **扩展任务类型（Liupass 1.6.3+）**：启动时注册 `liuguild:join` / `create` / `war` / `war-win` / `war-kill` / `checkin` / `donate` / `bank-deposit`，liupass 任务直接写 `type: liuguild:xxx` 即可；开关见 `config.yml` 的 `hooks.liupass.*`。
- **旧版任务 ID**：`tasks.yml` 事件绑定 + `addTaskProgress(UUID, String, String, int)`，由 `hooks.liupass.legacy-task-ids` 控制，默认保留兼容。
- liupass 热重载/重新启用后自动重新注册；未安装或版本过旧时任务进度静默降级，仅保留经验发放。
- 完整说明：[tasks.md](tasks.md)。

## Sparrow Sync（玩家快照）

- 入战 / 旁观时 `save(UUID)`（`CompletableFuture`，`CaptureMode.SYNC`，在玩家线程同步采集背包），
  出战 / 退出旁观时 `restore(UUID, snapshotId)`。
- 采集成功后立即 `pin(snapshotId)` 固定该快照，避免战斗期间因切世界 / 死亡触发的自动快照
  挤掉 `max-snapshots` 配额而被轮转清理；还原 / 退场后 `unpin(snapshotId)` 释放。
- 用于在统一装备 / 公平血量 / 旁观模式后完整还原玩家真实数据。
- 未安装时回退本地内存快照（`KitService.Snapshot`）。
- 两种快照都会额外持久化到 `guild_war_escrow` 表（含 `sparrow_snapshot_id`），
  作为服务器崩溃 / 重启 / 异常断线后的兜底，详见 [war.md](war.md)。

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
- 工会战入场/复活会清理玩家身上的药水效果，并在战斗期间拦截新效果，因此称号的
  `POTION_EFFECT` buff 不会带进竞技场；如果希望从源头关闭，可把竞技场世界加入
  PlayerTitle 的 `config.yml` → `disabledBuffWorld`（双保险，不强制）。

## LiuShop（价格参照与禁售联动）

工会商店在定价与上架时参考 LiuShop：

- **规范化物品 ID** —— 用 LiuShop 的 `idOf(ItemStack)`，保证「同一件物品」在两边被认成同一个（CE 优先、原版兜底）；
- **参考价** —— 上架时提示建议价：优先取玩家购买价，没有则取收购价 × `sell-markup`；
- **禁售联动** —— LiuShop 黑名单里的物品，工会商店**禁止上架**（否则黑名单形同虚设）。

全部走反射，LiuShop 缺失或版本演进时安全降级，不影响商店本身可用。
详见 [shop.md](shop.md)。

## Vault（经济）

- 工会创建费、银行存取、图标更换费、工会战个人金币奖励。银行只存 Vault 金币，所有工会成员均可存取；整数金额，余额以数据库原子增减为准。
- 工会资金对应本插件 `points`，独立于 Vault；工会战 `*-guild-funds` 奖励计入工会资金。
- 银行缺少经济服务时拒绝交易。

## NotBounties（悬赏）

- 可选软依赖，通过反射监听 `BountyClaimEvent`。
- 工会战参赛玩家的击杀会取消 NotBounties 悬赏领取，防止利用受控比赛击杀刷悬赏；普通世界击杀不受影响。
- 缺失 NotBounties 时完全不注册监听器，不影响工会战。

## PlaceholderAPI

- 注册标识符 `liuguild`，详见 [placeholders.md](placeholders.md)。

## CraftEngine

- 工会图标与统一装备支持 `CE:namespace:id`，详见 [craftengine.md](craftengine.md)。
