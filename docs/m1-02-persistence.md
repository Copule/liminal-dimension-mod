# M1-02 版本化持久化

## 选型记录

核对项目锁定的 Minecraft 26.3 patched sources、NeoForge 26.3.0.113 sources、DataFixerUpper 10.0.21 编译接口和 M1-01 源集。官方网页提供概念参考，编译与运行使用本项目版本。

| 候选 | 依据与选择 |
| --- | --- |
| Minecraft SavedData、SavedDataType 与 Codec | 复用项目自带的保存队列、NBT 编码与文件布局。MinecraftServer 持有世界根目录的 storage，ServerLevel 委托给 ServerChunkCache，其目录由 DimensionType.getStorageFolder 确定。原版天气和袭击数据分别示范这两种作用域。 |
| NeoForge Data Attachments | 随加载器维护，LGPL-2.1-only。AttachmentType 将值绑定到持有者，IAttachmentHolder.getData 可以就地创建默认值，序列化与脏标记依赖持有者生命周期。适合实体、方块实体、区块等对象上的局部数据。本任务需要独立辨识全局和维度文件的状态，因此仅记录评估。 |
| DataFixerUpper | 使用已有 MIT 许可库的 Codec、JsonOps、NbtOps。两版私有格式只需旧版解码器映射到当前不可变值。完整 DFU 数据修复注册增加维护成本，NeoForge 的 SavedDataType 支持空 dataFixType。 |
| 项目安全封装 | 由项目维护严格解析、状态判断、写入确认与最终发布。复用原生保存并暂存一次，多一次读盘和同文件系统重命名，适合低频小快照。没有引入外部存档框架或复制第三方实现。版本升级需复核原生保存、临时文件和路径行为。 |

[NeoForge Saved Data](https://docs.neoforged.net/docs/datastorage/saveddata/) · [26.1 迁移说明](https://docs.neoforged.net/primer/docs/26.1/) · [Data Attachments](https://docs.neoforged.net/docs/datastorage/attachments/) · [目标版本源码包](https://maven.neoforged.net/releases/net/neoforged/neoforge/26.3.0.113/neoforge-26.3.0.113-sources.jar) · [DataFixerUpper](https://github.com/Mojang/DataFixerUpper)

## 格式与迁移

`VersionedSchema.first(codec)` 定义独立的 V1。调用 `next(newCodec, oldToNew)` 得到下一版，原 schema 仍可读取和写出原版。历史解码器按顺序应用每一步转换，新版编码器只写当前版本。调用方提供具有值相等语义的不可变快照和无副作用的迁移函数。缺失的非关键字段可以在相应 Codec 中定义默认值；已存在且错误的字段会触发保护。

信封包含 `schema_version` 和 `payload`。NBT 只接受正整数 IntTag；JSON 只接受十进制正整数数字记号。字符串、布尔、NBT 其他数值标签、带小数点或指数的 JSON 数字均视为 CORRUPT。超过当前版本的合法版本号先返回 FUTURE，读取器不会尝试解释其 payload。JSON 布尔和 NBT ByteTag 在 ops 转换后可能失去原类型，因此直接检查原始标签或记号。

| 读取状态 | 保存契约 |
| --- | --- |
| MISSING | 确认文件缺失；允许显式初始化。 |
| LOADED / MIGRATED | 含当前类型快照。只读、自动保存和正常关服均不会发布迁移。 |
| CORRUPT | 格式、边界、完整性或解码错误；拒写。流读取过程中无法进一步分类的错误也走此保护。 |
| FUTURE | 应用 schema 或外层 Minecraft DataVersion 高于支持版本；拒写。 |
| IO_ERROR | 文件系统错误、目录占位或文件符号链接；拒写，保留路径和原因。 |

兼容缺省外层 DataVersion 的历史输入。已有 DataVersion 必须为正 IntTag。回读本次暂存文件时另外要求 DataVersion 存在且恰等于当前游戏版本。

## 两种作用域与提交

全局文件为 `<世界>/data/liminal_dimension/<键>.dat`。维度文件为 `<世界>/dimensions/<命名空间>/<维度路径>/data/liminal_dimension/<键>.dat`，包括主世界。目录分别依据 26.3 MinecraftServer 和 ServerChunkCache 的原生布局。入口要求逻辑服务端线程。键使用小写 ASCII 字母、数字、短横线、下划线和分隔符。各段排除两套 26.3 FileUtil 校验中的保留名，目录段最长 255 字节，末段为 .dat 留出空间。世界根及中间目录归服务端控制。

26.3 的 SavedDataStorage.get 会缓存空结果，readSavedData 捕获异常并允许部分解码结果，computeIfAbsent 可在读取失败后创建默认对象。tryWrite 捕获 IOException，Future 完成不能作为写入凭据。原生缓存没有逐键移除 API，scheduleSave 会收集同一 storage 中全部已标脏状态。

1. 打开句柄时直读目标，限制 NBT 内存计量为 8 MiB。接受未压缩 NBT 或单一 gzip 成员。gzip 解码器验证 CRC 与长度，并在支持 mark/reset 的底层流上回到成员结束处。分别检查 NBT 内容结束及原始流结束，可识别尾随垃圾与空的第二成员。
2. 显式提交前检查原始观察值，预编码新值，在目标同目录独占创建随机暂存文件。每次使用短生命周期的原生 SavedDataStorage，使用该作用域的目录、维度上下文、注册表和 DataFixer。其 set 调用 setDirty，close 等待自身的保存队列。其他原生数据保持各自的保存时机。
3. 暂存文件须通过外层版本、NBT、内容和值相等性验证，再次检查目标后用 ATOMIC_MOVE 发布。原生编码与队列运行时错误包装为含原因的 IOException；文件系统写入、核验和发布失败也向调用者报告。保护状态会抛出 IllegalStateException，过期观察值冲突抛出 IOException。发布成功才更新句柄的观察值。
4. 一次操作只会清理本次独占创建的暂存文件。先前进程的 .pending.dat、随机暂存文件和 NeoForge .neoforge-tmp 均原样留存。正式文件不参与任何目录遍历清理。

该操作在服务端线程同步读盘并等待这一份数据刷盘，适用于低频小快照。NeoForge IOUtilities 在移动临时文件前 force(true)。外层最终发布要求文件系统支持同目录原子替换。硬终止可能遗留暂存文件，整机断电后的目录元数据、外部并发编辑及跨文件事务不在保证范围。正式游戏入口没有调用原型。

## 依赖与测试方式

沿用目标运行时已有的 Apache Commons Compress 1.28.0（Apache-2.0）单成员解码器。Gradle 9.2.1 的 java-test-fixtures 插件只编译一次探针；JUnit 与开发 GameTest 引用其产物。普通运行和生产 JAR 使用 main，Maven Java component 跳过两种测试夹具发布变体。[gzip 单成员边界契约](https://commons.apache.org/proper/commons-compress/apidocs/org/apache/commons/compress/compressors/gzip/GzipCompressorInputStream.html) · [Gradle 测试夹具](https://docs.gradle.org/9.2.1/userguide/java_testing.html#sec:java_test_fixtures)

执行 `./gradlew --continue build runGameTestServer`，Windows 使用 `.\gradlew.bat`。GameTest 强制检查专用服务器类型和 `build/gametest` 下的 `universe/gametestworld`，此约束与 M1-01 运行配置对应。故障注入分别在实际原生编码和已验证暂存文件的发布边界发生。发布用例使用真实 NIO 原子移动、缺失源和真实目标触发异常；设备断电不在该用例覆盖范围。符号链接探针仅在主机成功创建测试链接时计为实测。

[PR #4 的构建与逐轮测试记录](https://github.com/Copule/liminal-dimension-mod/pull/4/checks)

## 双平台验证记录

实现提交 `50e9d8bc8db34ebadf418becd0adf680efd03c95` 在 Windows 11 / NTFS（Microsoft OpenJDK 25.0.1）和 GitHub Ubuntu 26.04（Temurin 25.0.5）均通过构建。两端 JUnit 56/56、GameTest 8/8，通过用例标识逐项相同。已下载 Ubuntu 原始 XML 压缩包并核对 GitHub SHA-256 `f954e837acf5b2cef80bb36c8fc7c9a0088074678ee26e2560f74da559922b14`。[PR 构建 #19](https://github.com/Copule/liminal-dimension-mod/actions/runs/44532738092) · [测试 XML 归档](https://github.com/Copule/liminal-dimension-mod/actions/runs/44532738092/artifacts/14534367782)

Windows 对 17 份样本独立复算关服后的 SHA-256，与测试记录的写入前、拒写或刷盘后均相同。其中原子发布和原生编码故障共用的原档哈希为 `bf7768350fc0f75b81b33433bb82fb525d1d0c1f40d68a4dba7e82acdd3c5e74`。Windows 正式 JAR 的 SHA-256 为 `1eb501aa2b4384f1d10b28f3c109390b25fd0baeb98b5309eb0b5dca8b43922f`，包内无测试类，Maven 元数据仅含 apiElements 和 runtimeElements。Ubuntu 实测拒绝目标文件符号链接；Windows 测试账户创建链接时缺少系统权限。
