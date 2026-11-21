# M1-06 生产客户端跨维度与联机同步验证

基线 `c70e0d37115a8860c41f1e911aa4e9bc7524e73d`，Minecraft Java 26.3、NeoForge 26.3.0.113、Java 25。复用 M1-04 的原始测试包，维度键为 `liminal_dimension_poc:probe`。工作范围是技术验证。文档在独立终审和所有者批准后由 [PR #8](https://github.com/Copule/liminal-dimension-mod/pull/8) 合入 `main`（`bcb15d7`）。[main Build #40](https://github.com/Copule/liminal-dimension-mod/actions/runs/44732989145) 的项目测试和证据上传通过，不替代下列实机补验。[M1-05 PR #7](https://github.com/Copule/liminal-dimension-mod/pull/7) · [main Build #35](https://github.com/Copule/liminal-dimension-mod/actions/runs/44705613903) · [探针数据包](../test-data/m1-04-dimension-poc/)

## 调研与选型

在工作树、现有打包测试、M1-04 数据包和 M1-05 终审 B-1 修复记录中核对了可复用设施。以 ModDevGradle 生成的 `minecraft-patched-26.3.0.113-sources.jar` 为目标源码，SHA-256 为 `8c9e1086bbd1f46e2c7b7fee6b9104c096cc54c2cbe8a5077396ed8c2df7e7a0`。查阅了 Mojang 发布说明、NeoForged 目标迁移文档和 Maven artifact。M1-05 终审接受已有专服实验和回归，要求堵住移包阶段的假阳性。M1-06 将客户端画面、服务端状态、停服 NBT 和重启恢复分别取证。[Mojang 26.3](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3) · [NeoForge 26.3 迁移说明](https://docs.neoforged.net/primer/docs/26.3/) · [NeoForge 26.3.0.113 源码](https://maven.neoforged.net/releases/net/neoforged/neoforge/26.3.0.113/neoforge-26.3.0.113-sources.jar) · [B-1 修复](https://github.com/Copule/liminal-dimension-mod/commit/9c07cd8d40bf736834caaca27695cd4b99753202)

| 候选 | 结论 |
| --- | --- |
| 原版 `execute in`、`tp` 与 M1-04 数据包 | 采用。目标版本源码和真实客户端都验证了命令。由 Minecraft 和 NeoForge 维护，与现有 JAR 同版。测试时才执行命令，无新增依赖、常驻监听或逐刻开销。 |
| 直接集成 `TeleportTransition` 或 NeoForge 维度事件 | 仅作源码参考。会引入项目要自行维护的代码、事件顺序和版本兼容问题，现有命令可覆盖本阶段的切换路径。 |
| 第三方传送／动态维度框架 | 本阶段无集成收益。安装会扩大权限、信任和版本兼容面，附带运行和维护成本。未选用外部实现，也没有复制第三方代码或新增许可／署名要求。 |

## 目标源码路径

- `ExecuteCommand` 的 `in` 分支通过 `DimensionArgument` 找到目标 `ServerLevel`，并替换执行上下文。随后位置版 `TeleportCommand` 将该 level 和显式坐标传给 `Entity#teleportTo`，玩家跨维度路径进入 `ServerPlayer#teleport(TeleportTransition)`。该命令路径与实体目标的 `tp` 重载不同。使用绝对坐标可避免上下文的相对位置及坐标缩放影响。
- `ServerConfigurationPacketListenerImpl` 在配置阶段排队同步注册表，NeoForge 的早期任务先于原版标签同步。`SynchronizeRegistriesTask` 发送网络可同步注册表的条目与标签，`ClientConfigurationPacketListenerImpl` 用 `RegistryDataCollector` 生成游戏阶段的注册表访问。对本地内存连接，客户端也会使用从集成服筛选出的注册表，所以 A 的结果不能外推至 B 的 TCP/认证链路。
- `PlayerList#placeNewPlayer` 在登录包发送可用 level 键与 `CommonPlayerSpawnInfo`。`ServerPlayer#teleport` 的跨维度分支经过 NeoForge travel hook，发送携带目标维度键与维度类型 holder 的 `ClientboundRespawnPacket`，从旧 level 移除玩家，切换 `ServerLevel`、发送位置并重新加入，随后同步能力、level 信息、效果和玩家附件。此处复用 respawn 数据包，不需要触发死亡。维度 ID 标识 level，维度类型描述该 level 的属性。探针使用原版主世界的维度类型，仍有独立的 level 键。
- `ClientPacketListener#handleRespawn` 在键变化时创建新的 `ClientLevel`，将其设为当前世界，并替换本地玩家与相机绑定。旧 level 的调试订阅被解除，渲染器、提取器和粒子系统通过 `Minecraft#setLevel` 切换目标，旧实例由 JVM 生命周期回收。位置包由 `handleMovePlayer` 应用并确认，区块与光照由 `handleLevelChunkWithLight` 应用。F3 位置条目直接读取当前客户端 `minecraft.level.dimension()`，可与服务端玩家 `Dimension` 对照。
- `PlayerList#remove` 在正常断线时保存玩家。`PlayerDataStorage` 写压缩 NBT，以临时文件替换当前档并保留旧档。玩家的 `Dimension`、`Pos`、`Rotation` 分开解码。专服重连时，`PrepareSpawnTask` 先选择维度、位置和旋转，加载目标区块，然后构造玩家、加载档案，按选择的位置和角度放置玩家并交给 `PlayerList`。

## 复验命令

使用独立 26.3 / 26.3.0.113 客户端和生产 `liminal_dimension-0.1.0-SNAPSHOT.jar`。先创建可使用命令的隔离单人世界，保存并关服，把原 `test-data/m1-04-dimension-poc` 目录放进新世界的 `datapacks/`，再加载世界并保留实验性世界确认时的备份。专服可在首启前放入同一原始包。以下命令从有权限的测试客户端执行。专服由控制台执行时，先用 `execute as <已授权的 Java 玩家名> at @s run …` 建立玩家上下文，避免给测试身份长期操作员权限。

```mcfunction
/gamemode creative
/execute in liminal_dimension_poc:probe run forceload add 0 0
/execute in liminal_dimension_poc:probe run fill 1 95 1 13 95 13 smooth_stone
/execute in liminal_dimension_poc:probe run tp @s 7.5 96 7.5 -90 25
/data get entity @s Dimension
/data get entity @s Pos
/data get entity @s Rotation
```

等目标区块加载后再建平台。用 F3 的位置条目核对维度键、坐标和朝向，用 F2 保存截图。记录实际走动及方块交互的变化。返回用 `/execute in minecraft:overworld run tp @s <已确认安全的主世界 X Y Z> <yaw> <pitch>`。本轮 A 使用初始登录位置 `-55.5 73 64.5 0 0`。保存退出，再独立启动客户端并截图核对。

### B/C 正版联机及重启恢复

新建专服实例，使用官方 NeoForge 安装后的参数文件启动同一生产 JAR。获得本次实例的 [EULA](https://www.minecraft.net/en-us/eula) 确认和授权玩家后，设置 `server-ip=127.0.0.1`、`online-mode=true`、`enforce-secure-profile=true`、`white-list=true`、`enforce-whitelist=true`，并设置 `enable-rcon=false`、`enable-query=false`、`management-server-enabled=false`。只从专服控制台加入指定测试身份。客户端从合法登录的官方启动器配置独立游戏目录和同版本 NeoForge，仅连本机指定端口。记录客户端及专服日志。比对主世界和探针内的 F3 与服务端实时 `Dimension/Pos/Rotation`，检查区块、基本交互、玩家状态、回程、同次专服在线时的断开重连。具备第二个独立授权身份时，再测两人分处探针与主世界。

C 让玩家安全下线于探针。控制台执行 `save-all flush` 和 `stop`，等待 JVM 正常退出。读取 26.3 世界 `players/data/<UUID>.dat` 的压缩 NBT，记录 `Dimension`、`Pos`、`Rotation`、`Health`、`playerGameType` 和相关 `abilities`，为玩家档、`level.dat`、世界生成设置、探针区块和数据包建立逐文件 SHA-256，归档整个停服世界，保留 ZIP 的独立 SHA-256。保留数据包重启，用原身份登录并再次比对 F3、服务端实时状态和退出后的玩家档。使用同一时间线关联四层证据，完整保留 Windows 和 Ubuntu 原有门禁。

### D 缺失维度风险

`PrepareSpawnTask` 在保存维度查不到时先选世界 `spawn` 指向的有效维度，找不到才选主世界。`Pos` 与 `Rotation` 各自独立回退：可解码的坐标仍被沿用，即使朝向缺失；没有可解码的位置才触发 `PlayerSpawnFinder.findSpawn`。因此成功登录仍可能被放在地下、液体中、半空或其他不安全的位置。登录后的正常自动存档或退出保存可能把当前维度与位置写回玩家档。重新装包可使维度定义恢复。若当前玩家档已覆盖了原维度的位置，恢复能力仍取决于完整停服备份。

先完成 A/B/C 再执行动态 D。关服后建立只读全量备份及哈希清单，在新目录解压校验并分别留存原件和实验副本。在副本停服移包后启动，等同一授权身份登录，分别记录回退维度、坐标与朝向、地形是否危险、生命值与游戏模式。保留副本登录前、正常退出后以及恢复包后再次登录的三组档案和日志。这一轮可用创造模式降低人物损失风险，但需另记录落点在生存模式下是否有危险，不把创造模式的生命值当作安全回退证据。若文件已写回，保留现场，从未改动的停服备份另开回滚对照。避免对唯一存档或个人世界执行移包，禁止篡改正式玩家档案。

## 本轮验收

生产 JAR 的 SHA-256 为 `d863ea5a730b1c5b885f3df2bdc4989f8c9926ab784c58978a492af71a84bbeb`。归档文件列表和每项内容与已验证的 M1-05 JAR 一致。Windows JUnit 59/59、项目 GameTest 8/8，其中生产 JAR 测试覆盖包内容和物理侧。另运行 M1-05 的 Python 反向验证 11/11。Ubuntu 由本 PR 既有 Build 工作流验收。

A 使用生产 Demo 客户端，创建新世界后停服备份，仅为该隔离世界启用 `allowCommands`，保持 LAN 关闭。客户端 F3 读出 `liminal_dimension_poc:probe`、坐标 `(7.5, 96, 7.5)` 和朝向 `(-90°, 25°)`，服务端也读出同一维度键。客户端实际渲染了安全平台，完成手动破坏方块和第三人称切换，再返回主世界并保存。关服玩家档记录了 `minecraft:overworld`、`(-55.5, 73, 64.5)`、`(0°, 0°)`、生命值 20、创造模式。重启后客户端恢复同一主世界位置，可再次传入原探针平台，首轮手动打掉的方块仍为空。

在重启后的探针平台上，玩家手动打掉脚下方块，重力使 Y 从 96 变为 94，玻璃方块承接玩家，客户端和集成服均读出 `(5.5, 94, 4.5)`。连续行走的自动输入未产生可确认位移。补验可在原 A 停服备份的副本中从 `(3.5, 96, 9.5)` 朝东沿平台按住 W 约一秒，比较 F3 起点、终点和停机档。Demo 客户端日志包含 Realms 认证提示，单人世界链路已独立验证。

### B 和 C 受认证专服实测

所有者接受本次实验实例的 EULA。独立生产专服仅监听 `127.0.0.1:25576`，保持在线认证、资料认证和白名单，名单内只有本次授权的 Java 身份。客户端通过官方启动器的独立 NeoForge 配置加载同一成品 JAR。两端分别记录 `CLIENT in PROD` 和 `DEDICATED_SERVER in PROD`。

- B 首次连接到 `minecraft:overworld` 的 `(-0.5, 80, 1.5)`。控制台调用原版命令将同一玩家送到探针 `(7.5, 120, 7.5)`、朝向 `(-90°, 25°)`。客户端 F3 与服务端查询一致，平台正常加载。客户端手动打掉 `(10, 119, 7)` 的平滑石，服务端方块查询确认由平滑石变为空气。返回主世界后，正常断线并在同一专服进程重新通过认证，客户端回到原主世界位置。
- C 使用另一处探针平台坐标 `(5.5, 120, 4.5)`、朝向 `(-90°, 15°)`。正常下线、`save-all flush`、`stop` 后，档案显示探针维度、原坐标和朝向、生命值 20、创造模式。保留数据包并重新启动专服及客户端，原身份登录时无需传送即恢复相同维度和玩家状态。B 的方块交互留下的孔洞也保留。重启前后均采集了玩家 NBT、世界全量归档及 SHA-256。

### D 移包及恢复实测

在 B/C 完成后，从已停服的 C 全量归档复制了世界。解包逐项复算 53 个文件的 SHA-256，在新副本中移走数据包。启动后原版命令确认探针维度不存在，登录前的玩家档与 C 关服档案字节一致。

| 检查点 | 观测 |
| --- | --- |
| 缺包登录 | 服务端记录在旧坐标 `(5.5, 120, 4.5)` 放置玩家。实际维度回退到主世界，原坐标下方两格均为空气。 |
| 落点 | 玩家下落 34 格至 `(5.5, 86, 4.5)`。客户端与服务端一致，落脚方块通过 `#minecraft:leaves` 检查。该轮保留创造模式和无敌能力，生命值为 20。后续生存模式补验见下。 |
| 档案写回 | 在线抽样时当前档仍为原探针位置，正常断开后当前档变成主世界的稳定落点。此时 `.dat_old` 仍保存原探针位置。 |
| 停服装回同一数据包后 | 探针维度、原平台、孔洞及强制加载票据恢复。玩家再次认证登录仍在主世界 `(5.5, 86, 4.5)`。正常退出后，当前档与 `.dat_old` 均记录主世界落点。 |

D 结束后，C 原世界的 53 个文件仍与停机清单逐项一致。该结果证明默认回退可以成功接入，同时产生危险落点及原位置覆盖。装回数据包可以恢复该探针维度和区块。要保留受影响的玩家位置，应以移包前的停服备份为依据。双客户端并发待验，本轮只有一个独立授权身份。


## 待补实机验收

| 检查 | 当前状态 | 通过证据 |
| --- | --- | --- |
| A：持续步行 | Windows 隔离客户端中，人工按 W 在同一维度平地向前移动约 5.177 格，保存位置与 F3 一致 | 在隔离探针平台上持续步行，记录客户端 F3 起止位置与服务端坐标，确认水平位移且维度不变。 |
| B：双客户端并发 | 单个授权身份可联机、切换维度及重连；双人未测 | 两个独立且明确获准的正版身份同时连入隔离专服，分别留在主世界与探针，逐一对照客户端维度及服务端状态，确认一人的切换不改变另一人的维度。保持在线认证与白名单。 |
| D：生存坠落 | 已用真实受认证客户端复现：回退主世界后下坠 34 格并死亡 | 仅在完整停服备份校验后的可丢弃副本中进行，记录回退前后维度、坐标、游戏模式、生命值和日志；完成后复核原备份的哈希不变。即使未掉血，也不能仅据此宣告回退安全。 |

`.dat_old` 不是可依赖的回滚介质：当前 `.dat` 正常可读时不会自动改读旧档；自动存档再加正常断线也可能在同一次会话中覆盖两份位置记录。缺包实验必须以校验过的停服备份为恢复依据，不在正式服、唯一存档或原件上执行。这里仅记录技术风险和复验条件，不决定正式维度落点、死亡规则或存档恢复策略。


### D 生存模式补验（2026-11-21）

在 Windows 上，使用已授权的同一正版客户端和仅监听本机回环的在线认证专服。只用校验过的新存档副本和同一成品 JAR，版本仍为 Minecraft 26.3、NeoForge 26.3.0.113。只在实验世界关闭自然恢复，以便读出结果。

- **装包基线：** F3 与关服玩家档一致：`liminal_dimension_poc:probe`，`(5.5, 120, 4.5)`，生存模式，生命值 20，无无敌、无飞行。正常关服后校验了全量备份的 53 个文件。
- **移包重连：** 先证实探针维度未注册。相同身份通过认证，在 02:35:19 UTC 以原坐标进入主世界，02:35:21 UTC 服务端记录高处坠落死亡。客户端死亡画面、服务端查询与关服玩家档相符：`minecraft:overworld`，`(5.5, 86, 4.5)`，生命值 0，生存模式且无无敌、无飞行。
- **范围：** 实测下坠 34 格并死亡；未测得原始伤害数值，不外推到其他存档。D 未执行死亡后重生或装回数据包后的恢复；持续按 W 见下，双账号并发仍暂缓。两个阶段均正常保存、停服，原 C 世界的 53 个文件与原清单逐项一致。含账号信息的原始日志和截图未入库。

### A 持续步行补验（2026-11-21）

Windows 生产 Demo 客户端，隔离副本。首次短平台人工尝试跌入虚空，复活坐标不计作步行终点。第二次仅在测试副本增设连续技术直道与下方承接层。人工按 W，起终点均在 `liminal_dimension_poc:probe`；期间没有传送命令或合成移动按键。F3 起点 `(7.500, 96.000, 7.500)`（02:56:52 UTC），终点 `(12.677, 96.000, 7.500)`（约 02:57:58 UTC）。正常保存退出，关服档案 X 为 `12.676862244947651`，Y 和 Z 不变，水平净位移约 5.177 格。朝向略有变化，按键时长未计量。原始 A03 归档的 52 个文件复核无改动。该结果只覆盖单客户端探针平台持续步行，不外推为双账号联机通过。
