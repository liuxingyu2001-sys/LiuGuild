# 工会系统邮件

用于把**玩家不在线时**错过的重要信息保存下来，登录后提示并可在游戏内查看。

## 投递方式

工会系统邮件优先通过 **LiuChat 的公开 `MailApi`** 投递：

- 收件箱、已读状态、过期时间由 LiuChat 统一管理；
- 使用 LiuChat 的 SQLite/MySQL 存储，MySQL 时天然跨子服；
- 发件人显示为「工会系统」；
- 工会邮件界面只显示该发件人的邮件，不会混入玩家私信；
- 每封系统邮件使用独立发件人 UUID，避开 LiuChat「同一发件人发送冷却」对批量战报的影响。

LiuChat 未安装、未启用或 API 版本不匹配时，自动回退到本插件原有的 `guild_mail` 表，保证通知不阻断业务。

## 当前类型

| type | 触发 | 保留 |
|---|---|---|
| `war.result` | 工会战结算后，给每名参赛者发送胜负、比分、MVP | 14 天 |
| `application.new` | 邀请制工会收到新申请，发给离线官员/会长 | 14 天 |
| `application.result` | 申请被拒绝时通知申请人 | 30 天 |
| `system` | 预留：系统公告/运营通知 | 30 天 |

`type` 仅用于业务标注；LiuChat 侧统一保存为标题/正文。

## 玩家命令

| 命令 | 说明 |
|---|---|
| `/guild mail` | 查看最近 10 封邮件及未读数 |
| `/guild mail read <编号>` | 查看指定邮件正文并标记已读（支持 id 前缀） |
| `/guild mail clear` | 全部标记为已读 |

登录时如果存在未读的系统邮件，会提示：
> [工会邮件] 你有 N 封未读邮件，[查看邮件]（可点击）。

跨服切换子服时也触发登录事件，但插件会通过 Redis 识别“刚离开其他子服”的切服标记，
不会每切一次服就重复弹出同一封未读提醒；真正的重新登录仍会正常提示。

> 注意：这里只统计「工会系统」发出的邮件；LiuChat 个人邮件仍通过 `/mail` 查看。

## 数据与清理

### LiuChat 主路径

- `MailApi.send(MailDraft)`：异步投递，不阻塞主线程；
- `MailApi.inbox(UUID, page)`：读取后按发件人「工会系统」过滤；
- `MailApi.markRead(...)`：标记已读；
- LiuChat 自己负责过期邮件清理；
- 当前 API 不支持批量已读，`/guild mail clear` 会逐页标记系统邮件。

### 回退路径

表 `guild_mail`（自动建表）：

| 列 | 说明 |
|---|---|
| `id` | 邮件 UUID |
| `guild_id` | 关联工会（可空） |
| `recipient_uuid` | 收件人 |
| `type` / `title` / `body` | 类型、标题、正文 |
| `created_at` / `read_at` / `expires_at` | 创建/已读/过期时间 |

- 写入：异步批量 INSERT，失败只记日志；
- 查询：异步读取后回主线程显示；
- 清理：`GuildCleanup` 每轮清理过期邮件，以及已读超过 7 天的邮件。

标题上限 128 字符，正文上限 512 字符。

## 线程

- 发送：任意线程调用 `MailService`；LiuChat 路径不阻塞，回退路径内部 `plugin.async` 批量写库。
- 登录提示：通过 LiuChat `inbox` 异步读取，回主线程发送。
- 不参与业务事务；邮件失败不会回滚战报/奖励。

## 外部 API

LiuChat 侧接口：`com.liu.liuchat.api.MailApi`，通过插件实例 `getMailApi()` 获取。
完整定义见 LiuChat 仓库的 `docs/mail.md`。
