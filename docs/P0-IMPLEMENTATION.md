# P0 实施记录

## 本次范围

- 独立的 `sim-core/` Gradle JVM build：不加载 Android Gradle Plugin、不查询 Android SDK。
- Android 壳 `:app` 通过 Gradle composite build 引用纯 JVM 内核。
- 单位明确的运动学换算与 14 个 JVM 测试。
- GitHub Actions：JDK 17/21 内核测试 → Android lint → debug APK + SHA-256 Artifact。
- P0 不需要地图、定位权限、模拟定位、root、第三方应用集成或签名材料。

## 构建版本和分支

- Gradle Wrapper **9.7.1**：Gradle 官方源码及 Wrapper JAR；JAR、binary distribution 的 SHA-256 与官方 checksum registry 一致。没有下载 Gradle binary distribution。
- AGP **9.3.1**、Kotlin **2.4.20**、API **36**、Build Tools **36.0.0**，workflow 目标分支为 `master`。
- AGP 9 使用 built-in Kotlin；Android app 仅应用 Compose compiler plugin，不重复应用 `org.jetbrains.kotlin.android`。
- Kotlin 官方 fully-supported matrix 列到 Gradle 9.7.0；**9.7.1 已由实际 CI 运行验证可用**（见下）。

## 云端验收结果（真实运行，非静态推断）

| 项目 | 结果 |
|---|---|
| 首次 run [`35842081920`](https://github.com/lxhtt/SchoolRunningSimulator/actions/runs/35842081920) | JDK 17/21 core job 成功；android job 失败 |
| 失败原因 | `sdkmanager` 在 CI stable channel 找不到 `platforms;android-37`，该平台当时不可安装 |
| 修正 | 改为 API 36 平台与 `build-tools;36.0.0`，提交 `89d3385` |
| 通过 run [`35848681022`](https://github.com/lxhtt/SchoolRunningSimulator/actions/runs/35848681022) | **三个 job 全部成功** |

通过的 run 明细：

- `JVM core (JDK 17, SDK-independent)`：成功，42s。
- `JVM core (JDK 21, SDK-independent)`：成功，47s。
- `Lint and debug APK`：成功，2m6s（含 `:app:lintDebug` 与 `:app:assembleDebug`）。

单测实测结果（JUnit XML，JDK 17 与 JDK 21 均为同一结果）：

```text
tests=14  failures=0  errors=0  skipped=0  time=0.111s
```

APK 产物（`ratemock-debug-2`）：

- 大小 9,389,491 字节。
- SHA-256：`13f385a59b0373cab196d0fc449a37b0cef812477050a8577f383b3ebb40864c`，与 Actions 生成的 `SHA256SUMS` 一致（本地 `shasum -a 256 -c` 校验通过）。
- 结构已核对：含 `AndroidManifest.xml`、`resources.arsc`、`classes.dex` 及 `classes2–7.dex`。
- 已确认 dex 中包含 `dev/ratemock/core/GaitKinematics`（composite build 的 `sim-core` 确实被打进 APK）与 `dev/ratemock/app/MainActivity`。

Android lint 在 `abortOnError = true` 下通过；报告只有警告，无 error。警告内容为提示型版本建议（targetSdk/compileSdk 可升 37、AGP 可升 9.4.1、activity-compose 与 Compose BOM 有新版本）以及 `upload-artifact@v4` 的 Node.js 20 弃用提示。这些都不影响本次构建结果。

## 流量与操作边界

- 本地未安装 Android SDK、Gradle 或 Kotlin；所有编译与测试均在 GitHub Actions 完成。
- 本地只下载了 APK 产物与测试/lint 报告（APK 约 9.0 MB，用于校验和交付真机安装）。
- 本地 Git 已初始化并推送到公开仓库 [`lxhtt/SchoolRunningSimulator`](https://github.com/lxhtt/SchoolRunningSimulator) 的 `master` 分支，已获用户明确授权。
- 未配置 Release、签名材料或 CNB 流水线。

## 模型约定

1. `stepLength` 是单步前进距离，不能与完整左右脚循环的 `stride length` 混用。
2. `speedMps = cadenceSpm * stepLengthMeters / 60`。
3. `172 spm`、`2 m/s` 算得约 `0.698 m/step`；没有充分个体数据时不宣称低于普适的生理下限。
4. 当前内核只负责单位换算与数值验证。GPS 不能唯一确定步频；P1 将单独建模区间、状态和不确定性。

## 验证清单

- [x] 构建依赖版本、Gradle/AGP/Kotlin 官方资料已核对。
- [x] Wrapper JAR SHA-256、ZIP SHA-256 与 Gradle 官方 registry 一致。
- [x] 14 个 Kotlin 测试覆盖换算、往返恒等式、静止、非法值和溢出。
- [x] `python3 scripts/check_project.py`：101 项离线结构检查通过，且脚本默认拒绝下载。
- [x] Ruby YAML 解析、workflow job graph、inline shell 语法检查通过。
- [x] 8 组显式浅色/深色文字与背景对比度达到 4.5:1（代码计算，非设备显示测量）。
- [x] **Kotlin 编译：云端实际通过**（JDK 17 与 21）。
- [x] **JUnit 执行：14/14 通过**（JDK 17 与 21，failures=0、errors=0）。
- [x] **Android lint：云端实际通过**（`abortOnError = true`，仅有警告）。
- [x] **debug APK：已生成、已下载、SHA-256 与结构均已校验**。
- [x] 自有项目内容按 Apache License 2.0 公开授权；第三方材料单独声明。
- [ ] **真机安装与界面验收**：尚无手机端结果，待用户执行。

## 下一步

P0 的云端部分已完成实测验收。剩余一项是**真机确认**：安装 `ratemock-debug-2` 的 APK，确认启动页显示演示计算 `2.70 米/秒`，并检查小屏、横屏、大字体与深浅色。真机通过后 P0 才算全部完成，随后进入 P1（可行域求解与步态模型）。

已知后续可选项（不阻塞 P0）：评估把 `upload-artifact` 升级到不再使用 Node.js 20 的版本；评估 AGP/compileSdk/依赖版本升级；确认 API 37 平台在 CI 可用后是否迁移。
