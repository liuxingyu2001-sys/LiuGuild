# 工会入会申请 / 招募板

开放加入的工会仍可直接加入；**邀请制工会**通过申请 + 官员审批，避免只能等人邀请。

## 玩家

- `/guild recruit`：打开工会列表（招募板）
  - 左键开放工会：直接加入
  - 左键邀请制工会：输入申请留言后提交
  - 右键：查看工会详情
- `/guild apply <工会> [留言]`：直接提交申请；不写留言会进入聊天输入
- `/guild apps cancel`：取消自己的待审批申请

同一玩家最多保留 `guild.application.max-pending` 条待审批申请（默认 3），避免广撒网。

## 官员

- `/guild apps`：列出本会待审批申请
- `/guild apps accept <编号|id前缀>`：批准
- `/guild apps deny <编号|id前缀>`：拒绝

官员/会长会收到在线提示；不在线的官员会收到 `application.new` 邮件。
批准要求申请人在线且工会有空位；拒绝会发送 `application.result` 邮件给申请人。

## 配置（`guild.yml`）

```yaml
guild:
  application:
    enabled: true
    max-pending: 3
    message-max-length: 100
```

## 数据

`guild_applications`（自动建表）：

| 列 | 说明 |
|---|---|
| `id` | 申请 UUID |
| `guild_id` / `player_uuid` | 目标工会 / 申请人 |
| `player_name` / `message` | 名字快照 / 留言 |
| `state` | PENDING / APPROVED / REJECTED / CANCELLED |
| `created_at` / `updated_at` | 创建 / 状态变更 |

- 待审批按 `(guild_id, state)`、`(player_uuid, state)` 查询。
- 加入其它工会、退会、工会解散时会取消相关待处理申请。
- 非 PENDING 的申请保留 90 天后清理。

## 审计与事件

- 审计动作：`application.submit` / `application.approve` / `application.reject`。
- API 事件：`GuildApplicationEvent`（`SUBMIT` / `APPROVE` / `REJECT`，`actor()` 为操作者）。
