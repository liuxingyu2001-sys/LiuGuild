# 统一装备（Kit）

`kit.yml` 支持**多套装备**，不同工会战模式通过 `war-modes.yml` 的 `kit: <key>` 选用；模式未写 `kit` 时继承 `war.defaults.kit`，两者都为空才使用 `default-kit`。
每套装备可以手工列举，也可以**直接保存玩家当前身上穿的装备**（序列化为 Base64 快照）。

```yaml
enabled: true          # 全局总开关
default-kit: default

kits:
  default:
    enabled: true
    clear-inventory: true
    armor:
      helmet: DIAMOND_HELMET
      chestplate: DIAMOND_CHESTPLATE
      leggings: DIAMOND_LEGGINGS
      boots: DIAMOND_BOOTS
    items:
      - DIAMOND_SWORD
      - BOW
      - ARROW:32
      - GOLDEN_APPLE:8
    effects:
      - SPEED:0

  season:
    enabled: true
    clear-inventory: true
    snapshot: "<Base64 玩家装备快照>"   # 由 /guildadmin kit save season 生成
    effects:
      - SPEED:0
      - REGENERATION:0
```

## 物品语法

| 写法 | 含义 |
|---|---|
| `DIAMOND_SWORD` | 原生物品 |
| `DIAMOND_SWORD:1` | 原生物品 + 数量 |
| `CE:namespace:id` | CraftEngine 自定义物品 |
| `CE:namespace:id:16` | CraftEngine 自定义物品 + 数量 |

## 效果语法

`TYPE:amplifier`（amplifier 从 0 开始），如 `SPEED:0`、`REGENERATION:1`。

## 保存玩家当前装备（快照）

管理员穿好装备后执行：

```
/guildadmin kit save season
```

插件会把**背包（36 格）+ 护甲（4 格）+ 副手（1 格）**序列化为 Base64 写入
`kits.season.snapshot`，写入后自动热重载。该套装既可用于发放给参赛者。

- 快照非空时**优先于** `armor` / `items`。
- 序列化采用 Bukkit 对象流（`BukkitObjectOutputStream`），可完整保留 NBT / 附魔 / CraftEngine 自定义数据。
- 管理指令：`kit list` / `kit info <key>` / `kit delete <key>`。

## 公平血量

`war-modes.yml` 的 `fair-health: true` 会在入战时屏蔽 LuckyPeaches 的蟠桃血量加成，
出战后恢复，保证血量一致。详见 [hooks.md](hooks.md)。

## 赛后还原

无论是否发放统一装备，参赛者入战时都会记录快照（Sparrow Sync 优先，缺失时本地内存），
退出/结束时完整还原玩家原有背包与状态，不会造成物品丢失。
