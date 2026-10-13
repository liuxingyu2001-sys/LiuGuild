# 工会任务与 liupass

LiuGuild 使用 liupass 的公开 `PassAPI.addTaskProgress` 推进独立 CUSTOM 任务，不替代通行证的任务奖励、周期重置和待领取界面。

在 LiuGuild `tasks.yml` 配置绑定：`event` 支持 `JOIN`、`CREATE`、`WAR_PLAY`、`WAR_WIN`、`BANK_DEPOSIT`；`task-id` 必须对应 liupass 中已有的任务 ID；`pass-id` 留空使用玩家默认通行证。`count-amount: true` 将事件金额作为进度，否则一次行为加 1。

例如放入 liupass `tasks/weekly/guild.yml`：

```yaml
tasks:
  - id: guild_war_play
    name: "工会协作：完成 3 场工会战"
    type: CUSTOM
    amount: 3
    xp: 500
    rewards: []
  - id: guild_war_win
    name: "工会荣誉：赢得 1 场工会战"
    type: CUSTOM
    amount: 1
    xp: 300
    rewards: []
```

任务可以单独放在一个 YAML 文件中，仍使用 liupass 原有任务分类/界面；示例无需额外修改 liupass 源码。任务奖励如需金币或称号，在 liupass rewards 中定义后引用。

取消的比赛不推进任务。入会任务适合配置为一次性 NORMAL，避免反复退会加入刷日常；存款任务默认禁用，因为成员可以循环存取金币。不要将可循环操作配置为高价值可重复任务。

安装或修改配置后，分别重载 LiuGuild 和 liupass；缺少 liupass 时跳过任务推进。
