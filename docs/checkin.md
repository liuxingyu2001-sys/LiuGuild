# 工会签到

工会成员每天可签到一次，领取工会资金（点数）与可选个人金币，**连续签到有额外奖励**。

## 指令 / 入口

| 方式 | 说明 |
|---|---|
| `/guild checkin` | 签到（别名 `/guild sign`、`/guild qiandao`） |
| 主菜单「每日签到」 | `/guild gui` → 点击「每日签到」 |

## 规则

- 以**本地自然日**（`Asia/Shanghai`）为准，一天一次；重复签到提示“已签到”。
- 连续签到天数：昨天签过 → +1；断签 → 重置为 1。
- 奖励 = `points` + `min(max-streak-bonus, streak-bonus × (连续天数 - 1))`。

示例（默认配置 `points: 10`、`streak-bonus: 5`、`max-streak-bonus: 50`）：

| 连续天数 | 工会资金 |
|---|---|
| 第 1 天 | 10 |
| 第 2 天 | 15 |
| 第 3 天 | 20 |
| 第 11 天及以后 | 60（封顶） |

## 配置（guild.yml）

```yaml
guild:
  checkin:
    enabled: true        # 总开关
    points: 10           # 每日工会资金
    money: 0.0           # 每日个人金币（需 Vault，0 = 不发）
    streak-bonus: 5      # 每连续 1 天的额外工会资金
    max-streak-bonus: 50 # 连续奖励上限
    broadcast: true      # 是否向全会广播
```

## 数据

签到状态持久化在 `guild_members` 表的 `last_checkin`（epoch day）与 `checkin_streak` 列，
关服时随成员数据一并刷写，重启不丢失。旧库会自动补列。

## 占位符

| 占位符 | 说明 |
|---|---|
| `%liuguild_checkin_streak%` | 连续签到天数 |
| `%liuguild_checkin_state%` | `已签到` / `未签到` |
