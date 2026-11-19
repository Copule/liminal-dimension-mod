# M0 engineering bootstrap

The 《未完全死亡》 mod is a single Java module. NeoForge discovers it through
`META-INF/neoforge.mods.toml` and constructs the class annotated with
`@Mod("liminal_dimension")`. The constructor writes the INFO message
`Liminal Dimension initialized (liminal_dimension).` on either physical side.
This is the entire runtime behavior at M0.

## Build baseline

| Component | Pinned version |
| --- | --- |
| Minecraft Java Edition | 26.3 |
| Java toolchain | 25 |
| NeoForge | 26.3.0.113 |
| ModDevGradle | 2.0.148 |
| Gradle Wrapper | 9.2.1 (bin) |

The build imports the official template at commit
[`324165e`](https://github.com/NeoForgeMDKs/MDK-26.3-ModDevGradle/tree/324165e73bc50e912270863278d64827bde71ad7).
The Wrapper and toolchain versions come from that revision. The NeoForge
coordinate is also published in the [official Maven repository](https://maven.neoforged.net/releases/net/neoforged/neoforge/26.3.0.113/neoforge-26.3.0.113.pom).
The template license is in `TEMPLATE_LICENSE.txt`.

## Project layout

| Path | Responsibility |
| --- | --- |
| `gradle.properties` | Pins the build dependency versions and supplies the mod ID, display name, license, group and development version. |
| `build.gradle` | Binds the main source set to the mod, defines the development runs and expands the metadata template into the JAR. |
| `src/main/templates/META-INF/neoforge.mods.toml` | Declares the mod identity and the Minecraft and NeoForge loader dependencies. |
| `src/main/java/io/github/copule/liminaldimension/LiminalDimension.java` | Common entry point. Keep its annotation ID in sync with `mod_id`. |
| `.github/workflows/build.yml` | Runs the Wrapper build on Linux with Temurin 25 for pushes and pull requests. |

## M0-06 display name

The existing ModDevGradle template expands `mod_name` from `gradle.properties`
into the NeoForge metadata. A shared property keeps the Mods list name and
description in sync. Unicode escapes let Gradle's Java properties reader
load the Chinese name. The pinned toolchain already supports this approach.
[NeoForge mod metadata](https://docs.neoforged.net/docs/gettingstarted/modfiles/)
and [Gradle 9.2.1 properties loading](https://github.com/gradle/gradle/blob/v9.2.1/platforms/core-runtime/base-services/src/main/java/org/gradle/util/internal/GUtil.java#L192-L222).

## Local verification

With a JDK 25 selected through `JAVA_HOME`, run the following in PowerShell
from the repository root. On Linux and macOS use `./gradlew` in place of
`.\gradlew.bat`.

```powershell
.\gradlew.bat --version
.\gradlew.bat build
Get-ChildItem build/libs/*.jar
.\gradlew.bat runClient
```

The mod JAR should contain the compiled entry point and the expanded
`META-INF/neoforge.mods.toml`. To verify a development launch, check
`run/logs/latest.log` for the constructor message, wait for the Minecraft
title screen, then quit from the client. The development run loads the main
source set. JAR inspection verifies packaging separately.

### M0 verification record (2026-11-18)

| Executed check | Windows 11 x64 result |
| --- | --- |
| `java -version`, `javac -version` (using the selected JDK's bin directory) | Microsoft OpenJDK 25.0.1+8-LTS, javac 25.0.1. This machine's Minecraft launcher supplied the JDK under `runtime/java-runtime-epsilon`. |
| `.\gradlew.bat --version` | Gradle 9.2.1 on Java 25.0.1. |
| `.\gradlew.bat --no-daemon --console=plain build` | First download of `jspecify-1.0.1.jar` failed with TLS `bad_record_mac`. Retrying the same command succeeded in 3m 26s, with five tasks executed. `:test` reported `NO-SOURCE`. |
| Python `zipfile` and `tomllib` inspection of `build/libs/liminal_dimension-0.1.0-SNAPSHOT.jar` | Verified one class (major version 69), expanded mod ID, name and version, and dependency ranges `[26.3]` and `[26.3.0.113,)`. SHA-256: `eef0c7d6f1a0a91c8bbc5e1881f8e7244bd2b189eefb67c9a91a6166def83dee`. |
| `.\gradlew.bat --no-daemon --console=plain runClient` | The NeoForge 26.3 window reached the first-launch welcome screen. The loader listed `Liminal Dimension 0.1.0-SNAPSHOT (liminal_dimension)` and the mod constructor logged `Liminal Dimension initialized (liminal_dimension).`. Closing the window shut down FML normally and Gradle reported `BUILD SUCCESSFUL`. |

### M0 packaged JAR acceptance (2026-11-18)

Tested `build/libs/liminal_dimension-0.1.0-SNAPSHOT.jar` from commit
`e56ad5c808a80d4a7e1dca277ad5c377ef02e976` in separate Minecraft 26.3 /
NeoForge 26.3.0.113 installations on Windows with Microsoft OpenJDK 25.0.1.
Both installed copies matched the SHA-256 recorded above for that earlier
artifact, `eef0c7d6f1a0a91c8bbc5e1881f8e7244bd2b189eefb67c9a91a6166def83dee`.
The dedicated-server result applies to that artifact. The loader
identified the file as `jar(mods/liminal_dimension-0.1.0-SNAPSHOT.jar)` and
the mod logged its initialization on both sides.

| Check | Result | Local evidence file |
| --- | --- | --- |
| Standalone client | Passed. FML reported `CLIENT in PROD`. The client started in local demo mode and the Mods screen showed Liminal Dimension, mod ID `liminal_dimension` and version `0.1.0-SNAPSHOT`. | `logs/client-latest.log`, `Mods详情页.png` |
| Dedicated server | Passed on retry. FML reported `DEDICATED_SERVER in PROD`. The server reached `Done (2.981s)!`, saved the test world after `stop` and exited normally. The first attempt initialized the mod and then failed when the sandbox denied a loopback connection. | `logs/server-latest.log`, `logs/server-first-latest.log` |

The local evidence directory is `M0-验收/2026-11-18-成品JAR/` at the repository
root. It contains `中文验收报告.md`, `SHA256.txt`, the screenshot, raw logs and
the recorded launch commands. The archive is kept outside Git.

### M0-07 single-player world acceptance (2026-11-18)

The standalone Windows x64 client ran Minecraft Java Edition 26.3,
NeoForge 26.3.0.113 and Microsoft OpenJDK 25.0.1 in Demo mode.
The tested `liminal_dimension-0.1.0-SNAPSHOT.jar` was built from
`4e4cc6be6cad95fcdd41ed4a962a2e070e774c01`. Its SHA-256 is
`362554492834249062d4dd31e18320db27d5eac80600fe45677c634bee243875`.

| Check | Result |
| --- | --- |
| Build and standalone client | The recorded Gradle build passed. The standalone client loaded the packaged JAR, and the Mods screen showed `未完全死亡` with mod ID `liminal_dimension`. |
| Single-player Demo world | Created a world from an empty saves directory, entered it, moved, saved and reloaded the same world. The reload restored the position reached after moving. |
| Integrated Server | All three sessions saved and stopped normally. |

The M0-08 archive uses the existing reports, hashes and logs for these
results. The evidence is retained locally outside Git. Paths below are
relative to the original repository root.

| Local directory | Evidence |
| --- | --- |
| `M0-验收/2026-11-18-M0-07-单人世界/` | `中文验收报告.md`, `artifacts/SHA256.txt`, `artifacts/neoforge.mods.toml`, `logs/build.log`, `logs/integrated-server.log`, client logs and screenshots. |
| `M0-验收/2026-11-18-成品JAR/` | `中文验收报告.md`, `SHA256.txt` and `logs/server-latest.log` for the earlier dedicated-server acceptance. |

### M0 remaining verification

- Dedicated-server acceptance with the M0-07 JAR, including client
  connection and two-client multiplayer.
- Full-edition account sign-in and the full-edition single-player
  new-world creation flow.
- Automated runtime tests. The M0-07 Gradle build recorded `:test NO-SOURCE`.
- Long-running stability.
