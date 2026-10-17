# Webhook 推送（Discord / 通用 JSON）

把工会战、赛季、工会升级等关键事件推送到 Discord 或自建 webhook。默认关闭。

## 配置（`server.yml`，每服独立）

Webhook 会主动连接外部服务，**不能放在共享 `config.yml`**；否则多台子服会重复推送。请在需要推送的那台子服的 `server.yml` 配置：

```yaml
# server.yml
webhook:
  enabled: false
  url: "https://discord.com/api/webhooks/xxx/yyy"
  # discord = {"content":"...","username":"..."}
  # generic = {"content":"..."}（自建服务/QQ 机器人网关可自行适配）
  format: discord
  username: "LiuGuild"
  timeout-seconds: 5
  retries: 2
  events:
    war-result: true
    season-result: true
    guild-level-up: true
    guild-create: false
    guild-disband: true
    application: true
```

共享 `config.yml` 里的 `hooks.webhook` 已被忽略；旧配置请迁移到 `server.yml`。

## 推送内容

- 工会战结束：双方名称/比分、胜负、承办服、MVP（击杀/死亡/存活）
- 赛季混战结束：模式、承办服、前几名
- 工会升级、新建、解散
- 新入会申请（申请人、工会、留言）

内容只包含公开战报/工会信息，不包含数据库密码、Redis 凭据或完整 webhook URL。

## 可靠性

- 使用 Java `HttpClient` 异步发送，不阻塞主线程。
- 非 2xx 或超时按 `retries` 重试，退避 1s/2s/4s。
- 最终失败只写 warning，不影响比赛结算、奖励、邮件等主流程。
- 单条内容截断到 1900 字符，避免超过 Discord 2000 限制。

## 安全

- `url` 含 token，不要提交到公开仓库；只放在本服 `server.yml`，不要放共享配置。
- 如果 webhook 服务支持，限制来源 IP 或使用代理。
- 日志只提示“Webhook 推送失败/URL 无效”，不会打印完整 URL。

## 测试

`WebhookPayloadTest` 覆盖 JSON 转义与 discord/generic payload 生成；真实 HTTP 推送需要在实际网络环境验证。
