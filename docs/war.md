# 工会战

工会战支持**多种模式**，每种模式配置完全独立，未写的项继承 `war.yml` 的 `war.defaults`。
内置两种模式：

- **匹配工会战（`match`）**：任意时间，玩家在工会菜单点击「参与匹配」，凑齐两队即刻开战。
- **赛季工会战（`season`）**：在配置的时间表到达时自动开启。

可复制任一段落新增自定义模式（如 `nightly`）。

## 一、全局：war.yml

```yaml
war:
  enabled: true
  server-mode: false          # 本服是否为工会战专属承办服
  dedicated-server: ""        # 全局默认承办服 server-id（模式/场地可覆盖）
  queue-interval-seconds: 5   # 匹配轮询间隔
  match-timeout-seconds: 180  # 队列超时，0=不超时
  default-mode: match
  broadcast-queue: true       # 有人参与匹配时是否全服广播
  defaults:                   # 所有模式的默认值
    fair-equipment: true
    fair-health: true
    kit: default
    team-size: 10
    min-members: 1
    min-level: 1
    prepare-seconds: 30
    duration-seconds: 600
    settle-seconds: 10
    allow-spectators: true
    score: { kill: 1, death: -1, objective: 5 }
    rewards: { win-money: 5000.0, lose-money: 1000.0, win-points: 200, lose-points: 50, win-pass-xp: 500, lose-pass-xp: 100, pass-id: "", win-title-id: 0 }
```

## 二、模式：war-modes.yml

```yaml
modes:
  match:
    enabled: true
    display: "匹配工会战"
    description: "随时匹配，人满即战。"
    arena: main              # 引用 arena.yml
    fair-equipment: true     # 是否发放统一公平装备
    fair-health: true        # 是否屏蔽 LuckyPeaches 蟠桃血量
    kit: default             # 使用 kit.yml 的哪套装备
    team-size: 10
    min-members: 1
    min-level: 1
    prepare-seconds: 30
    duration-seconds: 600
    settle-seconds: 10
    allow-spectators: true
    broadcast-queue: true
    dedicated-server: ""     # 覆盖承办服
    score: { kill: 1, death: -1, objective: 5 }
    rewards: { ... }

  season:
    enabled: true
    display: "赛季工会战"
    arena: main
    fair-equipment: true
    fair-health: true
    kit: season
    min-members: 2
    min-level: 3
    duration-seconds: 900
    schedule:
      - "sat 20:00"          # 周六 20:00
      - "sun 20:00"
    season:
      window-minutes: 10     # 到点后的报名/匹配窗口
      auto-enqueue: false    # 到点是否自动把所有符合条件的工会加入队列
      points-multiplier: 1.0 # 赛季奖励点数倍率
```

### 时间表语法

| 写法 | 含义 |
|---|---|
| `"20:00"` / `"* 20:00"` | 每天 20:00 |
| `"sat 20:00"` | 每周六 20:00 |
| `"mon-fri 20:00"` | 周一至周五 20:00 |
| `"sat,sun 21:30"` | 周六与周日 21:30 |

到点后：
1. 若 `auto-enqueue: false`，广播开启提示，玩家在菜单/指令中参与匹配；
2. 若 `auto-enqueue: true`，自动把等级达到 `min-level` 且在线人数达到 `min-members` 的工会加入队列。

管理员也可 ` /guildadmin mode start season` 强制开启。

## 三、匹配流程

1. 玩家打开工会菜单 → 工会战 → 点击某模式 → 参与匹配。
2. 其他玩家收到全服提示：`war-queue-broadcast`。
3. 匹配器每 `queue-interval-seconds` 秒扫描队列，**同模式**两两配对。
4. 配对成功 → 广播 `war-match-found`，进入准备阶段。
5. `prepare-seconds` 后进入战斗，`duration-seconds` 后结算。
6. 结算展示 `settle-seconds` 后清理并回收竞技场。

## 四、承办服与跨服

- `war.server-mode: true` 的服务器可专门承接工会战。
- 承办服优先级：**模式 `dedicated-server` > 场地 `server` > 全局 `dedicated-server` > 发起匹配的服务器**。
- 非承办服成员收到匹配通知后用 `/guild war join` 或菜单按钮，经 BungeeCord `Connect` 传送到承办服。

## 五、公平竞技

- `fair-equipment: true`：入战时发放 `kit` 指定的统一装备（见 [kit.md](kit.md)）。
- `fair-health: true`：入战时屏蔽 LuckyPeaches 的蟠桃血量加成，出战时恢复（见 [hooks.md](hooks.md)）。
- 赛后通过 Sparrow Sync 快照（缺失时本地快照）完整还原玩家原装备与状态。

## 六、胜负与奖励

- 击杀：`score.kill`；死亡：`score.death`（可为负）；目标：`score.objective`。
- 比分高者胜，相等为平局。
- 奖励：胜/负方的金币、工会点数、liupass 通行证经验、PlayerTitle 称号、截图点数倍率。
- 友谊赛保护：同会成员默认不可互相伤害（`guild.yml` 的 `friendly-fire`）。

## 七、赛事数据模型

`guild_wars` 表字段包含 `mode`、`state`、双方比分、`host_server`、`arena_world` 等。
历史战绩 `/guild war history`，进行中 `/guildadmin war list`。
