# 工会头衔

工会自定义的显示称号。会长/副会长创建一批头衔，每个头衔设一个**最低可用职位**；成员从自己够格的里选一个装备（每人同时最多一个）。

## 指令

| 指令 | 权限 | 说明 |
|---|---|---|
| `/guild title` | 所有人 | 打开头衔面板（GUI） |
| `/guild title list` | 所有人 | 列出本工会头衔，标出够格 / 已装备 |
| `/guild title set <标识>` | 所有人 | 装备（仅限自己够格的） |
| `/guild title clear` | 所有人 | 卸下当前头衔 |
| `/guild title create <标识> <显示> [最低职位]` | 管理 | 创建或更新头衔 |
| `/guild title delete <标识>` | 管理 | 删除头衔 |

别名：`/guild titles`、`/guild chenghao`。

**管理权限**：拥有 `liuguild.title.manage` 权限节点，**或**职位达到 `guild.titles.manage-rank`（默认 `OFFICER`）。

「最低职位」可写 `MEMBER` / `OFFICER` / `LEADER`，也接受中文 `成员` / `副会长` / `会长`。省略则为 `MEMBER`。

```text
/guild title create legend <gold>传说            → 所有人可用
/guild title create elite <red>精英 副会长        → 仅副会长/会长可用
```

## 头衔面板

主菜单 →「工会头衔」，或 `/guild title`。

- 左键：装备 / 卸下
- 右键：删除（需要管理权限）
- 底部：「创建头衔」（两步铁砧输入：标识 → 显示文本）、「卸下当前头衔」、「返回」

不够格的头衔**仍会显示但置灰**，让成员看得到目标。

## 显示位置

| 位置 | 写法 |
|---|---|
| PlaceholderAPI | `%liuguild_title%`（已装备的显示文本，未装备为空）/ `%liuguild_title_id%` |
| 成员列表 GUI | 头像名后与 lore 里的「头衔」一行 |
| 聊天 / 工会频道 | 由聊天插件按需引用 `%liuguild_title%` |

## 校验规则

| 项 | 规则 | 超限提示 |
|---|---|---|
| 标识 | `^[a-z0-9_-]{1,16}$`，工会内唯一 | `title-id-invalid` |
| 显示文本 | 去掉颜色/格式标签后 1~16 个可见字符 | `title-display-invalid` / `title-display-too-long` |
| 数量 | 每工会最多 `guild.titles.max`（默认 12） | `title-limit` |

显示文本**保留颜色与装饰**（`<red>`、渐变、`&c` 等），但会剥掉点击 / 悬浮 / 插入事件 —— 玩家可控文本直接进 MiniMessage 会被注入。

## 配置

```yaml
guild:
  titles:
    enabled: true        # 是否启用
    max: 12              # 每工会最多几个头衔
    manage-rank: OFFICER # 谁能创建/删除（MEMBER / OFFICER / LEADER）
```

## 一致性保证

- 成员**退会 / 被踢**：自动卸下头衔。
- 头衔被**删除**：所有装备它的成员一并卸下（事务内完成）。
- 成员被**降职**后不再满足某头衔的最低职位：该头衔**不再显示**（但仍保留装备记录，升回去就恢复）。
- 工会**解散**：头衔定义与装备记录全部清空。
- 跨服：头衔变更通过 `GUILD_TITLES` 消息广播，其他子服按工会重载。

## 数据表

```sql
liuguild_guild_titles (
  guild_id   VARCHAR(36),
  title_id   VARCHAR(32),
  display    VARCHAR(64),
  min_rank   VARCHAR(16),
  created_at BIGINT,
  PRIMARY KEY (guild_id, title_id)
)

liuguild_guild_member_titles (
  player_uuid VARCHAR(36) PRIMARY KEY,   -- 每人同时最多一个头衔
  guild_id    VARCHAR(36),
  title_id    VARCHAR(32)
)
```

> 装备记录**单独成表**而不是给 `guild_members` 加列：加列要改 5 处 INSERT/UPDATE 的列与绑定，容易漏改导致成员数据写入失败。

## 相关

- [configuration.md](configuration.md)
- [placeholders.md](placeholders.md)
- [gui.md](gui.md)
