# 配置

配置按功能模块拆分，每个文件独立，改一个模块无需触碰其它文件。

| 文件 | 作用 |
|---|---|
| `config.yml` | 本服系统设置、`server-id` |
| `database.yml` | 数据库（SQLite / MySQL） |
| `cross-server.yml` | 跨服 Redis、传送、心跳 |
| `guild.yml` | 工会玩法、权限、银行、图标费用 |
| `war.yml` | 工会战全局开关与**所有模式的默认值** |
| `war-modes.yml` | 各工会战模式（匹配 / 赛季 / 自定义） |
| `arena.yml` | 竞技场（承办服 + 世界 + 坐标） |
| `kit.yml` | 统一装备（可多套 + 玩家装备快照） |
| `gui.yml` | GUI 菜单布局 |
| `icons.yml` | 工会图标定义 |
| `messages.yml` | 多语言消息 |
| `server.yml` | **每服独立**，覆盖 `server-id`（不随共享配置同步） |

## 共享配置目录

在 `config.yml`：

```yaml
settings:
  shared-config-path: "/srv/liuguild-config"
  auto-reload-config: true
```

多台子服指向同一目录，改一处全服生效（插件每 5 秒检测 mtime 自动热重载）。
`server.yml` 始终从各服自己的插件目录读取，用于区分 `server-id`。

## config.yml

```yaml
settings:
  shared-config-path: ""
  auto-reload-config: true
  language: zh_CN
  debug: false
server-id: ""
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
```

- `messages.yml`：按语言段组织（内置 `zh_CN`），占位符用 `{name}`，同时支持 MiniMessage 与 `&` legacy 颜色代码（含 `&#RRGGBB`）。占位符值默认按纯文本插入以防注入。升级后新增的消息键会自动回退到内置默认，不会显示为键名。

## 热重载

- 自动：文件 mtime 变化后自动重载。
- 手动：`/guildadmin reload`。
