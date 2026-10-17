# 工会审计日志

记录影响工会资产/身份的操作，方便管理层与管理员排查纠纷。审计**不参与业务事务**：
业务成功后才异步写入，写入失败只记警告，不会回滚银行/商店/仓库操作。

## 记录范围

| 动作 key | 触发 |
|---|---|
| `bank.deposit` / `bank.withdraw` | 工会银行存入 / 取出（金额为实际入账/取出） |
| `shop.buy` | 成员购买工会商店商品（金额为支付总额，detail 含税后净额） |
| `shop.list` / `shop.remove` | 上架 / 下架商品 |
| `warehouse.deposit` / `warehouse.withdraw` | 仓库存入 / 取出（数量为移动件数） |
| `admin.disband` | 管理员强制解散 |
| `admin.setlevel` / `admin.setpoints` / `admin.setbank` | 管理员改等级 / 资金 / 银行 |
| `guild.rename` | 会长/玩家改名（受冷却与费用） |
| `admin.rename` | 管理员改名（绕开冷却与费用） |

每条记录包含：工会 UUID、操作者 UUID/名称、动作、对象、金额/数量、补充说明、时间。
自动清理：`GuildCleanup` 每轮清理 **180 天**前的记录。

## 查看

- `/guild log [条数]`：工会官员/会长查看本会最近审计（默认 20，最多 50）。
- `/guildadmin audit <工会|all> [条数]`：管理员查看指定工会或全服审计。

## 权限

- `/guild log`：需要 `liuguild.use` + 本会 `OFFICER`/`LEADER`。
- `/guildadmin audit`：`liuguild.admin.audit`（或总权限 `liuguild.admin`）。

## 数据表

`guild_audit_log`（自动建表）：

| 列 | 说明 |
|---|---|
| `id` | 记录 UUID |
| `guild_id` | 工会 UUID |
| `actor_uuid` / `actor_name` | 操作者（系统操作为空） |
| `action` | 动作 key |
| `target` | 商品/成员/目标对象 |
| `amount` | 金额或数量 |
| `detail` | 补充说明 |
| `created_at` | 毫秒时间戳 |

索引：`(guild_id, created_at)`、`created_at`。

## 线程

- 写入：异步线程，单条 INSERT。
- 查询：异步读取后回主线程输出，命令不会阻塞服务器。
- 保留策略：180 天；需要更长历史请自行备份表。

## 管理员清理动作

`/guildadmin purge` 会记录：

- `admin.purge.points` 清空资金
- `admin.purge.bank` 清空银行
- `admin.purge.activity` 重置活跃度
- `admin.purge.warehouse` 清空仓库租期/等级
- `admin.purge.shop` 清空商店上架
- `admin.purge.stats` 清空胜负与成员战绩
