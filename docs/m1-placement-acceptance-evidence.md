# 放置前隔离探针：成对证据核验器

这是 [PR #13](https://github.com/Copule/liminal-dimension-mod/pull/13) 独立实验的离线证据检查，不启动游戏，不读取玩家档或 ZIP，不修改任何证据，不代替实机验收，也不接入正式 Mod。Python 3.11+，无额外依赖：

```sh
python tools/placement_evidence/verify.py /path/to/private-evidence
python -m unittest discover -s tools/placement_evidence -v
```

## 先采集，再核验

只用两个从**同一已校验停机备份**独立复制的可丢弃服。每个服均为 Minecraft 26.3 / NeoForge 26.3.0.113 / Java 25、同一已核对的 #13 实验 JAR、开 JVM 实验旗标。确认专服、仅监听和连接 `127.0.0.1`、在线认证、强制安全档案和白名单、一个获准的正版测试身份；不得用离线、远程或正式存档代替。

1. **先采集两份完整停机起始清单。** 在每份未启动的实验副本的 `players/data`，枚举所有同级目录条目；逐个记录普通文件的实际文件名、字节数和 SHA-256。包括其他玩家、备份、临时/损坏备份等，不按所测身份过滤。该版本契约不支持子目录、链接、Windows junction 或其他非普通文件；遇到它们不可出具完整性声明。两份起始清单必须一致；测试身份的 `<UUID>.dat` 和 `<UUID>.dat_old` 都必须存在、非空且摘要不同（否则写入轮换可能被相同字节掩盖）。
2. **正向：装包。** 用服务端命令验证测试维度确实可加载；保留完整、独立、未经删改的 UTF-8 服务端日志。从已获准客户端尝试一次连接；必须看到在线鉴权、进入世界的放置及加入日志，然后正常退出和关服。采集完整关服后清单。
3. **反向：移包。** 仅在另一份副本移包；用命令确认相同探针维度已不可加载。用同一身份尝试一次连接，保留完整的服务端日志。断开后在本机控制台执行一次 `list`，保留零在线的结果，再干净停机。采集完整关服后清单。标准拒绝正文（含末尾句号）为：

   ```text
   Placement fixture blocked this connection before spawn: staged probe unavailable.
   ```

4. **记录上下文与摘要。** 本工具会核对目标版本日志中同一名称和 UUID 的在线鉴权、正向放置与加入、反向配置阶段的精确断开原因、反向无放置/加入记录及之后零在线，也会逐项对比停机清单。请另外人工核对旗标、维度命令与实际返回、安装的 JAR 和监听/连接设置；这些事实由捕获人员写入清单，工具无法从保存后的数据独立证明。建议保留客户端画面作本地旁证，不作为输入或提交。两个服必须都正常停机且退出码为 0。

## 私有输入格式（schema 1）

目录下只有下列七个文件会被读取；不必把实际存档放在此处。所有 SHA-256 均为 64 字符小写十六进制，文件摘要取**最终的原始文件字节**（包括换行），不能对 JSON 重新序列化后再算。先写日志与清单并计算其摘要，最后填 `evidence.json`。不上传、提交或共享任何原始输入，它们含账号标识。证据目录须稳定，禁止正在写入、链接路径及并发替换。

| 文件 | 结构 |
| --- | --- |
| `positive-server.log`、`negative-server.log` | 各捕获一次完整服启动 → 客户端尝试一次 → 干净关服；UTF-8，保留 26.3 原有英文服务端正文、线程/等级及 NeoForge 或原版时间前缀，不剪接，不混入另一轮。保持 `log-ips=true`，正向放置行须显示 IPv4 回环地址。 |
| `positive-before.json`、`positive-after.json`、`negative-before.json`、`negative-after.json` | `{"trial":"positive","phase":"before","entries":[{"name":"...","size":1,"sha256":"..."}]}`，每条普通文件唯一、不按 UUID 过滤；`trial` 与 `phase` 必须与文件名一致。列表顺序无关。 |
| `evidence.json` | 下例，全部键均必填，替换示例占位摘要和身份。 |

```json
{
  "schema": 1,
  "fixture_commit": "09d2019c951c1ad66f45e066054e548d91bdcdf8",
  "fixture_jar_sha256": "<hash of the same experimental JAR installed in both runs>",
  "minecraft": "26.3",
  "neoforge": "26.3.0.113",
  "java": 25,
  "profile": "TestProfile",
  "uuid": "00000000-0000-4000-8000-000000000001",
  "context": {
    "dedicated": true,
    "online_mode": true,
    "secure_profile": true,
    "whitelist": true,
    "authorized_count": 1,
    "disposable_copy": true,
    "bind": "127.0.0.1",
    "client_address": "127.0.0.1",
    "fixture_enabled": true,
    "full_logs": true,
    "complete_stopped_inventories": true
  },
  "trials": {
    "positive": {
      "pack_present": true,
      "probe_loaded": true,
      "exit_code": 0,
      "stopped_cleanly": true,
      "log_sha256": "<positive-server.log hash>",
      "before_sha256": "<positive-before.json hash>",
      "after_sha256": "<positive-after.json hash>"
    },
    "negative": {
      "pack_present": false,
      "probe_loaded": false,
      "exit_code": 0,
      "stopped_cleanly": true,
      "log_sha256": "<negative-server.log hash>",
      "before_sha256": "<negative-before.json hash>",
      "after_sha256": "<negative-after.json hash>"
    }
  }
}
```

## 结果与限制

- **0／CONSISTENT_EVIDENCE**：所提交的成对证据一致。正向必须单次成功放置并加入；反向必须在已经完成同一身份在线鉴权后由实验探针精确拒绝，并且拒绝后零在线，完整停机玩家档同级清单也一致。正向后档允许正常保存变化；它的起始清单仍须和反向完全一致。
- **1／FAIL**：证据中看到明确反例，例如反向出现放置、加入、拒绝原因不符、仍有在线玩家，或反向停机清单中出现字节变化、新文件或删除。
- **2／UNVERIFIED**：缺文件、摘要不符、上下文不明、格式不支持、日志不完整或顺序不符。两份不同的输入文件不是证明它们独立采集。摘要绑定只发现输入漂移，不防证据生产者遗漏、构造或篡改后重算，也不证明停机与登记字段的真实性。旧版、其他日志规则或不稳定目录须人工审查。若认证、配置阶段断开、在线人数、启停或放置等关键行看似相关但格式未知，不给通过；放置须有有效回环端口与三个有限数值坐标。结果只含固定状态码，不回显身份、路径、维度、坐标或原始日志。
- 未审计其他目录、磁盘瞬态写入、元数据或文件完全相同的原地重写；不能推导游戏外的副作用、不定次重连、联机并发或正式存档保护。不要因为验证器能处理合成样本，就声称 #13 已真实通过。

## 选型

复用 [M1-05](m1-05-dimension-removal.md) 的全量文件路径/大小/摘要比对思路，但不复用会启动专服、读档及恢复内容的实验运行器。通过 [Mojang 26.3 官方服务端包](https://piston-data.mojang.com/v1/objects/33680f5f2ac32864d6d7cf5e56a705fdb3e05f4c/server.jar) 核对 `ServerLoginPacketListenerImpl`、`ServerConfigurationPacketListenerImpl`、`PlayerList` 的日志常量与 `en_us` 列表返回消息；不使用宽泛的“lost connection”匹配。候选实时抓取 26.3/NeoForge 队列或加日志钩子会扩大接线与认证面，且与这次只交付核验器不符。改用 Python [标准库 JSON](https://docs.python.org/3/library/json.html)、[SHA-256](https://docs.python.org/3/library/hashlib.html) 与固定版本日志判定，不复制第三方代码；无新许可、生产加载或服务端开销。改版应重新核对真实日志格式。合成测试只在临时目录验证构造证据；未读取或启动真实存档。
