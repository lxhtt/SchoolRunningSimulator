# RateMock

面向步态与 GPS 数据仿真的 Android 项目。当前版本为 **P0 工程骨架**：包含纯 Kotlin/JVM 运动学内核和 Compose 验证页；尚不是完整的跑步模拟器。

> **项目状态：云端构建与测试已通过；真机界面验收待执行。**
>
> 已实测：Kotlin 编译、14/14 JUnit 测试（JDK 17 与 21）、Android lint、debug APK 生成与下载校验。
> 未完成：手机安装与启动页界面确认。详见 [`docs/P0-IMPLEMENTATION.md`](docs/P0-IMPLEMENTATION.md)。

## 当前功能

- 纯 Kotlin/JVM 内核：按 `speedMps = cadenceSpm × stepLengthMeters / 60` 换算步频、单步长度和速度，并校验数值输入。
- 14 个 JUnit 5 测试：覆盖换算、边界、非法输入、溢出和往返计算；已在云端 JDK 17 与 21 实际跑通（failures=0、errors=0）。
- Compose 验证页：显示明确标为非实测的演示值。
- GitHub Actions：JDK 17/21 内核测试 → Android lint → debug APK 与 SHA-256 产物，最近一次运行三个 job 全绿。
- 离线工程检查脚本；本地构建入口默认阻止依赖下载。

## 构建和验证

低流量环境可运行不下载依赖的静态检查：

```sh
python3 scripts/check_project.py
```

没有明确下载许可和网络预算时，不要运行 `./gradlew`（包括 `--offline`）：Wrapper 可能先下载 Gradle 发行包。

目标仓库为 [`lxhtt/SchoolRunningSimulator`](https://github.com/lxhtt/SchoolRunningSimulator)，CI 目标分支为 `master`。云端构建已成功（run [`35848681022`](https://github.com/lxhtt/SchoolRunningSimulator/actions/runs/35848681022)）；可在该 run 的 Artifacts 中下载 `ratemock-debug-2` 获得 `app-debug.apk` 与 `SHA256SUMS`（保留 7 天）。构建步骤和安装说明见 [`docs/BUILD.md`](docs/BUILD.md)。

## 模型边界

`stepLength` 是每一步的前进距离，不等于完整左右脚循环的 `stride length`。GPS 速度不能单独、唯一确定步频。当前内核只做运动学换算；个体校准、仿真轨迹、GPS 观测噪声及传感器建模均未实现。产品阶段和风险说明见 [`docs/PROJECT_PLAN.md`](docs/PROJECT_PLAN.md)。

本项目不保证与任何第三方应用、校园跑平台或设备兼容，也不承诺绕过第三方服务的检测或规则。研究笔记不是已实现功能说明：[`docs/DESIGN-injection-fusion.md`](docs/DESIGN-injection-fusion.md)。

## 文档导航

- [完整文档索引](docs/README.md)
- [项目计划](docs/PROJECT_PLAN.md)
- [构建与验证](docs/BUILD.md)
- [P0 实施记录](docs/P0-IMPLEMENTATION.md)
- [探索性定位注入研究笔记](docs/DESIGN-injection-fusion.md)

## 许可

RateMock 原创内容按 Apache License 2.0 授权，详见 [`LICENSE`](LICENSE)。该许可不覆盖明确标注的第三方材料；Gradle Wrapper 与其他依赖各自受其上游许可约束。开始分发构建产物前，请核查并保留相应的第三方许可与声明，见 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)。

## 致谢

RateMock 使用 Kotlin、Android Jetpack Compose、Gradle、JUnit 5 与 GitHub Actions 等开源工具和项目。第三方构建文件及可核实的上游信息列于 [`CREDITS.md`](CREDITS.md)。致谢不代表上游对 RateMock 的认可或背书。
