# 固定工会头衔

工会头衔由管理员在 `guild.yml` 配置，全服使用同一份预设。玩家（包括会长、官员及 OP）只能选择符合最低职位要求的头衔，不能通过命令或 GUI 创建、修改、删除头衔。

```yaml
guild:
  titles:
    enabled: true
    presets:
      member:
        display: "<white>工会成员"
        min-rank: MEMBER
      officer:
        display: "<aqua>工会官员"
        min-rank: OFFICER
      leader:
        display: "<gold>工会会长"
        min-rank: LEADER
```

- 最多45个预设；标识为1～16位小写字母、数字、下划线或连字符。
- 显示文字最多16个可见字符，支持颜色。
- `min-rank` 是最低职位：MEMBER / OFFICER / LEADER。降职后不再显示不符合要求的头衔。
- 旧配置缺少 `presets` 时使用上述三个默认预设；显式设置 `presets: {}` 可清空预设。
- 修改后重载配置即可生效。`max`、`manage-rank` 和 `liuguild.title.manage` 已停用。

| 指令 | 说明 |
|---|---|
| `/guild title` | 打开固定头衔面板 |
| `/guild title list` | 查看预设与职位要求 |
| `/guild title set <标识>` | 装备符合条件的头衔 |
| `/guild title clear` | 卸下头衔 |

GUI 左键装备或卸下，右键不再删除。每人同时最多一个，显示于成员列表、工会频道及 `%liuguild_title%`。

升级后不再读取数据库中的旧自定义头衔定义，旧记录保留但不展示。已装备ID若与固定预设一致，只显示预设文字；其它旧头衔不再生效，不会把旧自定义文本转成固定头衔。
