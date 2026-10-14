# 工会商店

工会把自己的物品明码标价挂出来卖，**任何玩家**（含非本会成员）都能买，货款进工会资金（按配置抽税）。

工会还能给每件商品设一个**收购价**：本会成员可以把这种物品**卖给工会**，工会出资、物品入库存 —— 和 LiuShop 的双向价格模型一致。

## 指令

| 指令 | 权限 | 说明 |
|---|---|---|
| `/guild shop` | 所有人 | 打开本会商店（GUI） |
| `/guild shop all` | 所有人 | 浏览全服工会商店 |
| `/guild shop list` | 所有人 | 文字列出本会商品 |
| `/guild shop add <售价> [收购价]` | 管理 | 上架**手持**物品（整堆） |
| `/guild shop remove <编号前 8 位>` | 管理 | 下架，剩余库存退回自己 |

别名：`/guild shangdian`、`/guild store`。

**管理权限**：`liuguild.shop.manage` 权限节点，**或**职位达到 `guild.shop.manage-rank`（默认 `OFFICER`）。

## GUI 操作

主菜单 →「工会商店」，或 `/guild shop`。

| 操作 | 效果 |
|---|---|
| 左键 | 购买（铁砧输入数量） |
| 右键 | 向工会供货（仅当该商品设了收购价） |
| Shift + 左键 | 改价（售价 → 收购价，两步输入） |
| Shift + 右键 | 下架（剩余库存退回） |

底排：「上架手持物品」、「全服商店」、「返回」。

## 上架流程

1. 把要卖的物品**拿在主手**。
2. 点「上架手持物品」（或 `/guild shop add 1000 500`）。
3. 输入**售价**（玩家买走一件多少钱）。
4. 输入**收购价**（`0` = 工会不收）。

上架会把主手**整堆**移入商店库存；如果序列化失败，物品会原样退回（不会凭空消失）。

### 参考价（LiuShop 集成）

如果服务器装了 LiuShop，上架前会提示一个参考价：

- 优先取 LiuShop 的「玩家购买价」；
- 没有则取「玩家出售价 × `sell-markup`」（默认 2.0，工会总得比收购价卖得高）；
- 都没有就不提示。

同时，**LiuShop 禁售的物品禁止上架** —— 否则黑名单形同虚设。

> 集成走反射，LiuShop 缺失/版本变化时全部安全降级，不影响商店本身可用。

## 资金与物品安全

买卖最怕「扣了钱没给货」。这里的规则是：**先把能失败的事做完，再执行不可逆的那一步。**

### 购买

1. 背包空间预检 —— 放不下直接拒绝，避免「给了又要收回」的脏流程。
2. 扣钱（Vault，主线程同步）。失败 → 直接返回，**无任何副作用**。
3. **数据库原子扣库存**。失败（被别人抢走最后一件）→ **全额退钱**。
4. 发货（此时必定成功）。
5. 货款入工会资金（扣税）。入账失败 → **全额退给买家**并记录 SEVERE。

### 供货（成员卖给工会）

1. 验货（手里够不够）+ 验钱（工会资金够不够）+ 验经济插件可用。
2. 扣货。
3. 异步扣工会资金。失败 → **货原样退回**，交易取消。
4. 给成员打钱。失败 → **货退回 + 工会资金退回**（整体回滚）。
5. 加库存、跨服广播。

### 并发

库存扣减是数据库层的原子操作：

```sql
UPDATE guild_shop SET stock = stock - ? WHERE listing_id = ? AND stock >= ?
```

影响行数为 0 = 库存不够。**这是唯一权威判断** —— 如果写成「先查库存、内存减 1、再写回」，两台子服的并发抢购会各自读到同一份旧值，结果是库存只减 1 但货发出去 2 份（刷物品）。

### 溢出

价格 × 数量用 `Math.multiplyExact` 包了一层，溢出就封顶到 `Long.MAX_VALUE`（玩家肯定买不起），**绝不回绕成负数** —— 负数的 total 意味着购买反而给玩家发钱。

## 配置

```yaml
guild:
  shop:
    enabled: true
    max-listings: 45           # 每工会最多几条
    max-stack-per-listing: 2304
    max-per-purchase: 2304     # 单次购买上限
    max-supply: 2304           # 单次供货上限
    tax-percent: 5.0           # 交易税，从货款里扣
    manage-rank: OFFICER
    sell-markup: 2.0           # 参考价回退倍率
```

## 数据表

```sql
liuguild_guild_shop (
  listing_id VARCHAR(36) PRIMARY KEY,
  guild_id   VARCHAR(36),
  item_data  TEXT,          -- Base64(serializeAsBytes)，固定单件
  stock      INT,           -- 可售数量（原子增减）
  price      BIGINT,        -- 玩家从工会买：单价
  buy_price  BIGINT,        -- 工会向成员收：单价（0 = 不收购）
  seller_uuid VARCHAR(36),
  seller_name VARCHAR(64),
  created_at BIGINT
)
```

物品用与工会仓库**同一套**序列化（`ItemCodec`），完整保留附魔 / 自定义名 / CraftEngine 物品。

## 一致性

- 工会**解散**：上架全部清空。
- 商品**序列化失败**：拒绝上架并退回物品。
- 跨服：变更通过 `GUILD_SHOP` 消息广播，其他子服按工会重载。

## 相关

- [warehouse.md](warehouse.md) —— 同一套物品序列化
- [configuration.md](configuration.md)
- [hooks.md](hooks.md) —— LiuShop 集成
