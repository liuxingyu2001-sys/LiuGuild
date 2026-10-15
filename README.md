# LiuGuild

> Paper 1.21 跨服工会插件 —— 工会管理 · 跨服工会战 · 自定义竞技场 · 公平竞技 · 高扩展

## 特性

- 🏰 **工会系统**：创建 / 加入 / 邀请 / 职位 / 转让 / 银行 / 等级 / 点数 / 公告 / 简介 / 自定义图标 / 每日签到。
- ⚔️ **跨服工会战**：多种模式（**匹配**、**赛季**、自定义），每种配置独立。
  - 匹配工会战：菜单点击参与，全服提示，凑齐两队即战。
  - 赛季工会战：配置多个开战时间（如 `sat 20:00`），到点自动开启。
- 🗺️ **自描述竞技场**：每条场地直接写「承办服 + 世界 + 坐标」，也支持 liudungeon 动态模板。
- 🛡️ **公平竞技**：统一装备（支持保存玩家当前装备为套装）、公平血量（屏蔽 LuckyPeaches 蟠桃加成）。
- 🧩 **高度可配置**：配置按功能模块拆分成 10+ 个文件，可共享目录 + 热重载。
- 🎨 **GUI 菜单**：主菜单 / 成员 / 列表 / 工会战 / 设置 / 图标 / 管理面板，全部可配置。
- 🔌 **完整 API**：静态门面 `GuildAPI` + 全套 Bukkit 事件；完整 PlaceholderAPI 变量。
- 💾 **存储**：SQLite 开箱即用；MySQL 自动建库建表；Redis 跨服（缺失自动降级单服）。
- 📥 **数据导入**：一键导入旧版 PlayerGuild 的 MySQL dump（含自定义图标）。

## 前置（全部软依赖，缺失自动降级）

`liudungeon` · `LiuChat` · `liupass` · `CraftEngine` · `Sparrow Sync` · `LuckyPeaches` · `PlayerTitle` · `NotBounties` · `mcMMO` · `HuskHomes` · `Vault` · `PlaceholderAPI`

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

- Java 21 · Paper API 1.21.1 · `folia-supported: false`
- 第三方库由 `plugin.yml` 的 `libraries` 下载，不打进 jar。
