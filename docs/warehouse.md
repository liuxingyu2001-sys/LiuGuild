# 工会空间（仓库）

独立配置为 `warehouse.yml`，货币枚举：`FUNDS` 工会公共资金、`BANK` 工会银行金币、`MONEY` 操作者 Vault 金币、`PLAYER_POINTS` 操作者 PlayerPoints 点券（已核对 3.3.5 API）。金额必须为非负整数。

`access.permanent.enabled` 启用后，显式 `/guild warehouse rent 0` 可购买永久使用权；缺少参数或非数字参数拒绝购买。容量等级与使用权分别购买，`levels.<等级>.duration-days: 0` 表示永久容量，正数为临时容量天数。临时容量过期退回永久等级；删除高等级配置后按不高于已购买等级的最高剩余配置计算容量，已有物品不会因缩容而直接删除。

`expiry.allow-withdraw` 控制使用权到期后能否只取不存，`expiry.retain-days` 为 `-1` 永久保留、`0` 到期清空、正数到期后保留天数。`disband.retain-days` 同样支持 `-1/0/正数`；解散保留仅供管理员恢复，不能由同名新工会访问。清理会在异步检查时执行，并非精确到毫秒。修改为较短保留期可能清理已过期物品，务必先备份数据库。

`manage-rank` 只接受 `MEMBER`、`OFFICER`、`LEADER`（不区分大小写）；错误职位拒绝加载，避免意外放宽权限。打开、管理和购买入口均检查 `liuguild.warehouse` 及工会成员身份；管理员可访问其他工会管理入口，但仍需仓库权限。异步打开时还会重新核对当前工会身份。

损坏的 `warehouse.yml` 拒绝加载并保留原文件，不使用默认收费或删除规则替换。首次升级导入旧 `guild.yml` 仓库设置；多服应同时升级并统一空间规则。

空间配置重载会关闭已打开的仓库及管理菜单，重新打开后使用新规则；重载前发起的旧加载结果会被拒绝。模块禁用时注销仓库监听器，重复启用不会重复注册。全局工会清理与配置监视任务在配置重载后按新配置重建。

**当前风险边界：**外部经济扣款与插件数据库不能组成同一事务，崩溃期间仍可能需要人工退款；MySQL、多服及 PlayerPoints 实服验证尚未完成，暂不视为生产就绪。
（跨服共享）

工会成员共用的物品存储。**物品存在共享数据库里，所有子服都能访问**；同一时刻**只允许一个成员打开**，避免并发写入互相覆盖导致丢物/复制。

> 仓库的**使用时间**与**容量升级**均消耗**工会资金（点数，`points`）**，与工会银行（Vault 金币）无关。

## 指令 / 入口

| 方式 | 说明 |
|---|---|
| `/guild warehouse` | 打开工会仓库；未开通/已过期时改为打开管理菜单（别名 `/guild wh`、`/guild cangku`） |
| `/guild warehouse info` | 打开仓库管理菜单（查看状态、购买时长、升级） |
| `/guild warehouse rent <天数>` | 用工会资金购买仓库使用时间 |
| `/guild warehouse upgrade` | 用工会资金升级仓库容量 |
| 主菜单「工会仓库」 | `/guild gui` → 点击「工会仓库」（同样会在未开通时改为管理菜单） |

权限：`liuguild.warehouse`（默认所有人）。购买时长 / 升级需要职位达到 `guild.warehouse.manage-rank`（默认 `OFFICER`），或拥有 `liuguild.admin`。

## 使用时间（租赁）

- 开启 `require-rent: true`（默认）时，**必须先在有效期内才能打开仓库**；过期后界面仍保留物品，但无法打开，需先续费。
- 续费按「工会资金 × 天数」计费，续期从「当前到期时间与当前时间的较大值」往后顺延，因此**在有效期内续费不会浪费剩余时间**。
- `max-days` 限制累计可持有的天数（0 = 不限）。
- 关闭 `require-rent` 则恢复旧行为：**永久可用，不消耗工会资金**。

## 容量升级

- 仓库槽位不再随工会等级增长，而是由**已购买的仓库等级**决定：`min(max-slots, base-slots + 仓库等级 × slots-per-level)`。
- 升级花费按 `base-cost × cost-growth^当前等级` 递增，最多升级 `max-level` 次（0 = 不可升级）。
- 升级只增不减；已购买等级与使用时间都随工会行持久化，跨服一致。

## 为什么需要跨服锁

如果两个玩家（哪怕在不同子服）同时打开同一个工会仓库：

1. 各自把整份内容读进内存；
2. 各自修改；
3. 各自写回 → **后写的完全覆盖先写的**，前面那位的操作凭空消失（或复制）。

所以打开前必须抢一把**跨服独占锁**。

### 锁的实现

| 场景 | 机制 |
|---|---|
| Redis 可用（跨服） | `SET lock:warehouse:<guildId> <token> NX EX 60` —— 原子抢占，抢不到就是有人正在用 |
| Redis 不可用（单服） | 回退本地内存锁，语义等价（只保证本服独占） |

细节：

- **持有者令牌**：`<serverId>:<playerUuid>:<random>`。释放/续期时先比对令牌，**绝不误删别人刚抢到的锁**。
- **TTL + 心跳**：锁 60 秒过期，每 20 秒续期一次。持有方崩溃/断网后最多 60 秒自动解锁，不会永久死锁。
- **先存库、再放锁**：关闭仓库时先把内容事务写库，**成功后才释放锁**。若先放锁，下一个玩家可能在写入落库前读到旧数据 → 物品回滚。
- 如果锁已被别人拿走（心跳失败），本地会话会被丢弃，下次打开重新抢占。

## 物品序列化

物品用 Paper 自带的 `ItemStack.serializeAsBytes()` 编码，再 Base64 存入 `item_data` 列（TEXT，MySQL/SQLite 通用）：

- 完整保留 NBT / 1.20.5+ 数据组件：自定义名、lore、附魔、耐久、CraftEngine 物品、玩家头颅皮肤等；
- 不依赖任何第三方库；
- 反序列化有防御：**空/损坏/超大数据一律按空槽处理，绝不抛异常、绝不产出乱码物品**（后者等于刷物品漏洞）；
- 单件编码上限 64KB，超过直接拒绝。

## 保存时机

| 时机 | 行为 |
|---|---|
| 关闭界面 | 异步写库 → 成功后释放锁 |
| 玩家掉线 | 同上 |
| 被踢出工会 / 退会 | 强制关闭并保存（`kick`） |
| 工会解散 | 关闭所有会话、释放锁、清空仓库数据 |
| 关服 / 插件卸载 | 同步写库 + 释放全部锁 |

## 槽位数量

`base-slots` 为基础槽位，`slots-per-level` 为每次升级增加的槽位，`max-slots` 为上限：

```yaml
guild:
  warehouse:
    enabled: true
    require-rent: true   # 必须先购买使用时间
    base-slots: 27       # 基础槽位（非 9 的倍数会自动向上取整）
    slots-per-level: 9   # 每次升级增加
    max-slots: 54        # 上限
    manage-rank: OFFICER # 购买时长 / 升级所需职位
    rent:
      price-per-day: 200 # 每 24 小时消耗的工会资金
      options: [1, 7, 30] # 管理菜单里的购买选项
      max-days: 90       # 累计最多持有天数（0 = 不限）
    upgrade:
      max-level: 3       # 最多升级次数（0 = 不可升级）
      base-cost: 1000    # 0 → 1 升级消耗
      cost-growth: 2.0   # 每级成本倍率
```

计算：`min(max-slots, base-slots + 仓库等级 × slots-per-level)`，**结果始终是 9 的倍数且落在 9~54**（Bukkit 只接受合法容器尺寸，配错会导致创建界面抛异常）。

> 降级/配置调小后若槽位缩小，多余槽位的物品**仍保留在数据库里**，不会因为界面变小而被删除；升级回去即可再次看到。

## 数据表

```sql
liuguild_warehouse (
  guild_id  VARCHAR(36),
  slot      INT,
  item_data TEXT,          -- Base64(serializeAsBytes)
  updated_at BIGINT,
  PRIMARY KEY (guild_id, slot)
)

-- 仓库状态随工会行一起存储（guilds 表）：
--   warehouse_level       INT     已购买升级等级
--   warehouse_expires_at  BIGINT  使用到期时间（epoch 毫秒，0 = 未开通）
```

## 并发安全

- **扣费与续期**：`UPDATE guilds SET points=points-cost, warehouse_expires_at=(CASE WHEN warehouse_expires_at>now THEN warehouse_expires_at ELSE now END)+duration WHERE guild_id=? AND points>=cost`，一条语句内完成，多台子服同时续费不会丢时长或重复扣费。
- **升级**：`UPDATE guilds SET points=points-cost, warehouse_level=warehouse_level+1 WHERE guild_id=? AND points>=cost AND warehouse_level=?`，以期望等级作为乐观锁，避免付一次钱升两级。
- **工会点数（资金）本身只经数据库原子 UPDATE 写入**，不再随整行保存写回，防止仓库扣费被并发的工会保存覆盖。
- **等级**：按点数重算出的等级以 `UPDATE guilds SET level=? WHERE guild_id=? AND level<?` 落库（只增不减），两台子服并发加点时较旧的一次不会把已升上去的等级写回退。

## 相关

- [configuration.md](configuration.md) —— 配置总览
- [shared-config.md](shared-config.md) —— 多服共享机制
