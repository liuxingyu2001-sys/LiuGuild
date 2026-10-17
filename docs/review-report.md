# LiuGuild 全面深度审查报告（共 11 轮 + 终审）

- 仓库：`/home/liu/plugins/LiuGuild`，分支 `main`
- 起始基线：`1016086`
- 构建：`mvn -o clean package -Dlicense.enabled=false`
- 测试：6 个测试类，**35** 个用例全部通过
- 产物：`target/Liu-LiuGuild-0.1.0.jar`（含 relocate 后的 Sparrow UI）
- 原则：每轮独立构建 + `git diff --check`，通过后单独提交并推送；不覆盖用户改动

---

## 一、总览

| 轮次 | 提交 | 主题 |
|---|---|---|
| 1 | `85cf9d5` | 创建/加入事务原子性与银行边界 |
| 2 | `c998651` | 统一文本格式（MiniMessage/legacy/RGB）、防注入、权限 |
| 3 | `1bcac86` | 工会战结算/重载/关服安全与生命池可测化 |
| 4 | `97baa04` | 跨服队列清理与 mcMMO 技能静默屏蔽 |
| 5 | `5fd279e` | 升级兼容（消息默认合并）、频道同步性能 |
| 6 | `a1a398d` | 工会驻地（HuskHomes，可选依赖） |
| 7 | `4ed9d72` | 补全官员权限校验、GUI 驻地入口与占位符 |
| 8 | `3d759c5` | 防自伤操作、GUI 退出确认 |
| 9 | `1b0c440` | 僵尸工会清理（自动/手动，可配置阈值） |
| 10 | `d3503e5` | 引入 Sparrow UI 界面引擎（relocate 打包） |
| 11 | `1bcac86`→`e1b8c00` | 工会战场地/临时世界/模板/战后返回全量可配置 |
| 终审 | `17b98f2` | 移除重复结算死代码、点数溢出、API 线程安全 |

> 另有前 5 轮性能/修复：`5019061`（排队异步）、`30c0c0d`（解散 NPE/脏数据）、`fa75f68`（缓存原子替换）、`bc0b039`（排行榜缓存）、`e6fd9f4`（战况节流+公告异步）。

---

## 二、核心缺陷修复（按严重度）

### 严重（数据/经济）
1. **创建工会非原子**：原实现先扣费、再分两步异步写库，失败会留下“扣钱无工会”。现改为**单事务**写入工会+会长，失败回滚缓存并尝试退款（`85cf9d5`）。
2. **加入工会先改内存后写库**：数据库失败仍显示已入会。现改为事务插入 + 成员计数自增，成功后才提示与派发事件。
3. **关服/重载会结算奖励**：`WarManager.disable()` 原本调用 `forceEnd()` → `applyRewards()`，导致 `/guildadmin reload` 或关服会“结束比赛并发放金币/工会资金/通行证经验/称号”。现改为**关服取消比赛不结算**，重载只重启定时任务（`1bcac86`）。
4. **会重复结算的死代码**：`GuildManager.recordWarResult` 与 `rewardGuild` 逻辑重复，若被调用会双倍计胜场与点数。已移除（`17b98f2`）。
5. **点数溢出**：`points + delta` 可能长整型溢出。已加饱和处理与 `Long.MIN_VALUE` 保护。

### 严重（崩溃/韧性）
6. **脏数据拖垮缓存**：任何一行 `player_uuid`/`leader_uuid` 非法都会让 `UUID.fromString` 抛异常，导致**整份工会缓存加载失败**。现改为容错解析，跳过脏行（`30c0c0d`，含回归测试）。
7. **解散 NPE**：`/guild disband` 直接对可能为 null 的 `leaderUuid()` 调用 `.equals`。已加空值保护。
8. **缓存重载期间“无工会”**：`clear() + putAll()` 与 `reloadGuild` 的“先删后加”会产生瞬时空档，影响战斗/守卫判定。现改为**引用原子替换**与“先建后换”。

### 中等（权限/安全）
9. **MiniMessage 注入**：消息模板先拼字符串再解析，玩家昵称/工会名中的 `<click:run_command:...>` 可被当作可点击命令。现占位符默认**纯文本插入**，另提供显式富文本 API；玩家公告/简介保留颜色但**剥离点击/悬浮事件**（`c998651`）。
10. **权限声明未生效**：`liuguild.use/create/war` 只在 `plugin.yml` 声明却从未校验；`officerInvite` / `officerDeclareWar` 配置也未生效（任意成员可邀请/代表全会上报匹配）。现均在命令层与管理层双重校验。
11. **自伤操作**：GUI 中会长点击自己头像可把自己降级，导致工会**没有会长**。已对 promote/demote/transfer/kick 增加自目标保护。

### 体验/数据一致性
12. **文本格式不统一**：全插件现支持 MiniMessage + `&`/`§` legacy + `&#RRGGBB`，同一段可混排。
13. **升级后消息缺失**：旧 `messages.yml` 缺新键会显示成键名。现先铺内置默认再用用户文件覆盖。
14. **公告/简介超长**：`VARCHAR(255)` 可能截断/报错。已加长度校验与提示。

---

## 三、性能优化（全部消除主线程阻塞 I/O）

| 问题 | 优化 |
|---|---|
| 工会战排队：`isQueued` + `queueGuild`（ZADD/HSET/EXPIRE）在主线程执行 | 移到异步线程，成功后回主线程提示（`5019061`） |
| `queuedMode` 每次拉取整份队列（ZRANGE + N×HGETALL） | 改为单次 `HGET`；`queueSize` 改为 `ZCARD` |
| 战况上报：每次击杀都阻塞 Redis PUBLISH | 500ms 节流 + 异步发布，结束/取消时强制发布（`e6fd9f4`） |
| 工会公告：跨服 PUBLISH 在主线程 | 改为异步发布 |
| 排行榜：`%liuguild_top_*%` 每次渲染都全量排序所有工会 | 3 秒 TTL 缓存（`bc0b039`） |
| 工会频道：每心跳所有玩家 × 所有频道反射同步 | 增加“玩家→频道”签名，无变化直接跳过 |
| 跨服队列残留 | 清理过期元数据对应的 zset 成员，避免永久排队 |

---

## 四、功能完善

1. **工会驻地（HuskHomes）**：坐标自存数据库（`guild_homes`），传送交由 HuskHomes，天然支持计时与跨服；`/guild home [槽位] | set | del | list`；按等级开放槽位、冷却、工会战禁用、GUI 入口、占位符 `%liuguild_home_slots%`；未安装时优雅降级。
2. **僵尸工会清理**：明确判定=「无人在线」且「超过 `inactive-days` 无活动」（可选成员数上限保护）；自动定时 + 管理员 `/guildadmin cleanup [dry|confirm]`；默认试运行；`last_active_at` 在成员登录（1 小时节流）与工会变更时更新。
3. **mcMMO 技能静默屏蔽**：仅用其公开事件（取消能力激活、经验置 0），只对工会战参赛者生效，可配置开关、**默认开启**。
4. **Sparrow UI 界面引擎**：随插件打包并 relocate 到 `com.liu.liuguild.libs.sparrow.ui`（避免与其他插件冲突）；`gui.engine: legacy|auto|sparrow`，默认 `auto`，失败自动回退内置 GUI。
5. **工会战全量可配置**（本轮重点）：场地/三方出生点/承办服/世界/动态模板，以及**临时世界**（环境、保留、命名格式、gamerule、时间、天气、难度）与**战后行为**（自动返回、返回延迟、统一集合点及集合世界）。

---

## 五、最终深度审查（终审）

- 移除 `recordWarResult`、空的 `WarManager.onDeath`、未使用的 `huskHomesPresent`。
- `GuildAPI.sendGuildMessage` 改为切主线程执行，避免跨线程投递实体消息。
- 全仓扫描确认无未保护的 `UUID.fromString`（其余均在 try/catch 内）。
- 复核 `guilds`/`memberIndex` 为 volatile + 原子替换，`top` 缓存 TTL 过期自愈。
- 引擎回退、`safe(...)` 包裹保证任何可选组件异常都不会导致插件启动失败。

---

## 六、集成状态

**已对接**：Vault、PlaceholderAPI、LiuChat（私密频道+审核后跨服）、liupass（任务）、liudungeon（动态竞技场）、Sparrow Sync、LuckyPeaches、PlayerTitle、NotBounties、mcMMO、HuskHomes、CraftEngine、Sparrow UI、LuckPerms。

**已评估未接入**：Citizens、CustomNameplates、MCPets、WorldGuard、PacketEvents、HuskHomes 之外的传送/宠物/区域类。原因：未确定具体玩法需求，避免引入非必要依赖；如需可单独排期，且只调用其公开 API（这些插件作者不在可修改授权范围内）。

---

## 七、测试与验证

- 6 个测试类 / **35** 个用例：
  - `GuildRepositoryTest`（SQLite 真实连接）：事务创建、重复成员、银行边界与溢出、驻地持久化、脏数据跳过；
  - `TextTest`：MiniMessage/legacy/RGB/混排、注入防护、事件剥离；
  - `WarMathTest`：生命池均衡与边界；
  - `GuildSettingsTest`：驻地槽位、僵尸工会判定；
  - `ArenaDefTest`：场地/临时世界/战后配置解析；
  - `CrossServerBusTest`：跨服载荷编解码与分隔符净化。
- 每轮均 `BUILD SUCCESS`；`git diff --check` 干净。

## 八、遗留风险（如实说明）

1. **未做真实服务端联调**：跨服 Redis、传送、装备还原、mcMMO 屏蔽、Sparrow UI 渲染均为单元测试/编译验证，需在测试服做端到端验证。
2. **名称/标签无唯一索引**：仍为应用层检查 + 事务，极端跨服并发仍可能撞名；需数据库唯一索引 + 旧数据清理迁移。
3. **离线玩家快照**：关服时若玩家离线且无 Sparrow 快照，本地内存快照会丢失。
4. **Sparrow UI 为 Beta**：API 可能变更；已用 `auto`+回退把风险限制在主菜单，可用 `gui.engine: legacy` 完全关闭。
5. **结算为主线程同步**：大规模发放 Vault/通行证时可评估异步化（需处理离线与回滚）。

## 九、复现

```bash
cd /home/liu/plugins/LiuGuild
mvn -o clean package -Dlicense.enabled=false   # 35 tests
ls target/Liu-LiuGuild-0.1.0.jar
```

---

## 十、第二轮深审（管理完善 + 缺陷修复）

在管理指令与图形化编辑器扩展之后，又做了一轮全量复查，重点修复“配置可写但不生效 / 跨服误判 / 权限空转”类问题。

### 修复的缺陷
| 严重度 | 问题 | 修复 |
|---|---|---|
| 高 | 僵尸工会清理只看**本服**在线成员，跨服有人在线也会被删 | 新增 `countNetworkOnline`，本服+其它子服去重统计；扫描转异步，删除回主线程 |
| 高 | 竞技场 `keep-on-end` 可配置但**从不生效**，动态世界总被销毁 | `cleanup()` 按该开关决定销毁/保留 |
| 中 | 解散工会时若正在进行工会战，战局仍引用已删工会 | `onGuildDisbanded` 取消该场战斗并出队 |
| 中 | 并发创建同名工会存在竞态 | 新增 `pendingNames/pendingTags` 预留集合，关闭进程内竞态 |
| 中 | `temporary-world.environment` / `name-format` 为**死配置**（liudungeon 自命名） | 新增内置临时世界（`builtin` + `WorldCreator`），environment/name-format/world-type 全部生效，结束后卸载并异步删目录 |
| 中 | 进入队列的成员数校验只看本服 | 改为异步跨服在线人数校验 |
| 低 | 无经济插件时创建回滚会误报“退款失败” | 仅在经济可用时才尝试退款 |
| 低 | `deleteGuild` 三条 DELETE 非事务，可能留下孤儿数据 | 改为显式事务 + 回滚 |
| 低 | 套装名未过滤 `.`/空格，会破坏配置路径 | `sanitizeKey` 归一化 |
| 低 | 内置世界返回值延迟会把玩家送到已删除世界 | 内置/动态世界一律不延迟返回 |
| 低 | 模块关闭异常被静默吞掉 | 记 warning 日志 |
| 清理 | 死代码 `returnHome`、无效 GUI 按钮 `admin_kit` | 删除 |

### 新增管理能力
- 指令：`/guildadmin verify [fix]` —— 校验并修复 `member_count`、会长成员关系、双向成员索引。
- 指令：`/guildadmin` 子权限全部落地（`liuguild.admin.*`，含新增 `verify`），不再“声明即空转”。
- GUI：管理面板新增「数据校验」「僵尸工会清理」入口；竞技场编辑器新增「内置临时世界」「世界类型」开关。
- 工会战：实时比分 **BossBar**（比分 + 剩余时间，`war.bossbar` 可关）。
- 状态：`/guildadmin status` 汇总全部钩子（含 HuskHomes / mcMMO / SparrowUI）。
- 聊天：工会频道消息格式改为可配置 `channels.guild-format`。

### 验证
- 每步 `mvn -o clean package -Dlicense.enabled=false` → **BUILD SUCCESS**，**35** 用例全绿。
- 产物 `target/Liu-LiuGuild-0.1.0.jar`：578 个已重定位的 Sparrow 类，0 处 `net/momirealms` 泄漏。

### 遗留风险更新
- 名称/标签唯一性：已增加**进程内**预留，跨服并发仍有极小概率；DB 唯一索引仍建议后续补。
- `temporary-world` 内置世界为全新地形，管理员需重新用 `setspawn` 设定 A/B/旁观出生点。

---

## 十一、生产化加固（从未部署 → 面向正式开服）

由于插件尚未上线，本轮按“全新部署”标准收紧约束，不再保留历史包袱，并对生产环境的关键点逐项加固。

### 数据层
- **名称/缩写数据库唯一索引**：`guilds.name`、`guilds.tag` 在 SQLite（`COLLATE NOCASE`）与 MySQL（默认大小写不敏感排序规则）上建立 `UNIQUE` 索引，从数据库层兜底跨服并发，不再只依赖应用层检查。
- **重复键识别**：新增 `GuildRepository.isDuplicateKey`，并发撞名时给出“名称被占用”而非“创建失败”。
- **删会事务化**：`deleteGuild` 改为单事务 + 回滚，避免残留孤儿成员/驻地。
- **关服刷写**：新增 `flushAll`（单事务批处理）与 `GuildManager.saveAllSync`，`onDisable` 时同步刷写全部工会/成员，消除异步写入的最后丢失窗口。
- **SCAN 替代 KEYS**：`RedisBridge.onlineServers` 改用游标 `SCAN`，避免在共享 Redis 上阻塞。

### 生命周期与热重载
- **区分重载**：新增 `reloadSettings()`（仅配置/消息/定时任务）与 `reloadAll()`（含数据重读）。竞技场/套装/模式等 GUI 与指令编辑只做配置热重载，不再全表重读。
- **配置检查**：启动后自动校验并在控制台列出可操作告警（缺经济、缺 HuskHomes/mcMMO、场地/模式引用错误、模板缺失、Redis 未连接等），`/guildadmin status` 同步展示。

### 权限
- **子权限可独立授予**：移除 `/guildadmin` 的命令级父权限门槛与 in-code 父权限硬校验，`liuguild.admin.arena` 等可单独授予；Tab 补全按发送者权限过滤。

### 正确性与安全
- **公平装备强制清包**：`fairEquipment` 模式下无视 `clear-inventory:false` 强制清空，杜绝自带物品带入。
- **竞技场保留世界生效**：`keep-on-end` 真正决定内置/动态世界是否保留与回收。
- **内置临时世界**：`environment` / `name-format` / `world-type` 真正生效，结束后卸载并异步删除目录。

### 验证
- `mvn -o clean package -Dlicense.enabled=false` → **BUILD SUCCESS**，**37** 用例全绿（新增唯一索引与 flushAll 回归）。
- `target/Liu-LiuGuild-0.1.0.jar`：578 个已重定位 Sparrow 类，0 处 `net/momirealms` 泄漏。
- 新增 `docs/deployment.md`：部署形态、配置清单、权限、备份升级、排障、并发说明。

### 仍建议（非阻塞）
- 真实服务器端到端验证（跨服 Redis、模板世界、传送、mcMMO 屏蔽、Sparrow 渲染）。
- 工会战结算目前仍在主线程同步发放（每场一次）；超大规模奖励场景可评估异步化。

---

## 十二、共享配置目录的多服一致性（专项审查）

**问题**：多台子服把 `settings.shared-config-path` 指向同一目录后，同一份配置会被所有子服同时读取。
若不处理，所有子服会同时执行“到点开赛/定时清理”等全局动作，导致重复发布、重复删除、重复公告。

### 逐项审查与结论

| 全局动作 | 原风险 | 现状 |
|---|---|---|
| **赛季到点开赛** | 每台子服各自 `startSeason`：N 份公告 + N 次自动入队 | 到期后在异步线程用 Redis `SET NX EX` 抢锁 `season:<模式>:<分钟>`，仅一台获得；获得者广播 `SEASON_OPEN`，各服各自公告并拉入本服工会。**恰好一次** |
| **僵尸工会清理** | 每台子服各自扫描并删同一批 | 每周期抢锁 `cleanup`（TTL=清理间隔），仅一台执行 |
| **匹配配对** | 可能重复匹配 | `war:matchlock` 全局锁 + `claimPair` 的原子 `ZREM`（原有机制，已确认） |
| **竞技场世界创建** | 多服同时建 | 仅承办服 `adoptWar` → `prepareArena`（原有机制，已确认） |
| **工会战结算/奖励** | 多服重复发 | 仅承办服 `endWar` → `applyRewards`（原有机制，已确认） |
| **在线心跳** | 互相覆盖 | 每玩家独立 `presence:<uuid>` 键（原有机制，已确认） |
| **工会变更/公告/聊天** | 重复投递 | 接收端按 `server` 字段跳过来源服（原有机制，已确认） |
| **配置热重载** | 一次编辑引发全服全表重读 | 自动热重载改为**仅配置**（`reloadSettings`），不再重读工会数据 |

### 配置互相覆盖与注释丢失

- 原先 GUI/指令直接整文件写回 `arena.yml`/`kit.yml`：多服同时编辑会互相覆盖，且 Bukkit 保存会**清空注释**。
- 现改为**覆盖层**：游戏内编辑只写 `overrides/<文件>`，加载时合并到带注释的基础文件之上；保存采用临时文件 + 原子替换。
- 删除通过覆盖层中的 `__liuguild_deleted__` 标记实现，且**删除最后应用**，避免被残留子键“复活”。
- 新增 `/guildadmin overrides [list|clear <文件|all>]` 管理覆盖层。

### 哪些必须每服独立

- **只有 `server.yml`**（核心 `server-id`，另可覆盖 `language`/`debug`/`auto-reload-config`）必须每服独立，且不参与共享。
- `database.yml`、`cross-server.yml` 必须**共享且一致**（同库、同 Redis、同 channel）。
- 其余玩法/展示配置共享即可。
- `settings.shared-config-path` 仅从**本服** `plugins/LiuGuild/config.yml` 读取，避免自指。

详见 `docs/shared-config.md`。

### 验证
- `mvn -o clean package -Dlicense.enabled=false` → **BUILD SUCCESS**，**40** 用例全绿（新增覆盖层合并/删除 3 例）。

---

## 十三、非阻塞项落实（结算分帧 / 预加载 / 端到端自检）

### 结算不再阻塞主线程
- 玩家层奖励（Vault 金币、通行证经验、称号、消息）改为**分帧队列**：`endWar` 只入队，`warTick` 的 SETTLING 阶段每 tick 发放 `war.settle-batch-per-tick`（默认 8）人。
- 单玩家发放异常由 `WarMath.drainBatch` 独立捕获，不影响其余玩家；`cleanup`/取消时清空队列。
- 选择“主线程分帧”而非“异步发放”，是因为第三方 API 线程安全不受本插件控制，分帧同样消除长 tick，且无跨线程风险。
- 新增纯函数 `WarMath.drainBatch` 及回归测试。

### 预加载
- **套装护甲/物品**：`KitService.preload()` 在启动与每次配置重载时一次性解析（含 CraftEngine 物品），入场直接取缓存并克隆。
- **套装玩家快照**：`KitProfile.decodedSnapshot()` 懒解码 + 启动预热，避免每次入场 Base64 解码 41 件物品。
- `/guildadmin status` 新增“预加载计数（套装/场地/模式/图标）”与“待发放奖励数”。

### 配置更清晰
- `server-id` 统一从 **`server.yml`** 读取（`config.yml` 已移除该键）；若为空则自动生成并写回，避免多服串号。

### 端到端自检
- 新增 **`/guildadmin doctor`**：逐项检查 server-id / 数据库 / 跨服 / 传送 / 经济 / HuskHomes / mcMMO / 每个模式的场地·模板·世界·套装，输出 ✓/✗ 清单。
- `docs/deployment.md` 增补“上线自检与端到端验收”清单（匹配→传送→击杀→结算→回原位、赛季单次公告、跨服频道、驻地、宕机降级）。

### 文档
- 新增 **`docs/threading.md`**：线程模型、预加载清单、每服定时任务与单写者、工会战生命周期与结算分帧、DB/Redis 线程规则、排查手段。

### 验证
- `mvn -o clean package -Dlicense.enabled=false` → **BUILD SUCCESS**，**41** 用例全绿。


---

## 十四、0.3.4 稳定性加固（本轮）

针对 0.3.4 的全量复审，落地了以下修复（详细清单见提交说明）：

### 关服与崩溃恢复
- `async` 在 `onDisable` 后自动降级到 `liuguild-teardown` 收尾线程，`sync/syncDelayed` 直接跳过，避免
  `IllegalPluginAccessException` 打断 cleanup（此前会导致玩家不还原、临时世界目录残留）。
- 关服刷写改 upsert，并只对本进程内新建的工会/成员补插；`pendingDisbands` 在关服前补做删除，
  解决“新建工会丢失 / 已解散工会复活”。
- 商店上架交接改为背包 before/after 快照精确比对；发货改为 `READY→DELIVERING` 先领取再发，
  消除“按件数推断吞物品”和“崩溃后重复发货”。
- 仓库 `loading` 状态在所有失败分支清理，修复“一次 DB 抖动后该玩家永久打不开仓库”。

### 数据一致性
- `level`/`member_count` 移出整行保存，只走原子 UPDATE（管理员设等级、数据校验收口到专用方法）。
- 奖励 outbox 改为 `PENDING→PROCESSING` 原子领取；失败退回重试，超限/超时标 FAILED；
  5 分钟定时补扫；关服时未发完的行退回 PENDING。
- `database.yml` 加入关键配置名单；非关键配置解析失败不再移动/覆盖用户文件，只复制带时间戳的备份。

### 性能
- 战斗结算写库移入 `warIo` 单线程；结算分帧每 tick 执行（含启动补偿）。
- 仓库整仓/背包 NBT 编码移出主线程，持久化改为差量 upsert + 尾部删除。
- 文本模板预处理缓存、GUI 标题只解析一次、无 `%` 时跳过 PAPI、自定义图标反序列化缓存。
- 在线心跳改用 pipeline、TTL 由心跳周期推导；僵尸清理/在线人数改用一次 `MGET`。
- 赛季轮询在无赛事时退避到 5 秒；SQLite 固定单连接并设置 WAL/NORMAL。
- 工会变更广播按 500ms 窗口合并并在窗口末尾补发。

### 正确性与接口
- 12 个事件各自持有 `HandlerList`；GUI 只响应左/右键（含 Shift）并加点击冷却；当前图标不可再点（不重复扣费）。
- 管理面板动作按命令层权限节点校验；补上 `/guildadmin rename` 分发。
- `/guild create` 标签可选（自动从名称生成）；管理命令数字参数不再静默回落；存款税率默认值改为 5（百分比语义）。


---

## 十五、0.3.4 收尾：职责拆分、软依赖与 MySQL 覆盖

### 职责拆分（行为不变，均有测试回归）
- `GuildManager`（1769 → 1627 行）拆出：
  - `GuildInvites`：邀请登记、跨服投递、接受/拒绝、过期清理；
  - `GuildCleanup`：僵尸工会扫描、跨服在线统计、自动清理与历史清理；
  - `GuildRanking`：排行榜排序缓存。
- `GuiManager`（2616 → 2557 行）拆出：
  - `GuiActions`：按钮 id → 动作映射、管理动作 → 权限节点、有效点击类型；
  - `GuiPlaceholders`：基础/详情/主菜单占位符与 `flatten`。
- `WarManager` 拆出 `WarRewards`：outbox 行构建、玩家/工会发放、失败退回、指令奖励与占位符。
- 场地生命周期（世界创建/销毁）仍由 `WarManager` 持有：它与战斗状态机、传送到回调强耦合，
  为避免行为回归本轮不做进一步拆分。

### 软依赖
- 对直接引用第三方类的钩子（liudungeon / packetevents / Vault / PlaceholderAPI）先做
  `PluginManager.getPlugin(...)` 门禁，再构造；缺失时根本不会触发其类初始化。
- `safe()` 捕获异常时写 FINE 日志，降级不再完全静默。
- 验证：Java `checkcast` 对 `null` 不会因目标类缺失而抛 `NoClassDefFoundError`（本地字节码实验），
  所以“`safe()` 返回 null 后赋值”本身是安全的；门禁只是减少无谓异常与日志噪音。

### MySQL 测试
- 新增 `GuildRepositoryMySqlTest`（默认跳过）：`-Dliuguild.test.mysql=true` 开启，
  自动建随机测试库、结束删除；覆盖唯一索引、关服 upsert、原子等级/人数、重复入会、
  奖励并发领取。
- 已在本地 MariaDB 11.8 上实跑通过，并因此发现并修复：并发领取奖励时 MariaDB 会报
  `Record has changed since last read`，领取 SELECT 增加 `FOR UPDATE` 行锁后消除（SQLite 不需要）。
- 说明：CI/生产前仍建议在目标 MySQL 版本上执行该测试。

### 死代码清理
移除约 400 行未使用代码：`GuildManager.invalidate/recomputeLevel/purgeInactive`、
`decrementMemberCount`、`ShopService.supply/disabledSupplyLegacy/commitSupply/countsByGuild`、
`ShopRepository.increment/delete`、`TitleService.displayPlain`、
`TitleRepository.saveTitle/deleteTitle/loadAllTitles/loadTitles`、
`WarehouseService.legacyRent/legacyUpgrade/purge/isOpen/busyMessage`。

### 验证
- `mvn -o test`：251 用例全绿（SQLite 真实 JDBC）。
- `mvn -o clean package`：BUILD SUCCESS，产物 `target/Liu-LiuGuild-0.3.4.jar`。
- `GuildRepositoryMySqlTest` 在本地 MariaDB 11.8 上 5 用例全绿（默认跳过）。
