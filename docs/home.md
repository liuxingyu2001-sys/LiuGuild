# 工会驻地（HuskHomes）

工会驻地让成员快速回到工会据点。坐标由 LiuGuild 自己保存（数据库表 `guild_homes`），传送交给 **HuskHomes** 执行，因此天然支持计时传送与跨服传送。

> HuskHomes 是**可选依赖**：未安装时相关命令提示 `home-unavailable`，其余工会功能不受影响。

## 指令

| 指令 | 权限 | 说明 |
|---|---|---|
| `/guild home [槽位]` | 工会成员 | 传送到指定驻地（默认槽位 1） |
| `/guild home set [槽位]` | 会长 / 副会长 | 把当前位置设为驻地 |
| `/guild home del [槽位]` | 会长 / 副会长 | 删除驻地 |
| `/guild home list` | 工会成员 | 查看各槽位是否已设置 |

别名：`/guild hq`。

## GUI

主菜单「工会驻地」按钮（`home`）打开专有的驻地菜单，按工会等级列出全部槽位：

- 已设置槽位：**左键传送**、**右键重设**为当前位置、**Shift+右键删除**（重设 / 删除需会长或官员权限）。
- 未设置槽位：**左键把当前位置设为该驻地**。
- 菜单读取数据库放在异步线程，渲染回主线程；设位 / 删除后延迟一秒刷新，避免看到旧状态。
- 未安装 HuskHomes 或 `home.enabled: false` 时，主菜单按钮自动隐藏。

## 配置（guild.yml）

```yaml
guild:
  home:
    enabled: true          # 总开关
    max-slots: 3           # 最多可用驻地数量
    levels-per-slot: 5     # 每多少级多开放 1 个驻地
    cooldown-seconds: 30   # 传送冷却
    warmup-seconds: 3      # >0 使用 HuskHomes 计时传送；0 立即传送
    deny-in-war: true      # 工会战期间禁用
```

- 可用驻地数量 = `min(max-slots, 1 + (等级-1) / levels-per-slot)`，至少 1 个。
- 例：`max-slots: 3`、`levels-per-slot: 5` → 1 级 1 个、6 级 2 个、11 级 3 个。

## 行为说明

- 坐标以 JSON 保存，包含服务器名、世界名与世界 UUID，因此**跨服传送**依然准确。
- 传送冷却按玩家记录在内存中；重载插件会清空冷却（不影响数据）。
- 会长/副会长设置驻地会即时落库；删除驻地与解散工会都会清理对应数据。
- 工会战期间是否禁用由 `deny-in-war` 控制，避免用驻地逃跑。

## 与 HuskHomes 的关系

- 只调用其公开 API（`HuskHomesAPI#adaptUser`、`#teleportBuilder`、`Position#at`），不修改 HuskHomes 本体。
- 传送时长由 HuskHomes 自身设置决定；`warmup-seconds > 0` 表示走其“计时传送”流程。
- 未安装 HuskHomes 时，LiuGuild 不会创建任何 HuskHomes 相关对象，也不会报错。
