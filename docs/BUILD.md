# 构建与验证

## 1. P0 的实际边界

本地零 Android SDK 是可行的，但 **APK 编译仍需要云端 Android SDK**。本地 JVM 开发也不等于零依赖：没有缓存时，Gradle、Kotlin、测试库都要下载。

实际执行结果：本地只做 Python/Bash/Ruby 静态检查，不启动 Gradle；编译、单测、lint 和打包全部在 GitHub Actions 完成，已于 run `35848681022` 全部通过。

构建采用两个独立 Gradle build：

```text
根 settings.gradle.kts → :app
                        includeBuild("sim-core")
sim-core/settings.gradle.kts → 独立 JVM 工程
```

App 使用 `implementation("dev.ratemock:sim-core:0.1.0")`，由 composite build 自动替换为本地源码。只测内核时必须用 `./gradlew -p sim-core test`，不在根 Android 工程中运行 `:sim-core:test`。内核构建脚本不加载 AGP，也不查询 Android SDK。

## 2. 固定版本

这是经官方兼容表核对的一组固定版本，不是“自动追踪最新版本”。

| 组件 | 当前值 |
|---|---|
| Gradle | 9.7.1 |
| Android Gradle Plugin | 9.3.1 |
| Kotlin / Compose Compiler 插件 | 2.4.20 |
| JVM 字节码目标 | 17 |
| CI 内核测试 JDK | 17 和 21 |
| CI Android JDK | 17 |
| compileSdk / targetSdk / minSdk | 36 / 36 / 26 |
| Android Build Tools | 36.0.0 |
| Compose BOM | 2025.04.01 |
| Activity Compose | 1.10.1 |
| JUnit BOM | 5.12.2 |

依赖集中在 `gradle/libs.versions.toml`。不配置 JDK 自动下载；输出统一为 Java 17。Gradle Wrapper 和发行包的官方校验值见 `THIRD_PARTY_NOTICES.md`。

AGP 9.3.0 的 compatibility table 要求 Gradle ≥9.5.0、Build Tools 36.0.0、JDK 17；API 36 是当前 CI 可用的平台目标。Kotlin 官方兼容表列 KGP 2.4.20 fully-supported 至 Gradle 9.7.0；**Gradle 9.7.1 已经过实际云端构建验证可用**，但仍不在该文档的明确列表内。AGP 9 已启用 built-in Kotlin，app 只 apply Compose Compiler plugin，不 apply `org.jetbrains.kotlin.android`。

核对资料（2026-09-23）：

- `https://developer.android.com/build/releases/agp-9-3-0-release-notes`
- `https://developer.android.com/build/migrate-to-built-in-kotlin`
- `https://kotlinlang.org/docs/gradle-configure-project.html`
- `https://gradle.org/release-checksums/`
- `https://docs.gradle.org/current/userguide/composite_builds.html`

## 3. GitHub Actions 主通道

配置文件：`.github/workflows/android.yml`。

1. `core` job 在 JDK 17/21 分别运行静态检查和 14 个内核测试；测试命令移除 Android SDK 环境变量。
2. 两个内核测试 job 均成功后，`android` job 在 Ubuntu 24.04 runner 上安装 Android API 36 和 Build Tools 36.0.0。
3. 运行 `:app:lintDebug :app:assembleDebug`。
4. 上传 debug APK、SHA-256、JUnit 报告和 lint 报告，保留 7 天。

分支触发范围为 `master`；仅文档变动不会自动触发，仍可手动运行。Actions 依赖固定到完整提交 SHA，权限为 `contents: read`，不发布 Release、不推送代码。

远程仓库为公开仓 [`lxhtt/SchoolRunningSimulator`](https://github.com/lxhtt/SchoolRunningSimulator)，本地 Git 已初始化，`origin` 指向该仓库，`master` 已推送并跟踪 `origin/master`。已获用户授权执行推送与触发 Actions。

已完成的运行：

| Run | 结果 | 说明 |
|---|---|---|
| [`35842081920`](https://github.com/lxhtt/SchoolRunningSimulator/actions/runs/35842081920) | 失败 | JDK 17/21 core job 成功；android job 因 `platforms;android-37` 在 CI stable channel 不存在而失败 |
| [`35848681022`](https://github.com/lxhtt/SchoolRunningSimulator/actions/runs/35848681022) | **成功** | 三个 job 全绿：JDK 17（42s）、JDK 21（47s）、Lint 与 debug APK（2m6s） |

单测实测 `tests=14 failures=0 errors=0 skipped=0`。APK 大小 9,389,491 字节，SHA-256 `13f385a59b0373cab196d0fc449a37b0cef812477050a8577f383b3ebb40864c`，本地校验与 `SHA256SUMS` 一致。

### 安装

下载 Artifact ZIP，解压后在手机文件管理器中打开 `app-debug.apk`，按系统提示为该文件管理器授权安装；不必安装 adb。下载 APK 仍然消耗手机或电脑的网络流量。

本次已将 `ratemock-debug-2` 的 APK 下载到本机 `~/Downloads/RateMock-P0/app-debug.apk`（附 `SHA256SUMS` 和中文安装说明），可直接用数据线或局域网传到手机，避免手机重复下载。

P0 使用默认 debug 签名，仅用于测试。不同临时 runner 上的 debug 密钥可能不同，下一次 APK 覆盖安装可能失败；需要卸载旧测试版再装（会清除其数据）。持久测试签名和 release 签名留到明确授权后配置，不复用 GoGoGo 的 keystore。

### CNB

用户接受 GitHub Actions 或 CNB，本次先实现 GitHub Actions，不维护两套未经验证的流水线。后续 CNB 可复用 `scripts/test-core.sh`、`scripts/build-android.sh`；还需确认 CNB 项目、镜像和产物存储后再添加 `.cnb.yml`。

## 4. 本地操作：明确授权下载后才运行

```sh
# 零依赖、零网络静态检查（Python 3.11+）。
python3 scripts/check_project.py

# 带下载保护的构建入口：默认拒绝本地运行。
bash scripts/test-core.sh

# 仅在有网络预算时允许下载 JVM 依赖；不下载 Android SDK。
RATEMOCK_ALLOW_DOWNLOADS=1 bash scripts/test-core.sh

# 可选阿里 Maven 镜像，默认仍使用官方源。
RATEMOCK_ALLOW_DOWNLOADS=1 RATEMOCK_USE_MIRRORS=true bash scripts/test-core.sh
```

`RATEMOCK_USE_MIRRORS` 只影响 Maven/插件仓库，不影响 Gradle 官方发行包地址。`scripts/update-gradle-wrapper.py` 只同步 Wrapper 小文件，不下载 Gradle 发行包；Wrapper JAR 与官方 SHA-256 registry 对照。镜像不禁用校验，也不代表省流量。

不要把 `--offline` 当成首次运行的流量保护：Wrapper 在启动 Gradle 之前就可能先下载发行包。只有本体和依赖都缓存齐全后，Gradle 离线模式才有意义。

## 5. 完成条件

本地静态检查涵盖 XML/TOML、资源引用、构建关系、依赖别名、Wrapper 校验、shell 语法和下载保护。**它不是 Kotlin 编译器，也不代替 Android lint、JUnit 或真机测试。**

P0 的云端验收已在 run `35848681022` 完成实测：JDK 17/21 单测全绿、lint 通过、APK 已生成并校验。**唯一未完成项是真机界面确认**：在手机上安装该 APK，确认启动页显示演示计算“2.70 米/秒”，并检查小屏、横屏、大字体与深浅色。真机确认后 P0 才算全部验收完成。

项目自有材料按根目录 [`LICENSE`](../LICENSE) 中的 Apache License 2.0 授权。第三方材料和依赖不因此自动采用同一许可证；发布 APK 前按实际解析依赖及 CI Action 检查各自声明。归属与致谢见 [`CREDITS.md`](../CREDITS.md) 和 [`THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md)。
