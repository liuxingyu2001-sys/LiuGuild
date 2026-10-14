# 工会战

工会战支持多模式：`match` 匹配、`season` 赛季和自定义模式。模式配置位于 `war-modes.yml`，公共默认值在 `war.yml` 的 `war.defaults`。

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
war:
  # true = 本服为工会战专属承办服（竞技场服）
  server-mode: false
  # 本服默认承办的 server-id（留空 = 由发起匹配的服务器承办）
  dedicated-server: ""
```

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
| `win-title-id` | 获胜方参赛玩家的 PlayerTitle 称号，0 表示不发 |
| `win-pass-xp` / `lose-pass-xp` / `pass-id` | 每位参赛玩家的通行证经验 |

平局双方按参与奖励结算，不发胜利称号。赛季 `points-multiplier` 为旧配置兼容保留，奖励倍率作用于金币、工会资金和通行证经验，不会产生点数。

升级兼容：旧 `win-points` / `lose-points` 会被忽略；旧 `win-money` / `lose-money` 只作为工会资金字段的迁移回退，不会发为个人金币。

## 跨服和竞技场

- 承办服优先级：模式 `dedicated-server` → 场地 `server` → 全局 `dedicated-server` → 发起匹配的服务器。
- **匹配即自动参战（`war.auto-enter`，默认 `true`）**：匹配成功、竞技场就绪后，系统会自动把双方**在线成员强制传送**到各自出生点并加入名单（承办服直接进入，其余服自动转移到承办服），然后倒计时开战——无需手动执行 `/guild war join`。
  - 不具备 `liuguild.war` 权限的成员视为自愿不参战，不会被强制传送（可用权限作为退出开关）。
  - 关闭 `war.auto-enter` 后恢复手动模式，「匹配成功」消息会附带 `[点击进入竞技场]` 按钮。
  - 传送后的准备倒计时为各模式的 `prepare-seconds`；想让匹配后尽快开打，把它调小即可。
- 非承办服玩家仍可手动使用 `/guild war join` 传送到承办服（例如中途加入）。
- 场地字段及管理命令见 [arena.md](arena.md)。
- `fair-equipment` 统一装备；`fair-health` 屏蔽 LuckyPeaches 蟠桃血量。
- Sparrow Sync 可用时优先使用其快照；否则回退本地快照还原玩家状态。
- NotBounties 可用时，工会战参赛者的击杀不会领取悬赏，避免以比赛击杀刷取悬赏。

## 胜负、计分与数据

击杀与死亡按 `score.kill` / `score.death` 调整比分，目标分数字段保留给后续目标玩法。比赛结束按比分或全队生命耗尽判胜；工会战记录保存模式、比分、承办服和竞技场世界。
