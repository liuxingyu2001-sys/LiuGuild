# 工会系统邮件

用于把**玩家不在线时**错过的重要信息保存下来，登录后提示并可在游戏内查看；
会长（及获授权的官员）也可以手动向本会全员**群发工会邮件**（含离线成员）。

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
| `guild.broadcast` | 会长/官员用 `/guild mail send` 或邮件菜单「群发邮件」手动群发 | 由 `guild.yml` 的 `mail.ttl-days` 决定（默认 30 天） |
| `system` | 预留：系统公告/运营通知 | 30 天 |

`type` 仅用于业务标注；LiuChat 侧统一保存为标题/正文。

## 玩家命令

| 命令 | 说明 |
|---|---|
| `/guild mail` | 查看最近 10 封邮件及未读数 |
| `/guild mail read <编号>` | 查看指定邮件正文并标记已读（支持 id 前缀） |
| `/guild mail clear` | 全部标记为已读 |
| `/guild mail send <标题> <正文>` | 向本会全员群发工会邮件（会长/官员，见下） |

登录时如果存在未读的系统邮件，会提示：
> [工会邮件] 你有 N 封未读邮件，[查看邮件]（可点击）。

跨服切换子服时也触发登录事件，但插件会通过 Redis 识别“刚离开其他子服”的切服标记，
不会每切一次服就重复弹出同一封未读提醒；真正的重新登录仍会正常提示。

> 注意：这里只统计「工会系统」发出的邮件；LiuChat 个人邮件仍通过 `/mail` 查看。

## 群发（会长 / 官员）

两个入口等价：

- 命令：`/guild mail send <标题> <正文>`（标题取第一个空格前的词，正文可包含空格；标题需要空格时用 GUI）；
- 邮件 GUI：主菜单 →「工会邮件」→ 底栏「群发邮件」，依次输入标题、正文。
  输入优先使用 Sparrow 铁砧界面（配置 `gui.anvil-input`），缺失时回退聊天栏；输入 `cancel` 取消。

权限与限制：

- **会长始终可以**；官员需要 `guild.yml` 的 `officer.send-mail: true`（默认开），
  且拥有权限节点 `liuguild.mail.send`（默认 `true`，可单独撤销）。
- 开关：`guild.mail.enabled`（默认开）；关闭后仅保留工会战、入会申请等系统自动邮件。
- 冷却：同一工会两次群发至少间隔 `guild.mail.cooldown-seconds`（默认 60 秒），按工会计算，防刷屏。
- 长度：标题 `guild.mail.max-title-length`（默认 32）、正文 `guild.mail.max-body-length`（默认 256），
  含 MiniMessage 标签；实际存储层仍限制标题 128 / 正文 512 字符。
- 标题按**纯文本**保存（清洗颜色/格式标签，避免收件箱占位符转义后露出标签）；
  正文允许 MiniMessage/legacy 颜色，但会清除 `click` / `hover` / `insert` 等交互事件再入库。
- 每封邮件标题统一加 `【工会邮件】` 前缀，正文首部记录发送者名字，例如
  `张三：今晚 8 点集合，记得提前上线`。
- 收件人为**当前全部工会成员**（含离线），每人一封；无成员时不会发送。
- 发送成功会写一条审计：`mail.broadcast`（包含标题与收件人数）。

群发同样经由 `MailService.sendMany` 投递：LiuChat 可用时逐封交给 `MailApi.send`，
失败的单封回退内置表；LiuChat 缺失时走内置 `guild_mail` 的异步批量 INSERT。
邮件不参与业务事务，发送失败不会回滚任何工会状态。

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
