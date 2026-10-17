# 工会战头顶铭牌（CustomNameplates）

装备统一后，仅靠外观很难区分队友/对手。LiuGuild 通过 **CustomNameplates** 注册占位符，在工会战期间把工会名显示在玩家头顶：

- A 方默认红色：`[工会名]`
- B 方默认蓝色：`[工会名]`
- 非工会战、旁观、非参赛者返回空字符串，不显示额外标签

> 需要安装 CustomNameplates 3.0.45+。LiuGuild 通过 `softdepend` 和反射接入，未安装时自动跳过。

## 1. LiuGuild 配置

```yaml
war:
  nameplate:
    enabled: true
    format-a: "&8[&c{guild}&8] "
    format-b: "&8[&9{guild}&8] "
```

占位符：

- `{guild}`：工会名
- `{player}`：玩家名

支持 `&` 颜色代码；格式会原样交给 CustomNameplates。

## 2. CustomNameplates 配置

**不要修改现有的 `tag_normal` / `tag_text_part`**。在 CustomNameplates 的 `configs/nameplate.yml` 的 `unlimited` 下新增一个只对工会战生效的标签：

```yaml
unlimited:
  liuguild_war:
    text: "%liuguild_war_guild%"
    translation: "0,0.4,0"
    viewer-conditions: {}
    owner-conditions:
      condition_liuguild_war:
        type: "!equals"
        refresh-interval: 20
        value:
          value1: "%liuguild_war_guild%"
          value2: ""
      potion-effect: "INVISIBILITY<0"
    affected-by-crouching: true
    affected-by-scale-attribute: true
    affected-by-spectator: true
    line-width: 1024
    background-color: "0,0,0,0"
    is-see-through: false
```

关键点：

- `condition_liuguild_war` 要求占位符**非空**
- 工会战外 `%liuguild_war_guild%` 返回空，条件不成立，标签不会显示
- 不再使用 `always-show: true` 强制全服显示
- 不要改掉原有的 `tag_normal` / `tag_text_part` / `tag_image_part`，否则会影响原有头顶标签

改完执行 CustomNameplates 重载，或重启服务器。

## 3. 验证

1. 启动日志应出现：
   ```text
   CustomNameplates 已接入：%liuguild_war_guild%
   ```
2. 进入一场工会战后，参赛者头顶出现 `[工会名]`
3. A/B 方颜色分别使用 `format-a` / `format-b`
4. 比赛结束/离开后标签消失

## 限制

- 只对**本场参赛者**显示；旁观者不显示
- 标签依赖 CustomNameplates 的标签渲染与资源包
- 若 CustomNameplates 重载后占位符丢失，重启服务器或重新执行 LiuGuild 重载
- 没有真实客户端资源包/多服环境联调记录
