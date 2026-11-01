# LiuGuild 开发指南

本文件是本仓库的项目级约定，适用于在 `LiuGuild` 仓库内工作的开发者与 AI。若上级目录另有工作区级
`AGENTS.md`，两者同时适用；本文件更具体，冲突时以本文件为准。

## 项目定位

- 单模块 Maven 插件，Java 21（`maven.compiler.release=21`）、Paper API 1.21.1；`plugin.yml` 声明
  `api-version: '1.21'`、`folia-supported: false`，不支持 Folia。
- 主类：`src/main/java/com/liu/liuguild/LiuGuild.java`，负责生命周期、模块装配、软依赖钩子、命令与调度。
- 硬依赖 **LiuLib 1.0.5+**（物品规格解析 / `ItemCodec`）：`pom.xml` 中 `provided` 编译，运行时由服务端
  安装的 LiuLib 提供，`plugin.yml` 的 `depend` 已声明。其余第三方插件均为软依赖（`softdepend` +
  反射访问），缺失时对应功能降级，不能因新增直接类引用导致插件加载失败。
- 数据库 / Redis 驱动与连接池（HikariCP、mysql-connector-j、sqlite-jdbc、jedis、commons-pool2）以
  `provided` 编译，运行时由 `plugin.yml` 的 `libraries` 交给 Paper 下载；两份清单的坐标与版本必须同步。
- VaultAPI 1.7 只发布在 jitpack，`pom.xml` 已声明 `jitpack.io` 仓库；不要删除，否则依赖解析失败。

## 代码结构

- Java 包根：`com.liu.liuguild`，按功能分包：
  - `guild/`、`model/`：工会管理与数据模型；`db/`：HikariCP、SQLite/MySQL 与仓储。
  - `war/`：匹配、赛季、竞技场、套装与结算；`crossserver/`：Redis 消息、心跳与跨服传送。
  - `module/`：`GuildModule` + `ModuleRegistry` 生命周期；`listener/`：事件监听与跨服同步。
  - `hook/`：可选插件集成（liudungeon / LiuChat / liupass / CraftEngine / PAPI / LuckPerms / Vault /
    PacketEvents / webhook / QQ 机器人等）。
  - `command/`、`gui/`：命令与菜单（Sparrow UI 与 Bukkit 菜单）；`api/`：`GuildAPI` 门面与 Bukkit 事件。
  - `application/`（入会申请 / 招募板）、`audit/`（审计日志）、`mail/`（离线工会邮件）、
    `activity/`（活跃奖励）、`buff/`（限时增益）、`shop/`（工会商店）、`title/`（固定头衔）、
    `warehouse/`（跨服仓库）、`importing/`（旧 PlayerGuild 导入）。
  - `config/`：`ConfigManager`、`Settings` 与各功能配置解析、消息；`util/`：通用工具。
- 默认 YAML 与插件声明：`src/main/resources/`（`config.yml`、`database.yml`、`cross-server.yml`、
  `guild.yml`、`war.yml`、`war-modes.yml`、`arena.yml`、`kit.yml`、`buffs.yml`、`gui.yml`、`icons.yml`、
  `messages.yml`、`tasks.yml`、`warehouse.yml`、`server.yml`、`plugin.yml`）。
- JUnit 5 测试：`src/test/java/com/liu/liuguild/`，与主代码按功能包对应。
- 项目文档：`docs/README.md` 为索引，`README.md` 为使用说明；改功能时同步对应文档。
- 代码风格：沿用现有四空格缩进、包结构与中文注释/日志；避免无关重构。

## 构建与测试

在本仓库根目录执行：

```bash
mvn test
mvn -Dtest=ConfigMergeTest test   # 示例：定向测试
mvn clean package
```

- 主产物：`target/Liu-LiuGuild-<version>.jar`（`finalName` 为 `Liu-${artifactId}-${version}`），
  版本以 `pom.xml` 为准。
- CI（`.github/workflows/build.yml`）使用 JDK 21 + `mvn -B -ntp clean package -Dlicense.enabled=false`；
  `README.md` 也用该参数，但当前 `pom.xml` 未定义 `license.enabled` 开关，不要据此声称授权已启用/禁用。
- 编译期本地依赖固定为仓库内提交的 `libs/liudungeon-1.9.3.jar`（`pom.xml` 的 `system` 依赖，
  见 `libs/README.md`）；不要改回 `../liudungeon/target/...` 或擅自更换版本。
- **Sparrow UI 是打包容器的例外**：`net.momirealms:sparrow-ui` 为 `compile`，由 Maven Shade 重定位到
  `com.liu.liuguild.libs.sparrow.ui`；保留该隔离，不要把其他依赖改成 `compile`。
- 单元测试不能代替 Paper 环境中的 GUI、传送、软依赖与多服 Redis 集成验证；最终报告需说明实际验证范围。
- 初始化或普通修改不分发 jar、不递增版本、不提交推送、不启动服务器。

## 生命周期与线程

- 新功能优先实现 `GuildModule`（`id()` / `order()` / `enable` / `disable` / `reload` / `onCrossServer`），
  由 `ModuleRegistry` 注册并按 `order` 启停、重载、分发跨服消息；确保任务与监听器能正确清理。
- Bukkit 世界、实体、背包、传送及第三方玩家 API 操作留在主线程；使用 `LiuGuild.async`、`sync`、
  `syncDelayed` 与既有调度约定。玩家触发的 JDBC / Redis IO 放到异步路径，回到主线程后再操作玩家/GUI；
  不要把 IO 放进战斗、菜单等热路径。
- 关服顺序（`onDisable`）依次包含：外部连接关闭、仓库跨服锁释放、跨服总线停止、模块逆序关闭、
  定时任务取消、收尾线程排空、`saveAllSync` 同步刷写、连接池关闭；不要打乱或省略。
- 区分 `reloadSettings()`（仅配置/消息与定时任务）与 `reloadAll()`（含工会数据全量重读）。不要把前者改名为
  `reloadConfig()`，否则覆盖 `JavaPlugin.reloadConfig()` 并导致初始化 NPE。跨服连接参数在启动时固化，
  热重载时会被冻结回旧值并告警，需重启服务器生效。
- 线程模型与工会战生命周期详见 `docs/threading.md`；具体实现以源码为准。

## 配置、接口与跨服兼容

- 本插件处于全新开发测试阶段；配置和功能调整直接采用新设计，不为旧版本保留兼容解析、别名或迁移路径，
  除非用户另行要求。

- 改配置需同步：默认 YAML、`config/` 解析、相关测试与 `docs/configuration.md` 或对应功能文档。
- Maven 只对 `plugin.yml` 做资源过滤；其他 YAML 不过滤，以保留运行时占位符。
- 共享目录仅从本服引导配置读取，`server.yml` 必须保持每服独立。游戏内编辑直接保存同名基础 YAML，
  保留原子保存及多服文件锁；旧 `overrides/` 仅用于升级迁移，成功备份合并后移除。
  共享配置与覆盖优先级详见 `docs/shared-config.md`。
- 不要把全局定时任务改成每服重复执行；保留 Redis 锁与消息防回声语义。
- 数据库表/迁移及 Redis 消息改动必须考虑已有数据、SQLite/MySQL 差异和多服滚动升级。
- 命令、权限和软依赖变更同步 `plugin.yml` 与 `docs/commands.md` / `docs/hooks.md`；
  API、事件及 PAPI 变量变更检查 `docs/api.md` / `docs/placeholders.md`。
- 配置和菜单变更优先运行 `config/`、`command/`、`gui/` 中相关回归测试；数据库、工会战和跨服变更补充对应测试。

## 环境边界

- `target/`、临时产物不是普通源码；不将生成 jar 或运行中的服务器配置提交。
- `libs/liudungeon-1.9.3.jar` 是刻意提交的编译期依赖，不要删除或加入 `.gitignore`。
- `/home/mcser` 为远程 sshfs 挂载，不在其中扫描、构建或测试。只有明确授权分发时才按对应技能流程写入目标目录。
- 不在日志、文档或提交中暴露数据库密码、Redis 凭据等秘密。
