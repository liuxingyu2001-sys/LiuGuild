# GUI

所有菜单在 `gui.yml` 中配置。材质支持原生物质或 `CE:命名空间:id`（CraftEngine）。
名称与 Lore 支持 `{占位符}` 与 PAPI `%xxx%`，渲染时替换（MiniMessage 格式）。

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
| `main` | 工会主菜单 | 成员 / 工会战 / 银行 / 设置 / 列表 / 退出 |
| `members` | 成员列表（分页） | 左键晋升 / 右键降级 / Shift+左键踢出 |
| `list` | 工会列表（分页） | 左键申请加入 |
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
| `settings` | 打开设置菜单 |
| `list` | 打开工会列表 |
| `leave` | 退出工会 |
| `toggle_open` | 切换加入方式 |
| `notice` / `desc` | 聊天栏输入公告/简介 |
| `icon` | 打开图标菜单 |
| `admin_status` / `admin_modes` / `admin_arena` / `admin_kit` | 管理面板各页 |
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
`{history_count}`；管理面板：`{server}` `{storage}` `{cross}` `{guild_count}` `{queue}` `{live_wars}`。

## 扩展

- 新增固定按钮：在对应菜单下加按钮 id，并在 `GuiManager.actionId` 中映射动作。
- 动态条目：列表型菜单已支持分页（`Pages` 工具），条目槽位自动排列，末行为返回与翻页。
