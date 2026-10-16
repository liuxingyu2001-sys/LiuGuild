# 配置

配置按功能模块拆分，每个文件独立，改一个模块无需触碰其它文件。

| 文件 | 作用 |
|---|---|
| `config.yml` | 本服系统设置（不含 `server-id`，已移至 `server.yml`） |
| `database.yml` | 数据库（SQLite / MySQL） |
| `cross-server.yml` | 跨服 Redis、传送、心跳 |
| `warehouse.yml` | 工会空间、逐级容量、收费货币、永久/临时解锁、过期与解散保留 |
| `guild.yml` | 工会玩法、权限、银行、图标费用 |
| `war.yml` | 工会战全局开关与**所有模式的默认值** |
| `war-modes.yml` | 各工会战模式（匹配 / 赛季 / 自定义） |
| `buffs.yml` | 公共工会资金购买的限时增益、等级槽位、费用与时长 |
| `arena.yml` | 竞技场（承办服 + 世界 + 坐标） |
| `kit.yml` | 统一装备（可多套 + 玩家装备快照） |
| `gui.yml` | GUI 菜单布局 |
| `icons.yml` | 工会图标定义 |
| `messages.yml` | 多语言消息 |
| `server.yml` | **每服独立**，设置唯一 `server-id`、工会战承办服 `war.server-mode` / `war.dedicated-server`，并可覆盖 `language` / `debug` / `auto-reload-config` / 任意键（`overrides`）（不随共享配置同步） |

## 共享配置目录

在 **本服** 的 `plugins/LiuGuild/config.yml`：

```yaml
settings:
  shared-config-path: "/srv/liuguild-config"
  auto-reload-config: true
```

多台子服指向同一目录，改一处全服生效（插件每 5 秒检测 mtime 自动热重载）。
`server.yml` 始终从各服自己的插件目录读取，用于区分 `server-id`。

> **重要：`server-id` 必须与该服在代理（BungeeCord / Velocity）`[servers]` 里登记的
> 名字完全一致**（例如 `zy`、`99`）。跨服传送、工会战承办服判断、跨服工会主页/商店
> 都以此为标识。若留空，插件会**自动生成**一个 `server-xxxxxxxx` 随机 id，它与代理名
> 不一致，会导致「A 服无法传送去承办服 B」「承办服不认领工会战」等问题。
> 插件会在玩家进入时通过 `GetServer` 子通道探测代理名并在日志中给出警告，但仍建议
> 手动把 `server-id` 写成代理登记名。`arena.yml` 的 `server` 与 `war.dedicated-server`
> 填的同样是**代理登记名**。

> 共享目录下哪些文件共享、哪些必须每服独立，以及多服定时任务如何避免重复执行、
> 游戏内编辑如何不破坏带注释的基础文件，详见 **[shared-config.md](shared-config.md)**。

## config.yml

```yaml
settings:
  shared-config-path: ""
  auto-reload-config: true
  language: zh_CN
  debug: false
# server-id 不在此文件，见 server.yml
```

## database.yml

```yaml
type: sqlite            # sqlite | mysql
host: 127.0.0.1
port: 3306
database: minecraft
user: root
password: ""
params: "useSSL=false&characterEncoding=utf8&serverTimezone=Asia/Shanghai&rewriteBatchedStatements=true"
sqlite-file: plugins/LiuGuild/data.db
table-prefix: liuguild
pool-size: 5
connection-timeout-ms: 5000
auto-create-database: true   # MySQL 自动 CREATE DATABASE
auto-create-tables: true     # 自动建表
```

## cross-server.yml

```yaml
enabled: true
redis: { host: 127.0.0.1, port: 6379, password: "", database: 0, pool-size: 8, timeout-ms: 2000, channel: liuguild }
transfer: { enabled: true }
heartbeat-seconds: 5
local-fallback: true     # Redis 不可用时降级为单服
```

## guild.yml

工会名称/标签长度、创建费、成员上限与每级增长、邀请有效期与冷却、银行开关与税率、
升级所需点数与成长系数、最高等级、友伤开关、官员权限（邀请/踢人/宣战/公告）、默认图标与图标更换费用。

## war.yml

仅全局开关与 `war.defaults`（所有模式的公共默认值）。详见 [war.md](war.md)。

## war-modes.yml

每个模式的完整独立配置，未写的项继承 `war.defaults`。详见 [war.md](war.md)。

## arena.yml

每个竞技场直接写「承办服 + 世界 + 坐标」。详见 [arena.md](arena.md)。

## kit.yml

多套装备 `kits.<key>`，可现场保存玩家装备快照。详见 [kit.md](kit.md)。

## gui.yml / icons.yml / messages.yml

- `gui.yml`：所有菜单标题、行数、按钮槽位、材质、名称、Lore。
- `icons.yml`：工会图标定义（支持 `CE:命名空间:id`）。
## 可选插件钩子（config.yml）

```yaml
hooks:
  mcmmo:
    enabled: true                    # mcMMO 兼容总开关（默认开启）
    suppress-abilities-in-war: true  # 工会战期间静默屏蔽技能
    suppress-xp-in-war: false        # 工会战期间静默屏蔽技能经验
  liupass:
    enabled: true                    # 向 liupass 上报工会玩法任务进度（需 Liupass 1.6.3+）
    join: true                       # liuguild:join
    create: true                     # liuguild:create
    war: true                        # liuguild:war（target = 模式 ID）
    war-win: true                    # liuguild:war-win
    war-kill: true                   # liuguild:war-kill
    checkin: true                    # liuguild:checkin
    donate: true                     # liuguild:donate（进度 = 金额）
    bank-deposit: false              # liuguild:bank-deposit（可循环刷进度，默认关闭）
    legacy-task-ids: true            # 兼容旧版 tasks.yml 的任务 ID 绑定
```

`hooks.liupass` 只控制任务进度；工会战通行证经验由 `war-modes.yml` 配置，完整用法见 [tasks.md](tasks.md)。

- `messages.yml`：按语言段组织（内置 `zh_CN`），占位符用 `{name}`，同时支持 MiniMessage 与 `&` legacy 颜色代码（含 `&#RRGGBB`）。占位符值默认按纯文本插入以防注入。升级后新增的消息键会自动回退到内置默认，不会显示为键名。

## 热重载

- 自动：文件 mtime 变化后自动重载。
- 手动：`/guildadmin reload`。


## 工会头衔 / 商店（`guild.yml`）与旧仓库配置

仓库配置现已迁移到独立 `warehouse.yml`。下面的 `guild.warehouse` 仅为旧版迁移参考，新版运行规则请编辑 `warehouse.yml`。首次迁移后修改旧仓库节点不会改变新规则。

```yaml
guild:
  # 玩家在线活跃 → 工会资金
  activity:
    enabled: true
    interval-seconds: 300
    points-per-player: 2
    daily-cap: 1000
    afk-seconds: 180
    min-online: 1

  titles:
    enabled: true
    max: 12
    manage-rank: OFFICER

  warehouse:
    enabled: true
    require-rent: true   # 必须先购买使用时间才能打开
    base-slots: 27
    slots-per-level: 9
    max-slots: 54
    manage-rank: OFFICER
    rent:
      price-per-day: 200 # 每 24 小时消耗的工会资金（点数）
      options: [1, 7, 30]
      max-days: 90
    upgrade:
      max-level: 3
      base-cost: 1000
      cost-growth: 2.0

  shop:
    enabled: true
    max-listings: 10
    max-stack-per-listing: 2304
    max-per-purchase: 2304
    max-supply: 2304
    tax-percent: 5.0
    manage-rank: OFFICER
```

- [titles.md](titles.md) —— 工会头衔
- [activity.md](activity.md) —— 玩家在线活跃发工会资金（活跃判定 + 每日上限）
- [warehouse.md](warehouse.md) —— 工会仓库（跨服独占锁；用工会资金购买时长 / 升级容量）
- [shop.md](shop.md) —— 工会商店（按工会隔离；个人工会资金购买，税后货款入工会点数）
