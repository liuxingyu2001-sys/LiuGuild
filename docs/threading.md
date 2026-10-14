# 线程模型、预加载与生命周期

本文把插件的“什么东西在哪个线程跑、什么时候预加载、什么时候结算”一次讲清楚，便于部署与排障。

## 1. 线程模型总览

Bukkit 只保证 **主线程（服务端 tick 线程）** 能安全操作世界/实体/背包/玩家。
本插件的规则：

| 类别 | 运行线程 | 说明 |
|---|---|---|
| 命令、GUI 点击、事件监听 | 主线程 | Bukkit 保证 |
| 世界/实体/背包/传送/发消息 | 主线程 | 所有这类调用都从主线程发起 |
| 数据库（JDBC）读写 | 异步 | 统一走 HikariCP；玩家触发的写入全部异步 |
| Redis 读写 | 异步 | 队列、发布、探活；combat 路径不阻塞主线程 |
| 工会内存缓存读写 | 主线程为主 | 缓存在读多写少时用 `ConcurrentHashMap`，重载时整体原子替换 |

辅助方法（`LiuGuild`）：

```java
plugin.async(runnable)   // 丢到异步线程
plugin.sync(runnable)    // 已在主线程则直接跑，否则排到主线程
plugin.syncDelayed(r, t) // 延迟 t tick 在主线程跑
```

## 2. 启动 / 重载预加载清单

启动与每次配置热重载时，以下内容会被**预先解析并缓存**，避免在玩家入场/打开菜单时重复计算：

| 预加载项 | 时机 | 作用 |
|---|---|---|
| 全部配置与消息 | 启动 + 热重载 | 解析为不可变 `Settings`，整体原子替换 |
| 工会数据 + 成员索引 | 启动（异步） | 一次性装配缓存与 `nameIndex`/`tagIndex` |
| **套装护甲/物品** | 启动 + 热重载（`KitService.preload()`） | 材质解析、CraftEngine 物品解析只做一次 |
| **套装玩家快照** | 启动 + 热重载（懒解码 + 预热） | Base64 解码 41 件物品只做一次 |
| 图标定义 `icons.yml` | 启动 + 热重载 | 供 GUI 直接取用 |
| 工会战模式 / 场地 / 竞技场 | 启动 + 热重载 | 供匹配、准备、结算直接取用 |
| 排行榜排序结果 | 按需 + TTL 缓存 | 避免频繁全量排序 |

> 结论：配置改动只需重载一次，之后所有热路径都是“读缓存”，不重复解析。

## 3. 每服定时任务清单（多服一致性）

所有子服都会跑下列定时器，因此凡是“全局只应发生一次”的行为都用 **Redis 抢锁** 保证单写者：

| 任务 | 周期 | 全局副作用 | 单写者保证 |
|---|---|---|---|
| 在线心跳 `presenceTask` | 心跳秒 | 写自己玩家的 presence 键 | 天然无冲突 |
| 配置热重载 `configWatchTask` | 5s | 仅重载配置（不重读数据） | 读操作，无副作用 |
| 僵尸工会清理 `cleanupTask` | 配置间隔 | 删除工会 | 抢锁 `cleanup` |
| 匹配 `queueTask` | 队列间隔 | 配对并创建工会战 | `war:matchlock` + 原子 `ZREM` |
| 工会战 tick `tickTask` | 1s | 倒计时/结算/回收 | 仅承办服有 `current`，他服空转 |
| 赛季到点 `seasonTask` | 1s | 开赛公告 + 自动入队 | 抢锁 `season:<模式>:<分钟>` + 广播 `SEASON_OPEN` |

详见 [shared-config.md](shared-config.md)。

## 4. 工会战生命周期与线程

```
匹配(matchTick, 异步)
  └─ 抢 matchlock → 选同模式两队 → 写库 → 广播 WAR_MATCHED
承办服收到 WAR_MATCHED (主线程 sync)
  └─ adoptWar → prepareArena
       ├─ 模板世界：liudungeon 异步创建（区块预热）→ 回到主线程应用世界设置
       ├─ 内置世界：WorldCreator 直接创建
       └─ 固定世界：直接用已加载世界
准备(PREPARING, 主线程 tick)  → 倒计时公告
开战(ACTIVE, 主线程 tick)
  ├─ 击杀计分（伤害事件在主线程）
  ├─ 生命池（纯计算 WarMath，可在主线程）
  ├─ 状态节流发布到 Redis（异步）
  └─ 比分 BossBar 每 tick 更新
结算(SETTLING, 主线程 tick)
  ├─ 立即：写库状态、事件、公告、工会层奖励（内存 + 异步落库）
  ├─ 分帧：玩家层奖励队列，每 tick 发 settle-batch-per-tick 人
  └─ 队列清空且计时到 → cleanup（传送回原位、回收世界）
```

### 结算为什么分帧

`war.settle-batch-per-tick`（默认 8）控制结算阶段每 tick 发放奖励的人数：

- 每个玩家的奖励（Vault 金币、通行证经验、称号、消息）在同一 tick 内不超过一批；
- 即使一次工会战有上百名参赛者，也不会出现“结算瞬间卡一下”；
- 单个玩家发放异常不会影响其余玩家（`WarMath.drainBatch` 独立捕获）；
- 奖励队列在 `cleanup` / 取消时清空，避免残留。

> 选择“主线程分帧”而非“异步发放”，是因为 Vault / 通行证 / 称号等第三方 API 的线程安全性不受本插件控制，
> 分帧既消除了长 tick，又完全避免跨线程调用第三方 API 的风险。

## 5. 数据库与 Redis 线程规则

- **玩家触发**的一切数据库/Redis 操作：异步（创建、加入、银行、驻地、队列、发布……）。
- **只读**的工会内存查询：主线程（零 IO）。
- **管理命令**中的库操作：多数异步；少数同步（如 `verify`、关服刷写）是有意为之且不频繁。
- **关服**：`onDisable` 先结算任务取消、再 `saveAllSync()` 单事务刷写全部工会/成员，最后关闭连接池。

## 6. 排查手段

`/guildadmin status` 会显示：

- 存储健康、跨服状态、工会数；
- 匹配队列 / 进行中 / 本服承办 / **待发放奖励数**；
- **预加载计数**（套装 / 场地 / 模式 / 图标）；
- 全部钩子（liudungeon / LiuChat / liupass / CraftEngine / SparrowSync / LuckyPeaches / Vault / PAPI / PlayerTitle / NotBounties / HuskHomes / mcMMO / SparrowUI）；
- 启动配置告警。

`/guildadmin verify [fix]`：校验内存数据一致性；`/guildadmin overrides`：查看/清除游戏内编辑覆盖层。
