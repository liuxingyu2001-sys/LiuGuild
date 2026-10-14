# Residence 领地共享

把成员自己的 **Residence 领地**共享给整个工会：会长在领地内一次授权，其他成员就能建造 / 打开容器 / 使用设施，退会或被踢时自动收回。

> 需要安装 [Residence](https://www.spigotmc.org/resources/residence-1-7-10-up-to-1-21.11480/) 插件。未安装时该功能自动隐藏，不影响其它功能。

## 指令

| 指令 | 说明 |
|---|---|
| `/guild residence list` | 列出自己名下所有领地 |
| `/guild residence share <领地名>` | 把自己拥有的领地共享给工会全体成员 |
| `/guild residence unshare <领地名>` | 取消共享 |

别名：`/guild res ...`、`/guild lingdi ...`

## 行为

- **只有领地主人能共享自己的领地**；试图共享别人的领地会被拒绝（`res-not-owner`）。
- 共享时对领地内的**每个工会成员**逐一授予以下标志为 `allow`：
  `build`、`destroy`、`place`、`container`、`use`、`bucket`、`bucketfill`、`ignite`、`pvp`、
  `animalkilling`、`mobkilling`、`shear`、`leash`、`ride`、`trade`、
  `door`、`button`、`lever`、`pressure`、`table`、`enchant`
- **自动同步**：
  - 新成员**加入工会**时，自动获得工会内所有共享领地的权限，无需会长重新授权；
  - 成员**退会或被踢**时，自动收回其所有共享领地权限，避免退会后仍能破坏。
- 权限按 **UUID** 写入，玩家改名后不会残留或丢失。
- 会长**离线**也能正常收回（全程用 UUID 查询，不依赖在线玩家对象）。

## 配置（guild.yml）

```yaml
guild:
  residence:
    enabled: true   # 总开关
```

## 兼容性

全部通过反射调用 Residence 的公开 API（`ResidenceApi.getResidenceManager()` → `ClaimedResidence` / `ResidencePermissions`），
因此：

- 不需要在 `pom.xml` 里硬依赖 Residence；
- 兼容多个 Residence 版本：会自动挑选可用的 `setPlayerFlag`
  重载（5 / 6 / 7 参数的 `(CommandSender, UUID, String, String, boolean...)`）。
- 若当前 Residence 版本没有带 UUID 的重载，会在控制台**打印警告**而不是静默失效。

## 注意事项

- 共享是**双向可见**的：工会成员能看到并使用彼此共享的领地，请只共享愿意开放的区域。
- 取消共享（`unshare`）会把上述标志改回 `deny`；如果你在共享前给某成员单独设过这些标志，取消共享后需要手动恢复。
- 领地世界与工会战竞技场互不影响；`fair-equipment` 不会干预领地权限。

## 相关

- [hooks.md](hooks.md) —— 全部软依赖钩子
- [commands.md](commands.md) —— 完整指令表
