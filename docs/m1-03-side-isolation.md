# M1-03 客户端／服务端隔离

## 目标版本核验

锁定 Minecraft 26.3、NeoForge 26.3.0.113、FML 12.0.12、ModDevGradle 2.0.148、Java 25 和 Gradle 9.2.1。官方 NeoForge 网页当前展示 26.1，概念说明与本项目核对的目标源码一致。签名与行为以目标版本的源码及运行结果为准。

- FML `@Mod` 默认包含 `CLIENT` 和 `DEDICATED_SERVER`，同一 Mod ID 可以有多个入口。`FMLJavaModLanguageProvider` 按当前物理侧过滤扫描结果，随后 `FMLModContainer` 才以类名加载筛选后的入口。共用入口先于单侧入口构造。
- FML `AutomaticEventSubscriber` 也在 `Class.forName` 前检查 Mod ID 与物理侧。客户端总线监听器应由客户端入口注册，或者在 `client` 包中以 `@EventBusSubscriber(value = Dist.CLIENT)` 自动注册。构造阶段和 Mod 总线的部分生命周期可并行执行，均不等同于存档线程。
- 26.3 的 `Level#isClientSide()` 区分逻辑侧。物理客户端的 `IntegratedServer` 与专用服的 `DedicatedServer` 都是 `MinecraftServer`。前者在客户端进程中承担服务端工作，后者由服务端启动器启动。两者均使用服务端保存和关闭流程。
- 普通专服通过 `PlayerList` 接入、保存并移除玩家。客户端断开和重连属于不同连接周期，真正的连接验收需要两侧的玩家加入或离开证据。客户端抵达标题屏幕只用于确认客户端启动。
- 原有 MDG `runClient`、`runServer` 使用 main 爆炸源集。`runGameTestServer` 使用开发夹具和独立 universe，通过原生 GameTest 启动、执行筛选并退出。生产验收另由 NeoForge 安装后的启动器从 `mods/` 加载 JAR，专服使用隔离的工作目录、世界和回环监听。

[NeoForge 物理侧与逻辑侧](https://docs.neoforged.net/docs/concepts/sides/) · [NeoForge 事件与生命周期](https://docs.neoforged.net/docs/concepts/events/) · [目标 NeoForge POM](https://maven.neoforged.net/releases/net/neoforged/neoforge/26.3.0.113/neoforge-26.3.0.113.pom) · [FML 12.0.12 源码](https://maven.neoforged.net/releases/net/neoforged/fancymodloader/loader/12.0.12/loader-12.0.12-sources.jar) · [ModDevGradle 2.0.148 源码](https://maven.neoforged.net/releases/net/neoforged/moddev-gradle/2.0.148/moddev-gradle-2.0.148-sources.jar)。Minecraft 签名来自本项目 Gradle 生成的 `minecraft-patched-26.3.0.113-sources.jar`。

## 方案选择

| 候选 | 取舍 |
| --- | --- |
| FML 多入口 + 独立 `client` 包 | 采用。复用 FML 12.0.12 的过滤及构造流程，保留公共入口与单个 Mod 描述。客户端构造器仅记录一条供验收使用的初始化日志。FML（LGPL-2.1-only）由 NeoForged 维护，无新增运行依赖，运行成本为一次构造与日志。 |
| 公共入口内按 Dist 分支 | 只适合公共 API 上的条件执行。公共类对客户端类的符号引用仍进入字节码、扩大类加载触点，且增加代码审查负担。本项目采用加载器在加载前过滤的方案。 |
| 独立 Gradle 客户端源集／附属 JAR | 隔离构建类路径需要重新组织生产包与开发运行绑定，增加多份配置及升级成本。当前单 Mod 及最小入口使用既有 main 源集和字节码检查。 |
| JUnit + 当前工具链已有的 ASM / NightConfig | 采用。ASM 9.11（BSD-3-Clause）结构化遍历真实类文件，NightConfig 3.9.0（LGPL-3.0）解析真实 TOML。沿用 NeoForge 提供的测试类路径，兼容 Java 25 classfile 与 Gradle 9.2.1，无新增声明依赖。只在构建测试时扫描小型 JAR。 |
| 源码字符串搜索、`jdeps`、新架构框架 | 字符串检查会把注释或普通文字误判为依赖，并受写法影响；`jdeps` 适合依赖盘点，还需独立核对注解与打包契约；新框架增加版本与维护负担。由本项目维护扫描规则，不复制第三方实现。 |

[ASM ClassRemapper](https://javadoc.io/doc/org.ow2.asm/asm-commons/9.11/org/objectweb/asm/commons/ClassRemapper.html) · [NightConfig](https://github.com/TheElectronWill/night-config) · [Gradle 9.2.1 Java testing](https://docs.gradle.org/9.2.1/userguide/java_testing.html)

## 边界与覆盖范围

- `LiminalDimension` 继续负责共用注册点。`client/LiminalDimensionClient` 只在物理客户端构造；生产包仍然只有一个 `liminal_dimension` Mod ID，显示名为 `未完全死亡`。
- 公共包及服务端基础类不得引用项目的 `client` 包、`net.minecraft.client`、`net.neoforged.neoforge.client` 或 `com.mojang.blaze3d`。客户端包内自动订阅者必须明确声明 `Dist.CLIENT`。客户端表现代码不得直接引用服务端 `SavedSnapshotStore`。
- M1-02 持久化仍由 `MinecraftServer`／`ServerLevel` 上的逻辑服务端线程打开和提交。原有线程拒绝用例沿用。服务端拥有权威状态，客户端静态字段不用于存档或服务端决策。生产入口没有创建快照或注册写盘处理器。
- JUnit 读取 `jar` 任务的实际产物。检查生产类和 main 编译输出逐项匹配、解析元数据和双方依赖范围、验证物理侧入口注解，并访问类签名、注解、指令、方法句柄等编译后类型引用。包前缀带分隔符，普通字符串和注释不触发依赖规则。
- 包约定检查不解析动态拼出的类名、资源驱动的类加载、第三方库内部引用及运行时字节码变换，也无法推断任意代码的逻辑线程归属。新增客户端专用命名空间时应核对规则。真实专服启动与 GameTest 线程断言补充这些覆盖边界。

## 验收记录

本任务在独立 Windows 工作树使用 Java 25，GitHub 使用仓库现有 Ubuntu 工作流。M1-03 的状态由本 PR 的实际记录和检查结果判定，等待项目所有者审核。

基线 `c9089f040013019ffd7b05bd8bafc4b61fbde878` 为 M1-02 合并提交，合并后的 [Ubuntu Build](https://github.com/Copule/liminal-dimension-mod/actions/runs/44537997459) 通过。

### Windows 本机

Windows 11 x64 26H2、NTFS、Microsoft OpenJDK 25.0.1+8。NeoForm 的资产下载工具沿用其 Java 21 工具链，Mod 编译、测试与 Minecraft 游戏均使用 Java 25。

| 检查 | 实际结果 |
| --- | --- |
| `gradlew.bat --offline --no-daemon --console=plain --continue build runGameTestServer` | 退出 0。JUnit 58/58、GameTest 8/8，均为 0 失败、0 错误、0 跳过。GameTest 含原有服务端线程拒绝、作用域、显式保存与故障保护。 |
| 生产 JAR 反向验证 | 定向测试退出 1。临时类的泛型签名引用 `net.minecraft.client.Minecraft`，类字面量引用项目客户端入口，两条都在失败 XML 中命中。移除探针后全量构建通过。 |
| 生产 JAR | SHA-256 `d863ea5a730b1c5b885f3df2bdc4989f8c9926ab784c58978a492af71a84bbeb`。共 10 个 Java 25 类，生产元数据和两入口通过检查，类清单与 main 一致。 |
| 真实专服启动、保存、停止及重启 | 使用 NeoForge 安装生成的 `win_args.txt` 和 `nogui` 启动，完整命令见本地证据。FML 两次均报告 `DEDICATED_SERVER in PROD`，加载同一 SHA-256 的 JAR，达到 `Done`，执行 `save-all flush` 和 `stop`，两次 JVM 退出 0。JVM 类加载日志只记录公共入口，重启后读回原版计分板探针值 42。 |
| 独立生产客户端与集成服 | 两个全新 JVM 启动均报告 `CLIENT in PROD` 和两条初始化日志。Demo 单人世界创建、保存退出，重启同一客户端目录后重新进入并保存。两个进程均退出 0，日志含集成服务端的登录、保存和关闭。 |

专服与 Demo 世界的两轮关服后均未生成 Mod 持久化命名空间。验收目录保存原始 Gradle／JVM 日志、退出状态、XML、命令、关键文件哈希及重入截图。EULA 由项目所有者明确确认，专服全程绑定 `127.0.0.1:25573`。完整的连接证据随 PR 验收记录提供。正版在线认证和双客户端场景需要相应客户端环境。

### 开发客户端与回环连接

本地验收以 `gradlew.bat --offline --no-daemon --console=plain -I <本地 init 脚本> runClient` 启动，Gradle 退出 0。FML 报告 `CLIENT in DEV` 并初始化两入口。首次连接实际抵达专服，由 26.3 默认开启的白名单拒绝。加入临时白名单的合成开发用户随后从 `127.0.0.1` 两次登录同一个生产专服进程，源端口不同；两侧日志与界面共同确认加入、断开、重连、再次断开。之后专服成功保存并退出 0，恢复在线认证设置及空白名单。本次回环用例使用本地开发身份。

### GitHub Ubuntu

沿用现有 `.github/workflows/build.yml`。工作流在 Ubuntu Runner 上执行 `./gradlew --no-daemon --console=plain --continue build runGameTestServer`，保留 JUnit 与 GameTest XML 为 `project-test-reports`。专服生命周期由本地生产环境验收，CI 沿用无需人工接受服务器 EULA 的原生 GameTest。

[M1-03 分支的 Build 运行记录](https://github.com/Copule/liminal-dimension-mod/actions?query=branch%3Afeat%2Fm1-03-side-isolation)
