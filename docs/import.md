# 旧版 PlayerGuild 数据导入

支持把旧版 **PlayerGuild** 的 MySQL dump（`.sql`）导入 LiuGuild。

## 用法

```
/guildadmin import <sql文件路径>
```

路径规则：绝对路径直接使用；相对路径先相对 `plugins/LiuGuild/`，再相对 `plugins/`。
导入为异步执行，完成后在后台/控制台输出统计。

## 字段映射

| PlayerGuild | LiuGuild |
|---|---|
| `guild_info.guild_name` | 工会名 |
| `guild_info.logo_name`（序列化 ItemStack） | 工会自定义图标（`icon_data`） |
| `guild_info.description` | 简介 |
| `guild_info.level` | 等级 |
| `guild_info.money` | 工会银行 |
| `guild_info.prosperity_degree` | 工会点数 |
| `guild_info.join_mode`（0=自动） | 加入方式 `open` |
| `guild_info.create_time` | 创建时间（`Asia/Shanghai`） |
| `guild_player.player_uuid` / `player_name` | 成员 UUID / 名称 |
| `guild_player.role_id` | 职位：1=会长，2=副会长，其它=成员 |
| `guild_player.total_money` | 成员贡献 |
| `guild_player.join_time` | 加入时间 |
| `guild_player.kill` / `die` | 击杀 / 死亡 |

## 行为与幂等

- 工会 ID 重新生成（源 id → 新 id 映射）。
- 已存在同名工会将被跳过。
- 已在其它工会的玩家（UUID 唯一）将被跳过。
- 无成员的工会跳过。
- 名称超长会按 `guild.yml` 的 `nameMax` 截断；标签按 `tagMin`/`tagMax` 自动生成并保证唯一。
- 导入结束自动重载工会缓存。

## 独立图标

从 `guild_info.logo_name` 读取的是当前生效图标（含 CraftEngine 自定义数据的序列化 ItemStack），
存入 `guilds.icon_data`。GUI 渲染时优先使用它，缺失时回退 `icons.yml`。

## 实现

- `importing/SqlDump.java`：轻量 MySQL dump 解析器（支持 `\'`/`\\`/`\n`、`''`、字面换行、`NULL`）。
- `importing/PlayerguildImporter.java`：字段映射、唯一化与写库。
