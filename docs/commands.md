# 指令

## 玩家指令 `/guild`（别名 `g` / `gh` / `gonghui`）

| 指令 | 说明 |
|---|---|
| `/guild`、`/guild gui` | 打开工会主菜单 |
| `/guild create <名称> [标签]` | 创建工会（扣除创建费） |
| `/guild disband confirm` | 会长解散工会（二次确认） |
| `/guild join <名称>` | 加入开放加入的工会 |
| `/guild leave` | 退出工会（会长需先转让/解散） |
| `/guild invite <玩家>` | 邀请玩家（会长/官员） |
| `/guild accept <工会>` | 接受邀请 |
| `/guild deny <工会>` | 拒绝邀请 |
| `/guild kick <玩家>` | 踢出成员（会长/官员） |
| `/guild members` | 查看成员列表（打开成员 GUI） |
| `/guild promote <玩家>` | 提升职位（左键同效） |
| `/guild demote <玩家>` | 降低职位（右键同效） |
| `/guild transfer <玩家>` | 转让会长 |
| `/guild seticon <图标id>` | 更换工会图标（扣费） |
| `/guild set open <true/false>` | 设置加入方式 |
| `/guild set notice <内容>` | 设置公告 |
| `/guild set desc <内容>` | 设置简介 |
| `/guild bank deposit <金额>` | 存入工会银行 |
| `/guild bank withdraw <金额>` | 取出工会银行（所有成员均可） |
| `/guild home [槽位]` | 传送到工会驻地（需 HuskHomes） |
| `/guild home set [槽位]` | 设置工会驻地（会长/副会长） |
| `/guild home del [槽位]` | 删除工会驻地（会长/副会长） |
| `/guild home list` | 查看驻地槽位 |
| `/guild checkin` | 每日签到领取工会资金（别名 `sign`） |
| `/guild info [工会]` | 查看工会信息 |
| `/guild list [页码]` | 工会列表 |
| `/guild top <points\|bank\|level\|wins>` | 排行榜 |
| `/guild war queue [模式]` | 参与匹配（默认模式） |
| `/guild war leave` | 退出匹配队列 |
| `/guild war join` | 进入竞技场（匹配成功后） |
| `/guild war spectate` | 旁观 |
| `/guild war status` | 当前战况 |
| `/guild war history` | 历史战绩 |
| `/guild residence list` | 列出自己的领地（别名 `res` / `lingdi`） |
| `/guild residence share <领地>` | 把自有领地在工会内共享 |
| `/guild residence unshare <领地>` | 取消共享 |
| `/guild warehouse` | 打开工会共享仓库（别名 `wh` / `cangku`；同一时间仅一人可开；未开通/过期时打开管理菜单） |
| `/guild warehouse info` | 打开仓库管理菜单（购买时长 / 升级容量） |
| `/guild warehouse rent <天数>` | 用工会资金购买仓库使用时间 |
| `/guild warehouse upgrade` | 用工会资金升级仓库容量 |
| `/guild title` | 打开头衔面板（别名 `titles` / `chenghao`） |
| `/guild title list` | 列出本会头衔 |
| `/guild title set <标识>` | 装备头衔（仅限自己够格的） |
| `/guild title clear` | 卸下头衔 |
| `/guild title create <标识> <显示> [职位]` | 创建/更新头衔（管理） |
| `/guild title delete <标识>` | 删除头衔（管理） |
| `/guild shop` | 打开本会商店（别名 `shangdian` / `store`） |
| `/guild shop all` | ~~浏览全服工会商店~~（已移除：商店按工会隔离） |
| `/guild shop add <工会资金单价>` | 上架手持物品（管理） |
| `/guild shop remove <编号前8位>` | 下架并退回剩余（管理） |

## 管理指令 `/guildadmin`（权限 `liuguild.admin`）

| 指令 | 说明 |
|---|---|
| `/guildadmin reload` | 重载全部配置与模块 |
| `/guildadmin status` | **详细运行状态**（存储、跨服、工会、队列、场地、钩子、模块） |
| `/guildadmin gui` | 打开管理面板 GUI |
| `/guildadmin import <sql文件> [force]` | 导入旧版 PlayerGuild 数据（见 [import.md](import.md)） |
| `/guildadmin arena list` | 竞技场列表 |
| `/guildadmin arena info <id>` | 竞技场详情 |
| `/guildadmin arena setspawn <id> <a\|b\|spec>` | **把当前所站位置设为出生点** |
| `/guildadmin arena setworld <id> <世界>` | 设置固定世界 |
| `/guildadmin arena setserver <id> <承办服id>` | 设置承办服 |
| `/guildadmin arena settemplate <id> <模板>` | 设置 liudungeon 动态模板 |
| `/guildadmin arena create <id>` | 新建竞技场 |
| `/guildadmin arena remove <id>` | 删除竞技场 |
| `/guildadmin arena setradius <id> <半径>` | 设置动态世界预热半径 |
| `/guildadmin arena tp <id>` | 传送到竞技场 A 点（若竞技场在别的子服承办，自动先跨服传送到承办服再送到场地） |
| `/guildadmin arena settempworld <id> <项> <值>` | 设置临时世界（environment / keep-on-end / time / weather / difficulty / name-format） |
| `/guildadmin arena setgamerule <id> <规则> <true\|false>` | 设置临时世界 gamerule |
| `/guildadmin arena setafter <id> <项> <值>` | 设置战后行为（auto-return / return-delay-seconds / end-spawn.enabled） |
| `/guildadmin arena setendspawn <id>` | 把当前位置设为集合点 |
| `/guildadmin war list` | 进行中的工会战与队列 |
| `/guildadmin war start <模式> <工会A> <工会B>` | **强制开启一场工会战** |
| `/guildadmin war stop [id]` | 停止工会战（等同 forceend） |
| `/guildadmin war tp` | 传送到当前竞技场（非承办服会自动先传送到承办服） |
| `/guildadmin war score <id> <a\|b> <分>` | 修改比分 |
| `/guildadmin guilds [页码]` | 分页列出全部工会 |
| `/guildadmin player <玩家>` | 查看玩家所属工会 |
| `/guildadmin broadcast <文本>` | 全服广播（支持颜色） |
| `/guildadmin setbank <工会> <金额>` | 设置工会银行余额 |
| `/guildadmin cleanup [dry\|confirm]` | 清理僵尸工会 |
| `/guildadmin kit list` | 装备套装列表 |
| `/guildadmin kit save <key>` | **把当前身上穿的装备保存为套装** |
| `/guildadmin kit delete <key>` | 删除套装 |
| `/guildadmin kit info <key>` | 套装详情 |
| `/guildadmin mode list` | 工会战模式列表 |
| `/guildadmin mode info <模式>` | 模式详情 |
| `/guildadmin mode start <模式>` | **强制开启赛季模式**（公告 + 可选自动入队） |
| `/guildadmin war list` | 进行中的工会战与队列 |
| `/guildadmin war <warId>` | 单场工会战详情 |
| `/guildadmin disband <工会>` | 强制解散 |
| `/guildadmin setlevel <工会> <等级>` | 设置等级 |
| `/guildadmin setpoints <工会> <点数>` | 设置点数 |
| `/guildadmin forceend [warId]` | 强制结束工会战 |
| `/guildadmin info <工会>` | 工会详情 |
| `/guildadmin cleanup [dry\|confirm]` | 清理僵尸工会（默认试运行） |

## 权限

| 权限 | 默认 | 说明 |
|---|---|---|
| `liuguild.use` | true | 基础功能（`/guild` 全部子命令） |
| `liuguild.create` | true | 创建工会（命令与 GUI 均校验） |
| `liuguild.create.free` | op | 免创建费 |
| `liuguild.war` | true | 参与 / 排队 / 进入工会战（仍需会长或官员身份） |
| `liuguild.warehouse` | true | 打开工会共享仓库 |
| `liuguild.title.manage` | op | 管理工会头衔（创建 / 删除） |
| `liuguild.shop.manage` | op | 管理工会商店（上架 / 改价 / 下架） |
| `liuguild.admin` | op | 管理总权限 |
| `liuguild.admin.reload` / `.status` / `.import` / `.arena` / `.kit` / `.mode` / `.war` / `.disband` / `.setlevel` / `.setpoints` / `.setbank` / `.forceend` / `.cleanup` / `.verify` / `.guilds` / `.player` / `.broadcast` / `.gui` | op | 各级管理子权限（默认由 `liuguild.admin` 控制） |

> 指令均带 Tab 补全：子命令、工会名、在线玩家、模式、场地、套装、坐标点均可补全。

## 文本格式

所有消息、GUI 名称与 Lore、工会战模式名与描述、以及玩家填写的工会公告/简介同时支持：

- MiniMessage：`<red>`、`<gradient:#3AA0FF:#8AD4FF>`、`<bold>` 等；
- 旧版颜色代码：`&a` / `§a`、`&l` 等；
- RGB：`&#FF0000` / `§#FF0000`。

消息占位符（`{player}`、`{guild}` 等）默认按纯文本插入，玩家昵称/工会名不会注入颜色、点击或悬浮事件。玩家填写的公告/简介允许颜色与装饰，但其中的点击/悬浮/插入事件会被自动剥离，且长度上限为 255 字符（含颜色代码）。
