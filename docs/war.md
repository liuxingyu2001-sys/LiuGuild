# 工会战

工会战支持多模式：`match` 匹配、`season` 赛季和自定义模式。模式配置位于 `war-modes.yml`，公共默认值在 `war.yml` 的 `war.defaults`。

## 恢复与场地安全边界

入场先持久化内置装备托管，成功后才发放比赛套装；编码或写库失败拒绝入场。等待期间限制背包点击、拖拽、捡拾、交换副手及伤害。恢复失败、玩家已离线或托管数据损坏时保留恢复依据，供再次恢复或管理员检查。

显式指定的世界不存在、模板不可用时取消比赛；不再回退普通世界。已取消比赛的异步场地创建结果不会重新启动比赛。

匹配工会战现在也会跨服返程：入场前把出发服与坐标写入 `guild_war_origins`（并随 `war:pending` 标记带一份），结算时优先按 `after-war.end-spawn.server`，否则把玩家传送回出发服原坐标；玩家在赛后离线、承办服重启时保留记录，重新上线后由托管恢复流程补做返程。出发服/目的服不可用时玩家会留在承办服并保留记录，不会丢装备或重复发奖。两种模式的玩家存档与插件托管仍不是同一个原子事务。上线前仍需验证断线、切服失败、承办服重启、SparrowSync 加载时序及临时世界清理，不能将单元测试通过视为这些流程已验证。

## 管理员开启赛事

`/guildadmin war start season` 开启同一场多工会混战，不进入两两匹配队列。各子服汇总在线名单，按工会等级、最低人数和队伍上限筛选。击杀加分，同分按死亡次数升序排名；时间到或只剩一个存活工会结束，按名次发公共工会资金奖励。完整配置、跨服返程和验证边界见 [多工会赛季混战](season-melee.md)。

## 配置示例

```yaml
# war.yml（共享）
war:
  enabled: true
  # 承办服在 arena.yml 的 server 设置，无需每服开关。
  queue-interval-seconds: 2
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
    score: { kill: 1, death: -1 }
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
```

> **承办服为什么总是对不上？** 工会战承办服用「代理登记名」判断：`arena.yml` 的
> `server` 填代理名（如 `zy`），插件拿它和本服 `server-id`
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
    score: { kill: 1, death: -1 }
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

## 不同模式使用不同规则

`war.defaults` 只补全模式里**没有写**的项，模式里写出的项优先。模式规则可以写在 `war-modes.yml`，也可以直接写在 `war.yml` 里与 `defaults` 同级（更直观）：

```yaml
# war.yml
war:
  defaults:
    fair-equipment: true
    fair-health: true
    team-size: 10
  match:
    fair-equipment: true   # 统一套装
    fair-health: true      # 屏蔽蟠桃血量
  owngear:
    enabled: true
    display: "自带装备工会战"
    arena: pvp
    fair-equipment: false  # 使用玩家自己的装备
    fair-health: false     # 保留原生血量（含蟠桃加成）
    prepare-seconds: 10
    duration-seconds: 600
```

与 `defaults` 同级写模式时，不能用 `defaults`、`modes`、`score`、`lives`、`rewards` 这些保留名，也不能与 `enabled`、`broadcast-queue` 等全局标量重名；建议模式名用 `owngear`、`nightly` 这类不会冲突的词。也可以把它们放进 `war.modes:` 下，效果相同。

优先级从低到高：

1. `war.defaults`
2. `war.yml` 中与 `defaults` 同级的模式，或 `war.modes.<key>`
3. `war-modes.yml` 的 `modes.<key>`（同名模式以此为准）

`owngear` 中未写的 `team-size`、`min-level`、`rewards` 等仍继承 `war.defaults`；`match` 不受影响。模式 key 可自定义，启用后会出现在 `/guild war queue <模式>` 与工会战菜单中。

## 生命与复活

- `lives.per-player` 是每名参赛者初始生命数，包含开战时的当前生命。
- `lives.balance-teams: true` 时，人数不等的双方获得相等总生命池：`max(较大队伍人数, 较小队伍人数 × per-player)`。分配时保证每名参赛者至少有 1 命；较小队伍可能每人分到多于基础值。关闭后双方均按每人基础生命数计算。
- 达到致命伤害时，在原版死亡流程前拦截，扣除一命。参赛者不会掉落装备或进入原版死亡界面。
- 入场和每次复活都会先清空玩家当前全部药水效果（包括 PlayerTitle 称号 buff、命令或药水来源），再发放 `kit.yml` 的套装效果；`fair-equipment: false` 也适用。
- 战斗期间（准备、复活等待、已淘汰待返程）拦截新增/变化的药水效果，只放行本插件发放的套装效果，避免 PlayerTitle 在切世界或复活后重新施加。
- 还有生命时，玩家临时进入旁观模式，等待 `respawn-delay-seconds` 后在己方出生点复活并重新领取统一套装。
- 生命耗尽后被淘汰并持续旁观；一方全员淘汰时立即判负。比赛进入 ACTIVE 后锁定参赛名单，不接受新参赛者。
- 比赛结束（含强制结束）时，参赛者会被传送回参赛前的位置并还原装备。
- **关闭服务器或重载配置不会结算奖励**：`/guildadmin reload` 只重启工会战定时任务，正在进行的比赛继续进行；插件卸载/关服时比赛按取消处理并还原玩家，不发放金币、工会资金、通行证经验或称号。只有正常结束或管理员 `forceend` 才会结算。
- `PlayerDeathEvent` 作为 `/kill` 等直接死亡的兜底入口。

## 匹配与赛季

- 玩家从 `/guild gui` → 工会战选择启用的模式，或使用 `/guild war queue <mode>`。
- 模式开启 `broadcast-queue` 时，玩家入队会向**所有子服**广播（Redis 跨服消息），其他服玩家可直接点击提示加入；未启用跨服时只在当前服广播。
- `auto-enter: true` 时，匹配成功、开战、倒计时、比分、击杀和结算等**两个工会之间**的消息只发给本场参赛者与承办服旁观者，不跨服、不发给未参赛的工会成员。
- `auto-enter: false` 时，匹配成功提示仍会作为工会内部入场邀请发给双方在线成员，但不发给全服。
- 只有“入队邀请”和赛季开放报名属于公开招募信息，才按 `broadcast-queue` / 赛季开放逻辑广播。
- 配对按模式分组，不会让不同模式的工会互相阻塞。
- `schedule` 支持 `"20:00"`、`"sat 20:00"`、`"mon-fri 20:00"`、`"sat,sun 21:30"`。
- 判定为混战的模式（`season.melee: true`）使用 `season.registration-seconds` 汇总名单，并自动登记符合条件的工会，不使用 `window-minutes` / `auto-enqueue`。
- 未启用混战的时间表模式仍使用 `season.window-minutes` 控制报名窗口，`season.auto-enqueue` 控制是否自动入队。
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

- `daily-guild-funds-cap` 按**工会 + 自然日**统计，默认由 `war.defaults` 提供、模式可单独覆盖；匹配战默认 `2000`。混战排名奖励走独立事务，不读取该上限。
- 实际上限判定在**数据库内原子完成**（读当日已发额 → 算本次可发额 → 乐观锁写入），多台子服同时结算也不会超发；已达上限时结算消息显示「已达今日上限」，不再发放工会资金（个人金币/经验/称号不受影响）。
- 匹配战默认奖励已从 `5000/1000` 下调为 `800/100`，配合每日上限避免反复匹配刷工会资金。

平局双方按参与奖励结算，不发胜利称号。`season.points-multiplier` 只对按时间表开启的非混战赛事生效，作用于金币、工会资金和通行证经验；混战使用 `season.rank-rewards`，不受该倍率影响。


## 跨服和竞技场

- 承办服由场地 `server` 决定；留空则由发起匹配的服务器承办。
- **承办服池**：`arena.yml` 可配置 `hosts: [zy, 99, fb]`；匹配时按 `war:host` 忙碌标记和最近使用时间选择一台空闲承办服，避免所有比赛挤在单台服务器。候选都忙时本轮不配对，工会继续排队。
- **承办服列表警告**：代理 `GetServers` 返回的列表可能滞后；插件每 5 分钟或在玩家进服时按 30 秒节流刷新一次。列表里暂时没有 `zy` 时只会提示一次，不会阻止创建/传送；实际能 `Connect` 成功即可忽略。
- **匹配即自动参战（`war.auto-enter`，默认 `true`）**：匹配成功、竞技场就绪后，系统会自动把双方**在线成员强制传送**到各自出生点并加入名单（承办服直接进入，其余服自动转移到承办服），然后倒计时开战——无需手动执行 `/guild war join`。
  - 不具备 `liuguild.war` 权限的成员视为自愿不参战，不会被强制传送（可用权限作为退出开关）。
  - 关闭 `war.auto-enter` 后恢复手动模式，「匹配成功」消息会附带 `[点击进入竞技场]` 按钮。
- **低延迟入场**：入队后立即尝试匹配（不等轮询）；匹配锁在一次扫描后立刻释放，轮询间隔默认 2 秒；自动入场对整会成员使用一次 Redis pipeline 写入待入场标记，再统一切服；玩家接入时只做一次 pipeline 读取待入场/返程/竞技场标记，接入后 2 tick（约 0.1 秒）即处理，不再固定等待 1 秒。
- **快速开战（`fast-start`，默认 `true`）**：双方都至少有一名参赛者、且本批装备采集完成后，最短等待 `fast-start-min-prepare-seconds`（默认 1 秒）即自动开战，不再固定等完整 `prepare-seconds`；仍在切服的成员可在 `fast-start-join-grace-seconds`（默认 10 秒）内加入已开始的比赛。想恢复“完整倒计时”就设 `fast-start: false`。
  - 开启时匹配提示使用「人员到齐后立即开战」，BossBar 显示「等待双方到齐」，不再播固定 `X 秒后开战`/倒计时，避免提示与实际开战时间不一致。
- **掉线重连（`reconnect-seconds`，默认 90 秒，0=关闭）**：开战后掉线且**未被淘汰**的参赛者，在宽限内执行 `/guild war rejoin` 可回到战场：
  - 保留掉线前的剩余生命，不会重置；
  - 恢复统一套装并传送到己方出生点；
  - 超过宽限、已被淘汰或比赛已结束时按正常入场规则拒绝；
  - 该功能用于网络抖动，不是“掉线免死”；担心利用可把 `reconnect-seconds` 调小或设 0。
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
- **跨服旁观**：任意子服执行 `/guild war spectate` 会写 `war:spectate` 标记并传送到承办服旁观点；赛后按出发地返程，旁观者不能交互/丢物/开容器。
- **赛后返程**：跨服参赛者默认回出发服原坐标；配置 `after-war.end-spawn.server` 时统一送到指定子服的集合点。超过队伍上限被拒绝、或开战后才迟到的跨服玩家，会收到提示并直接送回出发地，不会被留在承办服。
- 场地字段及管理命令见 [arena.md](arena.md)。
- `fair-equipment` 决定是否发放统一装备；关闭时保留玩家自带装备（仍会采集/恢复快照用于返程兜底）。
- `fair-health` 决定是否屏蔽 LuckyPeaches 蟠桃血量加成，匹配战与赛季混战都遵循该模式的开关。
- NotBounties 可用时，工会战参赛者的击杀不会领取悬赏，避免以比赛击杀刷取悬赏。

## 跨服生命周期、镜像与崩溃恢复

跨服工会战会在**所有子服**建立一份只读「镜像」用于战况展示与匹配互斥。镜像必须以终态广播收尾，否则子服会一直把相关工会判为“正在工会战中”而无法再次匹配：

- **正常结束**：承办服在结算阶段结束时（`cleanup`）把战斗置为 `ENDED` 并写入数据库，然后**强制广播** `WAR_STATE(ENDED)`；
- **中途取消**（竞技场准备失败、通知人数不足、参战工会解散等）：置为 `CANCELLED` 并广播 `WAR_STATE(CANCELLED)`；
- 子服收到非存活状态后立即移除镜像；
- **兜底自愈**：承办服崩溃 / Redis 丢消息时镜像会残留。`warTick` 每 30 秒清理一次过期镜像，`warOf` / `liveWars` 也会在命中过期镜像时立刻清除。过期阈值 = 该模式 `prepare + duration + settle` 秒 + 5 分钟安全余量（无法取到模式时退化为 2 小时）。
- **非承办服重启**：启动扫描时会把数据库里仍在进行、且不是本服承办的战斗重新装入只读镜像，避免重启期间同一工会被再次匹配；错过的 `WAR_AUTOJOIN` 不补发，成员可手动 `/guild war join`。
- **状态同步**：承办服在每个阶段变更（`PREPARING/ACTIVE/SETTLING/ENDED`）和每次比分变化时广播 `WAR_STATE`，并带上剩余秒数；子服的 `/guild war status`、GUI 和 `%liuguild_war_time_left%` 以镜像的 `phaseUntil` 显示，不再出现 `-1`。
- **终态补发**：收尾广播先进入待发队列，Redis 暂时不可用时由 `warTick` 在恢复后补发，避免镜像残留到过期时间。
- **承办服忙碌**：承办比赛/赛季期间会以 `war:host:<承办服>` 续期忙碌标记（60 秒 TTL，5 秒续期）。发起匹配的子服在配对前会跳过忙碌的承办服，避免“匹配成功 → 承办服正忙 → 取消重排”每 5 秒循环。
- **匹配确认**：远程承办的 `WAR_MATCHED` 需要承办服 `WAR_MATCH_ACK` 确认；发起服每 5 秒重发一次（最多 4 次），30 秒仍未认领会取消本场、写库广播并把双方重新入队，并在本服对该承办服冷却 2 分钟，避免对离线承办服反复配对。承办服认领前还会校验数据库状态仍为 `MATCHED`，防止迟到的消息复活已取消的比赛。
- **队列显示**：每台子服每 5 秒独立刷新队列快照缓存，GUI/PAPI/`/guild war status` 不再依赖“抢到匹配锁的那台服”才更新。
- **崩溃恢复**：承办服启动时会扫描数据库里 `host_server` 等于自己的非终态战斗：已写入 `endedAt`（进过结算阶段）的记为 `ENDED` 并进入历史，否则记为 `CANCELLED`；两种情况都会广播终态，避免历史缺失与镜像残留。扫描会跳过内存里仍在承办的战斗，且只在插件启动时执行——`/guildadmin reload` 不触发恢复，避免把正在进行的比赛在数据库里误记为结束。

> 历史记录 `/guild war history` 只统计 `ENDED` 的战斗；被取消 / 中断的战斗不会进入历史。

### 占点模式（KOTH）

模式配置：

```yaml
modes:
  koth:
    arena: 超平坦_1
    score:
      kill: 0        # 可关闭击杀分，只靠占点
      death: 0
    objective:
      type: koth
      points-per-second: 1
      target: 100
      name: "据点"
```

场地配置（`arena.yml`）：

```yaml
arenas:
  超平坦_1:
    koth: { x: 0.0, y: -60.0, z: 0.0, radius: 6 }
```

规则：

- 开战后每秒统计 A/B **存活**（未淘汰、不在复活等待、非旁观）在占点球体内的玩家数；
- 只有一方在点内时，该方每秒获得 `points-per-second` 分；
- 双方都在点内、或都无人时不得分；
- 先到 `target` 分立即结束；时间到仍未达标则按当前比分判定；
- 击杀/死亡仍按 `score.kill` / `score.death` 计分，可设为 `0` 做成纯占点；
- 占点分直接写入本场比分，跨服镜像、BossBar、结算、胜负、奖励、战绩、邮件全部复用；
- 场地未配置 `koth` 时开战前会取消比赛（`/guildadmin doctor` 也会报错）。

### 战后战绩与 MVP

- 结算时把本场参赛者的击杀、死亡、存活秒数写入 `guild_war_player_stats`；
- `/guild war stats [战 id]` 查看最近一场或指定场次的前 10 名；不填 id 时取本会最近一场；
- MVP = 击杀×3 - 死亡×2 + 存活分钟，最高分者获得；无人得分时不评 MVP；
- 结算公告会单独播报本场 MVP，并给每名参赛者发送一封 `war.result` 邮件（离线也能看到，保留 14 天）；
- 战绩在结算写库阶段保存，崩溃/中途取消的比赛不生成战绩；历史战绩保留 365 天后清理。

### 结算崩溃恢复

结算前会把每名玩家的奖励写入 `guild_war_rewards` outbox，再分帧发放；启动和运行时会扫描 `PENDING` 行继续补发。
发完后标记 `DONE`，重复扫描不会重复生成。工会层奖励仍即时结算，玩家层奖励不再只存在内存队列里。

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
