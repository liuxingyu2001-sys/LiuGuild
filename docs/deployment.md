# 生产部署与运维

本文面向正式开服场景，覆盖环境要求、单服 / 跨服两种部署形态、配置清单、权限、备份与常见排障。

## 1. 运行环境

| 项 | 要求 |
|---|---|
| 服务端 | Paper 1.21.x（`api-version: 1.21`），**不支持 Folia** |
| Java | 21 |
| 数据库 | 单服：SQLite（内置）；跨服：MySQL 8.0+ / MariaDB 10.6+ |
| 缓存/消息 | 跨服工会战：Redis 6+ |
| 依赖库 | HikariCP / mysql-connector-j / sqlite-jdbc / jedis 由 Paper `libraries` 自动下载，无需手动放置 |

产物：`target/Liu-LiuGuild-<version>.jar`，放入各子服 `plugins/`。

> 首次启动会自动生成 `plugins/LiuGuild/` 下的全部配置与数据库文件。

## 2. 两种部署形态

### A. 单服（最简）

- `database.yml` 保持 `type: sqlite`。
- `cross-server.yml` 可不启用 Redis；工会战在同一服务器内匹配。
- 适合：单个生存服、测试服。

### B. 跨服工会战（推荐正式服）

1. **所有**子服使用同一 MySQL：`database.yml` → `type: mysql` + 相同库名/账号/前缀。
2. **所有**子服配置相同的 `cross-server.yml`（同一 Redis、同一 `channel`）。
3. 每台子服在 `server.yml` 设**唯一** `server-id`（如 `lobby`、`survival`、`arena1`）。
4. 选择承办模式：
   - **指定承办服**：竞技场 `server` 填目标服的代理登记名；玩家会被自动传送过去，无需每服承办开关。
   - **动态承办**：留空，由发起匹配的服务器承办。
5. 在承办服（或所有服）用 `/guildadmin arena ...` 配置场地与出生点。

> 只要有一台子服的 `server-id` 重复，跨服在线状态与工会战路由都会错乱——务必唯一。
>
> 共享目录中哪些文件该共享、哪些必须每服独立，以及如何避免定时任务被多服重复执行，
> 详见 **[shared-config.md](shared-config.md)**。

## 3. 上线配置清单

按顺序逐项确认（全部可在游戏内 `/guildadmin gui` 完成）：

- [ ] `server.yml` 的 `server-id` 唯一。
- [ ] `/guildadmin status` 无「配置告警」。
- [ ] `war-modes.yml` 至少一个模式；`war.default-mode` 指向它。
- [ ] 每个模式引用的 `arena` 在 `arena.yml` 中存在。
- [ ] 竞技场 A/B/旁观出生点用 `/guildadmin arena setspawn` 在**现场**设置到正确高度。
- [ ] 使用 liudungeon 模板：模板名存在且 liudungeon 已装；或改用 `temporary-world.builtin: true` 自建世界。
- [ ] `kit.yml` 每个模式对应套装已用 `/guildadmin kit save <key>` 保存或配置好列表。
- [ ] `guild.home.enabled` 与 HuskHomes 安装情况一致。
- [ ] `hooks.mcmmo.enabled` 与 mcMMO 安装情况一致。
- [ ] 经济相关（`create-cost`、银行）需要 Vault + 经济插件。
- [ ] `gui.engine`：默认 `auto`；如遇渲染问题改 `legacy`。

启动后 3 秒内，有任何配置问题会在控制台以 `配置检查发现 N 项需注意的问题` 的形式打印，并可在 `/guildadmin status` 查看。

### 上线自检与端到端验收

执行 `/guildadmin doctor`，逐项确认：

- [ ] `server-id` 已设置；数据库连接正常；跨服 Redis 已连接（若启用）；跨服传送可用。
- [ ] 经济插件可用（若有创建费/银行）；HuskHomes/mcMMO 与开关一致。
- [ ] 每个模式的场地、模板/世界、套装均可用。

端到端验收（在不同子服上分别操作）：

| 场景 | 预期 |
|---|---|
| 两服各建一个工会并点击参与匹配 | 匹配成功，双方被传送到承办服并收到公告 |
| 比赛中互相击杀 | 比分与 BossBar 更新，生命耗尽后变旁观，结束统一结算并回原位 |
| 赛季到点 | **全服只出现一次**开赛公告，各服自动拉入本服在线工会 |
| 工会频道 / 公告 | 全服同会成员均收到，发送方不重复 |
| 工会驻地 | 成员可传送，跨服也能到（HuskHomes） |
| 关闭一台子服 | 其在线状态自动消失；进行中比赛在承办服正常结束 |
| `/guildadmin doctor` | 全部 ✓（无 ✗） |

## 4. 权限（LuckPerms 建议）

| 节点 | 默认 | 用途 |
|---|---|---|
| `liuguild.use` | true | 基础命令 |
| `liuguild.create` | true | 创建工会 |
| `liuguild.create.free` | op | 免创建费 |
| `liuguild.war` | true | 参与工会战 |
| `liuguild.admin` | op | 管理总权限 |
| `liuguild.admin.*` | op | 分项管理（见 commands.md） |

建议给管理员组 `liuguild.admin`（含全部子权限），不要单独 `*`。

## 5. 备份与升级

- **数据库**：定期备份 `plugins/LiuGuild/*.db`（SQLite）或 MySQL 库。
- **配置**：`plugins/LiuGuild/*.yml` 纳入版本管理；新增配置项会以默认值补齐。
- **升级**：替换 jar → 重启。插件启动时自动建表/补列/建唯一索引，均为幂等操作。
- **回滚**：旧 jar + 原数据库即可；结构升级不会删除既有数据。

## 6. 常见排障

| 现象 | 原因 / 处理 |
|---|---|
| 启动日志「数据库初始化失败」 | `database.yml` 账号/库名/端口错误；MySQL 需可连接。 |
| 「已启用跨服但 Redis 未连接」 | Redis 地址/密码/端口错误，或防火墙拦截；未连上时工会战退回单服。 |
| 「场地 xxx 配置了模板但未安装 liudungeon」 | 安装 liudungeon，或改 `temporary-world.builtin: true`，或清空 `template` 用固定世界。 |
| 「模式 xxx 引用不存在的场地」 | 在 `arena.yml` 建对应 id，或修正模式的 `arena`。 |
| 玩家在竞技场摔落/卡在地形里 | 用 `/guildadmin arena setspawn` 重新设置出生点；内置世界需按新地形高度设点。 |
| 工会驻地命令提示不可用 | 未安装 HuskHomes 或 `guild.home.enabled=false`。 |
| 创建工会提示名称/缩写被占用 | 名称与缩写大小写不敏感唯一（数据库唯一索引保证）。 |
| 数据异常怀疑不同步 | `/guildadmin verify` 检查，`/guildadmin verify fix` 修复。 |
| 想清理长期无人工会 | `/guildadmin cleanup`（试运行）→ 确认后 `/guildadmin cleanup confirm`。 |

## 7. 性能与并发说明

- 所有玩家触发的数据库/Redis 写入均在异步线程；删除、结算等回主线程。
- 工会缓存整体原子替换，避免重载期间短暂「无工会」。
- 工会战结算在比赛结束时于主线程同步发放（每场一次），大规模奖励场景可关注 TPS。
- SQLite 使用 WAL + `busy_timeout`；高并发写入请改用 MySQL。
