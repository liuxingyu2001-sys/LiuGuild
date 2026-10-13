# 开放 API

## 工会资产

`GuildAPI.funds(id)` / `addFunds(id, delta)` 读取和修改本插件工会资金（points 别名），与 Vault 无关。
`GuildAPI.bankBalance(id)` 返回银行金币整数余额；`changeBank(id, delta)` 返回 `CompletableFuture<Boolean>`，使用数据库原子增减，余额不足或溢出返回 false，数据库故障异常完成。不要在主线程 join；该接口只改银行余额，不移动玩家 Vault 钱包。旧 `addBank` 保留兼容。
所有工会成员都可存取银行整数金币；Vault 服务缺失时拒绝交易。


## 门面：`com.liu.liuguild.api.GuildAPI`

静态门面，任何插件可直接调用。数据以不可变视图（record）返回。

```java
if (GuildAPI.isReady()) {
    GuildAPI.GuildView g = GuildAPI.guildOf(player.getUniqueId());
    String name = g == null ? "无" : g.name();
}
```

| 方法 | 说明 |
|---|---|
| `isReady()` | 插件是否已就绪 |
| `guildById(String)` / `guildByName(String)` | 按 id / 名称查询 |
| `guildOf(UUID)` | 玩家所在工会 |
| `isInGuild(UUID)` | 是否已入会 |
| `sameGuild(UUID, UUID)` | 两名玩家是否同会 |
| `guildCount()` | 工会总数 |
| `top(String metric, int limit)` | 排行榜（`points`/`bank`/`level`/`wins`） |
| `members(String guildId)` | 成员视图列表 |
| `rankOf(UUID)` | 玩家职位 |
| `onlineMembers(String guildId)` | 在线成员数 |
| `addPoints(String guildId, long)` / `addBank(String, long)` | 增加点数 / 银行 |
| `sendGuildMessage(String guildId, Component)` | 向工会发消息 |
| `warOf(String guildId)` | 进行中的工会战视图 |
| `isInWar(UUID)` | 玩家是否在工会战中 |
| `plugin()` | 主插件实例（高级用途） |

`GuildView`：`id, name, tag, leaderName, icon, level, points, members, memberLimit, wins, losses, bank, open, notice`。

`MemberView`：`uuid, name, rank, contributed, kills, deaths, joinedAt`。

`WarView`：`id, guildA, guildB, nameA, nameB, state, scoreA, scoreB, winner, hostServer`。

## 事件：`com.liu.liuguild.api.event`

所有事件均在 Bukkit 主线程触发，可正常监听。

| 事件 | 关键字段 |
|---|---|
| `GuildCreateEvent` | `guild`, `player` |
| `GuildDisbandEvent` | `guild`, `cause`（PLAYER/ADMIN/INACTIVE） |
| `GuildJoinEvent` | `guild`, `player`, `viaInvite` |
| `GuildLeaveEvent` | `guild`, `player`, `cause`（QUIT/KICK/DISBAND） |
| `GuildLevelUpEvent` | `guild`, `oldLevel`, `newLevel` |
| `GuildBankEvent` | `guild`, `player`, `action`（DEPOSIT/WITHDRAW）, `amount`, `balance` |
| `GuildWarMatchEvent` | `war` |
| `GuildWarStartEvent` | `war` |
| `GuildKillEvent` | `war`, `killer`, `victim`, `points` |
| `GuildWarEndEvent` | `war`, `result`（A_WIN/B_WIN/DRAW/CANCELLED） |

`GuildEvent` 提供 `guild()` / `guildId()` / `guildName()`；`GuildWarEvent` 提供 `war()` / `warId()`。

```java
@EventHandler
public void onKill(GuildKillEvent e) {
    e.war();      // GuildWar
    e.killer();   // Player
    e.victim();
    e.points();
}
```

## 二次开发

- 功能模块实现 `com.liu.liuguild.module.GuildModule`，在 `LiuGuild` 启动时用 `modules().register(...)` 注册。
- `GuildModule` 提供 `id()` / `order()` / `enable()` / `disable()` / `reload()` / `onCrossServer(type, data)`。
- 跨服自定义消息：`plugin.bus().publish(type, CrossServerBus.map(...))`，在模块的 `onCrossServer` 中接收。
