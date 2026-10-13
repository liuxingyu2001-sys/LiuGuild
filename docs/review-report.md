# LiuGuild 深度审查报告（5 轮）

- 仓库：`/home/liu/plugins/LiuGuild`，分支 `main`
- 基线：`1016086`（审查开始前最后一次推送）
- 方式：先审计、再修复、再验证；每轮独立构建 + 单元测试 + `git diff --check`，通过后单独提交并推送
- 构建命令：`mvn -o clean package -Dlicense.enabled=false`
- 测试：新增 4 个测试类，共 **22** 个用例，全部通过（`Tests run: 22, Failures: 0, Errors: 0`）

## 提交一览

| 轮次 | 提交 | 主题 |
|---|---|---|
| 基线 | `1016086` | 审查起点 |
| 第 1 轮 | `85cf9d5` | 创建/加入事务原子性与银行边界测试 |
| 第 2 轮 | `c998651` | 统一文本格式（MiniMessage/legacy/RGB）、防注入与权限校验 |
| 第 3 轮 | `1bcac86` | 工会战结算/重载/关服安全与生命池可测化 |
| 第 4 轮 | `97baa04` | 跨服队列清理与 mcMMO 技能静默屏蔽（可配置，默认开启） |
| 第 5 轮 | `5fd279e` | 升级兼容（消息默认合并）、频道同步性能与文档 |

---

## 第 1 轮：核心数据与并发一致性

### 发现
1. **创建工会非原子（严重）**：先扣费、再异步分别 `insertGuild` 与 `insertMember`。第二步失败会留下“扣了钱但没工会”，或“有工会但会长不是成员”。
2. **加入工会先改内存后写库（严重）**：`insertMember` 失败只打日志，内存里玩家仍显示已入会，跨服数据不一致。
3. **重复入会异常被吞（中）**：原实现把所有 `SQLException` 都当作“已在工会”，掩盖真实数据库故障。
4. **成员计数与实际行数可能不一致（中）**：`member_count` 与成员行分开更新。
5. **银行取款缺少权限校验与原子性（中）**：早期实现要求会长职位；实际所有成员应可存取，且余额必须以数据库原子增减为准。

### 修复
- 新增 `GuildRepository.insertGuildWithLeader(...)`：**单连接事务**内同时写入工会与会长。
- 新增 `insertMemberAndIncrement(...)`：事务内写成员并把 `member_count` 自增，任一步失败整体回滚。
- 重复入会不再依赖方言相关的 `SQLState`，改为**回滚后显式查询玩家是否已存在**，跨 SQLite/MySQL 一致。
- 扣除创建费改为“先只读检查余额，通过后再单次 `withdraw`”，杜绝重复扣费；写库失败时回滚缓存并**尝试退款**，退款失败写 `SEVERE` 日志。
- 成功提示与 `GuildCreateEvent` / `GuildJoinEvent` 仅在**持久化成功之后**触发。

### 验证
- `GuildRepositoryTest`（SQLite 真实连接）：事务创建、重复成员回滚且不污染计数、银行余额上下界与溢出保护。
- 该测试在过程中真实暴露并修复了 SQLite 下重复成员未按预期返回 `false` 的缺陷。

### 遗留
- 工会名称/标签目前是**应用层唯一性检查 + 插入**，数据库层没有唯一索引；两个子服极端同时提交仍可能撞名。建议后续为 `name`/`tag` 增加唯一索引并提供旧数据冲突清理工具（涉及数据迁移，未在本轮擅自执行）。

---

## 第 2 轮：文本格式、玩家输入与权限

### 发现
1. **格式不一致（严重，体验）**：各处只支持 MiniMessage，`&a`、`§a`、`&#RRGGBB` 不生效；`&` 代码在不同代码路径处理方式不同。
2. **MiniMessage 注入（严重，安全）**：消息模板先把占位符拼成字符串再解析，玩家昵称/工会名中的 `<red>`、`<click:run_command:...>` 会被当作格式甚至可点击命令执行。
3. **公告/简介的富文本行为不一致（中）**：命令与 GUI 显示路径不同，且长度未校验，可能超 `VARCHAR(255)`。
4. **声明权限未生效（中）**：`plugin.yml` 声明 `liuguild.use` / `liuguild.create` / `liuguild.war`，但命令与 GUI 路径都不校验。

### 修复
- 重写 `Text` 渲染管线：
  - `mm()` 同时解析 MiniMessage、`&`/`§` legacy、`&#RRGGBB` 与 `§#RRGGBB`；
  - `fmt()` 占位符默认**按纯文本插入**（`Placeholder.unparsed`），杜绝注入；
  - `fmtRich()` / `fmtSelective()` 提供显式富文本路径；
  - `sanitizeToMini()` 保留颜色与装饰，但**剥离点击/悬浮/插入事件**。
- GUI 统一走 `fmtSelective()`：`notice` / `description` 允许颜色，玩家名等保持纯文本。
- 玩家自定义公告/简介集中到 `GuildManager.setNotice/setDescription`，长度上限 255 并给出 `input-too-long` 提示。
- 权限在**命令层与管理层双重校验**：`GuildManager.create`、`WarManager.queue/join`、`GuildCommand` 顶部 `liuguild.use`。
- `messages.yml` 增加 `create-failed`、`operation-failed`、`input-too-long`。

### 验证
- `TextTest`（9 个用例）：`&a`/`§b`/`&#FF0000`/MiniMessage/混排、注入防护、`fmtRich`、`fmtSelective`、`sanitizeToMini` 事件剥离、`clean`/`hasFormatting`。
- 过程中修复了“富文本占位符只解析 MiniMessage、不解析 `&` 代码”的真实缺陷。

### 验证方式说明
- 纯逻辑单元测试，不依赖服务器；颜色/事件断言在组件树上递归校验。

---

## 第 3 轮：工会战状态机、生命与结算

### 发现
1. **关服/重载会结算奖励（严重）**：`WarManager.disable()` 调用 `forceEnd()` → `endWar()` → `applyRewards()`。因此 `/guildadmin reload` 或关服会“结束比赛并发放金币/工会资金/通行证经验/称号”。
2. **结束后玩家不回到原位（严重，体验）**：`leaveLocal()` 只还原装备与模式，不传送回参赛前位置；竞技场世界随后被销毁，玩家可能被丢到默认世界。
3. **生命池逻辑不可测（中）**：分布算法内联在管理器里，无法单测，且边界（0 生命、空队伍）行为未固定。

### 修复
- 拆分生命周期：新增 `cancelTasks()`；`disable()` **只取消比赛、还原玩家、不结算**；`reload()` 只重启定时任务，**不打断正在进行的比赛**。
- `leaveLocal`/`leaveSpectator` 增加 `returnHome()`：在竞技场销毁前传送回参赛前坐标（仅跨世界或距离较远时触发）。
- 抽出纯计算类 `WarMath`：`distributeLives` 与 `teamLifePools`，明确“人数均衡时双方总生命池相等、每名玩家至少 1 命”。
- `docs/war.md` 明确写入“关服/重载不结算”。

### 验证
- `WarMathTest`（6 个用例）：均分余数、每人至少 1 命、人数均衡池相等、非均衡按人数、空队伍。
- 修正了测试中我自己写错的期望值，确认实现语义为“3v1 → 双方各 3 条总生命”。

### 遗留
- 结算奖励在主线程同步调用 Vault/通行证，极端人数下可能有轻微卡顿；未观察到问题，未做异步化（异步发放会引入与离线玩家、回滚的复杂度）。
- 离线玩家在关服时若没有 Sparrow 快照，本地内存快照会丢失（见总体遗留）。

---

## 第 4 轮：跨服同步与可选插件

### 发现
1. **跨服队列残留（中）**：子服崩溃后，工会匹配队列的元数据 TTL 会过期，但有序集合成员永久保留；`isQueued` 只看 zset，导致工会“永远在排队”。
2. **mcMMO 缺失**：用户要求接入 mcMMO 并做“技能系统静默屏蔽、可配置开关、默认开启”。
3. **可选插件生命周期**：确认各钩子缺失时均能降级；`GuildChatHook` 通过反射对接 LiuChat，旧版本缺少审核后 API 时仅本服可用。

### 修复
- `CrossServerBus.queueSnapshot()` 清理元数据已过期的 zset 成员；`isQueued()` 要求元数据存在，否则顺带清理。
- 新增 `McMmoHook`：仅用 mcMMO 公开事件（`McMMOPlayerAbilityActivateEvent` 取消、`McMMOPlayerPreXpGainEvent` 经验置 0），**只对工会战参赛者**生效，旁观者与普通玩家不受影响。
- 配置 `config.yml` → `hooks.mcmmo`：`enabled`（默认 `true`）、`suppress-abilities-in-war`（默认 `true`）、`suppress-xp-in-war`（默认 `false`）。
- `plugin.yml` 增加 `mcMMO` 软依赖；`LiuGuild` 生命周期中安全注册（`safe(...)` 包裹）。

### 验证
- `CrossServerBusTest`（4 个用例）：编码/解析往返、值内分隔符净化、空载荷、null 值。
- mcMMO 事件签名通过阅读 mcMMO 源码确认（`nossr50` 等作者，未修改 mcMMO 本体）。

### 遗留
- mcMMO 屏蔽逻辑未在真实 mcMMO 服务器上联调（无运行环境）；逻辑为事件驱动，风险集中在事件类名/方法名版本差异，已做反射容错与“找不到事件则告警并跳过”。
- 跨服 Redis 联调未进行（无 Redis 环境），仅覆盖编码与状态机逻辑。

---

## 第 5 轮：升级兼容、性能与体验

### 发现
1. **升级后新消息键缺失（中，体验）**：`Messages.raw` 找不到键时直接返回键名，旧 `messages.yml` 升级后会显示 `create-failed` 之类的原始键名。
2. **频道同步性能（中）**：`GuildChatHook.refresh()` 每个心跳（默认 5 秒）对所有在线玩家 × 所有频道做反射订阅/退订，玩家多时是稳定开销。

### 修复
- `Messages.reload()` 改为**先铺内置默认、再用用户文件覆盖**，新增键在旧配置下也能正常显示。
- `GuildChatHook` 增加轻量“在线玩家→频道”签名 `lastSignature`，无变化时跳过反射同步；`close()` 重置签名。
- 文档补充 `config.yml` 的 `hooks.mcmmo` 与升级兼容说明。

### 验证
- 全量构建 + 22 个单元测试通过；`git diff --check` 干净。

---

## 可选插件对接情况

| 插件 | 状态 | 说明 |
|---|---|---|
| mcMMO | **已实现** | 工会战技能静默屏蔽，可配置，默认开启（本轮用户明确要求） |
| Vault | 已用 | 银行存取、创建费、图标费；缺失时拒绝经济操作并提示 |
| PlaceholderAPI | 已用 | `%liuguild_*%` 变量 |
| LiuChat | 已用 | 工会私密频道 + 审核后跨服投递（配套 API 在 LiuChat 仓库另提交） |
| liupass | 已用 | `tasks.yml` 绑定 CUSTOM 任务 |
| liudungeon | 已用 | 动态竞技场世界 |
| Sparrow Sync / LuckyPeaches / PlayerTitle / NotBounties / CraftEngine | 已用 | 见 `docs/hooks.md` |
| HuskHomes | 已评估，未实现 | API 可用（坐标/跨服传送）；本次 5 轮聚焦质量审查与 mcMMO，未落地工会驻地 |
| MCPets / Citizens / CustomNameplates / WorldGuard / PacketEvents | 已评估，未实现 | 未确定具体玩法需求，且避免引入非必要硬/软依赖；如需接入建议单独排期 |

> 说明：HuskHomes、MCPets、Citizens、CustomNameplates 等作者不在可修改授权范围内，若要对接将只调用其公开 API，绝不改动其源码。

---

## 总体遗留与建议

1. **名称/标签唯一索引**：建议加数据库唯一索引 + 旧数据冲突清理脚本（本轮未做破坏性迁移）。
2. **离线玩家快照**：关服时若玩家离线且无 Sparrow 快照，统一装备的本地快照无法还原；建议依赖 Sparrow Sync 或改为持久化快照。
3. **集成测试**：本轮为单元测试 + 编译验证；建议在测试服验证跨服 Redis、传送、mcMMO 屏蔽、装备还原的端到端流程。
4. **结算异步化**：人数很多时可评估把 Vault/通行证发放移出主线程（需处理离线与回滚）。
5. **未提交/未触碰**：LiuChat 的配套改动位于 LiuChat 仓库（此前已单独提交）；LiuGuild 工作区在每轮提交后保持干净。

## 复现方式

```bash
cd /home/liu/plugins/LiuGuild
mvn -o clean package -Dlicense.enabled=false     # 22 个测试
git log --oneline -6                              # 查看 5 轮提交
```
