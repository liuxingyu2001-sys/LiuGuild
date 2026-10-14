# 竞技场

`arena.yml` 中每个竞技场**自描述**：直接写「承办服 + 世界 + 坐标」，因此自定义程度最高，
既不依赖 liudungeon 也能用固定世界，需要动态开世界时再填 `template`。

```yaml
pregenerate: true            # 使用动态模板时是否预热区块
create-timeout-seconds: 60   # 动态世界创建超时

arenas:
  main:
    server: ""               # 承办服 server-id；留空 = 全局 / 发起匹配的服务器
    world: ""                # 固定世界名；留空 = 主世界
    template: ""             # 可选 liudungeon 模板；填写且存在时优先动态开世界
    radius: 48               # 预热半径（方块），0=不预热
    spawn-a:         { x: 0.5,  y: 70.0, z: 0.5,  yaw: 0.0,   pitch: 0.0 }
    spawn-b:         { x: 20.5, y: 70.0, z: 0.5,  yaw: 180.0, pitch: 0.0 }
    spawn-spectator: { x: 10.5, y: 80.0, z: 10.5, yaw: 0.0,   pitch: 30.0 }
```

## 字段说明

| 字段 | 说明 |
|---|---|
| `server` | 承办该场工会战的服务器 `server-id`。跨服时玩家会被传送到该服。 |
| `world` | 固定世界名（如 `world` / `arena_world`），留空用主世界。 |
| `template` | 可选，liudungeon 动态世界模板。填写且模板存在时动态创建独立竞技场世界。 |
| `radius` | 动态开世界时的区块预热半径。 |
| `spawn-<名>` | 任意数量的命名出生点（`spawn-a` / `spawn-b` / `spawn-c` / `spawn-d`…），坐标为 `x/y/z/yaw/pitch`。 |
| `spawn-spectator` | 旁观出生坐标（名固定为 `spectator`）。 |
| `team-a-spawn` / `team-b-spawn` | A / B 队分别使用哪个命名出生点（默认 `a` / `b`），因此可配 3 个以上并自由指定。 |

## 现场设置坐标

管理员站到目标位置后：

```
/guildadmin arena setspawn main a      # 设为出生点 a
/guildadmin arena setspawn main b      # 设为出生点 b
/guildadmin arena setspawn main c      # 设为出生点 c（可任意命名）
/guildadmin arena setspawn main d
/guildadmin arena setspawn main spec   # 设为旁观点（spec = spectator）
/guildadmin arena setteampoint main a c   # A 队使用出生点 c
/guildadmin arena setteampoint main b d   # B 队使用出生点 d
```

会立即写入 `arena.yml` 并热重载。也可在 GUI（竞技场编辑器）里点击「设置命名出生点…」用铁砧输入点名。

> 注意：`setspawn` 才是设出生点；`setworld <id> <世界名>` 设的是**世界**，`setspawn <id> <点名> c/d` 才能保存新的点位。

其它设置：

```
/guildadmin arena setworld main world_arena
/guildadmin arena setserver main arena-1
/guildadmin arena settemplate main guild_arena
/guildadmin arena setteampoint main a c   # 指定 A 队使用出生点 c
/guildadmin arena create pvp           # 新建固定坐标场地
/guildadmin arena info main            # 查看详情（含全部出生点）
```

## 引用方式

在 `war-modes.yml` 的模式里指定 `arena: <id>` 即可。多个模式可共用同一场地，也可各自独立。

## 完整可自定义项

每个竞技场都是自描述的，没有硬编码：

| 配置 | 说明 |
|---|---|
| `server` | 承办服 server-id（跨服时玩家会被传送到该服） |
| `world` | 固定世界名（留空 = 主世界） |
| `template` | liudungeon 动态模板（填了则优先动态开世界） |
| `radius` | 动态世界预热半径 |
| `spawn-a` / `spawn-b` / `spawn-spectator` | 三方出生点（x/y/z/yaw/pitch） |
| `temporary-world.builtin` | 开启后由 LiuGuild 自建临时世界（无需 liudungeon 模板），结束后自动卸载并删除 |
| `temporary-world.world-type` | 内置临时世界类型：`flat` / `normal` |
| `temporary-world.environment` | 动态世界环境：`NORMAL` / `NETHER` / `THE_END`（内置世界时生效） |
| `temporary-world.keep-on-end` | 结束后是否保留世界 |
| `temporary-world.name-format` | 动态世界名格式（`{id}` / `{time}`，内置世界时生效） |
| `temporary-world.time` | 固定时间（-1 = 不修改） |
| `temporary-world.weather` | `clear` / `rain` / `thunder`（空 = 不修改） |
| `temporary-world.difficulty` | `PEACEFUL` / `EASY` / `NORMAL` / `HARD` |
| `temporary-world.gamerules` | 任意 gamerule 开关（如 `keepInventory`、`fallDamage`） |
| `after-war.auto-return` | 结束后自动送回参赛前位置 |
| `after-war.return-delay-seconds` | 返回延迟（仅固定世界生效） |
| `after-war.end-spawn.enabled` | 启用统一集合点 |
| `after-war.end-spawn.world` | 集合点世界（留空 = 竞技场世界） |
| `after-war.end-spawn.x/y/z/yaw/pitch` | 集合点坐标 |

> 若 `after-war` 同时关闭 `auto-return` 且未启用 `end-spawn`，玩家将停留在原位；
> 当使用的是**动态世界**时该世界会被销毁，因此这种组合仅建议在 `keep-on-end: true` 时使用。

> **内置临时世界**：在竞技场 `temporary-world` 下设置 `builtin: true` 且不配置 `template` 时，
> LiuGuild 会用 Bukkit `WorldCreator` 自行创建世界——此时 `environment`、`world-type`、`name-format`
> 全部生效，战斗结束后自动卸载并异步删除世界目录（`keep-on-end: true` 时保留）。
> 由于是全新世界，请务必用 `/guildadmin arena setspawn` 重新设置 A/B/旁观出生点到实际地形高度。
