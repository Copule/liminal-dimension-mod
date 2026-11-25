# M1-05 维度数据包移除与存档恢复

基线为 M1-04 合并提交 `954a2441eafe18acd0ab9f1e0beec12512c767b1`。真实专服使用 Minecraft 26.3、NeoForge 26.3.0.113、Temurin Java 25.0.5 和该基线构建的生产 JAR。构建工具为 ModDevGradle 2.0.148、Gradle 9.2.1。[main Build #28](https://github.com/Copule/liminal-dimension-mod/actions/runs/44676419025)

## 真实专服结果

| 场景 | 实测结果 |
| --- | --- |
| A 已有原版世界后装再移除 | 用 Mojang 原版专服建世界、写入主世界金块和票据并备份。NeoForge 后装原 M1-04 包，在探针维度保存绿宝石块和独立票据。移包后专服警告缺包并达到 `Done`，主世界金块和票据可读，探针 ID 返回 `Unknown dimension`。 |
| B 新世界预装再移除 | 首启前包已在 `datapacks/` 且列入 `initial-enabled-packs`，当时尚无 `level.dat`。正常预设建世界时加载了探针维度。移包启动同样达到 `Done`，主世界状态可读，自定义维度 ID 消失。 |
| C 从 A、B 各自的移包后状态恢复 | 关服后放回同一路径、逐文件 SHA-256 相同的原包。专服自动启用，读回两个维度的方块及各自票据。恢复前只有包文件新增。读回命令没有重写探针。 |
| 预设对照 | 另建隔离世界，显式选择含两个测试 LevelStem 的预设。移包后仅引用原版注册项的测试维度仍加载。依赖包内 `probe_type` 的探针因缺引用被跳过，服务器启动并保存后该定义从世界生成设置消失。放回原包可再次读到该探针的旧方块和票据，持久化维度表里的条目仍缺失。 |
| 原版备份回滚 | 将 A 未装包基线 ZIP 解压到另一空世界目录，启动前逐文件核对 SHA-256。用原版专服启动副本，再次读回主世界金块和票据。 |

所有正式验收轮次执行了 `save-all flush` 与 `stop`，JVM 退出 0。A、B、预设对照的主世界在 `8 180 8` 有金块，探针在相同坐标有绿宝石块。主世界仅持有 `[0,0]` 的 `minecraft:forced` 票据，探针有 `[0,0]` 和 `[1,0]`。

- A、B 的 `dimensions/liminal_dimension_poc/probe/` 在移包期间逐文件 SHA-256 不变，包括 `region/r.0.0.mca` 和 `data/minecraft/chunk_tickets.dat`。恢复包后，探针区域文件重新参与保存，哈希变化。两维度的方块及票据读回通过。主世界区域文件也随保存变化。
- A、B 的 `data/minecraft/world_gen_settings.dat` 始终只有三个原版维度，各阶段 SHA-256 相同。B 从首次启动就在运行时加载探针，但独立 LevelStem 没有序列化进该文件。A、B 的 `level.dat` 在缺包一轮保存后从 `DataPacks.Enabled` 去掉包 ID，`Disabled` 未加入它。重装后 ID 再次进入 `Enabled`。`LastPlayed` 和 `Time` 随正常保存推进。
- 预设对照的 `world_gen_settings.dat` 初始有两个额外 LevelStem。移包后日志出现 `Failed to decode key` 并指出缺少 `probe_type`。仍可解码的定义保留，失败的条目从 `data.dimensions` 消失。恢复包后，旧区块和票据可用，原持久化条目仍未恢复。一次启动到 `Done` 也需要核对日志、种子和维度表。

首轮 A 的原版基准通过，随后 NeoForge 监听阶段因 Windows 沙箱拒绝回环连接而未到达 `Done`，尽管 JVM 退出 0。保留首轮现场后使用新隔离目录完成正式复跑。Windows 门禁另行执行 `build runGameTestServer`，JUnit 59/59、GameTest 8/8。LevelStem 加载证据来自真实专服，Ubuntu 使用本 PR 的 Build 工作流验收。

## 目标版本源码核验

1. `MinecraftServer.configurePackRepository` 对启用列表中不存在的包记 `Missing data pack`，重新选择当前可用的包。在本实验中放回的世界包符合自动启用条件。`WorldLoader.load` 先加载 `WORLD_REGISTRIES`（含 `DimensionType`），再加载 `DIMENSION_REGISTRIES`（含 `LevelStem`）。`RegistryDataLoader.load` 汇总读取、冻结和校验注册表时的错误，未通过可中止 `Main` 的世界加载。仍在加载的资源依赖缺项与整包缺席有不同的处理路径。
2. 新专服的 `Main.createNewWorldData` 调用 `DedicatedServerProperties.createDimensions` 取得预设维度。NeoForge 对映射做 Codec 编解码，随后 `WorldDimensions.bake` 取数据包维度与预设维度的并集，同键优先用数据包。新存档的 `WorldGenSettings` 持有预设映射，运行时注册表则用合并结果。已有存档的 `LevelStorageSource.getLevelDataAndDimensions` 读取保存的 `WorldGenSettings`，解码后与当前数据包维度合并。
3. `WorldDimensions.CODEC` 使用 NeoForge 的 `LenientUnboundedMapCodec`，独立解码键值、记录错误，仅纳入完全成功的条目。仍可解析的存档条目可以参与 `bake`。缺少主世界时，`WorldDimensions` 构造器仍抛错。若整个 `WorldGenSettings` 读取返回失败，`LevelStorageSource` 有报错后启用随机种子默认设置的分支，此分支仅做源码核验。`MinecraftServer` 将设置放进 `SavedDataStorage` 并标脏。正常保存可把跳过条目后的映射写回，预设对照已验证这一步。
4. `MinecraftServer.createLevels` 为合并后的注册项创建 `ServerLevel`。`DimensionType.getStorageFolder` 将维度键映射到 `<world>/dimensions/<namespace>/<path>/`。区块、实体、POI 和维度级 SavedData 各在对应目录下。`saveAllChunks` 为当前加载的维度保存，并更新 `level.dat` 与世界级 SavedData。A、B 验证了未加载探针目录留在原位。

依据为本地 NeoForm 26.3-1 生成的 NeoForge 26.3.0.113 patched sources，以及同版本的 NeoForge source JAR。[Mojang 26.3 发布说明](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3) · [NeoForge 维度存储布局](https://docs.neoforged.net/primer/docs/26.1/) · [目标版本 NeoForge 源码包](https://maven.neoforged.net/releases/net/neoforged/neoforge/26.3.0.113/neoforge-26.3.0.113-sources.jar)

## D 玩家滞留，仅源码核验

`ServerPlayer.SavedPosition.MAP_CODEC` 分别读取 `Dimension`、`Pos`、`Rotation`。若 `Dimension` 是语法有效的键而 `server.getLevel` 得不到维度，`PrepareSpawnTask.start` 退到世界重生配置指向的已加载维度，否则退到主世界。**有效的原 `Pos` 和 `Rotation` 仍会使用。** 只有缺少可用坐标时才调用 `PlayerSpawnFinder.findSpawn`。回退维度可能沿用另一维度的坐标，落在方块内、空中或危险地形。

`ServerConfigurationPacketListenerImpl` 在配置任务完成后调用 `spawnPlayer`。`PrepareSpawnTask.Ready.spawn` 在选定维度构造玩家、读取档案，再以 `snapTo` 应用已求得的坐标和朝向，随后进入 `PlayerList.placeNewPlayer`。后续玩家保存会用实际维度和坐标写档，恢复原包后可能失去原下线位置。实际落点、客户端同步和玩家档案写回均未用受认证测试身份复验。

## 复验与备份

复用 M1-04 世界级数据包、目标版本 NeoForge 专服和原版控制台，避免动态维度框架和存档编辑器引入的注册表及写入变量。测试工具用 Python 标准库生成 SHA-256 和 ZIP，使用 MIT 许可的 nbtlib 2.0.4 只读解析目标版本压缩 NBT。I/O 成本取决于隔离存档的体积，解析在停服后执行，生产服务无新依赖。升级时应复核 NBT 和控制台反馈。[M1-04 测试包](../test-data/m1-04-dimension-poc/) · [nbtlib](https://github.com/vberlier/nbtlib)

1. 在全新的隔离目录下建 `runtime`，安装 NeoForge 26.3.0.113，把 Mojang 原版专服 JAR 放为 `runtime/vanilla-26.3.jar`，把构建产物放到 `runtime/mods/`。所有者明确接受 EULA 后为该实例准备 `runtime/eula.txt`。保持专服工作目录专用及空白名单，使用在线认证、仅监听 `127.0.0.1`，关闭 RCON、查询和管理端口。
2. 在隔离的 Python 3.12 环境安装 `nbtlib==2.0.4`，然后运行下方命令。`runtime` 和新证据目录须同盘同父。脚本通过控制台执行命令，并记录 `launch.json`、`result.json`、`probes.txt`、`console.log` 和原版日志。重跑应使用新的隔离实例并留存失败现场。
3. 预设对照另用空世界和测试包副本。从原版 26.3 服务端内部 JAR 读取 `data/minecraft/worldgen/world_preset/normal.json` 和 `data/minecraft/dimension_type/overworld.json`。在副本中把后者放到 `data/liminal_dimension_poc/dimension_type/probe_type.json`，让原探针引用 `liminal_dimension_poc:probe_type`。在预设的 `dimensions` 增加此探针及使用原 M1-04 定义的 `liminal_dimension_poc:preset_control`，预设保存为 `data/liminal_dimension_poc/worldgen/world_preset/m1_05_probe.json`。首启设置 `level-type=liminal_dimension_poc:m1_05_probe`，执行相同 B/C 操作。比对三阶段的维度表及日志中是否有保存 `preset_control` 的记录。原版资源在本地隔离环境临时读取。
4. 停服等 JVM 退出后，备份完整世界和原包，另记录版本、Mod JAR、配置及哈希。`world.zip` 归档关服世界文件，排除 `session.lock`，逐项校验并标记只读。`backup.json` 记录 ZIP 哈希，`files.json` 记录逐文件哈希和大小。回滚时先校验 ZIP，再解压到新目录，对照清单逐项复算，之后用原版本启动副本验证原状态。保留原归档及移包后现场。

~~~powershell
python ./test-data/m1-05/run_dimension_removal.py --runtime "<隔离目录>/runtime" --evidence "<隔离目录>/dedicated" --java "<JDK25>/bin/java.exe" --port 25576
~~~

只有控制台探测通过、JVM 正常退出且根目录的 `level.dat` 可完整读取并包含 NBT `Data` compound，阶段才通过验收。启用数据包按控制台列表中的完整 ID 核对，不把同名前缀当成探针包。缺失/不完整世界的哈希清单仍可留作排查，不代表可回滚；只有通过备份校验的阶段才发布 `backup.json`。回滚会拒绝不含根 `level.dat` 的旧归档，并再次验证恢复出来的 NBT。

仅复跑脚本的合成回归时，在仓库根目录使用以下命令，不会启动 Minecraft 专服。GitHub Actions 的独立 Python 作业使用相同命令。

~~~powershell
python -m pip install nbtlib==2.0.4
python -B -m unittest discover -v -s test-data/m1-05
~~~

## 验收边界与发布建议

- **已实测通过**：原 M1-04 包两种世界创建路径的移除与恢复，主世界与探针状态、备份校验和原版回滚启动。
- **已实测失败但可恢复**：首轮宿主回环权限导致 NeoForge 启动失败，在新实例复跑通过。预设对照中包内类型引用解码失败，原包可恢复该探针的运行时维度、方块和票据。
- **已实测失败且有状态损失风险**：预设对照中缺引用的条目从持久化维度表丢失，原包恢复后该条目仍缺失。此轮未观察到探针区块、票据被删除。
- **仅完成源码核验**：D 的维度回退、保留坐标及保存风险。整个世界生成设置读取失败、活动注册表引用错误的分支。
- **尚未测试**：真实玩家滞留与重连、Minecraft 跨版本迁移、生产 Mod 卸载、正式自定义内容及跨 Mod 依赖、断电一致性。
- **需要所有者决定**：未来是否支持无 Mod 启动，维度 ID 退出周期、兼容包维护期、离线玩家处理策略、如何处理备份以后产生的合法进度。适用范围以原批准的 V1.0 需求为准。

建议保持维度键稳定，让旧定义在迁移窗口内继续可解析。升级或卸载前，用隔离副本核验启动、种子、依赖、区块票据和玩家落点，并保留停服全量备份。离线玩家的处理需要经所有者批准。若误移包，暂停玩家接入并停止反复启动，在副本恢复精确版本的包与 Mod 后核对世界生成设置。预设对照表明，若目标包括恢复原始世界生成定义，应从移包前的完整备份验证回滚。
