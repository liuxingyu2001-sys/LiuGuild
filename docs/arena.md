# 竞技场

`arena.yml` 中每个竞技场**自描述**：直接写「承办服 + 世界 + 坐标」，因此自定义程度最高，
既不依赖 liudungeon 也能用固定世界，需要动态开世界时再填 `template`。

```yaml
pregenerate: true            # 使用动态模板时是否预热区块
create-timeout-seconds: 60   # 动态世界创建超时

arenas:
  main:
    server: ""               # 承办服 server-id；留空 = 全局 / 发起匹配的服务器
    world: ""                # 固定世界名；留空 = 主世界
    template: ""             # 可选 liudungeon 模板；填写且存在时优先动态开世界
    radius: 48               # 预热半径（方块），0=不预热
    spawn-a:         { x: 0.5,  y: 70.0, z: 0.5,  yaw: 0.0,   pitch: 0.0 }
    spawn-b:         { x: 20.5, y: 70.0, z: 0.5,  yaw: 180.0, pitch: 0.0 }
    spawn-spectator: { x: 10.5, y: 80.0, z: 10.5, yaw: 0.0,   pitch: 30.0 }
```

## 字段说明

| 字段 | 说明 |
|---|---|
| `server` | 承办该场工会战的服务器 `server-id`。跨服时玩家会被传送到该服。 |
| `world` | 固定世界名（如 `world` / `arena_world`），留空用主世界。 |
| `template` | 可选，liudungeon 动态世界模板。填写且模板存在时动态创建独立竞技场世界。 |
| `radius` | 动态开世界时的区块预热半径。 |
| `spawn-a` / `spawn-b` | 双方出生坐标与朝向。 |
| `spawn-spectator` | 旁观出生坐标。 |

## 现场设置坐标

管理员站到目标位置后：

```
/guildadmin arena setspawn main a      # 设为 A 方出生点
/guildadmin arena setspawn main b      # 设为 B 方出生点
/guildadmin arena setspawn main spec   # 设为旁观点
```

会立即写入 `arena.yml` 并热重载。

其它设置：

```
/guildadmin arena setworld main world_arena
/guildadmin arena setserver main arena-1
/guildadmin arena settemplate main guild_arena
/guildadmin arena create pvp           # 新建固定坐标场地
/guildadmin arena info main            # 查看详情
```

## 引用方式

在 `war-modes.yml` 的模式里指定 `arena: <id>` 即可。多个模式可共用同一场地，也可各自独立。
