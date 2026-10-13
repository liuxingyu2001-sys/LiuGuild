# PlaceholderAPI 变量

`%liuguild_home_slots%`：当前等级可用的工会驻地数量。

`%liuguild_funds%` 是本插件工会资金（points 别名）；`%liuguild_bank%` / `%liuguild_bank_raw%` 分别为 Vault 银行金币格式化余额和整数余额。


变量标识符：`liuguild`。用法：`%liuguild_<变量>%`。

## 服务端级

| 变量 | 说明 |
|---|---|
| `%liuguild_server_id%` | 本服 server-id |
| `%liuguild_guild_count%` | 全服工会数 |
| `%liuguild_total_players%` | 本服在线人数 |

## 玩家所在工会

| 变量 | 说明 |
|---|---|
| `%liuguild_name%` | 工会名 |
| `%liuguild_tag%` | 标签 |
| `%liuguild_level%` / `%liuguild_points%` | 等级 / 点数 |
| `%liuguild_members%` / `%liuguild_member_limit%` | 当前 / 上限成员数 |
| `%liuguild_wins%` / `%liuguild_losses%` | 胜 / 负场 |
| `%liuguild_bank%` / `%liuguild_bank_raw%` | 银行（格式化 / 原始） |
| `%liuguild_rank%` / `%liuguild_rank_display%` | 职位（枚举 / 显示名） |
| `%liuguild_leader%` | 会长名 |
| `%liuguild_notice%` / `%liuguild_description%` | 公告 / 简介 |
| `%liuguild_icon%` | 图标 id |
| `%liuguild_open%` | `open` / `closed` |
| `%liuguild_created%` | 创建日期 |
| `%liuguild_online%` | 在线成员数 |
| `%liuguild_kills%` / `%liuguild_deaths%` | 个人击杀 / 死亡 |
| `%liuguild_contributed%` | 个人贡献 |

## 工会战

| 变量 | 说明 |
|---|---|
| `%liuguild_war_state%` | 状态 |
| `%liuguild_war_enemy%` | 对手 |
| `%liuguild_war_score%` / `%liuguild_war_enemy_score%` | 比分 / 对方比分 |
| `%liuguild_war_time_left%` | 当前阶段剩余秒数 |
| `%liuguild_war_winner%` | 胜者 |
| `%liuguild_war_host%` | 承办服 |
| `%liuguild_war_mode%` / `%liuguild_war_mode_display%` | 模式 key / 显示名 |
| `%liuguild_queued%` / `%liuguild_queued_mode%` | 是否在队列 / 队列模式 |

## 指定工会 `g_<字段>_<工会名>`

例：`%liuguild_g_points_王者公会%`、`%liuguild_g_level_王者公会%`。
支持字段：`name, tag, level, points, members, wins, losses, bank, leader, icon`。

## 排行榜 `top_<名次>_<字段>`

例：`%liuguild_top_1_name%`、`%liuguild_top_1_points%`、`%liuguild_top_3_bank%`。
`top_<n>_<field>` 会按对应字段排序（`points`/`bank`/`level`/`wins`/`members`/`losses`），
字段可选：`name, tag, level, points, wins, losses, members, bank, leader, icon`。
