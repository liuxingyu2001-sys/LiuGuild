# LiuGuild 开发指南

本指南补充上级 `/home/liu/plugins/AGENTS.md`，适用于本仓库。

## 项目与目录

- 单模块 Maven 插件，Java 21、Paper API 1.21.1；不支持 Folia。
- 主类：`src/main/java/com/liu/liuguild/LiuGuild.java`，负责生命周期、模块装配、钩子、命令和调度。
- Java 包根：`com.liu.liuguild`。
  - `guild/`、`model/`：工会管理与数据模型；`db/`：HikariCP、SQLite/MySQL 与仓储。
  - `war/`：匹配、赛季、竞技场、套装与结算；`crossserver/`：Redis 消息和跨服传送。
  - `module/`、`listener/`：模块生命周期及事件；`hook/`：可选插件集成。
  - `command/`、`gui/`、`api/`：命令、菜单、开放 API。
  - `activity/`、`shop/`、`title/`、`warehouse/`：独立玩法服务；`importing/`：旧 PlayerGuild 数据导入。
  - `config/`：配置加载、解析、消息与设置；`util/`：通用工具。
- 默认 YAML 与插件声明：`src/main/resources/`。
- JUnit 5 测试：`src/test/java/com/liu/liuguild/`，按功能包组织。
- 使用与部署说明：`README.md` 和 `docs/`；修改对应功能时同步检查相关文档。

## 构建与测试

在本仓库根目录执行：

```bash
mvn test
mvn -Dtest=ConfigMergeTest test   # 示例：定向测试
mvn clean package
```

- 主产物：`target/Liu-LiuGuild-<version>.jar`，版本以 `pom.xml` 为准。
- 编译依赖固定本地文件 `../liudungeon/target/liudungeon-1.9.3.jar`（Maven `systemPath`）。缺失时先报告/核实依赖，不擅自删除集成或更改版本。
- 数据库及 Redis 库使用 `provided`，运行时由 `plugin.yml` 的 `libraries` 加载；修改版本需同步两处。
- **Sparrow UI 是例外**：由 Maven Shade 打包，并重定位到 `com.liu.liuguild.libs.sparrow.ui`，保留该隔离。
- README 使用 `-Dlicense.enabled=false` 示例，但当前 `pom.xml` 未定义该开关的构建逻辑；不要据此声称已启用或禁用授权。
- 单元测试不能代替 Paper 环境中的 GUI、传送、软依赖与多服 Redis 集成验证；最终报告实际验证范围。
- 初始化或普通修改不分发 jar、不递增版本、不提交推送、不启动服务器。

## 代码与生命周期

- 沿用现有四空格缩进、包结构和中文注释/日志；避免无关重构。
- 新服务优先沿用 `ModuleRegistry` 的注册、启用、重载、关闭机制，确保任务和监听器能正确清理。
- Bukkit 世界、实体、背包、传送及第三方玩家 API 操作留在主线程。使用 `LiuGuild.async`、`sync`、`syncDelayed` 与既有调度约定。
- 玩家触发的 JDBC/Redis IO 放到异步路径；回到主线程后再操作玩家/GUI。不要把 IO 放进战斗、菜单等热路径。
- 保留关闭顺序中的模块/任务清理、同步刷写和连接池关闭；区分 `reloadSettings()`（配置）与 `reloadAll()`（含工会数据）。不要将前者改名为 `reloadConfig()`，避免覆盖 Bukkit 初始化路径。
- 可选插件缺失时保持降级，避免新引入的直接类引用导致插件加载失败。
- 线程与工会战生命周期详见 `docs/threading.md`；具体实现以源码为准。

## 配置、接口与跨服兼容

- 改配置需同步默认 YAML、`config/` 解析、相关测试与 `docs/configuration.md` 或功能文档。
- Maven 仅过滤 `plugin.yml`；其他 YAML 不过滤，以保留运行时占位符。
- 共享目录仅从本服引导配置读取，`server.yml` 必须保持每服独立。游戏内编辑走 `overrides/` 覆盖层，保留删除标记和原子保存机制，不直接覆盖基础配置。
- 共享配置与覆盖优先级详见 `docs/shared-config.md`；不要把全局定时任务变成每服重复执行，保留 Redis 锁与消息防回声语义。
- 数据库表/迁移及 Redis 消息改动必须考虑已有数据、SQLite/MySQL 差异和多服滚动升级。
- 命令、权限和软依赖变更同步 `plugin.yml` 与 `docs/commands.md` / `docs/hooks.md`；API、事件及 PAPI 变量变更检查 `docs/api.md` / `docs/placeholders.md`。
- 配置和菜单变更优先运行 `config/`、`command/`、`gui/` 中相关回归测试；数据库、工会战和跨服变更补充对应测试。

## 环境边界

- `target/` 和临时产物不是普通源码；不将生成 jar 或运行中的服务器配置提交。
- `/home/mcser` 为远程 sshfs 挂载，不在其中扫描、构建或测试。只有明确授权分发时才按对应技能流程写入目标目录。
- 不在日志、文档或提交中暴露数据库密码、Redis 凭据等秘密。
