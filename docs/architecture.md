# M0 engineering bootstrap

Liminal Dimension is a single Java module. NeoForge discovers the mod through
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
Both installed copies matched the JAR's SHA-256 recorded above. The loader
identified the file as `jar(mods/liminal_dimension-0.1.0-SNAPSHOT.jar)` and
the mod logged its initialization on both sides.

| Check | Result | Local evidence file |
| --- | --- | --- |
| Standalone client | Passed. FML reported `CLIENT in PROD`. The client started in local demo mode and the Mods screen showed Liminal Dimension, mod ID `liminal_dimension` and version `0.1.0-SNAPSHOT`. | `logs/client-latest.log`, `Mods详情页.png` |
| Dedicated server | Passed on retry. FML reported `DEDICATED_SERVER in PROD`. The server reached `Done (2.981s)!`, saved the test world after `stop` and exited normally. The first attempt initialized the mod and then failed when the sandbox denied a loopback connection. | `logs/server-latest.log`, `logs/server-first-latest.log` |

The local evidence directory is `M0-验收/2026-11-18-成品JAR/` at the repository
root. It contains `中文验收报告.md`, `SHA256.txt`, the screenshot, raw logs and
the recorded launch commands. The archive is kept outside Git.

Single-player world playtesting and joining the dedicated server from a
client remain pending.
