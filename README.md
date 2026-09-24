# RateMock

面向步态与 GPS 数据仿真的 Android 项目。当前源码包含纯 Kotlin/JVM 仿真内核、前台 GPS/步数记录器，以及开发中的应用内模拟跑台。

> **项目状态：P1 S1–S7 与 S7→S6 桥接已实现并纳入 CI；S7 连续写入已在真机验证。当前设备原始记录经保守桥接后为 insufficient，未生成个人默认模型。**
>
> CI 构建成功不等于真机后台行为已验收。P2 的实现边界与设备验收步骤见 [`docs/DESIGN-p2-simulator.md`](docs/DESIGN-p2-simulator.md)。

## 当前功能

- 纯 Kotlin/JVM 内核：可行域、运动计划、固定步长真值、GPS 观测、导出和校准；新增可暂停交互会话已在首轮 P2 CI 的 JDK 17/21 测试中通过。
- Android 前台记录器：独立采集真实 GPS 与累计步数到应用私有 CSV，已在真机上验证连续采样。
- S7→S6 离线桥接：`scripts/recording_to_calibration.py` 将 Android 原始 CSV 按稳定窗口转换为 S6 输入，并生成质量报告；个人原始数据与派生文件不进仓库。
- P2 模拟跑台：选择目标速度与时长，自动使用独立的步行/跑步工程默认模型；实时显示距离、步数和步频，通过通知暂停、继续或停止；息屏/后台不主动暂停。当前 APK 已通过 CI 和真机界面复测。
- GitHub Actions：JDK 17/21 内核测试（含交互会话与 S7 桥接 Python 测试）→ Android lint → debug APK 与 SHA-256 产物；不会上传个人健康数据。
- 离线工程检查脚本；本地构建入口默认阻止依赖下载。

## 构建和验证

低流量环境可运行不下载依赖的静态检查：

```sh
python3 scripts/check_project.py
```

没有明确下载许可和网络预算时，不要运行 `./gradlew`（包括 `--offline`）：Wrapper 可能先下载 Gradle 发行包。

目标仓库为 [`lxhtt/SchoolRunningSimulator`](https://github.com/lxhtt/SchoolRunningSimulator)，CI 目标分支为 `master`。最近一次包含 P2/P1 桥接的成功构建为 run [`36052244751`](https://github.com/lxhtt/SchoolRunningSimulator/actions/runs/36052244751)；构建步骤和安装说明见 [`docs/BUILD.md`](docs/BUILD.md)。

## 模型边界

`stepLength` 是每一步的前进距离，不等于完整左右脚循环的 `stride length`。GPS 速度不能单独、唯一确定步频。P1 内核包含运动计划、步态仿真、GPS 观测与校准拟合；Apple Health 数据可在本机提取为步行和跑步两份探索性样本。个人拟合的解释力不足，**不进入公开 APK**；P2 首版只使用清楚标为未校准的工程默认档位。产品阶段和风险说明见 [`docs/PROJECT_PLAN.md`](docs/PROJECT_PLAN.md)。

本项目不保证与任何第三方应用、校园跑平台或设备兼容，也不承诺绕过第三方服务的检测或规则。研究笔记不是已实现功能说明：[`docs/DESIGN-injection-fusion.md`](docs/DESIGN-injection-fusion.md)。

## 文档导航

- [完整文档索引](docs/README.md)
- [项目计划](docs/PROJECT_PLAN.md)
- [构建与验证](docs/BUILD.md)
- [P0 实施记录](docs/P0-IMPLEMENTATION.md)
- [P1 内核设计与校准说明](docs/DESIGN-p1-sim-core.md)
- [P2 应用内模拟跑台](docs/DESIGN-p2-simulator.md)
- [探索性定位注入研究笔记](docs/DESIGN-injection-fusion.md)

## 许可

RateMock 原创内容按 Apache License 2.0 授权，详见 [`LICENSE`](LICENSE)。该许可不覆盖明确标注的第三方材料；Gradle Wrapper 与其他依赖各自受其上游许可约束。开始分发构建产物前，请核查并保留相应的第三方许可与声明，见 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)。

## 致谢

RateMock 使用 Kotlin、Android Jetpack Compose、Gradle、JUnit 5 与 GitHub Actions 等开源工具和项目。第三方构建文件及可核实的上游信息列于 [`CREDITS.md`](CREDITS.md)。致谢不代表上游对 RateMock 的认可或背书。
