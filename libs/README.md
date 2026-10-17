# 编译期本地依赖

这个目录存放无法从公共 Maven 仓库获取、但编译期直接引用的插件 API。

- `liudungeon-1.9.3.jar`：`DungeonHook` / `WarManager` 动态竞技场 API，`pom.xml` 以 `system` 依赖引用。

其他软依赖（PacketEvents、PlaceholderAPI、LuckPerms、Vault、CustomNameplates 等）通过 Maven 仓库或运行时反射接入，不需要复制到这里。
