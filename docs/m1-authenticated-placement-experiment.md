# 已认证连接的放置前只读集成实验

本实验的基线为 PR #13 的 `09d2019c951c1ad66f45e066054e548d91bdcdf8`，沿用其独立配置队列夹具的注册位置、tick 驱动和 `PlacementGate`。实验构建从仓库 `testFixtures` 引入 PR #14 的原始 `PlayerDimensionProbe.java`，对应 `6e691d235e67da8562847e1a6c80eb7b1911ed28`。集成草稿以 #13 分支为基线，评审须同时核对 #13 和 #14。未来如获合并批准，先将 #13、#14 各自合入 `main`，再以 `main` 为集成草稿基线复查差异并重跑验证。 [PR #13](https://github.com/Copule/liminal-dimension-mod/pull/13) · [PR #14](https://github.com/Copule/liminal-dimension-mod/pull/14)

## 接口选型

| 候选 | 目标版本结论 |
| --- | --- |
| NeoForge 26.3.0.113 `RegisterConfigurationTasksEvent` 和原版 `ConfigurationTask.tick` | 沿用 #13。事件在配置任务收集时触发，顺序早于 `returnToWorld` 放入的 `PrepareSpawnTask` 和 `JoinWorldTask`。注册及 `start` 可能从配置包回调运行。实际数据检查在专服 tick 上调度，原版只在任务 tick 返回 true 时完成该任务。 |
| `PlayerList.loadPlayerData` 或入服后事件 | 读取路径会尝试复制坏档，入服事件发生在所需拦截点之后。 |
| 自定义握手、反射或 Mixin | 已有版本公开访问足以建立受控快照，不增加客户端消息、认证协议或私有接口补丁。配置监听器和密钥处理的来源结论需要随目标版本复核。 |

专服在线登录收到密钥后由 Mojang `SessionService.hasJoinedServer` 提供 profile，成功后 `handleLoginAcknowledgement` 将它放入首个配置监听器。NeoForge 目标版本保留该登录代码。实验只使用该监听器的公开 `getOwner()`，并要求服务端配置与当前在线认证状态一致、实际连接有原版 `CipherDecoder`、没有单人 profile 且禁止 transfer。会话服务验证失败回退为离线资料只出现在单人分支。Cipher 是对**该固定版本和受控服务器**的来源交叉核对，不能单独当成认证证明。游戏内的配置切换创建新监听器，原版回调无首连握手的 `startConfiguration` 调用。实验还给同一连接设置一次性标签，重复注册会令未完成的检查失效。 [Mojang 26.3 官方制品](https://piston-data.mojang.com/v1/objects/33680f5f2ac32864d6d7cf5e56a705fdb3e05f4c/server.jar) · [NeoForge 26.3.0.113 userdev 补丁](https://maven.neoforged.net/releases/net/neoforged/neoforge/26.3.0.113/neoforge-26.3.0.113-userdev.jar) · [NeoForge 事件源码](https://maven.neoforged.net/releases/net/neoforged/neoforge/26.3.0.113/neoforge-26.3.0.113-sources.jar) · [配置任务 API](https://docs.neoforged.net/docs/networking/configuration-tasks/)

## 运行边界

- 单独的实验 JAR 只注册专服入口。必须设置 `-Dliminal.placement.integration.enabled=true`，且专服必须明确以 `server-ip=127.0.0.1` 启动。连接须为非内存 TCP，服务端实际端点和对端均须解析为准确的 IPv4 `127.0.0.1`。未启用或超出网络范围时不注册探针。此实验假定没有代理、转发、修改认证或配置队列的其他模组。
- 权威路径来自 `MinecraftServer.getWorldPath(LevelResource.PLAYER_DATA_DIR)`，其底层使用当前专服的 `LevelStorageAccess`。`LevelResource.ROOT` 在该版本附加 `.`，因此使用不含该别名的 `PLAYER_DATA_DIR`。路径必须逐字对应服务器工作目录下的 `placement-integration-world/players/data`，不对点段做词法归一化。目录需使用绝对的 universe 路径。I/O 线程单独校验世界根实际路径及 `.liminal-placement-experiment` 普通文件，内容必须为 ASCII `liminal-placement-integration-v1` 加 LF。标记只用于一次性合成世界。探针不接收客户端或配置提供的扫描路径。
- 服务器 tick 捕获资料 ID、世界根及 `MinecraftServer.levelKeys()` 中实际已加载维度的不可变快照。工作线程仅接收快照，不访问游戏对象。每个实验进程至多提交一次读取，工作池为单线程且无等待队列。快照前后要求当前监听器一致、无在场玩家、无其他已连接会话、主世界已加载。后续会话不能复用第一次结果。结果回到服务器 tick 后重新核验输入。3 秒单调时限内只用 `Future.state()` 和 `resultNow()`，读档超时后取消并丢弃结果。底层文件系统操作可能无法被中断，由单个守护读取线程和一次性预算隔离。 [Java 25 Future API](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/Future.html)
- 每次对照都使用新的、调用方控制且无其他写入者的合成目录和专服进程。首次任务后的普通游戏流程仍归原版。配置阶段无法锁住到放置阶段之间的文件变化，而且原版重复登录处理可能先于本任务运行。路径替换、外部写入者、重复账号竞态和已放置玩家的再配置没有运行时验证。此处的防护只适用于受控实验，未来接入需要新的评审和证据。

| 结果 | 队列行为 |
| --- | --- |
| `NO_PLAYER_DATA` | 合成无档对照，当前连接仍有效时只返回一次 true。 |
| `DIMENSION_LOADED` | 合成正常对照，当前连接仍有效时只返回一次 true。 |
| `DIMENSION_MISSING` 或 `UNCONFIRMED` | 保持 false，尝试发送一次仅含状态的配置断开。同一连接的重复任务共享断开发送闩锁。 |
| `ABANDONED` | 监听器已替换或连接已离开，取消读取，不主动发断开，也不记录为通过。 |

报告只包含实验状态。原版自己的网络日志行为保持原样。任何放行状态只说明该任务可以推进一次，实际加入世界需要独立观察。实验既不进行存档修复，也不提供正式登录、迁移或重生策略。

## 合成验证

Java 25，仓库根目录执行。两个命令只编译及运行合成探针或打包。

```sh
./gradlew --no-daemon --console=plain -p test-data/m1-10-pre-placement-guard guardProbe jar
./gradlew --no-daemon --console=plain -p test-data/m1-10-pre-placement-guard/integration build
```

Windows 使用 `gradlew.bat`。设 `CI=true` 可走目标 NeoForge 的二进制补丁构建路径，校验的仍然是 Minecraft 26.3 / NeoForge 26.3.0.113 / Java 25。集成构建运行独立 `probe` 源集的 `integrationProbe`，用临时合成主档验证四种状态，并模拟未知异常、超时、重复注册、快照失配和普通断线。本机 Temurin 25.0.5.1+2 的集成构建通过 86 项合成断言，同一选项下根项目 `build` 通过 87 项 JUnit，0 失败、0 跳过。没有使用真实账号、真实存档或实机联机，合成用例不证明真实连接、并发安全或运行时磁盘行为。
