# M1-01 测试基础设施

## 选型记录

在 M0 合并点检查了构建脚本、CI、现有入口、Gradle 依赖缓存和生成的 Minecraft 源码。原工程只有 main 源集和 MDK 运行配置，JUnit 没有测试源码。沿用现有 Wrapper、JDK 和 NeoForge 版本。

| 候选 | 兼容性、成本与取舍 |
| --- | --- |
| Gradle Test + JUnit Jupiter 5.13.4 | 采用。Gradle 原生支持 JUnit Platform 和 XML 报告，JUnit BOM 对齐引擎与 launcher。该层只测 Java 契约，无需启动服务器。JUnit 为 EPL-2.0，仅作测试依赖。 |
| NeoForge 原生 GameTestServer | 采用。使用本地 26.3.0.113 sources、userdev config 及 patched Minecraft 源码确认注册、参数与报告。借助现有 MDG 2.0.148 的源集绑定，复用原版空结构，无新增运行时依赖。启动成本限于服务端验证。NeoForge 为 LGPL-2.1-only，MDG 为其官方维护的构建插件。 |
| NeoForge Test Framework / 模拟服务端 | 本轮只需一个真实逻辑服务端，额外框架会扩大测试依赖、版本适配和维护范围。未引入，也未复制第三方实现。 |

官方资料：[Gradle 9.2.1 测试](https://docs.gradle.org/9.2.1/userguide/java_testing.html)、[JUnit 5.13.4](https://docs.junit.org/5.13.4/user-guide/)、[NeoForge 目标版本源码包](https://maven.neoforged.net/releases/net/neoforged/neoforge/26.3.0.113/neoforge-26.3.0.113-sources.jar)、[ModDevGradle 源集与运行配置](https://github.com/neoforged/ModDevGradle)、[NeoForge Game Tests 概念文档](https://docs.neoforged.net/docs/misc/gametest/)。概念文档的示例对应其他版本，方法签名以本项目锁定版本为准。

## 运行与判定

在仓库根目录使用 JDK 25。Linux 执行下列命令，Windows 将 `./gradlew` 换为 `.\gradlew.bat`。

```sh
./gradlew test
./gradlew runGameTestServer
./gradlew --configuration-cache build runGameTestServer
./gradlew '-PgameTestFilter=liminal_dimension:server_environment' runGameTestServer
```

- `test` 通过 `useJUnitPlatform()` 执行 Jupiter。用例读取构建展开的 Mod 描述文件，检查入口 ID 和中文显示名。每轮先清理 JUnit 报告，并要求本轮实际执行。即使 Gradle 将测试任务标为 `NO-SOURCE`，后续校验仍要求 XML 含本项目包名下的通过用例。
- `runGameTestServer` 通过 MDG 的 `loadedMods` 加载 main 与 gameTest 组成的同一个开发 Mod。客户端、普通服务端及数据生成仅加载 main。生产 JAR 来自 main 源集。
- 测试专用的 Mod 总线订阅者通过 `RegisterEvent` 将函数注册到 `Registries.TEST_FUNCTION`，通过 `RegisterGameTestsEvent` 注册环境与 `FunctionGameTestInstance`。使用原版 `minecraft:empty` 结构。断言在 `GameTestHelper.runAfterDelay(1, …)` 回调中检查服务端线程、测试 Tick 推进以及项目 Mod 加载状态。
- 在 MDG 的 `programArguments` 中按独立参数传入 `--tests liminal_dimension:*`、`--report results.xml`、`--universe universe`。这里的 `--tests` 是游戏程序参数。在 Gradle 命令行使用上例的项目属性传入 GameTest 筛选器。工作目录为 `build/gametest/`，原生服务端每次会重建其中的 `universe/`。
- Gradle JUnit XML 使用 `testcase.classname` 标识 Java 包。原生 GameTest XML 使用嵌套的 `testsuite`，其中 `testcase.name` 是命名空间 ID，`classname` 是结构 ID。校验器逐个读取用例，要求至少一个本项目用例通过，并拒绝报告中的失败、错误或跳过记录。GameTest 启动前移除上轮 XML。
- 实测未注册项目测试的 M0 使用旧属性、不传筛选器时，唯一用例是 `minecraft:always_pass`，进程正常退出。26.3.0.113 的 `GameTestHooks` 检查运行环境与 `neoforge.enableGameTest`，源码中没有读取 `neoforge.enabledGameTestNamespaces` 的逻辑。
- CI 执行原有 `build` 与 `runGameTestServer`，保留 Gradle 控制台日志。每轮上传两层 XML，若失败则另存可用的 GameTest 服务端摘要。产物保留 7 天。

## Windows 中文路径

Gradle 9.2.1 的测试 worker 通过 Java 启动器的 `@file` 传递类路径。本机原生字符集为 GBK，默认 UTF-8 的参数文件使 worker 在中文目录找不到项目测试类。项目将 Gradle daemon 的 `file.encoding` 设为 Java 25 的 `COMPAT`，使参数文件使用本机原生字符集；同时显式使用 UTF-8 展开 Mod 元数据和编译 Java 源码。源路径中出现本机字符集无法表示的字符时，仍应使用可表示路径。参见 [Gradle worker 参数文件问题](https://github.com/gradle/gradle/issues/30304)。

## Windows 验证记录（2026-11-19）

| 场景 | 观察结果 |
| --- | --- |
| 正常构建及两层测试 | JUnit 1/1、项目 GameTest 1/1 通过，各 0 失败、0 跳过。 |
| 临时修改元数据用例预期值 | JUnit 1 例失败，`build` 退出 1。 |
| 在 GameTest 的下一 Tick 注入失败 | XML 标记项目用例失败；服务端与 Gradle 均退出 1。 |
| 已有通过 XML，筛选不存在的项目 ID | 启动前移除旧 XML。原生服务端提示零匹配，退出 -1，Gradle 退出 1。 |
| 只选原版 `minecraft:always_pass` | 原生测试通过，XML 中项目用例数为 0，Gradle 报告校验失败。 |
| 删除 JUnit 源码并预置旧的通过 XML | 增量构建与干净构建均被校验器拒绝。干净构建日志明确记录 `:test NO-SOURCE`。 |
| 删除报告后恢复正常源码 | 两层重新生成 XML，均通过。 |
| 完整命令重复执行 | Gradle 复用配置缓存，JUnit、GameTest 仍各实际运行 1 例。变更筛选器时，原生服务端实际收到新选择。 |

生产 JAR 的 SHA-256 为 `362554492834249062d4dd31e18320db27d5eac80600fe45677c634bee243875`，与 M0-07 归档值一致。归档中仅有正式入口类、清单、Mod 描述文件及目录项。
