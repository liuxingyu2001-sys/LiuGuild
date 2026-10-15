# 工会玩法任务与 liupass

LiuGuild 与 [liupass](../liupass/)（战令 / 通行证）对接有两种方式，可单独或同时使用：

1. **扩展任务类型（推荐，需 Liupass 1.6.3+）**：LiuGuild 注册 `liuguild:xxx` 类型并上报进度，服务器在 liupass 任务里直接写 `type: liuguild:xxx` 即可，**不需要**再创建同 ID 的 `CUSTOM` 任务。
2. **旧版任务 ID 绑定**：`tasks.yml` 把事件绑定到已有 `CUSTOM` 任务的 ID，通过 `PassAPI.addTaskProgress` 上报。为兼容旧配置保留，可用 `hooks.liupass.legacy-task-ids: false` 关闭。

无论哪种方式，任务的经验、奖励、每日/每周周期与待领取界面都由 liupass 原生处理，LiuGuild 只负责上报进度。

## 推荐方式：扩展任务类型

LiuGuild 在启用时向 liupass 注册以下类型（无需在 LiuGuild 侧额外配置任务 ID）：

| 任务类型 `type` | 触发 | `target` | 每次进度 |
|---|---|---|---|
| `liuguild:join` | 加入工会 | 无 | 1 |
| `liuguild:create` | 创建工会 | 无 | 1 |
| `liuguild:war` | 参与一场工会战（取消的比赛不算） | 模式 ID（`match` / `season` / 自定义模式 key） | 1 |
| `liuguild:war-win` | 赢得一场工会战 | 模式 ID | 1 |
| `liuguild:war-kill` | 工会战中的一次击杀 | 模式 ID | 1 |
| `liuguild:checkin` | 完成每日签到 | 无 | 1 |
| `liuguild:donate` | 捐献个人工会资金 | 无 | 捐献金额 |
| `liuguild:bank-deposit` | 向工会银行存入金币 | 无 | 存入金额（默认关闭，见下） |

- 不写 `target` = 匹配任意；写了则按模式精确匹配（区分大小写，逐字符比较，不做规范化）。因此同一类型可以配置多个模式专属任务。
- `donate` / `bank-deposit` 的进度是**金额**，适合「累计捐献 10000 工会资金」这类任务；其余类型每次行为进度 +1。
- 每个通行证各自维护任务，进度写入玩家在该通行证的默认（或任务所属）通行证。

在 liupass 的 `tasks/` 下配置即可，例如赛季任务：

```yaml
tasks:
  - id: guild_war_play
    name: "工会协作：完成 3 场工会战"
    type: liuguild:war
    amount: 3
    xp: 500
    rewards: []

  - id: guild_war_win_match
    name: "在匹配战中获胜 1 次"
    type: liuguild:war-win
    target: match
    amount: 1
    xp: 300
    rewards: []

  - id: guild_donate
    name: "为工会累计捐献 10000 资金"
    type: liuguild:donate
    amount: 10000
    xp: 800
    rewards: []
```

配置无需等待 LiuGuild 注册：liupass 会按最新配置匹配；LiuGuild 未安装或未注册时该类型的任务不会推进，但不会报错。

## 开关（config.yml）

```yaml
hooks:
  liupass:
    enabled: true          # 总开关：关闭后不再上报任何任务进度
    join: true
    create: true
    war: true
    war-win: true
    war-kill: true
    checkin: true
    donate: true
    bank-deposit: false    # 成员可循环存取刷进度，默认关闭
    legacy-task-ids: true  # 是否保留旧版 tasks.yml 绑定
```

- 总开关只影响**任务进度**；工会战通行证经验由 `war-modes.yml` 的 `rewards` 控制，与之独立。
- `bank-deposit` 默认关闭：存入后取出、再存入可以无限刷进度。开启前请把对应任务设为一次性 `NORMAL`，或降低奖励。

## 旧版方式：tasks.yml

`tasks.yml` 通过 `event` → `task-id` 绑定推进 liupass 中已有的 `CUSTOM` 任务：

```yaml
enabled: true
bindings:
  play:
    event: WAR_PLAY            # JOIN / CREATE / WAR_PLAY / WAR_WIN / BANK_DEPOSIT
    task-id: guild_war_play    # 必须在 liupass 中已存在的任务 ID
    pass-id: ""                # 留空 = 玩家默认通行证
    count-amount: false        # true = 用事件金额作进度，否则一次行为 +1
```

- 旧版路径同样受 `hooks.liupass.legacy-task-ids` 与 `hooks.liupass.enabled` 控制。
- 旧版没有「签到」「捐献」「击杀」事件绑定；这些请使用上面的扩展类型。
- 建议新配置一律使用扩展任务类型；`tasks.yml` 仅用于迁移期。

## 通行证经验

两种来源互不冲突：

- **任务经验**：liupass 任务完成时按任务自身的 `xp` 入账（含 `liuguild:xxx` 任务）。
- **工会战经验**：`war-modes.yml` 的 `rewards.win-pass-xp` / `lose-pass-xp` / `pass-id`，由 `WarManager` 结算时发放；`pass-id` 留空使用玩家默认通行证。

## 运行注意

- 进度只在**举办工会战的子服**上报（出战名单保存在举办服内存）；liupass 数据经 MySQL/Redis 跨服共享，其他子服可见。
- LiuGuild 启动时注册类型，卸载时关闭句柄；liupass 热重载/重新启用后，LiuGuild 会在下次事件时自动重新注册。
- 已注册的任务类型不要求 liupass 里存在对应任务；没有匹配任务时进度自然为空。
- 取消的比赛不推进任务。入会等可由玩家反复触发的行为，建议配置为一次性任务，避免刷日常。
