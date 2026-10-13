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
