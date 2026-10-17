# QQ 官方机器人（WebSocket）

通过 QQ 开放平台官方机器人接入群聊/C2C/频道，支持：

- 获取 AppAccessToken（自动刷新）
- WebSocket 网关 Identify / Resume / 心跳 / 断线重连
- @机器人查询工会、工会战、资金排行
- 把战报/赛季/升级等事件推送到指定 channel/group/c2c

> 这是 **QQ 官方机器人 API**，不是 OneBot / 第三方 QQ 框架。需要先在 QQ 开放平台创建机器人并拿到 AppID / ClientSecret。

## 配置（`server.yml`，每服独立）

QQ 机器人会建立外部 WebSocket 连接，**不能放在共享 `config.yml`**；否则每台子服都会重复建连/重复回复。请在**只负责推送/接待的那台子服**的 `server.yml` 配置：

```yaml
# server.yml
qqbot:
  enable: true
  app-id: "你的AppID"
  app-secret: "你的AppSecret"
  sandbox: false
  connection:
    gateway: ""              # 留空自动请求 /gateway，失败回退内置地址
    reconnect-seconds: 5
    reconnect-max-seconds: 60
    timeout-seconds: 15
  intents: [group, c2c]      # group/c2c/guild/guild-private/direct/all
  intents-bits: 0
  command-prefix: "!"
  receive:
    enable: true
    channels: []             # 留空接收全部；填群/用户 openid 或频道 ID 做白名单
    log-targets: true        # 首次收到某会话时打印 target，方便复制
    max-length: 300
  send:
    enable: false            # 主动推送开关；指令回复不受影响
    targets: []              # group:<openid> / c2c:<openid> / channel:<id> / direct:<id>
    interval-ms: 1000        # 主动消息最小间隔
    max-length: 2000
    passive-reply: true      # @消息回复带 msg_id，走官方被动回复
```

### intents

| 名称 | 含义 | 位 |
|---|---|---|
| `group` | 群聊 @机器人 | 1<<25 |
| `c2c` | 私聊消息 | 1<<25 |
| `guild` | 公域频道 @机器人 | 1<<30 |
| `guild-private` | 私域频道 @机器人 | 1<<9 |
| `direct` | 频道私信 | 1<<12 |
| `all` | group + c2c + guild + direct | — |

`intents-bits` 可额外补位；默认 `[group, c2c]`。频道 @消息需要 `guild` 或 `guild-private`，按机器人类型选择。

### 兼容旧配置

旧的 `qqbot.enabled`、`qqbot.client-secret`、数字 `qqbot.intents`、`qqbot.push.type/id` 仍会读取并转换；新配置推荐上面的 `enable` / `app-secret` / `intents` 列表 / `send.targets`。

共享 `config.yml` 里的 `hooks.qqbot` 已被忽略；旧配置请迁移到 `server.yml`。

## 机器人指令

在群聊/C2C/频道中 @机器人：

| 指令 | 说明 |
|---|---|
| `!help` | 显示帮助 |
| `!guild <工会名>` | 工会等级、资金、银行、成员、战绩、会长 |
| `!war <工会名>` | 当前比赛比分/阶段/承办服，或排队状态 |
| `!top` | 资金排行前 5 |

回复通过官方 OpenAPI 发送：

- 频道：`POST /channels/{channel_id}/messages`
- 群聊：`POST /v2/groups/{group_openid}/messages`
- C2C：`POST /v2/users/{openid}/messages`

群聊/C2C 回复会带 `msg_id` 和 `msg_seq`。

## 事件推送

主动推送由 `qqbot.send.enable` 控制，事件范围复用同服 `server.yml` 中 `webhook.events` 的开关与 `WebhookHook` 生成的文本：

- 工会战结束
- 赛季混战结束
- 工会升级/新建/解散
- 新入会申请

即使同服 `webhook.enabled=false`，只要 `qqbot.send.enable=true` 且配置了 `targets`，事件仍会推送到 QQ；Discord webhook 是否发送由 `webhook` 自己控制。

> 主动推送（无 `msg_id`）受 QQ 官方主动消息频控/模板限制；群聊/C2C 建议优先使用被动回复，或确认机器人有主动消息权限。

## 连接与重连

- 启动时异步获取 token，再连接 `wss://api.sgroup.qq.com/websocket`（沙箱为 `sandbox.api.sgroup.qq.com`）
- 收到 Hello 后发送 Identify；断线后优先 Resume
- 心跳按服务端 `heartbeat_interval` 发送
- 连接失败、op7/op9 会按 `reconnect-seconds` 重连；token 过期自动刷新
- `/guildadmin doctor` 会检查缺 AppID/Secret、非法 gateway、send.targets 为空或类型错误等问题

## 线程

- WebSocket/HTTP 在独立线程/HttpClient 线程执行，不阻塞主线程
- 收到指令后通过 `plugin.sync` 回主线程查询工会数据
- 停止插件时先关闭 WebSocket 和调度器

## 限制

- 需要 QQ 开放平台机器人权限与对应 intents 审核
- 未在真实 QQ 机器人环境完成联调；协议按官方 WebSocket/OpenAPI 实现
- 频道 @消息需要 `intents: [guild]` 或 `[guild-private]`，且机器人有频道权限
- 主动推送与被动回复的频控、模板要求以 QQ 开放平台为准
