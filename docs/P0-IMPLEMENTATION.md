# P0 实施记录

## 本次范围

- 独立的 `sim-core/` Gradle JVM build：不加载 Android Gradle Plugin、不查询 Android SDK。
- Android 壳 `:app` 通过 Gradle composite build 引用纯 JVM 内核。
- 单位明确的运动学换算与 14 个 JVM 测试定义。
- GitHub Actions：JDK 17/21 内核测试 → Android lint → debug APK + SHA-256 Artifact。
- P0 不需要地图、定位权限、模拟定位、root、第三方应用集成或签名材料。

## 构建版本和分支

- Gradle Wrapper **9.7.1**：Gradle 官方源码及 Wrapper JAR；JAR、binary distribution 的 SHA-256 与官方 checksum registry 一致。没有下载 Gradle binary distribution。
- AGP **9.3.1**、Kotlin **2.4.20**、API **36**、Build Tools **36.0.0**，branch workflow 目标为 `master`。
- AGP 9 使用 built-in Kotlin；Android app 仅应用 Compose compiler plugin，不重复应用 `org.jetbrains.kotlin.android`。
- Kotlin 官方 fully-supported matrix 标到 Gradle 9.7.0；Gradle 9.7.1 patch 兼容需首次 CI 验证。

## 流量与操作边界

- 本地不安装 Android SDK/Gradle/Kotlin，不运行会触发 Gradle/依赖下载的 Wrapper 命令。
- 只利用 Python/Bash/Ruby 对现有源码做静态校验；CI 与真机运行均未发生。
- GitHub 仓库目标为 [`lxhtt/SchoolRunningSimulator`](https://github.com/lxhtt/SchoolRunningSimulator)，目标分支 `master`；本地 Git 尚未初始化。
- 初始化本地 Git、创建提交、推送公开仓库和触发 Actions 均未执行；仍需用户单独授权。
- 云端 SDK 仍是 Android 构建的前提；本机免 SDK 不代表云端无需 SDK 或无需下载依赖。

## 模型约定

1. `stepLength` 是单步前进距离，不能与完整左右脚循环的 `stride length` 混用。
2. `speedMps = cadenceSpm * stepLengthMeters / 60`。
3. `172 spm`、`2 m/s` 算得约 `0.698 m/step`；没有充分个体数据时不宣称低于普适的生理下限。
4. 当前内核只负责单位换算与数值验证。GPS 不能唯一确定步频；P1 将单独建模区间、状态和不确定性。

## 验证清单

- [x] 构建依赖版本、Gradle/AGP/Kotlin 官方资料已核对；Compose/JUnit/Activity 对应 POM 已确认存在。
- [x] Wrapper JAR SHA-256、ZIP SHA-256 与 Gradle 官方 registry 一致。
- [x] 14 个 Kotlin 测试定义覆盖换算、往返恒等式、静止、非法值和溢出。
- [x] `python3 scripts/check_project.py`：离线结构检查通过，且脚本默认拒绝下载。
- [x] Ruby YAML 解析、workflow job graph、5 段 inline shell 语法检查通过。
- [x] 8 组显式浅色/深色文字与背景对比度达到 4.5:1（代码计算，非设备显示测量）。
- [x] Kotlin 编译和 JUnit 执行：本机未运行，待首次 CI。
- [x] 首次 CI run `35842081920`：JDK 17 与 JDK 21 的 core job **均成功**；Kotlin 编译与 14 个 JUnit 测试实际通过。
- [ ] Android lint 和 APK 构建：首次 run 的 android job 在 `sdkmanager` 安装 `platforms;android-37` 时失败（该平台在 CI 的 stable channel 尚未发布）。已改为 API 36 并重新触发。
- [ ] 手机安装与 UI 验收：当前无 APK。
- [x] 自有项目内容按 Apache License 2.0 公开授权；第三方材料单独声明，见根目录 `LICENSE`、`CREDITS.md` 和 `THIRD_PARTY_NOTICES.md`。

## 下一步

目标仓库和 `master` 分支已确定。首次 CI 的 JDK 17/21 core job 已实测通过；Android job 因 API 37 尚未在 CI stable channel 发布而失败，现已改为 API 36 并重新运行，待其结果确认 APK 与 lint。之后再安装真机验收。P0 源码与离线配置检查通过不等同于云端构建或最终验收通过。
