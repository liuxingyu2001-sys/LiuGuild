# LiuGuild

> Paper 1.21 跨服工会插件 —— 工会管理 · 跨服工会战 · 自定义竞技场 · 公平竞技 · 高扩展

## 特性

- 🏰 **工会系统**：创建 / 加入 / 邀请 / 职位 / 转让 / 银行 / 等级 / 点数 / 公告 / 简介 / 自定义图标 / 每日签到。
- ⚔️ **跨服工会战**：多种模式（**匹配**、**赛季**、自定义），每种配置独立。
  - 匹配工会战：菜单点击参与，入队广播（可配置），凑齐两队即战。
  - 赛季工会战：配置多个开战时间（如 `sat 20:00`），到点自动开启。
- 🗺️ **自描述竞技场**：每条场地直接写「承办服 + 世界 + 坐标」，也支持 liudungeon 动态模板。
- 🛡️ **公平竞技**：统一装备（支持保存玩家当前装备为套装）、公平血量（屏蔽 LuckyPeaches 蟠桃加成）。
- 🧩 **高度可配置**：配置按功能模块拆分成 10+ 个文件，可共享目录 + 热重载。
- 🎨 **GUI 菜单**：主菜单 / 成员 / 列表 / 工会战 / 设置 / 图标 / 管理面板，全部可配置；可选 Sparrow UI 渲染，按 ESC 返回上一页。
- 🔌 **完整 API**：静态门面 `GuildAPI` + 全套 Bukkit 事件；完整 PlaceholderAPI 变量。
- 💾 **存储**：SQLite 开箱即用；MySQL 自动建库建表；Redis 跨服（缺失自动降级单服）。
- 📥 **数据导入**：一键导入旧版 PlayerGuild 的 MySQL dump（含自定义图标）。

## 前置（全部软依赖，缺失自动降级）

`liudungeon` · `LiuChat` · `liupass` · `CraftEngine` · `Sparrow Sync` · `LuckyPeaches` · `PlayerTitle` · `NotBounties` · `mcMMO` · `HuskHomes` · `Vault` · `PlaceholderAPI`

## 构建记录

- `0.2.6`：移除**工会领地共享（Residence）**功能。审查发现其设计存在无法修复的权限泄漏：没有“已共享”状态持久化，新成员入会会被自动授予所有成员的全部领地（隐私泄漏）；领地主退会时剩余成员仍保留其私宅权限；工会解散不回收集权限；取消共享写 `deny` 会覆盖分组权限。连同 `ResidenceHook`、指令 / GUI / 配置 / 消息 / 文档一并删除，`plugin.yml` 不再软依赖 Residence。**195 项单元测试通过**。
- `0.2.5`：世界识别兼容 Paper 1.21.11+ / Leaf 26.x 的 `dimensions/<命名空间>/<世界>` 新布局（新增 `WorldResolver`，支持纯名 / `minecraft:xxx` / 目录名，修复 gamerule 名归一化），新增 `/guildadmin arena listworlds`；GUI 全面接入 Sparrow UI 会话栈，**按 ESC 返回上一页**（子菜单压栈、根菜单才关闭），仓库菜单与铁砧输入一并接入。**201 项单元测试通过**。详见 [GUI 文档](docs/gui.md) 与 [竞技场文档](docs/arena.md)。
- `0.2.4`：修复 `/guildadmin arena tp <id>` 与 `/guildadmin war tp` 在非承办服上「世界未加载，无法传送」——改为先代理传送到承办服（写入待传送标记），抵达后再送到竞技场 A 点。197 项单元测试通过。
- `0.2.3`：修复跨服工会战「A 服传不到承办服」——承办服判定兼容插件 `server-id` 与代理（BungeeCord/Velocity）登记名，新增 `GetServer`/`GetServers` 子通道探测、代理名不一致警告与承办服补领；明确 `server-id` 必须等于代理 `[servers]` 名称。197 项单元测试通过。
- `0.2.2`：工会战 10 轮深度审查（匹配重入队、热重载不误结束战斗、Redis IO 异步、淘汰/观战守卫、平局不计负、结算期不回退、跨服托管恢复、世界销毁疏散、未知模式拒绝）以及工会商店改用工会资金（个人资金支付、税后点数入工会）。197 项单元测试通过。
- `0.2.1`：完善 liupass 战令对接——注册 `liuguild:join/create/war/war-win/war-kill/checkin/donate/bank-deposit` 扩展任务类型，新增 `GuildCheckinEvent` / `GuildFundsEvent`，`config.yml` 的 `hooks.liupass.*` 控制开关，保留旧版 `tasks.yml` 任务 ID 绑定；189 项单元测试通过。详见 [docs/tasks.md](docs/tasks.md)。
- `0.2.0`：包含独立工会空间配置、逐级收费与数据保留策略，以及资金、商店和装备托管修复；178 项单元测试通过。跨服返程与崩溃恢复的限制见 [工会战文档](docs/war.md) 和 [空间文档](docs/warehouse.md)。

## 快速开始

1. 安装 `Liu-LiuGuild-<version>.jar`，启动生成配置。
2. 单服用 SQLite；跨服用同一 MySQL + `cross-server.yml`（Redis）。
3. 每台子服在 `server.yml` 设置唯一 `server-id`。
4. `/guildadmin arena setspawn main a` 现场设置竞技场出生点。
5. 玩家 `/guild gui` → 工会战 → 选择模式参与匹配。

> 正式开服请先阅读 **[docs/deployment.md](docs/deployment.md)**（部署形态 / 配置清单 / 排障 / 备份）。

## 指令

- 玩家：`/guild`（简写 `/g`），菜单 `/guild gui`。
- 管理：`/guildadmin status|reload|arena|kit|mode|war|import ...`。

详见 [docs/commands.md](docs/commands.md)。

## 文档

| 文档 | 说明 |
|---|---|
| [docs/README.md](docs/README.md) | 文档总览与快速开始 |
| [docs/funds.md](docs/funds.md) | 个人资金、公共资金、金币与捐献规则 |
| [docs/shop.md](docs/shop.md) | 工会商店（仅出售，不收购） |
| [docs/checkin.md](docs/checkin.md) | 工会每日签到 |
| [docs/tasks.md](docs/tasks.md) | 工会任务与 liupass 对接（任务类型 / 通行证经验） |
| [docs/deployment.md](docs/deployment.md) | **生产部署与运维** |
| [docs/shared-config.md](docs/shared-config.md) | **共享配置目录与多服一致性** |
| [docs/threading.md](docs/threading.md) | **线程模型 / 预加载 / 生命周期** |
| [docs/commands.md](docs/commands.md) | 指令与权限 |
| [docs/configuration.md](docs/configuration.md) | 配置拆分说明 |
| [docs/war.md](docs/war.md) | 工会战模式 / 匹配 / 赛季 |
| [docs/arena.md](docs/arena.md) | 竞技场（承办服/世界/坐标） |
| [docs/kit.md](docs/kit.md) | 统一装备与装备快照 |
| [docs/gui.md](docs/gui.md) | GUI 配置 |
| [docs/api.md](docs/api.md) | 开放 API 与事件 |
| [docs/placeholders.md](docs/placeholders.md) | PAPI 变量 |
| [docs/craftengine.md](docs/craftengine.md) | CraftEngine 集成 |
| [docs/hooks.md](docs/hooks.md) | 软依赖钩子 |
| [docs/import.md](docs/import.md) | PlayerGuild 数据导入 |

## 构建

```bash
mvn clean package -Dlicense.enabled=false
# 产物：target/Liu-LiuGuild-<version>.jar
```

- JDK 21 + Maven。
- `libs/liudungeon-1.9.3.jar` 已随仓库提供，不再依赖同级 `../liudungeon/target` 目录。
- GitHub Actions：`.github/workflows/build.yml`，push/PR 自动编译、跑测试并上传插件 jar。

- Java 21 · Paper API 1.21.1 · `folia-supported: false`
- 第三方库由 `plugin.yml` 的 `libraries` 下载，不打进 jar。
