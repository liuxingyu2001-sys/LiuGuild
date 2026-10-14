# GUI

所有菜单在 `gui.yml` 中配置。材质支持原生物质或 `CE:命名空间:id`（CraftEngine）。
名称与 Lore 支持 `{占位符}` 与 PAPI `%xxx%`，渲染时替换（同时支持 MiniMessage 与 `&` legacy 颜色）。

## 界面引擎

```yaml
gui:
  engine: auto   # legacy | auto | sparrow
```
- `legacy`：使用内置库存 GUI（与旧版一致）。
- `auto`（默认）：优先使用 **Sparrow UI** 引擎渲染主菜单，初始化或渲染失败时自动回退内置 GUI。
- `sparrow`：强制使用 Sparrow UI（仍会在失败时回退）。

Sparrow UI（`beta.38`）是面向 Paper/Folia 的响应式库存 UI 库，已随 LiuGuild 打包，并 relocate 到
`com.liu.liuguild.libs.sparrow.ui`，因此无需在服务端单独安装，也不会与其它携带该库的插件冲突。

> 若主菜单出现异常，将自动降级为内置 GUI 并写入日志；要彻底关闭该引擎请把 `engine` 设为 `legacy`。

### 铁砧输入（Sparrow UI）

所有“请在聊天栏输入…”的交互（创建工会、金额、公告/简介、竞技场参数、套装名、比分、确认等）优先使用 **Sparrow UI 的铁砧界面**：

- 上方为铁砧输入框，下方放「确认 / 取消」按钮；输入实时生效，点确认提交。
- 未安装 / 未启用 Sparrow UI 或打开失败时，自动回退到聊天栏输入，功能不受影响。
- 开关：`gui.anvil-input`（默认 `true`）；要强制聊天栏输入设为 `false`。

## 管理面板（图形化编辑）

`/guildadmin gui` 打开管理面板：

- **竞技场**：列表 → 点击进入编辑器，可一键完成：
  - 传送并设置 A/B/旁观出生点（使用当前位置）
  - 设置集合点（使用当前位置）
  - 输入世界 / 承办服 / 模板 / 预热半径
  - 循环切换环境、时间、天气、难度
  - 开关“保留世界”“自动返回”
  - 删除竞技场
- **工会管理**：列表 → 点击进入编辑器，可改等级 / 资金 / 银行 / 公告 / 简介，或强制解散。

所有修改会即时写入 `arena.yml` 并热重载。

## 通用结构

```yaml
gui:
  filler:                       # 空槽填充物
    material: GRAY_STAINED_GLASS_PANE
    name: " "

  <菜单key>:
    title: "<标题，支持占位符>"
    rows: 6                     # 1~6
    buttons:                    # 固定按钮
      <按钮id>:
        slot: 20
        material: DIAMOND
        fallback-material: STONE
        name: "<名称>"
        lore: ["<第一行>", "<第二行>"]
    entry:                      # 列表型菜单的条目模板（成员/列表/图标/工会战模式）
      material: PLAYER_HEAD
      name: "<条目名>"
      lore: [...]
    back:                       # 返回按钮（分页菜单）
      slot: 49
      material: ARROW
      name: "<返回>"
```

## 内置菜单

| key | 说明 | 点击操作 |
|---|---|---|
| `main` | 工会主菜单（已入会） | 成员 / 工会战 / 银行 / 签到 / 设置 / 列表 / 退出 |
| `noguild` | **未入会引导菜单**（`/guild gui` 自动进入） | 创建工会 / 浏览列表 / 如何加入 / 关闭 |
| `members` | 成员列表（分页） | 左键晋升 / 右键降级 / Shift+左键踢出 |
| `list` | 工会列表（分页，未入会时含“创建工会”） | 左键申请加入 |
| `war` | 工会战（动态列出各模式） | 点击某模式参与匹配；含退出/战况/历史/返回 |
| `settings` | 工会设置 | 图标 / 加入方式 / 公告 / 简介 |
| `icons` | 图标选择（分页，按权限过滤） | 点击使用 |
| `admin` | 管理面板 | 状态 / 模式 / 场地 / 装备 / 重载 |

## 主菜单点击操作表

| 按钮 id | 操作 |
|---|---|
| `info` | 仅展示（使用工会自定义图标） |
| `members` | 打开成员菜单 |
| `war` | 打开工会战菜单 |
| `bank` | 左键存入 / 右键取出（聊天栏输入金额） |
| `checkin` | 每日签到（连续奖励） |
| `settings` | 打开设置菜单 |
| `list` | 打开工会列表 |
| `leave` | 退出工会 |
| `toggle_open` | 切换加入方式 |
| `notice` / `desc` | 聊天栏输入公告/简介 |
| `icon` | 打开图标菜单 |
| `admin_status` / `admin_modes` / `admin_arena` / `admin_guilds` / `admin_wars` / `admin_kits` | 管理面板各页 |
| `admin_verify` / `admin_cleanup` | 数据校验 / 僵尸工会清理 |
| `admin_reload` | 重载配置 |
| `close` | 关闭菜单 |
| `back` | 返回上级 |

## 常用占位符

工会：`{guild}` `{tag}` `{leader}` `{level}` `{max_level}` `{points}` `{members}`
`{member_limit}` `{wins}` `{losses}` `{bank}` `{notice}` `{description}` `{icon}` `{open}`。

成员条目：`{player}` `{rank}` `{contributed}` `{kills}` `{deaths}` `{joined}`。

模式条目（工会战菜单）：`{mode}` `{mode_key}` `{mode_state}` `{mode_min}` `{mode_team}`
`{mode_fair}` `{mode_health}` `{mode_arena}` `{mode_schedule}` `{mode_duration}`。

其它：`{count}` `{state}` `{enemy}` `{score}` `{enemy_score}` `{icon_name}` `{icon_cost}`
`{history_count}`；签到：`{checkin_streak}` `{checkin_state}`；
管理面板：`{server}` `{storage}` `{cross}` `{guild_count}` `{queue}` `{live_wars}`。

## 扩展

- 新增固定按钮：在对应菜单下加按钮 id，并在 `GuiManager.actionId` 中映射动作。
- 动态条目：列表型菜单已支持分页（`Pages` 工具），条目槽位自动排列，末行为返回与翻页。


## 新增菜单

| 入口 | 菜单 | 操作 |
|---|---|---|
| 主菜单「工会仓库」/ `/guild warehouse` | 共享仓库 | 直接存取物品（同一时间仅一人可开） |
| 主菜单「工会头衔」/ `/guild title` | 头衔面板 | 左键装备/卸下，右键删除，底部创建 |
| 主菜单「工会商店」/ `/guild shop` | 工会商店 | 左键购买，右键供货，Shift+左键改价，Shift+右键下架 |

> 三个入口都在主菜单第二排（槽位 28 / 29 / 30），与管理类按钮分开，避免误点。
