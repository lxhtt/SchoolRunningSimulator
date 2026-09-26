# 校园跑步频模拟器 — 项目计划

> 状态：P0–P1 源码、S7→S6 桥接、云端构建和 JVM/Python 验证已完成；P2 模拟跑台、P3 实跑辅助、P5 本地安全回放和 P6 生命周期/导出硬化已实现。设备 ROM 的息屏电源管理限制已记录，不能泛化为所有 Android 设备行为。
> 当前实现状态以最近 GitHub Actions run、`docs/superpowers/specs/2026-09-26-recording-lifecycle-export-design.md` 和 `docs/superpowers/specs/2026-09-26-p6-preflight-presets-design.md` 为准。

## 0. 一句话定义

一个独立 Android 应用：由用户给出速度与步频目标，在应用内生成可解释、可复核的模拟历史，同时提供独立的真实跑步记录与显式导出。物理内核保持纯 Kotlin/JVM；系统级定位/传感器注入不属于当前交付。

## 1. 已确认的需求边界

| 维度 | 结论 |
|---|---|
| 形态 | A. 独立 App，真实跑（不依赖目标 App） |
| 注入 | 不实现系统级 Mock Location 或 root + Xposed/LSPosed 注入；仅有纯本地事件协议与只读诊断回放，真实记录和模拟数据分离 |
| 首版 | **内置仿真跑台**（In-app Simulator） |
| 构建 | **本地不装 Android SDK**，APK 走 CI（详见 §4.0） |
| P7 | 本应用真实传感器诊断与自有本地 JSON 测试接收端已接入；独立接收 APK 与主 APK 的 CI/真机验收未完成（见 `superpowers/specs/2026-09-26-p7-platform-diagnostics-design.md`） |
| GitHub CI | 仓库 [`lxhtt/SchoolRunningSimulator`](https://github.com/lxhtt/SchoolRunningSimulator)，目标分支 `master`；公开仓库写入须事先授权 |

> 相关研究：[`DESIGN-injection-fusion.md`](DESIGN-injection-fusion.md) 记录定位注入机制调研；其中推测不代表实现结论。

由此推出的架构原则：**物理内核与 Android 界面/记录服务解耦**。内核是纯 Kotlin、零 Android 依赖、可 JVM 单测；Android 层只消费其计算结果及导出策略，不能把模拟输出伪装成真实观测。

## 2. 核心模型

### 2.1 单位与运动学约束

```text
speedMps = cadenceSpm × stepLengthMeters / 60
```

`cadenceSpm` 是每分钟单步数；`stepLengthMeters` 是每一步的前进距离，不是完整左右脚循环的跨步长度（stride length）。P0 只实现此换算及输入校验。

例如 `172 spm`、`2 m/s` 对应约 `0.698 m/step`。**没有来源和个体校准，不能把 0.78 米视为普适生理下限。** 初稿中的固定腿长比例、步长上下限和据此作出的“不可能”结论撤回，不进入代码。

### 2.2 P1 可行域与个体校准

- GPS 速度不能单独、唯一确定步频；必须结合用户给定区间、个体校准或实际步态传感器信息。
- 步长上下限作为显式的模型参数，后续基于数据校准，不声称是全人群生理常数。
- 求解速度、步频与步长约束的交集；冲突时报告“给定模型参数下无解”，不能只把步长截断而破坏恒等式。
- 起步、稳定运动、暂停、结束应有明确状态；稳定运动的目标区间不能机械套用在零速度的暂停状态。
- 初稿的步频幂律指数、疲劳百分比与地形方向关系均为待验证假设，不作为 P0 默认值。

### 2.3 轨迹真值与 GPS 观测分离

- `GroundTruth`：由同一个仿真时钟驱动速度、单步事件、累计距离与无噪声轨迹。
- `GpsObservation`：在真值上叠加定位噪声、采样与观测精度，独立标识为观测值。
- 恒等式在真值层校验；有噪声 GPS 相邻点差分不要求逐点等于真速度，不再采用“误差必须始终小于 2%”的错误验收条件。
- 不设“相邻点位移必须大于 2 米”的硬门槛；静止与低速场景都应被保留。
- 固定种子用于回归重放；随机性模型和统计参数在 P1 根据数据再确定。

### 2.4 后续字段

心率与着地时间不属于 P0。若以后模拟这些字段，必须明确标记为模型输出，不作为真实测量或健康建议；初稿中无依据的固定公式不直接采用。


## 3. 架构

当前交付为独立 App，不包含系统级定位/传感器注入：

```text
:app (Android / Compose)
  ├─ 真实记录前台服务 → 私有 recordings/ → 停止并关闭 → 严格解析 → 用户选择导出目标
  ├─ 应用内模拟前台服务 → 私有 simulations/ → 波形复核 / 只读诊断 / 显式导出
  └─ 测试接收页 → 共享 :receiver-ui
:receiver (独立安装的测试 App) → 共享 :receiver-ui
:receiver-ui → 本地 JSON 文档选择 / 只读校验 / 结果展示
:sim-core (纯 Kotlin/JVM，无 Android 依赖)
  ├─ 可行域、运动计划、步态真值与 GPS 观测
  ├─ 预设自检、事件校验、回放、波形复核
  └─ 真实记录导出策略及格式转换
```

模拟历史与真实记录分别存储，前者不能成为后者的 GPS 补点来源。Kotlin 2.4.20、Compose、Gradle 9.7.1 composite build 和 JUnit 5 的固定版本见 [`BUILD.md`](BUILD.md)。早期系统注入设想仅保留为历史研究笔记，不属于本次实现或验收范围。

## 4. 分阶段计划

### 4.0 构建策略：本地不装 Android SDK

本次采用 **CI-first**：本地只写代码、做离线结构检查；Gradle/Kotlin/Android 依赖下载和正式测试、编译交给 GitHub Actions。

| 场景 | 位置 | 现状 |
|---|---|---|
| XML/TOML、Wrapper、shell 与下载保护检查 | 本地 | 可零网络运行 `python3 scripts/check_project.py` |
| `sim-core` JUnit 测试 | 云端 JDK 17/21 | 工作流已配置；本地未运行，需提交后由 CI 验证 |
| Gradle 与 Android 构建链 | Wrapper 9.7.1；AGP 9.3.1 / Kotlin 2.4.20；API 36 / Build Tools 36.0.0 | 工作流已配置；本地不运行，需提交后由 CI 验证；兼容性说明见 [`BUILD.md`](BUILD.md) |
| 真机验证 | 手机 | CI 成功后下载 APK，文件管理器安装，不强制要求 adb |

- `sim-core` 使用自己的 `settings.gradle.kts`。独立命令为 `./gradlew -p sim-core test`，而不是依赖根 Android 工程能跳过 SDK 检查。
- JVM 模块无需 SDK，但没有缓存仍然需要下载 Gradle、Kotlin 和测试依赖。不再承诺固定 200 MB 或固定构建时长。
- 本地构建脚本默认拒绝依赖下载；网络充足时才显式设置 `RATEMOCK_ALLOW_DOWNLOADS=1`。
- Wrapper 默认使用官方分发地址并校验 SHA-256。阿里 Maven 镜像为显式可选项；镜像加速不等于省流量。
- GitHub Actions 仅上传 debug APK、SHA-256 和测试报告，不自动发 Release，不使用个人签名，不作免费额度承诺。
- CNB 作为后续备通道，本次不创建未经测试的 `.cnb.yml`。共享入口和后续条件见 `BUILD.md`。
- 本地仓库已初始化，`master` 已推送到 [`lxhtt/SchoolRunningSimulator`](https://github.com/lxhtt/SchoolRunningSimulator)；云端已跑通测试、lint 与 APK 构建。

平台版本从 37 回退到 36，因为 37 在 CI stable channel 不可安装（首次 run `35842081920` 已证）。此前版本已在真机做过启动与权限冒烟检查；本批 P6/P7 改动的界面、完整录制流程和独立接收 APK 尚未验收。

| Phase | 交付物 | 关键风险 | 验证方式 |
|---|---|---|---|
| P0 环境 | Gradle 9.7.1 Wrapper + AGP 9.3.1 / Kotlin 2.4.20 + 两个独立构建 + GH Actions master workflow；本地不下载 SDK 或构建依赖 | 已通过云端实测（run `35848681022`）；Kotlin 官方兼容表列至 9.7.0，9.7.1 由实测覆盖 | 已完成：JDK 17/21 单测（14/14）、lint、API 36 APK；剩余真机启动确认 |
| **P1 内核** | S1–S7 与 S7→S6 桥接已实现：可行性、计划、真值、GPS、导出、校准、Android 原始记录和稳定窗口转换 | **模型是否生理可信**——默认值仍全部未校准；当前设备记录桥接后为 insufficient，不生成默认模型 | CI 运行 `36050777912`/`36052244751` 已验证核心与 Android；真机已验证连续记录、服务控制和数据备份 |
| **P2 仿真跑台** | Compose 表单（区间/距离/体重身高）→ 实时仪表盘 + 双曲线 + 轨迹地图 + 语音提示 + 结束导出 JSON/GPX/CSV | 图表与地图首次集成；**无法本地预览 UI** | 真机跑一次仿真，肉眼核对曲线无阶跃、无锁频 |
| **P3 真跑模式** | 前台服务采真 GPS；逐步传感器估计实时步频，累计计数器只核对健康状态；可选语音提示及用户发起的真实 CSV/JSON/GPX 导出 | 手持/口袋场景和小米息屏限制尚未量化 | 核心策略测试与 CI 编译；三次户外独立参考计数完成前不声明步频误差范围 |
| **P4 注入-A（历史设想，不在当前范围）** | 早期 Mock Location 研究，未实现 | 不作为独立 App 的发布门槛 | 不开展第三方应用检测绕过或兼容性验证 |
| **P5 安全范围交付** | provenance-aware 本地事件协议、顺序/时间/计数器/路线校验、只读离线回放和 Android 私有模拟历史诊断 | 不提供系统级定位/传感器注入；不声明第三方兼容性或绕过检测 | sim-core JVM 单测、离线检查、CI lint/APK；规格见 `docs/superpowers/specs/2026-09-25-p5-safe-replay-design.md` |
| **P6 硬化** | 记录生命周期状态持久化、原子关闭、严格真实导出资格、异常状态、自检、波形复核、参数预设和模拟历史诊断 | 代码与离线检查已完成；CI 编译/lint、真机多尺寸/后台流程、三次独立参考计数和 Google Play `specialUse` 评估仍待外部验收 | 离线结构检查、JVM/Python 测试；Android 编译/lint 交由 CI；规格见 `docs/superpowers/specs/2026-09-26-recording-lifecycle-export-design.md` 与 `docs/superpowers/specs/2026-09-26-p6-preflight-presets-design.md` |
| **P7 设备诊断与自有接收端** | 本应用真实传感器时序诊断、用户主动导出诊断 JSON；共享应用内/独立安装的 JSON 事件测试接收端 | 独立包只读取用户选择的本地文件，没有第三方平台包或协议；不能推断其他应用读取结果，也未完成息屏/后台验证 | 离线结构检查和核心 JVM 测试；两包 Android 编译/lint 与真机需后续验证；范围见 `docs/superpowers/specs/2026-09-26-p7-platform-diagnostics-design.md` |

**当前交付范围**：P0–P3 构成独立记录与模拟应用，P5 是应用内只读诊断，P6 是生命周期与体验硬化；P7 包含设备诊断及本地 JSON 自有测试接收端（应用内与独立 APK），仍待 CI 和真机验收。P4 和历史 P7 软件融合设想仅作为研究笔记，不实现系统注入，也不承诺第三方兼容或检测绕过。

## 5. 验收标准（P1+P2，P0 另见实施记录）

1. 单位明确，真值层满足 `speedMps = cadenceSpm * stepLengthMeters / 60`，不混淆单步与完整跨步。
2. 稳定运动满足配置的速度/步频/步长区间；无解时给出约束冲突，不篡改输出掩盖冲突。
3. 起步、暂停、恢复、结束及零速度输入有明确状态行为，非法/非有限输入必须被拒绝。
4. 轨迹真值累计距离与速度积分一致；GPS 观测噪声单独评估，不强制逐点差分等于真速度。
5. 同一输入、配置和种子可复现；随机模型的统计验收在 P1 校准后再制定。
6. 导出带单位、仿真标记、配置和种子，可独立重算，不把模拟字段当作测量值。
7. P2 真机验证小屏、横屏、大字体、深浅色以及开始/暂停/停止/导出流程。

## 6. 风险与明确的不做承诺

- **不承诺**能通过任何特定校园跑 App 的检测。P4 和历史 P7 软件融合的系统注入研究不代表实现或兼容性承诺；P5 的本地回放及 P7 首期诊断也不产生第三方可见数据。
- 不保证任意区间都有可行解；按显式模型参数求交，并区分“模型配置冲突”和没有证据支持的生理结论。
- 步频注入（IMU 层）需要 root；免 root 路线在传感器层面基本无解，这是平台限制而非实现问题。
- 第三方代码与依赖的许可须在实际引入/分发前审查，不能仅凭“只参考机制”就保证没有许可义务；本次 P0 未复制 GoGoGo 业务代码。标枪定位的相关描述只是上游声明，未独立检查其源码、二进制或许可履行情况。
- **上游公开声明**：GoGoGo README 声明不支持将其用于特定校园运动类 App。本项目不承诺兼容第三方 App 或绕过其检测；用户应遵守服务条款与适用规则。
- 项目自有内容采用 Apache License 2.0；第三方材料另依其上游许可。详见 [`LICENSE`](../LICENSE)、[`CREDITS.md`](../CREDITS.md) 与 [`THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md)。

## 7. 下一步

1. **完成 P0 真机验收**：安装最新 debug APK，确认启动页显示“2.70 米/秒”，并检查小屏/横屏/大字体/深浅色。
2. **完成 S7 真机验收**：授予定位、活动识别和通知权限，息屏记录 GPS 与 `TYPE_STEP_COUNTER`，停止后取出 CSV 并检查数据完整性。
3. **P1 已补齐 S7→S6 桥接**：运行 `scripts/recording_to_calibration.py` 将私有 Android 原始 CSV 转为稳定窗口输入；若报告为 `insufficient`，必须继续采集更干净的稳定段，不能放宽规则伪造拟合。
4. **P6 已完成代码交付**：生命周期硬化、严格导出资格、全链路自检、波形复核和参数预设已接入；当前工作区的 CI 编译、lint、APK 和真机多尺寸/后台流程仍需按交付门槛验收。
5. **P7 待验收**：本应用真实传感器回调摘要和共享测试接收屏幕已接入；独立 APK 待 CI/真机验证，不触碰保密平台材料，也不宣称第三方兼容。
6. 安排三次带独立参考计数的户外运行，并评估 `specialUse` 前台服务的 Google Play 政策适配；不据此承诺第三方兼容性。
