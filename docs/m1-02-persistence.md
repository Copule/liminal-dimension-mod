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

## 读写契约

生产入口只接受逻辑服务端及其维度，在服务器线程执行。调用者提供项目命名空间内的专用键、不可变值与 Codec。每一版包含显式 schema_version 和 payload，Minecraft 外层的 DataVersion 由原生机制维护。打开缺档只返回 MISSING。读取 V1 得到 MIGRATED 与升级后的值，只有显式保存才发布 V2。V2 返回 LOADED。未来版本、坏字段、损坏数据和读取失败均禁止通过该句柄保存。缺失的非关键字段可由该版本的 Codec 明确给定默认值。

26.3 的 SavedDataStorage.get 会缓存空结果，readSavedData 会捕获异常并尝试 resultOrPartial。computeIfAbsent 遇到空值会创建新对象，set 会调用 setDirty。原生 tryWrite 捕获 IOException，提交 Future 正常结束不足以说明字节写入成功。正常自动保存可调度后台写入，强制保存和正常关闭会调用 saveAndJoin。硬终止不保证执行关闭逻辑。

项目先读取目标文件，再在对应原生 storage 的专用暂存键上 set 并等待保存。NeoForge 的 IOUtilities 对临时文件 force(true)，随后尝试移动，某些文件系统会退回普通替换。项目验证暂存文件的完整 NBT 和解码结果后，才以同一目录的原子移动发布到目标文件；文件系统拒绝原子发布时向调用者报告失败。读写都核对目标状态，过期的句柄需要重新打开。暂存键归本模块独占，正常完成或失败后清理；进程被杀可能留下暂存文件，下次显式提交会重新生成。旧版本回滚副本不属于该流程。

约束为单个持有世界锁的服务端及模块专属键。离线编辑应在服务端关闭后进行。同一时刻由外部工具写文件、整机断电后的目录元数据持久性、跨文件事务和大批量高频写入均超出验证范围。正式 Mod 没有注册调用该原型的游戏事件。

## 文件布局与运行

全局文件为 `<世界>/data/liminal_dimension/<键>.dat`，维度文件为 `<世界>/dimensions/<维度命名空间>/<维度路径>/data/liminal_dimension/<键>.dat`。提交期间原生队列只持有同目录专用键 `<键>.pending.dat`。发布时要求快照按值相等；调用方应提供有值相等语义的不可变类型。适配器对读取的 NBT 使用 8 MiB 内存计量上限，支持未压缩文件和 gzip，并读到流结束以校验 gzip 尾部。大于当前 Minecraft DataVersion 的文件也进入 FUTURE。原生提交会顺带调度同一个 storage 中其他已经标脏的数据。

从项目根目录执行 `./gradlew --continue build runGameTestServer`，Windows 入口为 `.\gradlew.bat`。探针仅存在于测试源集。GameTest 检查服务器类型和隔离世界根目录，直接读取实际文件，覆盖迁移、过期句柄、遗留暂存文件、原生写入失败和再次打开。保存失败用例依赖 Windows NTFS / Ubuntu 常用文件系统的文件名组件长度限制。运行日志中该用例会产生一条预期的原生 I/O 错误。
