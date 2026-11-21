# Java 26.3 只读玩家维度探针

PR #9 的回滚实验表明，玩家保存的维度缺失时，原版回退仍可能使用原坐标。本探针在合成玩家档上独立验证如何识别该状态。PR #12 的 Python 预检提供判定语义参考，PR #13 提供配置队列实验背景。实现基于 `main`，使用现有 `testFixtures` 和 `test` 源集。 [回滚实验](https://github.com/Copule/liminal-dimension-mod/pull/9) · [Python 预检参考提交](https://github.com/Copule/liminal-dimension-mod/commit/ff1e11f30208d96ae7dd6a10ff2c4bbf8ca3acc1) · [配置队列实验](https://github.com/Copule/liminal-dimension-mod/pull/13)

## 接口选型

| 候选 | 目标版本观察与取舍 |
| --- | --- |
| `PlayerList.loadPlayerData` | 26.3 的 `PlayerDataStorage.load` 在主档读取失败后可以复制原文件，并尝试读取备份。该路径包含写入和数据修复行为。 |
| `NbtIo.readCompressed` / `parseCompressed` | Minecraft 26.3 提供只读 NBT 解码和 `NbtAccounter`。底层 `GZIPInputStream` 支持拼接成员，也可能忽略成员之后无法解析的头，且 NBT 解码接口可在读完根后返回。 |
| `NbtIo.parse` + JDK 25 `Inflater` | 复用目标版本 NBT 解析、收集器、限额及 `Identifier`。探针只维护原版写出的固定 GZIP 头与尾部校验、输入边界和判定逻辑。沿用项目已有依赖，不引入外部解析库或复制其源码。 |

目标版本依据为 Mojang 26.3 官方服务端制品内的 `version.json`、`LevelResource`、`PlayerDataStorage` 和 `NbtIo`，以及 NeoForge 26.3.0.113 userdev 补丁。JDK 25 的解压 API 提供结束状态及已消耗字节数。 [Mojang 26.3 服务端制品](https://piston-data.mojang.com/v1/objects/33680f5f2ac32864d6d7cf5e56a705fdb3e05f4c/server.jar) · [NeoForge userdev 制品](https://maven.neoforged.net/releases/net/neoforged/neoforge/26.3.0.113/neoforge-26.3.0.113-userdev.jar) · [OpenJDK GZIP 读取实现](https://github.com/openjdk/jdk25u/blob/master/src/java.base/share/classes/java/util/zip/GZIPInputStream.java#L277-L320) · [Java 25 Inflater API](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/zip/Inflater.html)

## 判定与边界

调用者提供稳定的合成世界根目录、类型为 `UUID` 的玩家标识和已加载维度 ID 的集合。探针检查 `players/data` 下该记录的主档和备份，仅返回状态枚举。

| 状态 | 含义 |
| --- | --- |
| `NO_PLAYER_DATA` | 目标目录中的主档和备份均已确认缺席，且没有发现旧布局迹象。 |
| `DIMENSION_LOADED` | 主档的 `DataVersion` 为整型 `5023`，根标签含可解码的规范命名空间维度 ID，调用者的集合包含该 ID。 |
| `DIMENSION_MISSING` | 同样可解码的主档维度 ID 不在调用者的集合中。 |
| `UNCONFIRMED` | 文件或目录状态不明、仅有备份、旧布局存在、主档损坏、版本未知、类型或维度 ID 无效，或超过限额。 |

文件打开为只读并指定 `NOFOLLOW_LINKS`，目录和主档须为预期类型且实际路径须与请求路径一致。备份只在主档缺席时检查存在性。压缩文件、解压输出、NBT 记账各限 8 MiB，NBT 容器深度限 128。GZIP 只接受原版写出的无可选字段头及单个完整成员，检查压缩边界、CRC32 和 ISIZE。NBT 只接受完整的无名根 compound，拒绝重复的两个判定字段。状态不包含 UUID、路径、坐标、维度 ID 或原始异常。

边界是调用者控制且没有并发写入的实验目录。Java 路径核对和打开之间存在时间窗，硬链接及并发替换未验证。判定只针对维度引用，独立于玩家其余字段的运行时加载结果。

## 复现

从仓库根目录使用 Java 25 执行。

```powershell
.\gradlew.bat --no-daemon --console=plain test --tests io.github.copule.liminaldimension.playerdata.PlayerDimensionProbeTest
.\gradlew.bat --no-daemon --console=plain build
```

`CI=true` 的本机执行返回 `BUILD SUCCESSFUL`。Gradle 报告 84 项 JUnit 通过，其中探针 25 项，0 失败、0 跳过。生产 JAR 内没有探针类。未执行 `runGameTestServer`。

独立单元验证使用 Mojang 26.3 服务端内层 JAR、其随附的库以及 JUnit Platform Console Standalone 1.13.4，以 `javac --release 25` 编译两个源文件，再用 `ConsoleLauncher execute --select-class io.github.copule.liminaldimension.playerdata.PlayerDimensionProbeTest` 运行。项目 Gradle `build` 另行验证 NeoForge 编译、全量 JUnit 及生产 JAR 的源集边界。测试数据由原版 NBT 写入器或少量手工字节在临时目录中生成。测试覆盖四种状态、坏档及尾随数据、旧布局、限额和文件内容保持。两种验证均无需启动 Minecraft。实机登录、在线维度枚举、放置前接线、存档恢复与迁移决策留在本原型边界之外。

Windows 上使用 Temurin 25.0.5.1+2 的独立单元验证通过 25 项，0 失败、0 跳过。测试覆盖目录和文件节点类型错误。环境拒绝为合成测试目录创建 Windows junction，因此未取得实际 junction 测试结果。

默认 Gradle 构建先遇到依赖下载停滞，复用按 Mojang 清单校验的 26.3 制品后继续。该次执行在 NeoForm 的 `recompile` 节点报告 `net/minecraft/world/waypoints/package-info.java: null`。使用 ModDevGradle 的 Eclipse 编译器开关时，同一节点报告 `Flexible Constructor Bodies is a preview feature and disabled by default`。这两次执行均未编译项目代码。ModDevGradle 在 `CI=true` 时使用目标版本的二进制补丁生成依赖，项目编译仍使用 Java 25 工具链。

```powershell
.\gradlew.bat --no-daemon --console=plain '-PneoForge.neoFormRuntime.useEclipseCompiler=true' build
$env:CI = 'true'
.\gradlew.bat --no-daemon --console=plain build
```
