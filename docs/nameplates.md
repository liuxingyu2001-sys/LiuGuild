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

编辑 CustomNameplates 的 `configs/nameplate.yml`，让无限标签使用 LiuGuild 注册的占位符：

```yaml
always-show: true

unlimited:
  tag_normal:
    text: '%liuguild_war_guild%'
  tag_image_part:
    text: '%liuguild_war_guild%'
  tag_text_part:
    text: '%liuguild_war_guild%'
```

说明：

- `always-show: true` 保证没有装备 nameplate 图片的玩家也能显示文字标签
- `tag_normal` 用于没有 nameplate 图片的玩家
- `tag_text_part` / `tag_image_part` 用于已有 nameplate 图片的玩家
- 工会战外占位符返回空，因此不会给全服玩家增加常驻文本

改完 CustomNameplates 配置后执行其重载命令，或重启服务器。

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
