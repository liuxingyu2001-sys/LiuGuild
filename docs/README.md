# LiuGuild 文档

Paper 1.21 跨服工会插件。核心特性：工会管理、跨服工会战（匹配 / 赛季 / 自定义模式）、
竞技场（承办服 + 世界 + 坐标全自定义）、公平装备与公平血量、CraftEngine 自定义图标、
模块化配置、完整开放 API、PAPI 变量、旧版 PlayerGuild 数据导入。

## 目录

| 文档 | 说明 |
|---|---|
| [commands.md](commands.md) | 玩家 / 管理指令、权限、Tab 补全 |
| [configuration.md](configuration.md) | 全部配置文件说明（模块化拆分） |
| [war.md](war.md) | 工会战：模式、匹配、赛季时间表、生命周期 |
| [arena.md](arena.md) | 竞技场：承办服、世界、坐标、动态模板 |
| [kit.md](kit.md) | 统一装备：多套装、快照保存、公平血量 |
| [gui.md](gui.md) | GUI 菜单与按钮配置、点击操作 |
| [home.md](home.md) | 工会驻地（HuskHomes 传送） |
| [api.md](api.md) | 开放 API 与 Bukkit 事件 |
| [placeholders.md](placeholders.md) | PlaceholderAPI 变量 |
| [craftengine.md](craftengine.md) | CraftEngine 物品/图标集成 |
| [hooks.md](hooks.md) | 软依赖钩子（liudungeon / LiuChat / liupass / Sparrow Sync / LuckyPeaches / PlayerTitle / NotBounties / mcMMO） |
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
