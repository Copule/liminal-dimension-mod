# 放置前保护的隔离探针

这是一个独立、默认不启用的**实验包**，与正式 Mod 的构建、代码和存档没有接线。它仅测试 NeoForge 是否能在 Minecraft 采用缺失维度的旧坐标前中止正常的专服连接。它不会读取真实玩家档、账号或坐标，也不会定义正式登录/死亡规则。不要部署到正式世界。

## 精确版本依据

固定 Minecraft Java 26.3、NeoForge 26.3.0.113、Java 25：

- [Mojang 26.3 正式服务端包](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3#get-the-release)（外层包 SHA-1 `33680f5f2ac32864d6d7cf5e56a705fdb3e05f4c`）字节码显示：`startConfiguration` 调 `addOptionalTasks` 再调 `returnToWorld`；后者才把 `PrepareSpawnTask` 和 `JoinWorldTask` 加入 FIFO 队列。真正的玩家档读取、后备维度选择及位置选择在 `PrepareSpawnTask.start`。
- [NeoForge 26.3.0.113 userdev 补丁](https://maven.neoforged.net/releases/net/neoforged/neoforge/26.3.0.113/neoforge-26.3.0.113-userdev.jar) 把上述原版配置代码置于 `runConfiguration`，并在 `addOptionalTasks` 尾部发布公开的 `RegisterConfigurationTasksEvent`，把注册任务放入同一队列。因此该任务晚于部分握手/数据同步，却早于此次初始连接的放置准备。无法完成握手的连接可能根本不触发探针。
- [精确版本 NeoForge 源码](https://maven.neoforged.net/releases/net/neoforged/neoforge/26.3.0.113/neoforge-26.3.0.113-sources.jar) 明确事件走 Mod bus，暴露 `getListener()`、`register(ConfigurationTask)`；配置监听器沿 NeoForge 扩展获得 `getConnection()` 和 `disconnect(Component)`。不使用内部的 `ConfigurationInitialization.configureEarlyTasks` 或 Mixin。
- 原版 `MinecraftServer.tickConnection` → `ServerConnectionListener.tick` → `Connection.tick` 转入配置监听器任务的 `tick`；只有返回 true 才结束当前任务并启动下一项。探针注册及 `start` 不访问世界注册表。首个 `tick` 在服务端线程检查当前真实加载的测试维度；拒绝前先锁定返回 false，再发送配置阶段断开消息，之后也不会允许队列推进。并非依赖消息先送达或等待时长。

探针只在独立实验 JAR 安装且 JVM 启用 `-Dliminal.placement.fixture.enabled=true` 时注册；仅接收非内存回环连接，要求专服明确绑定 `127.0.0.1`，以既有 M1-04 的 `liminal_dimension_poc:probe` 作为整次受控实验的**合成已存维度引用**。它不会读取真实档案来推断玩家是否住在该处。此隔离测试服中的每条符合约束的连接都使用同一合成输入。

实际保护方案不应在登录任务中调用原版的 `PlayerList.loadPlayerData`：坏档读取可能产生 `_corrupted_` 备份，并非只读。测试不覆盖已有在线玩家被重复登录顶掉时此前的存档写入、游戏内重新配置、单人/模组混合协议、真实 NBT/DataFixer、并发和其他版本。暂不承诺运行时保护生产存档。

## 构建和复验

在仓库根目录、Java 25 下：

```sh
./gradlew --no-daemon -p test-data/m1-10-pre-placement-guard guardProbe jar
```

单独的 Placement Fixture 工作流会在本目录与工作流本身变更时执行此命令；不启动游戏、上传产物或发布。

`guardProbe` 合成检查四种队列决策：存在时推进、缺失时锁定只断开一次、检查异常默认阻断、断开异常也不使队列推进。可只用 Java 25 编译 `PlacementGate.java` 与 `PlacementGateProbe.java` 到临时目录并执行 `fixture.placement.PlacementGateProbe`；这不执行 Minecraft 的网络队列。

后续仅在校验过的**可丢弃副本**上测试真实连接。保持在线认证和白名单、仅监听回环、使用已获授权客户端。分别验证关闭旗标时没有探针任务、开旗标且装包时能正常进入世界、开旗标且移包时在配置阶段被拒，检查没有生成已进入世界的记录且关服档哈希不变。不要在原始备份上运行，不用伪造认证来代替测试。

当前已有的 [M1-05](../../docs/m1-05-dimension-removal.md) / [M1-06](../../docs/m1-06-client-dimension-travel.md) 工程资料和只读离线预检仍是较低耦合的运维筛查。若没有可信、可实时读取的玩家信息来源，优先考虑启动前的停服副本检查：如有风险或无法判定，先不要启动/开放连接。不要把 Netty 事件、登录后的玩家事件、在正式服试登录或直接打补丁当默认方案。
