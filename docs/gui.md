# GUI

所有菜单在 `gui.yml` 中配置。材质支持原生物质或 `CE:命名空间:id`（CraftEngine）。
名称与 Lore 支持 `{占位符}` 与 PAPI `%xxx%`，渲染时替换（同时支持 MiniMessage 与 `&` legacy 颜色）。

## 界面引擎

```yaml
gui:
  engine: auto   # legacy | auto | sparrow
```
- `legacy`：使用内置库存 GUI（与旧版一致）。
- `auto`（默认）：优先使用 **Sparrow UI** 引擎渲染全部菜单，初始化或渲染失败时自动回退内置 GUI。
- `sparrow`：强制使用 Sparrow UI（仍会在失败时回退）。

所有由 `GuiManager` 与仓库管理菜单构建的库存都会经同一渲染器接入 Sparrow 会话栈：

- 进入子菜单时压栈，**按 ESC（主动关窗）返回上一页**；在根菜单按 ESC 才真正关闭。
- 菜单内的「返回 / 关闭」按钮仍按配置语义跳转（返回主页会重置会话栈，避免无限叠层）。
- 仓库的**真实存储界面**仍是原生库存（需要自由存取物品），打开前会先结束 Sparrow 会话。

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

## 布局约定

- 主菜单：顶部工会名片；第三行成员、驻地、头衔、签到和频道；第四行个人资金、金币银行、商店、仓库及仓库管理；第五行工会战、工会列表、排行榜和设置；底部居中关闭。
- 列表页内容区与底栏分开，翻页位于左右两端，返回优先放底部中央；头衔卸下操作放在返回旁边。
- 排行榜前十名排列在内侧槽位，底部使用中文分类名称并标记当前选项。
- 配置已有返回或关闭按钮时，不再自动补第二个返回按钮。

已有服务器的 `gui.yml` 不会被默认布局覆盖；调整已有布局时请修改实际生效文件中的对应 `slot`，或备份后采用新版默认文件。代码修复（重复导航及排行榜布局）不依赖替换配置。

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
| `main` | 工会主菜单（已入会） | 成员 / 工会战 / 银行 / 签到 / 设置 / 列表 / 排行榜 / 驻地 / 仓库 / 商店 / 头衔 / 资金 / 关闭 |
| `noguild` | **未入会引导菜单**（`/guild gui` 自动进入） | 创建工会 / 浏览列表 / 待处理邀请 / 如何加入 / 关闭 |
| `members` | 成员列表（分页） | 左键晋升 / 右键降级 / Shift+左键转让 / Shift+右键踢出 / 底部邀请玩家 |
| `list` | 工会列表（分页，未入会时含“创建工会”） | 左键申请加入 |
| `war` | 工会战（动态列出各模式） | 点击模式匹配；重连/本场战绩/战况/旁观/退出/历史/返回 |
| `settings` | 工会设置 | 图标 / 加入方式 / 公告 / 简介 / 成员 PvP |
| `icons` | 图标选择（分页，按权限过滤） | 点击使用 |
| `mail` | 个人系统邮件（代码动态构建） | 查看正文 / 全部标记已读 |
| `applications` | 入会申请审批（官员，代码动态构建） | 左键批准 / 右键拒绝 |
| `audit` | 工会审计日志（官员，代码动态构建） | 只读 |
| `war_stats` | 最近一场工会战个人战绩（代码动态构建） | 只读 |
| `admin` | 管理面板 | 状态 / 模式 / 场地 / 装备 / 重载 |

### 代码动态构建的子菜单

以下菜单不写在 `gui.yml` 里，由代码按数据动态生成（与工会战 / 商店同风格）：

| 菜单 | 入口 | 操作 |
|---|---|---|
| 工会驻地 `homes` | 主菜单「工会驻地」/ `/guild home` | 逐槽位：左键传送 / 右键重设 / Shift+右键删除；多槽位完整暴露 |
| 排行榜 `top` | 主菜单「工会排行榜」 | 切换 points / bank / level / members / wins / losses，条目左键看详情 |
| 入会邀请 `invites` | 无工会菜单「待处理邀请」 | 左键接受，右键拒绝 |

## 主菜单点击操作表

| 按钮 id | 操作 |
|---|---|
| `info` | 仅展示（使用工会自定义图标） |
| `members` | 打开成员菜单 |
| `war` | 打开工会战菜单 |
| `home` | 打开工会驻地菜单（多槽位传送 / 设置 / 删除） |
| `top` | 打开工会排行榜 |
| `buffs` | 打开工会增益面板（效果 / 剩余时间 / 购买与续时确认） |
| `warehouse_manage` | 打开仓库管理菜单（购买时长 / 升级容量） |
| `chat` | 切换自己的工会频道（需 LiuChat，未安装时自动隐藏） |
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
`{mode_fair}` `{mode_health}` `{mode_arena}` `{mode_schedule}` `{mode_duration}` `{ranking}`。

其它：`{count}` `{state}` `{enemy}` `{score}` `{enemy_score}` `{icon_name}` `{icon_cost}`
`{history_count}` `{home_slots}` `{chat_state}` `{invite_count}`；签到：`{checkin_streak}` `{checkin_state}`；
管理面板：`{server}` `{storage}` `{cross}` `{guild_count}` `{queue}` `{live_wars}`。

## 扩展

- 新增固定按钮：在对应菜单下加按钮 id，并在 `GuiManager.actionId` 中映射动作。
- 动态条目：列表型菜单已支持分页（`Pages` 工具），条目槽位自动排列，末行为返回与翻页。


## 新增菜单

| 入口 | 菜单 | 操作 |
|---|---|---|
| 主菜单「工会仓库」/ `/guild warehouse` | 共享仓库 | 直接存取物品（同一时间仅一人可开） |
| 主菜单「仓库管理」/ `/guild warehouse info` | 仓库管理 | 购买使用时长 / 升级容量 / 进入仓库 |
| 主菜单「工会头衔」/ `/guild title` | 头衔面板 | 左键装备/卸下，底部卸下当前头衔 |
| 主菜单「工会商店」/ `/guild shop` | 工会商店（每会独立） | 左键购买，Shift+左键改价，Shift+右键下架；仅展示本会上架，无跨工会商店 |

> 三个入口都在主菜单第二排（槽位 28 / 29 / 30），与管理类按钮分开，避免误点。
