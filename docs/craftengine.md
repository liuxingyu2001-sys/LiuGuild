# CraftEngine 集成

LiuGuild 通过软依赖反射调用 CraftEngine，用于：

1. **工会图标**（`icons.yml`）
2. **统一装备中的自定义物品**（`kit.yml`）

缺失 CraftEngine 时自动回退 `fallback-material` 或原生物质，不影响启动。

## 物品 / 图标写法

| 写法 | 含义 |
|---|---|
| `DIAMOND_SWORD` | 原生物质 |
| `CE:namespace:id` | CraftEngine 自定义物品 |
| `CE:namespace:id:16` | CraftEngine 自定义物品 + 数量（仅 kit 的物品列表支持数量） |

## 图标定义示例 `icons.yml`

```yaml
icons:
  default:
    display: "默认"
    item: "DIAMOND"
    fallback-material: SHIELD
    permission: ""
  dragon:
    display: "巨龙徽记"
    item: "CE:liuguild:dragon_emblem"
    fallback-material: SHIELD
    permission: "liuguild.icon.dragon"
```

- `display`：菜单中显示名。
- `item`：材质或 CE 物品。
- `fallback-material`：CE 缺失时的替代材质。
- `permission`：使用该图标所需权限（空 = 无限制）。

## 统一装备示例 `kit.yml`

```yaml
kits:
  ce:
    items:
      - "CE:liuguild:war_sword"
      - "CE:liuguild:war_helmet"
      - "ARROW:64"
```

## 工会自定义图标（玩家导入/上传）

从旧版 PlayerGuild 导入的工会会保留其序列化 ItemStack 图标（含 CraftEngine 自定义数据），
GUI 会优先渲染该自定义图标；没有时才回退到 `icons.yml` 的 `icon`。

## 注意

- CraftEngine 的物品解析在渲染 / 发装备时进行，属主线程操作。
- `CE:` 前缀大小写不敏感。
- 数量仅对原生物质与 `CE:` 物品末尾的纯数字生效，避免误切 `CE:namespace:id`。
