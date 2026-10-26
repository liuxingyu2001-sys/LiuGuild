# 共享配置目录与多服一致性

跨服部署时，多台子服会把 `settings.shared-config-path` 指向同一个目录，实现“改一处、全服生效”。
但这带来两类风险：**配置互相覆盖** 与 **定时任务被多台子服重复执行**。
本文说明哪些文件共享、哪些必须每服独立，以及插件如何从机制上避免重复与覆盖。

## 1. 目录模型

```
plugins/LiuGuild/
├── config.yml        ← 每服的“引导配置”（只用于读取 shared-config-path 等）
├── server.yml        ← 每服独立，【不共享】，必须设置唯一 server-id
└── <数据文件>

<共享目录>/  (由 settings.shared-config-path 指定)
├── config.yml  database.yml  cross-server.yml  guild.yml
├── war.yml  war-modes.yml  arena.yml  kit.yml
├── gui.yml  icons.yml  messages.yml  tasks.yml
└── migration-backups/ ← 升级时旧覆盖文件的备份，不参与配置加载
```

> `settings.shared-config-path` 只从 **本服** 的 `plugins/LiuGuild/config.yml` 读取；
> 共享目录里的同名键会被忽略（避免“A 服把共享路径指向 B 目录”这类递归/自指问题）。

## 2. 哪些共享、哪些不共享

| 文件 | 是否共享 | 说明 |
|---|---|---|
| `server.yml` | ❌ **每服独立** | 唯一 `server-id`；可覆盖 `language` / `debug` / `auto-reload-config`；`webhook` / `qqbot` 外部通知；通用每服覆盖 `overrides` |
| `plugins/LiuGuild/config.yml` | ❌ 每服引导 | 仅用于读取共享路径与自动重载开关 |
| `database.yml` | ✅ 共享 | 跨服必须同一库、同一表前缀 |
| `cross-server.yml` | ✅ 共享 | 同一 Redis、同一 `channel`、同一 `database` |
| `guild.yml` / `warehouse.yml` / `buffs.yml` / `war.yml` / `war-modes.yml` / `arena.yml` / `kit.yml` / `gui.yml` / `icons.yml` / `messages.yml` / `tasks.yml` | ✅ 共享 | 玩法与展示配置，全服一致 |
| 共享目录 `config.yml` | ✅ 共享 | 全服一致（`server-id` 已移至 `server.yml`） |

**每服独立设置放在 `server.yml`：** `server-id`、可选语言/调试。竞技场承办位置写在共享 `arena.yml`。
其余共享即可。

### 哪些设置【不应】共享

凡是「不同子服取值不同」的都应放在 `server.yml`，而不是共享文件：

| 设置 | 为何每服不同 | 配置位置 |
|---|---|---|
| `server-id` | 每服唯一 | `server.yml: server-id` |
| `language` / `debug` | 各服可不同 | `server.yml`（已支持） |
| `webhook` / `qqbot` | 多服同时启用会重复推送/重复建连 | `server.yml`（不随共享配置同步） |
| 其它任意键（如 `hooks.*`、`transfer.enabled`） | 各服安装插件/策略不同 | `server.yml: overrides.<模块>: { 路径: 值 }` |

`server.yml` 的通用覆盖示例（只影响本服，不写回共享目录）：

```yaml
server-id: "survival-1"

overrides:
  config.yml:
    debug: false
    hooks.mcmmo.block-in-war: true
  cross-server.yml:
    transfer.enabled: true
```

## 3. 每个模块只有一份生效文件

通过 GUI 或 `/guildadmin` 修改竞技场出生点、套装、模式等时，直接保存到共享目录中的同名 YAML。例如设置出生点直接修改 `arena.yml`；手工编辑也修改这份文件。

- 保存前重新读取磁盘文件，只修改目标节点，保留其它配置与可解析的 YAML 注释。
- 多服通过文件锁串行执行读取、修改、保存，并使用临时文件 + 原子替换，避免读到半份文件。
- `server.yml` 仍是每服独立设置，只影响运行配置，不会被写入共享文件。

### 从旧版本升级

首次加载自动将旧 `overrides/<文件名>` 合并到同名基础 YAML（沿用旧覆盖值及删除标记），成功保存后移除旧覆盖文件。原覆盖文件备份到 `migration-backups/`，备份不参与加载。迁移失败时保留旧文件并停止加载，不会静默丢弃游戏内修改。

升级完成后只维护基础文件，例如 `shared/LiuGuild-config/arena.yml`。不再生成游戏内编辑覆盖层，也不再提供 `/guildadmin overrides` 命令。滚动升级期间应避免旧版子服继续编辑配置，完成全部子服升级后再编辑。

## 4. 定时任务不会被多台子服重复执行

共享配置意味着 **同一时刻所有子服都会看到相同的计划**。插件对“全局只应发生一次”的行为统一使用
**Redis 原子锁**（`SET key NX EX ttl`）保证单写者；Redis 不可用时自动退化为单服语义。

| 行为 | 机制 | 结果 |
|---|---|---|
| **赛季到点开赛** | 到期后所有子服抢锁 `season:<模式>:<分钟>`（TTL 180s），仅一台获得；获得者通过 Redis 广播 `SEASON_OPEN` | 全服**恰好一次**公告与自动入队；每台子服各自公告并拉入本服在线工会 |
| **匹配队列配对** | `war:matchlock` 全局锁 + `claimPair` 原子 `ZREM` | 同一对工会只会被一台子服匹配一次 |
| **僵尸工会清理** | 每个周期抢锁 `cleanup`（TTL = 清理间隔） | 每个周期只有一台子服执行删除 |
| **在线心跳** | 每个玩家写自己的 `presence:<uuid>` 键，值 = 所在 server-id | 各服互不干扰，不会互相覆盖 |
| **工会数据变更** | 变更后广播 `GUILD_CHANGED`，各服按来源跳过自己 | 不会重复加载 |
| **工会频道/公告** | 广播 `GUILD_ANNOUNCE` / `GUILD_CHAT`，接收端跳过来源服 | 不会重复投递 |

> 抢锁失败或 Redis 瞬时异常时，赛季任务**不会**记录“已完成”，会在同一分钟内的下一秒自动重试，
> 因此不会因为一次网络抖动而漏开赛。

## 5. 配置变更如何传播

- 任一子服修改配置后，文件 mtime 变化，其它子服通过指纹轮询感知并**自动热重载**。
- 自动热重载只重载配置/消息/定时任务，**不会**全表重读工会数据（避免风暴）。
- 需要连同数据一起重读时，使用管理命令 `/guildadmin reload`。
- `server-id`、`cross-server.*`、`transfer.enabled` 在启动时固化；热重载会保留旧连接并记录警告，修改后需重启。

## 6. 共享目录配置检查清单

- [ ] 每台子服 `server.yml` 的 `server-id` **唯一**。
- [ ] 所有子服 `database.yml`、`cross-server.yml` 指向同一库 / 同一 Redis、同一 `channel`。
- [ ] 共享目录位于所有子服都能读写的共享盘（NFS/SMB 均可），且 **不要** 指向某个插件目录本身。
- [ ] 修改共享配置后观察是否触发自动重载；如需即时生效执行 `/guildadmin reload`。
- [ ] 多服环境下不要同时用两份不同的 `shared-config-path` 混用（会导致不同子服读到不同配置）。

## 7. 常见误配与后果

| 误配 | 后果 | 处理 |
|---|---|---|
| 多台子服 `server-id` 相同 | 在线状态/工会战路由错乱、消息来源判定失效 | 在 `server.yml` 设为唯一值 |
| 各服 `cross-server.yml` 的 `channel` 不一致 | 视为不同群组，跨服工会战完全不通 | 统一 `channel` |
| 各服 `database.yml` 不同库 | 工会数据割裂 | 统一库与表前缀 |
| 共享目录不可写 | 游戏内编辑失败并记录告警，回退只读 | 授予写权限或改用手改基础文件 |
| 手工修改后本服值仍不同 | `server.yml` 有本服独立设置 | 检查本服 `server.yml` 的对应键 |
