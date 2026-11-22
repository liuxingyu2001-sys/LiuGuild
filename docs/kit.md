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

`war-modes.yml` 的 `fair-health: true` 会把参赛者的**最大生命值在战斗中统一压到
`fair-base-health`**（默认 `20`，即原版满血），离场后自动解除：

- 入场/复活回满用的是公平值，而不是“玩家当前最大生命”——避免上一场胜方拿到带
  “生命上限 +20”的胜利称号后，下一场比别人多 20 血；
- 抵消范围包括蟠桃血量、胜利称号的属性类 buff（PlayerTitle / LiuTitle 的
  `max_health`、AttributePlus / MythicLib / AttributeSystem / SX-Attribute）、
  其它插件对 `max_health` 的加成；
- 实现方式是不动玩家基础值、也不删别人的 modifier，只追加一个 **transient** 补偿
  modifier（不写玩家存档），战斗期间每秒重算一次，第三方插件把加成加回来也会被压回；
- `fair-base-health` 可按模式单独覆盖（写在该模式段里），非法值钳到 `1..1024`；
- 如果 `kit.yml` 的 `effects` 里配了 `HEALTH_BOOST` 这类会改变最大生命的药水效果，
  它也会被计入公平值（所有人一样），需要更大血量请直接上调该模式的 `fair-base-health`；
- LuckyPeaches 的蟠桃屏蔽与死亡扣罚豁免仍按原逻辑保留，详见 [hooks.md](hooks.md)。

## 赛后还原

无论是否发放统一装备，参赛者入战时都会记录快照（Sparrow Sync 优先，缺失时本地内存），
退出/结束时完整还原玩家原有背包与状态，不会造成物品丢失。
公平血量补偿在按快照还原血量**之前**解除，避免真实血量被公平值上限截断。
