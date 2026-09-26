# RateMock

面向步态与 GPS 数据仿真的 Android 项目。当前源码包含纯 Kotlin/JVM 仿真内核、前台 GPS/步数记录器，以及应用内模拟跑台。

> **项目状态：P1 S1–S7、S7→S6 桥接、P2 模拟跑台、P3 实跑辅助、P5 本地安全回放、P6 生命周期/导出硬化和 P7 自有诊断/接收已实现。位置工作台的原创 Mock Location 服务已加入源码，但其 CI 编译与真机验收尚未完成；不包含步频传感器注入或 LSPosed/Xposed。**
>
> CI 构建成功不等于真机后台行为已验收。设备息屏限制、三次户外独立计数和 `specialUse` 前台服务政策评估仍需单独完成。

## 当前功能

- 纯 Kotlin/JVM 内核：可行域、运动计划、固定步长真值、GPS 观测、导出和校准；新增可暂停交互会话已在首轮 P2 CI 的 JDK 17/21 测试中通过。
- Android 前台记录器：独立采集真实 GPS 与累计步数到应用私有 CSV，逐步传感器提供实时步频；可选语音提示，停止后由用户显式选择 CSV/JSON/GPX 导出目标。没有新鲜 GPS 时拒绝导出，不会用模拟路线补点。
- S7→S6 离线桥接：`scripts/recording_to_calibration.py` 将 Android 原始 CSV 按稳定窗口转换为 S6 输入，并生成质量报告；个人原始数据与派生文件不进仓库。
- P2 模拟跑台：选择目标速度与时长，自动使用独立的步行/跑步工程默认模型；实时显示距离、步数和步频，通过通知暂停、继续或停止；息屏/后台不主动暂停。支持私有历史曲线、合成路线、显式 CSV/JSON/GPX 导出及 provenance 校验诊断。
- P5 安全回放基础：纯 JVM 本地位置/步数事件协议、时间/序号/计数器/路线校验、只读离线回放；该回放协议本身不产生 Android Location/SensorEvent。独立位置工作台提供用户显式启动的系统 Mock Location，并可在模拟服务成功关闭后由用户明确选择回放最近模拟路线；两者不会自动联动或读取真实记录。规格见 [`docs/superpowers/specs/2026-09-25-p5-safe-replay-design.md`](docs/superpowers/specs/2026-09-25-p5-safe-replay-design.md)。
- P6 硬化：真实记录使用持久化生命周期状态和原子关闭流程；真实导出严格绑定已关闭文件并重新解析校验。模拟页提供运行前自检、波形复核和三个只填入表单的保守参数预设，不读取真实 GPS、不修改个人校准。
- P7 首期设备诊断与自有测试接收端：诊断页检查真实步进/计数器能力及本应用回调时序；“测试接收”页和独立 `dev.ratemock.receiver` 测试包只读取用户选择的本地 `ratemock.local-replay.v1` JSON，复用只读校验器，不注入系统、不跨应用通信。
- 位置工作台：单 APK 内提供坐标输入、OSM 栅格地图与离线画布回退、Nominatim 地点搜索、私有历史位置、摇杆路线和用户显式启动的 Android Mock Location 前台服务；可在模拟路线成功结束并关闭历史文件后，由用户显式回放该合成路线。Mock GPS 与真实记录互斥，服务停止时清理 test provider；不复制 GoGoGo 源码、百度 SDK、密钥或签名材料。
- GitHub Actions：JDK 17/21 内核测试（含交互会话与 S7 桥接 Python 测试）→ Android lint → debug APK 与 SHA-256 产物；不会上传个人健康数据。
- 离线工程检查脚本；本地构建入口默认阻止依赖下载。

## 构建和验证

低流量环境可运行不下载依赖的静态检查：

```sh
python3 scripts/check_project.py
```

没有明确下载许可和网络预算时，不要运行 `./gradlew`（包括 `--offline`）：Wrapper 可能先下载 Gradle 发行包。

此前成功构建为 run [`36252420344`](https://github.com/lxhtt/SchoolRunningSimulator/actions/runs/36252420344)；**该产物不包含当前未提交的位置工作台改动**。构建步骤和安装说明见 [`docs/BUILD.md`](docs/BUILD.md)。

## P7 自有测试接收端

P7 现包含两个同源入口：主应用内“测试接收”页，以及独立的 `dev.ratemock.receiver` 测试 APK。两者都只通过系统文档选择器读取用户主动选择的 `ratemock.local-replay.v1` JSON，并复用 `LocalReplayValidator` 检查事件顺序、时间、累计步数和位置跳变。主应用模拟历史可显式导出测试事件 JSON；导出只使用历史中已有的本地 east/north 位置样本，不补造传感器事件。

接收端限制输入文件为 1 MiB、10,000 事件，拒绝未知字段、未知事件类型、非有限数值和非法来源。独立测试 APK 不声明位置、活动识别、网络或前台服务权限，不导出后台接收组件。它用于验证自有文件协议和诊断报告，不代表系统传感器可见性、第三方应用行为或任何校园跑平台兼容性。

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

RateMock 原创内容按 Apache License 2.0 授权，详见 [`LICENSE`](LICENSE)。本版本的位置工作台为 RateMock 原创实现，使用公开 OSM 瓦片和 Nominatim 服务时须遵守其服务条款与使用限制。该许可不覆盖明确标注的第三方材料；GoGoGo 源码、百度 SDK、密钥和签名材料未复制或打包。Gradle Wrapper 与其他依赖各自受其上游许可约束。开始分发构建产物前，请核查并保留相应的第三方许可与声明，见 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)。

## 致谢

RateMock 使用 Kotlin、Android Jetpack Compose、Gradle、JUnit 5 与 GitHub Actions 等开源工具和项目。第三方构建文件及可核实的上游信息列于 [`CREDITS.md`](CREDITS.md)。致谢不代表上游对 RateMock 的认可或背书。
