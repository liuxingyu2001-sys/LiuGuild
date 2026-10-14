# 管理工具

LiuGuild 提供完整的命令行与图形化（GUI）管理能力，管理员几乎所有配置都能在游戏内完成。

## 图形化管理面板

`/guildadmin gui` 打开管理面板：

| 分类 | 可做操作 |
|---|---|
| 插件状态 | 查看存储 / 跨服 / 工会数 / 队列 / 进行中比赛 |
| 工会战模式 | 查看各模式配置 |
| **竞技场** | 列表 → 编辑器：传送、设置 A/B/旁观出生点、设置集合点、输入世界/承办服/模板/半径、循环切换环境/时间/天气/难度、开关保留世界与自动返回、删除竞技场 |
| **工会管理** | 列表 → 编辑器：改等级/资金/银行/公告/简介、查看成员与会长、强制解散 |
| **工会战管理** | 进行中比赛列表 → 编辑器：传送竞技场、改 A/B 比分、强制结束 |
| **装备套装** | 列表 → 编辑器：用当前装备保存快照、删除、新建套装 |
| 重载配置 | 重新加载全部模块 |

所有 GUI 修改都会即时写入对应 `*.yml` 并热重载。

## 管理指令 `/guildadmin`

### 通用
| 指令 | 说明 |
|---|---|
| `/guildadmin help` | 管理帮助 |
| `/guildadmin reload` | 重载全部配置与模块 |
| `/guildadmin status` | 详细运行状态 |
| `/guildadmin gui` | 打开管理面板 |
| `/guildadmin import <sql文件>` | 导入旧版 PlayerGuild 数据 |
| `/guildadmin broadcast <文本>` | 全服广播（支持颜色） |

### 竞技场
| 指令 | 说明 |
|---|---|
| `/guildadmin arena list` | 竞技场列表 |
| `/guildadmin arena info <id>` | 竞技场详情 |
| `/guildadmin arena create <id>` | 新建竞技场 |
| `/guildadmin arena remove <id>` | 删除竞技场 |
| `/guildadmin arena setspawn <id> <a\|b\|spec>` | 用当前位置设置出生点 |
| `/guildadmin arena setworld <id> <世界>` | 设置固定世界 |
| `/guildadmin arena setserver <id> <承办服id>` | 设置承办服 |
| `/guildadmin arena settemplate <id> <模板>` | 设置 liudungeon 动态模板 |
| `/guildadmin arena setradius <id> <半径>` | 设置预热半径 |
| `/guildadmin arena settempworld <id> <项> <值>` | environment / keep-on-end / name-format / time / weather / difficulty |
| `/guildadmin arena setgamerule <id> <规则> <true\|false>` | 设置临时世界 gamerule |
| `/guildadmin arena setafter <id> <项> <值>` | auto-return / return-delay-seconds / end-spawn.enabled |
| `/guildadmin arena setendspawn <id>` | 用当前位置设置集合点 |
| `/guildadmin arena tp <id>` | 传送到竞技场 A 点 |

### 工会战
| 指令 | 说明 |
|---|---|
| `/guildadmin war list` | 进行中的比赛与队列数量 |
| `/guildadmin war queue` | 列出匹配队列中的工会 |
| `/guildadmin war start <模式> <工会A> <工会B>` | 强制开启一场比赛 |
| `/guildadmin war stop [id]` | 停止比赛（立即结算） |
| `/guildadmin war tp` | 传送到当前竞技场 |
| `/guildadmin war score <id> <a\|b> <分>` | 修改比分 |
| `/guildadmin forceend [id]` | 同 `war stop` |

### 工会与数据
| 指令 | 说明 |
|---|---|
| `/guildadmin guilds [页码]` | 分页列出全部工会 |
| `/guildadmin info <工会>` | 工会详情 |
| `/guildadmin player <玩家>` | 查看玩家所属工会 |
| `/guildadmin disband <工会>` | 强制解散 |
| `/guildadmin setlevel <工会> <等级>` | 设置等级 |
| `/guildadmin setpoints <工会> <点数>` | 设置工会资金 |
| `/guildadmin setbank <工会> <金额>` | 设置银行余额 |
| `/guildadmin cleanup [dry\|confirm]` | 清理僵尸工会 |
| `/guildadmin verify [fix]` | 校验（并修复）内存数据一致性：member_count、会长关系、成员索引 |
| `/guildadmin doctor` | 部署自检：逐项检查 server-id / 数据库 / 跨服 / 经济 / 场地 / 模板 / 套装 / 世界 |

### 装备 / 模式
| 指令 | 说明 |
|---|---|
| `/guildadmin kit list` | 套装列表 |
| `/guildadmin kit save <key>` | 用当前装备保存套装 |
| `/guildadmin kit delete <key>` | 删除套装 |
| `/guildadmin kit info <key>` | 套装详情 |
| `/guildadmin mode list` | 模式列表 |
| `/guildadmin mode info <模式>` | 模式详情 |
| `/guildadmin mode start <模式>` | 开启赛季赛事 |

> 所有子命令、工会名、在线玩家、竞技场、套装、模式、坐标点均有 Tab 补全。
