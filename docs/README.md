# LiuGuild 文档

Paper 1.21 跨服工会插件。核心特性：工会管理、跨服工会战（匹配 / 赛季 / 自定义模式）、
竞技场（承办服 + 世界 + 坐标全自定义）、公平装备与公平血量、CraftEngine 自定义图标、
模块化配置、完整开放 API、PAPI 变量、旧版 PlayerGuild 数据导入。

## 目录

| 文档 | 说明 |
|---|---|
| [deployment.md](deployment.md) | **生产部署与运维**（环境、单服/跨服、配置清单、排障、备份） |
| [shared-config.md](shared-config.md) | **共享配置目录与多服一致性**（共享什么/不共享什么、单写者锁、直接保存与旧配置迁移） |
| [threading.md](threading.md) | **线程模型 / 预加载 / 生命周期**（什么在哪个线程跑、结算分帧） |
| [commands.md](commands.md) | 玩家 / 管理指令、权限、Tab 补全 |
| [admin.md](admin.md) | 管理工具（完整指令 + 图形化编辑器） |
| [configuration.md](configuration.md) | 全部配置文件说明（模块化拆分） |
| [war.md](war.md) | 工会战：模式、匹配、赛季时间表、生命周期 |
| [arena.md](arena.md) | 竞技场：承办服、世界、坐标、动态模板 |
| [kit.md](kit.md) | 统一装备：多套装、快照保存、公平血量 |
| [gui.md](gui.md) | GUI 菜单与按钮配置、点击操作 |
| [home.md](home.md) | 工会驻地（HuskHomes 传送） |
| [cleanup.md](cleanup.md) | 僵尸工会清理（自动/手动） |
| [checkin.md](checkin.md) | 工会每日签到（连续奖励） |
| [tasks.md](tasks.md) | 工会任务与 liupass 对接（扩展任务类型 / 通行证经验） |
| [activity.md](activity.md) | 玩家在线活跃发工会资金（活跃判定 + 每日上限） |
| [warehouse.md](warehouse.md) | 工会仓库（跨服共享 + 原子锁） |
| [buffs.md](buffs.md) | 工会公共资金购买限时增益 |
| [titles.md](titles.md) | 固定工会头衔 |
| [shop.md](shop.md) | 工会商店（按工会隔离；个人工会资金购买，点数入账） |
| [api.md](api.md) | 开放 API 与 Bukkit 事件 |
| [placeholders.md](placeholders.md) | PlaceholderAPI 变量 |
| [craftengine.md](craftengine.md) | CraftEngine 物品/图标集成 |
| [hooks.md](hooks.md) | 软依赖钩子（liudungeon / LiuChat / liupass / Sparrow Sync / LuckyPeaches / PlayerTitle / NotBounties / mcMMO / 宠物） |
| [gui-coverage.md](gui-coverage.md) | 玩家功能 GUI 覆盖清单 |
| [known-issues.md](known-issues.md) | 已知问题与待实测项 |
| [nameplates.md](nameplates.md) | CustomNameplates 工会战头顶铭牌 |
| [qqbot.md](qqbot.md) | QQ 官方机器人 WebSocket、指令查询与事件推送 |
| [webhook.md](webhook.md) | Discord / 通用 JSON webhook 推送 |
| [audit.md](audit.md) | 工会审计日志与管理员清理动作 |
| [mail.md](mail.md) | 离线工会邮件 |
| [applications.md](applications.md) | 入会申请 / 招募板 |
| [gui.md](gui.md) | GUI 菜单、Sparrow UI 界面引擎 |
| [import.md](import.md) | 旧版 PlayerGuild 数据导入 |
| [review-report.md](review-report.md) | 五轮深度审查报告（发现、修复、验证、遗留） |

## 快速开始

1. 把 `Liu-LiuGuild-<version>.jar` 放入 `plugins/`。
2. 启动服务器，生成配置（`plugins/LiuGuild/`）。
3. **单服**：`database.yml` 保持 `sqlite` 即可。
   **跨服工会战**：所有子服改用同一 `mysql`，并配置相同的 `cross-server.yml`（Redis）。
4. 每台子服在 `plugins/LiuGuild/server.yml` 设置唯一 `server-id`。
5. 用 `/guildadmin arena setspawn main a` 等在竞技场现场站好点设置出生坐标。
6. 玩家 `/guild gui` 打开工会菜单，点击「工会战」选择模式参与匹配。

## 模块与扩展

- 代码按功能分包：`guild`（核心）、`war`（工会战）、`gui`、`hook`、`crossserver`、`db`、`config`、`command`、`listener`、`api`、`importing`、`util`。
- 所有功能实现 `GuildModule`，由 `ModuleRegistry` 按 `order` 启停与重载，便于二次开发。
- 软依赖全部通过反射访问，缺失时对应功能自动降级，不影响启动。

## 构建

```bash
mvn clean package -Dlicense.enabled=false
# 产物：target/Liu-LiuGuild-<version>.jar
```

- Java 21 / Paper API 1.21.1。
- 第三方库由 `plugin.yml` 的 `libraries` 交给 Paper 下载，不打进 jar。
