# 软依赖钩子

所有钩子均通过反射/软依赖访问，缺失时对应功能自动降级，不影响插件启动。
`/guildadmin status` 会显示每个钩子的可用状态。

## PacketEvents（包级拦截，工会战准备阶段锁定）

用于在**网络包层**锁定准备阶段的参赛者，开销低：

- 移动/转视角包（`PLAYER_POSITION` / `PLAYER_POSITION_AND_ROTATION` / `PLAYER_ROTATION` / `PLAYER_FLYING`）
  以及挖掘 / 放置 / 使用物品 / 攻击 / 交互 / 丢物 / 载具等包**直接取消**。
  > 快捷栏切换（`HELD_ITEM_CHANGE`）不取消：否则服务端 held slot 与客户端脱节，开战后会继续按空手判定。
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

## 宠物（工会战屏蔽）

对应 mcPets / LiuPets 与 [MyPet](https://github.com/MyPetORG/MyPet)，均通过软依赖反射接入，不修改宠物插件本体。

- 参赛者、旁观者和赛季战斗玩家在战斗期间不能召唤、骑乘宠物或触发宠物技能；
- 入场时立即移除已有宠物，战斗 tick 每秒兜底清理，登录/激活事件再次兜底；
- mcPets/LiuPets 取消 `PetSpawnEvent`、`PetCastSkillEvent`、`PetOwnerInteractEvent`、`EntityMountPetEvent`，并对 `PetSpawnedEvent` 做延迟移除；
- MyPet 取消 `MyPetCallEvent`，在 `MyPetActivatedEvent` / `MyPetPlayerJoinEvent` 后延迟移除，并通过 `MyPetManager#getMyPet` + `MyPet#removePet` 清理已激活宠物；
- 配置在 `config.yml`：
  - `hooks.pets.block-in-war`（默认 `true`）；
- 未安装对应宠物插件时完全跳过；宠物菜单/召唤指令本身也已被工会战指令与容器拦截覆盖。

## mcMMO（技能静默屏蔽）

对应 mcMMO 2.2.x，仅使用其公开事件，不修改 mcMMO 本体。

- 工会战期间对参赛者、旁观者和赛季战斗玩家完全屏蔽 mcMMO：
  - 取消 `McMMOPlayerAbilityActivateEvent`，技能/能力不能激活；
  - 取消 `McMMOPlayerNotificationEvent`，聊天、动作栏、升级/技能解锁/冷却等提示全部不再显示。
- 只有一个开关：`config.yml` 的 `hooks.mcmmo.block-in-war`（默认 `true`）。
- 未处于工会战/赛季战斗的玩家不受影响；未安装 mcMMO 时完全跳过。

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

## PlayerTitle（称号奖励 / 工会名展示）

对应 PlayerTitle 5.x，全部通过反射调用 `cn.handyplus.title.api.PlayerTitleApi`，未安装或版本不符时自动跳过。
`/guildadmin status` 会显示 `PlayerTitle` 钩子与「公会称号」状态。

### 工会战奖励称号

- 工会战胜利时按模式 `rewards.win-title-id` 发放 PlayerTitle 称号：
  `PlayerTitleApi.setPlayerTitle(UUID, int)`。
- `win-title-id: 0` 表示不发放。
- 工会战入场/复活会清理玩家身上的药水效果，并在战斗期间拦截新效果，因此称号的
  `POTION_EFFECT` buff 不会带进竞技场；如果希望从源头关闭，可把竞技场世界加入
  PlayerTitle 的 `config.yml` → `disabledBuffWorld`（双保险，不强制）。

### 工会名展示称号（可选）

- 原理：PlayerTitle 的称号名支持 PlaceholderAPI 变量（聊天前缀与 Tab 前缀显示时会解析），
  而 LiuGuild 已注册 `%liuguild_name%`。因此**所有公会共用一个称号**即可，成员戴上后显示的
  是各自工会名，工会改名实时生效；不需要给每个公会建称号，也不占 PlayerTitle 免费版
  100 个称号的名额。
- 只占「展示槽」：佩戴时通过 `TitleUtil.playerTitleUse(..., TitleUseTypeEnum.SHOW, true)`
  只切换展示称号，**不会顶掉玩家自己的 buff / 粒子称号**（旧版 PlayerTitle 缺少该重载时
  降级为 `playerUseTitle`，会占用 ALL 槽位）。
- 前置：
  - 已安装 **PlaceholderAPI**，且 PlayerTitle 启动时能检测到它（`USE_PAPI=true`，
    即先装 PAPI 再启服）；否则模块会直接跳过并告警，避免称号显示成字面量。
  - PlayerTitle 为 5.x 且包含 `findPlayerTitle` / `playerUseTitle` / `removePlayerTitle` 等 API。
- 在 PlayerTitle 里创建隐藏、免费、永久的称号（名字启用后会被本插件按配置自动同步），记下返回 ID：
  ```
  /plt title add not &b%liuguild_name%工会 0 0 false
  ```
- 配置（`config.yml`）：
  ```yaml
  hooks:
    player-title:
      guild-title:
        enabled: true
        title-id: <上一步返回的称号 ID>
        name: "%liuguild_name%工会"   # 称号显示文本；必须含 %liuguild_name%
        recheck-seconds: 60           # 0 = 关闭对账
        force: true                   # true 始终佩戴；false 仅在玩家没有展示称号时佩戴
  ```
- 称号文本：`name` 决定显示内容，默认 `"%liuguild_name%工会"`（显示成「某某工会」，避免只显示
  一个名字看不出是工会）；可以加颜色/前后缀，例如 `"&7[&b%liuguild_name%&7]工会"`。
  PlayerTitle 用 MiniMessage 渲染称号名，所以也支持渐变标签，例如
  `"<gradient:#00E5FF:#FF00FF>%liuguild_name%</gradient>&7工会"`。
  启用 / 重载时本插件会通过 `PlayerTitleApi.updateTitleName` 把该称号名同步成 `name` 的值
  （PlayerTitle 会同时更新所有玩家已拥有称号的显示名并刷新在线玩家，无需重新发放）；
  `name` 里缺少 `%liuguild_name%` 会告警并回退默认值。
- 工会头衔：可以把玩家当前装备的工会固定头衔（`guild.yml` → `titles.presets`）拼进称号，
  例如 `name: "%liuguild_name_title%"` 显示成「星辰 会长」；未装备头衔时只显示工会名，
  不会留下多余空格。也可以写 `"%liuguild_name%工会%liuguild_title_spaced%"` 保留「工会」后缀。
  玩家换装 / 卸下头衔时本插件会主动刷新 PlayerTitle，聊天与 Tab 前缀立即更新。
- 工会名颜色：`guild.name.allow-colors: true`（默认）且玩家拥有 `liuguild.name.color` 权限时，
  工会名可带 `&a` / `&#FF0000` 等颜色代码，`%liuguild_name%` 会原样返回，PlayerTitle 渲染称号时
  按旧版颜色代码解析，因此聊天前缀 / Tab 里显示的是各工会自己的颜色；名字里的 `<`、`>`、`\`
  会被拒绝，避免经 PAPI 注入 MiniMessage。需要不带颜色的纯文本（给不解析颜色的插件/日志）时
  使用 `%liuguild_name_plain%`。
- 启动 / 重载 / 每轮对账都会校验 `title-id` 是否存在，不存在会告警并自动跳过（不会反复重试写库）。
- 开启后：进服 / 入会 / 建会 → 自动授予并佩戴；退会 / 被踢 / 解散 → 自动回收
  （回落到 PlayerTitle 默认称号）；`recheck-seconds` 定时对账兜底手动换称号、跨服入退会与漏事件。
- 改名：聊天前缀每次消息都会重新解析 `%liuguild_name%`，实时生效；Tab / 头顶前缀由
  PlayerScoreboard 缓存，本服改名会立即刷新，跨服改名会在下一次对账（≤ `recheck-seconds`）
  前刷新（仅真改名时触发，普通 `GUILD_CHANGED` 不会重复刷新）。
- 显示位置：PlayerTitle 的 `config.yml` 中 `isChat`（聊天）与 `isTabPrefix`（Tab / 头顶，
  需 PlayerScoreboard）；`displayPrefix` / `displaySuffix` 决定 `[ 工会名 ]` 前后缀。
- 多子服：`title-id` 写在共享 `config.yml` 里，因此 PlayerTitle 必须使用 **共享 MySQL**
  （`storage.yml` → `storage-method: MySQL`），保证所有子服看到同一个称号 ID 与玩家数据；
  各子服进服时会补授 / 补戴，退会回收后下次进服也会自愈。
- 注意：`force: true` 会替换玩家当前的展示称号，和工会战奖励称号共用展示槽；称号名在
  Tab 前缀受 Minecraft 64 字符截断（颜色代码计入）。

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

## LuckPerms（上下文）

安装 LuckPerms 后自动注册 Bukkit 上下文，供权限组/条件权限直接使用：

| 上下文 | 值 |
|---|---|
| `liuguild:guild_id` | 工会 UUID |
| `liuguild:guild` | 工会名称 |
| `liuguild:guild_rank` | `member` / `officer` / `leader` |
| `liuguild:in_war` | 工会战或赛季混战参赛中时为 `true` |
| `liuguild:spectator` | 工会战旁观中时为 `true` |

- 只在主线程读内存缓存，不产生数据库/Redis IO。
- 入战、退场、加入/退出工会、升职/降职会主动 `signalContextUpdate`。
- 未安装 LuckPerms 时整个钩子不加载，不影响启动。

## Webhook（Discord / 通用 JSON）

- 配置在每服 `server.yml` 的 `webhook`，默认关闭；不随共享配置同步。
- 支持工会战/赛季/升级/创建/解散/入会申请事件，异步发送、失败重试。
- 详细字段、payload 与安全说明见 [webhook.md](webhook.md)。

## CustomNameplates（工会战头顶铭牌）

- 安装后 LiuGuild 注册 `%liuguild_war_guild%` 占位符。
- 工会战期间在参赛者头顶显示 `[工会名]`，A/B 方使用不同颜色。
- 非工会战返回空字符串，不常驻显示。
- 配置与 CustomNameplates 的 `nameplate.yml` 示例见 [nameplates.md](nameplates.md)。

## QQ 官方机器人（WebSocket）

- 配置在每服 `server.yml` 的 `qqbot`，默认关闭；不随共享配置同步。
- 支持官方 WebSocket 网关、token 自动刷新、心跳/重连、@机器人查询工会/战况/排行。
- 战报等事件可主动推送到 channel/group/c2c，复用 webhook 事件开关。
- 详细配置、指令与限制见 [qqbot.md](qqbot.md)。

## PlaceholderAPI

- 注册标识符 `liuguild`，详见 [placeholders.md](placeholders.md)。

## CraftEngine

- 工会图标与统一装备支持 `CE:namespace:id`，详见 [craftengine.md](craftengine.md)。
