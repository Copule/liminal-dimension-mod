# M1-04 数据包维度加载原型

## 目标版本与取舍

本原型使用 Minecraft 26.3、NeoForge 26.3.0.113，运行于 Java 25。参考 Neon 的预研后，在本地目标版本的 patched sources 和原版资源中核对了下列路径。服务端 JAR 的 version.json 声明数据包版本 121.0。

| 方案 | 评价 |
| --- | --- |
| 世界级数据包、原版维度类型和 `FlatLevelSource` | 采用。使用现有 `LevelStem.CODEC` 和内置生成器，资源包只含描述和一个维度条目。无运行依赖，无自定义类型、事件监听、传送或归档格式。空层平坦生成器适合小规模区块和存储探针。 |
| NeoForge 数据生成 | 官方支持世界层与维度层的数据生成，适合规模更大或由代码维护的注册表资源。两份固定 JSON 使用数据生成会增加产物管理和测试源集工作。 |
| 动态维度框架 | 需要额外核对 26.3 版本兼容性、许可证和运行时信任边界，还需承担注册表、客户端同步和存档生命周期维护。当前只验证启动时加载，本阶段无此依赖。 |

资源按目标版本公开数据格式编写，引用原版 ID，无第三方实现或 Mojang 源文件分发。版本升级需复核打包元数据与生成器 Codec。

## 加载与隔离

- `RegistryDataLoader.WORLD_REGISTRIES` 加载 `DimensionType`，`DIMENSION_REGISTRIES` 随后解码 `data/<namespace>/dimension/<id>.json` 的 `LevelStem`。`WorldDimensions.bake` 合并当前数据包条目及存档中的维度定义，优先使用同键的数据包条目。该流程发生在启动阶段。
- `LevelStem` 引用原版 `minecraft:overworld` 的维度类型及现有环境属性、时钟与时间线。原型条目使用 `minecraft:flat` 的空层配置。测试 ID 为 `liminal_dimension_poc:probe`。
- `MinecraftServer.createLevels()` 为合并注册表中的条目构造 `ServerLevel`。世界生成设置由 `WorldGenSettings` 在世界级 `data/minecraft/world_gen_settings.dat` 保存。`DimensionType.getStorageFolder` 和 `ServerChunkCache` 将区块与原生维度数据放在 `<world>/dimensions/<namespace>/<path>/`。服务器在关服时保存并关闭各维度。
- `test-data/m1-04-dimension-poc/` 位于全部 Gradle 源集之外。专服停止期间显式复制到隔离世界的 `datapacks/`。JUnit 检查真实生产 JAR 的 ZIP 条目，拒绝包含探针命名空间或测试数据包目录。现有原生 GameTest 继续覆盖服务端线程和持久化回归。

[Java 26.3 发布说明](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3) · [数据包版本范围格式](https://www.minecraft.net/en-us/article/minecraft-java-edition-1-21-9) · [NeoForge 维度存档布局迁移](https://docs.neoforged.net/primer/docs/26.1/) · [NeoForge 26.3.0.113 源码](https://maven.neoforged.net/releases/net/neoforged/neoforge/26.3.0.113/neoforge-26.3.0.113-sources.jar)

## 复验流程

在 Windows 执行 `gradlew.bat --no-daemon --console=plain --continue build runGameTestServer`，Ubuntu 使用 `./gradlew`。专服使用同一生产 JAR、NeoForge 安装生成的 `win_args.txt` 和 `nogui`，工作目录独立，绑定 `127.0.0.1`。仅使用项目所有者明确接受 EULA 的测试实例。

1. 空实例启动并建立测试世界。验证生产 JAR 可加载、探针维度未注册，在主世界落下基准方块，并正常保存停止。
2. 将数据包复制至同一隔离世界的 `datapacks/m1-04-dimension-poc/`。重启，确认数据包已启用及探针维度可执行原版命令。确认主世界原有方块，在探针维度相同坐标写入不同方块，并添加独立区块加载票据，保存后正常停止。
3. 保持同一世界与数据包重启。在无需重写探针的情况下确认两个方块和各自票据，并验证两套区块及票据文件的实际路径。再次保存停止。

既有世界加包、真实维度加载和重启保留由专服操作验证。自动化门禁不启动带这个数据包的物理专服。数据包卸载后的存档兼容性、热增减维度、客户端进入与联机同步留在本次验证范围之外。

## 本地验收记录

Windows 11 x64 26H2、NTFS、Microsoft OpenJDK 25.0.1+8 与 Gradle 9.2.1。完整构建与原生 GameTest 退出 0，JUnit 59/59、GameTest 8/8，均为 0 失败、0 错误、0 跳过。新用例针对正式 JAR 内的测试包隔离。生产 JAR 的 SHA-256 为 `d863ea5a730b1c5b885f3df2bdc4989f8c9926ab784c58978a492af71a84bbeb`，与 M1-03 验收产物逐字节一致。

真实专服的无包基准、既有世界加包和保留包重启三轮验收均正常保存停止，JVM 退出 0。无包基准返回未知维度。加包时从已保存的基准备份恢复隔离测试世界，四个关键文件的 SHA-256 与无包基准一致。专服启用测试包并加载探针维度。主世界金块与探针绿宝石块处于同一坐标，重启后都能读取。探针维度有独立的额外强制加载票据，主世界未出现该票据。`region/r.0.0.mca` 与 `data/minecraft/chunk_tickets.dat` 也分别存在于各自维度目录。证据为本地验收目录的原始日志、命令响应与文件清单。

[基线 main Build #25](https://github.com/Copule/liminal-dimension-mod/actions/runs/44661441426) · [M1-04 分支 CI](https://github.com/Copule/liminal-dimension-mod/actions?query=branch%3Afeat%2Fm1-04-dimension-poc)
