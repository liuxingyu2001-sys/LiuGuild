# 玩家功能 GUI 覆盖

目标：玩家常用功能优先通过 GUI 完成，不要求记忆命令。命令仍保留作为快捷方式/管理入口。

| 功能 | 原命令 | GUI 入口 | 状态 |
|---|---|---|---|
| 创建工会 | `/guild create` | 无工会菜单「创建工会」 | 已有 |
| 浏览/申请工会 | `/guild list`、`/guild apply` | 工会列表左键申请 | 已有 |
| 接受/拒绝邀请 | `/guild accept/deny` | 无工会菜单「待处理邀请」 | 已有 |
| 成员管理 | `/guild promote/demote/transfer/kick` | 成员列表左/右键/Shift | 已有 |
| 邀请玩家 | `/guild invite` | 成员列表底部「邀请玩家」 | 已有 |
| 工会设置 | `/guild set ...` | 设置菜单 | 已有 |
| 银行 | `/guild bank ...` | 主菜单「工会金币银行」 | 已有 |
| 签到 | `/guild checkin` | 主菜单「每日签到」 | 已有 |
| 驻地 | `/guild home ...` | 主菜单「工会驻地」 | 已有 |
| 商店 | `/guild shop ...` | 主菜单「工会商店」 | 已有 |
| 仓库 | `/guild warehouse` | 主菜单「工会仓库」 | 已有 |
| 头衔 | `/guild title ...` | 主菜单「工会头衔」 | 已有 |
| 工会战匹配 | `/guild war queue` | 工会战菜单模式条目 | 已有 |
| 退出匹配 | `/guild war leave` | 工会战菜单「退出匹配」 | 已有 |
| 重连战场 | `/guild war rejoin` | 工会战菜单「重连战场」 | 新增 |
| 战况 | `/guild war status` | 工会战菜单「当前战况」 | 已有 |
| 历史战绩 | `/guild war history` | 工会战菜单「历史战绩」 | 已有 |
| 本场战绩 | `/guild war stats` | 工会战菜单「本场战绩」 | 新增 |
| 旁观 | `/guild war spectate` | 工会战菜单「旁观比赛」 | 已有 |
| 系统邮件 | `/guild mail` | 主菜单/无工会菜单「工会邮件」 | 新增 |
| 入会申请审批 | `/guild apps` | 主菜单「入会申请」（官员） | 新增 |
| 审计日志 | `/guild log` | 主菜单「工会日志」（官员） | 新增 |
| 退出工会 | `/guild leave` | 主菜单「退出工会」 | 已有 |
| 解散工会 | `/guild disband confirm` | 设置/详情中的解散按钮 | 已有 |

仍保留命令的功能主要是管理类（`/guildadmin`）和需要精确文本输入的操作；文本输入优先走铁砧，失败回退聊天栏。
