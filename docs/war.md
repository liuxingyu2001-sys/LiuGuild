# 工会战

工会战支持多模式：`match` 匹配、`season` 赛季和自定义模式。模式配置位于 `war-modes.yml`，公共默认值在 `war.yml` 的 `war.defaults`。

## 恢复与场地安全边界

入场先持久化内置装备托管，成功后才发放比赛套装；编码或写库失败拒绝入场。等待期间限制背包点击、拖拽、捡拾、交换副手及伤害。恢复失败、玩家已离线或托管数据损坏时保留恢复依据，供再次恢复或管理员检查。

显式指定的世界不存在、模板不可用时取消比赛；不再回退普通世界。已取消比赛的异步场地创建结果不会重新启动比赛。

跨服返程和数据托管仍有待完善：当前没有完整的出发服坐标往返记录；代理 Connect 消息不代表玩家已抵达；数据库恢复记录与 Minecraft 玩家存档并非原子提交。上线前仍需验证断线、切服失败、承办服重启、SparrowSync 加载时序及临时世界清理，不能将单元测试通过视为这些流程已验证。

## 配置示例

```yaml
# war.yml（共享）
war:
  enabled: true
  # 注意：server-mode / dedicated-server 是【每服独立】设置，已移至 server.yml，
  #       不再写在共享 war.yml 里，避免多台子服互相冲突。
  queue-interval-seconds: 5
  match-timeout-seconds: 180
  default-mode: match
  broadcast-queue: true
  bossbar: true
  settle-batch-per-tick: 8
  defaults:
    fair-equipment: true
    fair-health: true
    kit: default
    team-size: 10
    min-members: 1
    min-level: 1
    lives:
      per-player: 3
      balance-teams: true
      respawn-delay-seconds: 3
    prepare-seconds: 30
    duration-seconds: 600
    settle-seconds: 10
    allow-spectators: true
    score: { kill: 1, death: -1, objective: 5 }
    rewards:
      win-player-money: 500.0
      lose-player-money: 100.0
      win-guild-funds: 5000
      lose-guild-funds: 1000
      win-pass-xp: 500
      lose-pass-xp: 100
      pass-id: ""
      win-title-id: 0
```

```yaml
# server.yml（每服独立，不共享）
server-id: "zy"   # 必填：必须与代理 [servers] 登记名一致（不是随便起的名字）
war:
  # true = 本服为工会战专属承办服（竞技场服）
  server-mode: false
  # 本服默认承办的 server-id（留空 = 由发起匹配的服务器承办）
  dedicated-server: ""
```

> **承办服为什么总是对不上？** 工会战承办服用「代理登记名」判断：`arena.yml` 的
> `server`、`war.dedicated-server` 都填代理名（如 `zy`），插件拿它和本服 `server-id`
> 比较。若 `server-id` 是留空后自动生成的 `server-xxxxxxxx`，承办服就不会认领战斗，
> 玩家也传不过去。详见 [configuration.md](configuration.md#共享配置目录)。

```yaml
# war-modes.yml
modes:
  match:
    enabled: true
    display: "匹配工会战"
    arena: main
    fair-equipment: true
    fair-health: true
    kit: default
    team-size: 10
    min-members: 1
    min-level: 1
    lives: { per-player: 3, balance-teams: true, respawn-delay-seconds: 3 }
    prepare-seconds: 30
    duration-seconds: 600
    score: { kill: 1, death: -1, objective: 5 }
    rewards:
      win-player-money: 500.0
      lose-player-money: 100.0
      win-guild-funds: 5000
      lose-guild-funds: 1000
      win-pass-xp: 500
      lose-pass-xp: 100
      pass-id: ""
      win-title-id: 0
```

## 生命与复活

- `lives.per-player` 是每名参赛者初始生命数，包含开战时的当前生命。
- `lives.balance-teams: true` 时，人数不等的双方获得相等总生命池：`max(较大队伍人数, 较小队伍人数 × per-player)`。分配时保证每名参赛者至少有 1 命；较小队伍可能每人分到多于基础值。关闭后双方均按每人基础生命数计算。
- 达到致命伤害时，在原版死亡流程前拦截，扣除一命。参赛者不会掉落装备或进入原版死亡界面。
- 还有生命时，玩家临时进入旁观模式，等待 `respawn-delay-seconds` 后在己方出生点复活并重新领取统一套装。
- 生命耗尽后被淘汰并持续旁观；一方全员淘汰时立即判负。比赛进入 ACTIVE 后锁定参赛名单，不接受新参赛者。
- 比赛结束（含强制结束）时，参赛者会被传送回参赛前的位置并还原装备。
- **关闭服务器或重载配置不会结算奖励**：`/guildadmin reload` 只重启工会战定时任务，正在进行的比赛继续进行；插件卸载/关服时比赛按取消处理并还原玩家，不发放金币、工会资金、通行证经验或称号。只有正常结束或管理员 `forceend` 才会结算。
- `PlayerDeathEvent` 作为 `/kill` 等直接死亡的兜底入口。

## 匹配与赛季

- 玩家从 `/guild gui` → 工会战选择启用的模式，或使用 `/guild war queue <mode>`。
- 配对按模式分组，不会让不同模式的工会互相阻塞。
- `schedule` 支持 `"20:00"`、`"sat 20:00"`、`"mon-fri 20:00"`、`"sat,sun 21:30"`。
- `season.window-minutes` 控制赛季报名窗口，`season.auto-enqueue` 控制是否自动入队。
- 管理员可以使用 `/guildadmin mode start <mode>` 手动开放赛季。

## 奖励

奖励类型仅有：个人金币（Vault）、工会资金、胜方称号（PlayerTitle）、通行证经验（liupass）。默认不发点券；工会资金以本插件 points 保存，与 PlayerPoints 无关。

| 配置 | 收益方 |
|---|---|
| `win-player-money` / `lose-player-money` | 每位参赛玩家的 Vault 个人金币 |
| `win-guild-funds` / `lose-guild-funds` | 本插件工会资金（points），与 Vault 银行分开 |
| `daily-guild-funds-cap` | 该模式每个工会每天通过工会战最多获得的工会资金（0 = 不限） |
| `win-title-id` | 获胜方参赛玩家的 PlayerTitle 称号，0 表示不发 |
| `win-pass-xp` / `lose-pass-xp` / `pass-id` | 每位参赛玩家的通行证经验 |

通行证经验与「工会战任务」是两条独立路径：前者由本表直接发放，后者由 liupass 任务完成时的 `xp` 发放（`type: liuguild:war` / `war-win` / `war-kill`），见 [tasks.md](tasks.md)。

### 每日上限（防刷）

- `daily-guild-funds-cap` 按**工会 + 自然日**统计，默认由 `war.defaults` 提供、模式可单独覆盖：匹配战默认 `2000`，赛季默认 `20000`。
- 实际上限判定在**数据库内原子完成**（读当日已发额 → 算本次可发额 → 乐观锁写入），多台子服同时结算也不会超发；已达上限时结算消息显示「已达今日上限」，不再发放工会资金（个人金币/经验/称号不受影响）。
- 匹配战默认奖励已从 `5000/1000` 下调为 `800/100`，配合每日上限避免反复匹配刷工会资金。

平局双方按参与奖励结算，不发胜利称号。赛季 `points-multiplier` 为旧配置兼容保留，奖励倍率作用于金币、工会资金和通行证经验，不会产生点数。

升级兼容：旧 `win-points` / `lose-points` 会被忽略；旧 `win-money` / `lose-money` 只作为工会资金字段的迁移回退，不会发为个人金币。

## 跨服和竞技场

- 承办服优先级：模式 `dedicated-server` → 场地 `server` → 全局 `dedicated-server` → 发起匹配的服务器。
- **匹配即自动参战（`war.auto-enter`，默认 `true`）**：匹配成功、竞技场就绪后，系统会自动把双方**在线成员强制传送**到各自出生点并加入名单（承办服直接进入，其余服自动转移到承办服），然后倒计时开战——无需手动执行 `/guild war join`。
  - 不具备 `liuguild.war` 权限的成员视为自愿不参战，不会被强制传送（可用权限作为退出开关）。
  - 关闭 `war.auto-enter` 后恢复手动模式，「匹配成功」消息会附带 `[点击进入竞技场]` 按钮。
- **准备阶段锁定（`freeze-on-prepare`，默认 `true`）**：传送入场后到开战前的 `prepare-seconds` 内，参赛者被完全锁定：
  - 装了 **PacketEvents** 时在包层取消移动/转视角与挖掘/放置/交互/攻击/丢物等包（**只取消、不改写字节**）；
  - 未装时回退 Bukkit 事件拦截；移动与视角无论哪种情况都由 `PlayerMoveEvent` 兜底钉死。
  - **跳跃也会被禁止**：`allow-jump-on-prepare`（默认 `false`）控制是否允许；
    由于客户端跳跃在输入状态更新前就已生效（取消 `PLAYER_INPUT` 拦不住），额外用两层保障：
    每秒检测 Y 轴速度并清零拉回，以及在出生点脚下自动垫一层屏障（`prepare-platform`，默认 `true`）。
  - 因此开战瞬间双方都精确站在各自出生点上，位置完全确定。
  - 倒计时结束的瞬间自动解锁（并移除屏障）转为生存模式，立即开打。
  - `prepare-seconds` 默认已调至 **15**（原 30），想更快可直接调小或设 `freeze-on-prepare: false`。
- 非承办服玩家仍可手动使用 `/guild war join` 传送到承办服（例如中途加入）。
- 场地字段及管理命令见 [arena.md](arena.md)。
- `fair-equipment` 统一装备；`fair-health` 屏蔽 LuckyPeaches 蟠桃血量。
- NotBounties 可用时，工会战参赛者的击杀不会领取悬赏，避免以比赛击杀刷取悬赏。

## 跨服生命周期、镜像与崩溃恢复

跨服工会战会在**所有子服**建立一份只读「镜像」用于战况展示与匹配互斥。镜像必须以终态广播收尾，否则子服会一直把相关工会判为“正在工会战中”而无法再次匹配：

- **正常结束**：承办服在结算阶段结束时（`cleanup`）把战斗置为 `ENDED` 并写入数据库，然后**强制广播** `WAR_STATE(ENDED)`；
- **中途取消**（竞技场准备失败、通知人数不足、参战工会解散等）：置为 `CANCELLED` 并广播 `WAR_STATE(CANCELLED)`；
- 子服收到非存活状态后立即移除镜像；
- **兜底自愈**：承办服崩溃 / Redis 丢消息时镜像会残留。`warTick` 每 30 秒清理一次过期镜像，`warOf` / `liveWars` 也会在命中过期镜像时立刻清除。过期阈值 = 该模式 `prepare + duration + settle` 秒 + 5 分钟安全余量（无法取到模式时退化为 2 小时）。
- **崩溃恢复**：承办服启动时会扫描数据库里 `host_server` 等于自己的非终态战斗：已写入 `endedAt`（进过结算阶段）的记为 `ENDED` 并进入历史，否则记为 `CANCELLED`；两种情况都会广播终态，避免历史缺失与镜像残留。扫描会跳过内存里仍在承办的战斗，且只在插件启动时执行——`/guildadmin reload` 不触发恢复，避免把正在进行的比赛在数据库里误记为结束。

> 历史记录 `/guild war history` 只统计 `ENDED` 的战斗；被取消 / 中断的战斗不会进入历史。

## 战斗期间限制

参赛者与旁观者从入场到完全退场期间，以下行为被强制禁止，防止把竞技场统一装备带出场、
或把外部物品 / 坐标带进场：

- **禁止打开任何容器 / 菜单 / 面板**：末影箱、箱子、熔炉、商店、工会仓库、`/guild gui` 主菜单等一律拦截
  （`InventoryOpenEvent` + `GuiManager.openMain` 双重拦截）。
- **禁止一切外部传送**：`/home`、`/spawn`、`/tpa`、传送卷轴、地狱门等一律拦截（`PlayerTeleportEvent`）。
  工会战内部发起的传送（入场、复活、旁观、赛后回城、结束清场）通过白名单豁免，
  同一竞技场世界内的末影珍珠 / 紫颂果位移也放行。
- **禁止使用外部指令**：仅放行聊天（`/msg` `/tell` `/w` `/whisper` `/r` `/reply` `/me`）
  与工会沟通 / 战况查询（`/guild chat|c|help|info`、`/guild war [status|history|leave]`）。
  注意：裸 `/guild`（打开主菜单）也被禁止。
- 拥有 `liuguild.admin` 权限者完全豁免以上限制，便于管理员运维。

## 数据还原与装备托管

入战 / 旁观前会采集玩家状态，退场时按优先级还原：

1. **Sparrow Sync 快照**（安装时）：采集后立即 `pin` 固定，还原无论成功失败都 `unpin`；
2. **本地内存快照**：Sparrow Sync 缺失或还原失败时回退；
3. **数据库托管记录 `guild_war_escrow`**：每次都落库，作为崩溃 / 重启 / 异常断线的兜底。

- **战中掉线**：`PlayerQuitEvent`（LOW，早于 Sparrow Sync 的 MONITOR 采集）会用本地快照
  **同步**把背包 / 护甲 / 等级 / 游戏模式写回 Player 对象，避免 Sparrow Sync 下一 tick 的退出采集
  把竞技场统一装备写进跨服档案。
- **崩溃 / 重启**：内存快照丢失后，玩家再次上线时按 `guild_war_escrow` 记录恢复未归还的装备与坐标；
  若记录里有 `sparrow_snapshot_id` 则优先用 Sparrow Sync 还原，失败再回退托管物品数据。
- 旁观者同样会被采集，保证崩溃后不会永久卡在旁观模式。
- 还原成功后删除托管记录；仅在玩家离线且还原失败时保留，等待下次上线继续兜底。

## 胜负、计分与数据

击杀与死亡按 `score.kill` / `score.death` 调整比分，目标分数字段保留给后续目标玩法。比赛结束按比分或全队生命耗尽判胜；工会战记录保存模式、比分、承办服和竞技场世界。
