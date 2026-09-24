# 校园跑步频模拟器 — 项目计划

> 状态：P0 源码与云构建配置已落盘；首次云端编译、测试及真机验收未执行。
> 本文区分当前交付与未来计划；P0 实际状态见 [`P0-IMPLEMENTATION.md`](P0-IMPLEMENTATION.md) 和 [`BUILD.md`](BUILD.md)。

## 0. 一句话定义

一个 Android 应用：由用户给出**速度区间 + 步频区间**，结合可校准的步态模型与 GPS 观测模型，生成可解释、可重复校验的仿真数据。先交付内置仿真跑台，再扩展真实跑步辅助；定位通道与软件融合保留为后续独立阶段，不作为 P0 的交付内容。

## 1. 已确认的需求边界

| 维度 | 结论 |
|---|---|
| 形态 | A. 独立 App，真实跑（不依赖目标 App） |
| 注入 | **两种都要**：① Mock Location（免 root）② root + Xposed/LSPosed |
| 首版 | **内置仿真跑台**（In-app Simulator） |
| 构建 | **本地不装 Android SDK**，APK 走 CI（详见 §4.0） |
| P7 | **软件融合**：不融合进程，融合机制（详见 `DESIGN-injection-fusion.md`） |
| GitHub CI | 仓库 [`lxhtt/SchoolRunningSimulator`](https://github.com/lxhtt/SchoolRunningSimulator)，目标分支 `master`；公开仓库写入须事先授权 |

> 相关研究：[`DESIGN-injection-fusion.md`](DESIGN-injection-fusion.md) 记录定位注入机制调研；其中推测不代表实现结论。

由此推出的架构原则：**物理内核与注入/消费层彻底解耦**。内核是纯 Kotlin、零 Android 依赖、可 JVM 单测；注入只是它的下游消费者之一。这样"两种注入都要"不会污染核心模型，也不会让算法被 Android 生命周期绑架。

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

以下是后续目标架构；P0 实际只有 Android 验证页与独立 JVM 内核，未创建地图/传感器/注入模块。

```
┌───────────────────────────────────────────────────────────┐
│ :app  (Android: Compose + MapLibre + MPAndroidChart)      │
│   InAppSimulator · 曲线仪表盘 · 轨迹地图 · 语音引导        │
└───────────────────────┬───────────────────────────────────┘
                        │ 消费
┌───────────────────────▼───────────────────────────────────┐
│ :sim-core  纯 Kotlin / 零 Android 依赖 / JVM 可测           │
│                                                           │
│  RunPlan ──▶ FeasibilitySolver  ← 用户区间                 │
│                 │                                         │
│                 ▼                                         │
│  SpeedPlanner (距离/时长/变速/冲刺)                         │
│                 │                                         │
│                 ▼                                         │
│  GaitEngine ── CadenceModel + StrideModel + FatigueModel   │
│                 │       + TerrainModel                     │
│                 ▼                                         │
│  StepEvent 流 (t, x, y, v, cadence, stride, gct, hr)       │
│                 │                                         │
│      ┌──────────┼───────────────┐                         │
│      ▼          ▼               ▼                         │
│  GpsStream  ImuSynthesizer  StatsReporter                 │
│  (噪声/精度) (加速度波形)    (统计+PNG/CSV/GPX/JSON)       │
└───────────────────────┬───────────────────────────────────┘
                        │ 注入（后续阶段）
      ┌─────────────────┴──────────────────┐
      ▼                                    ▼
 MockLocationSink                    XposedModule
 (addTestProvider, 免 root)          (定位 + 加速度计 + 步数)
 ↑ 参数与机制取自影梭 ServiceGo              ↑ 影梭完全未覆盖
 (已验证的 provider 参数常量)                (本项目核心价值所在)
```

**P0 实际选型**：Kotlin 2.4.20 · Compose · 独立 JVM `sim-core` · Gradle 9.7.1 composite build · JUnit 5。固定版本见 `BUILD.md`。地图、图表和导出依赖留到 P2 再定，P0 不下载这些依赖，也不预设地图服务可以免除服务条款或隐私评估。

## 4. 分阶段计划

### 4.0 构建策略：本地不装 Android SDK

本次采用 **CI-first**：本地只写代码、做离线结构检查；Gradle/Kotlin/Android 依赖下载和正式测试、编译交给 GitHub Actions。

| 场景 | 位置 | 现状 |
|---|---|---|
| XML/TOML、Wrapper、shell 与下载保护检查 | 本地 | 可零网络运行 `python3 scripts/check_project.py` |
| `sim-core` JUnit 测试 | 云端 JDK 17/21 | 工作流已配置，尚未运行 |
| Gradle 与 Android 构建链 | Wrapper 9.7.1；AGP 9.3.1 / Kotlin 2.4.20；API 36 / Build Tools 36.0.0 | 工作流已配置，尚未运行；兼容性说明见 [`BUILD.md`](BUILD.md) |
| 真机验证 | 手机 | CI 成功后下载 APK，文件管理器安装，不强制要求 adb |

- `sim-core` 使用自己的 `settings.gradle.kts`。独立命令为 `./gradlew -p sim-core test`，而不是依赖根 Android 工程能跳过 SDK 检查。
- JVM 模块无需 SDK，但没有缓存仍然需要下载 Gradle、Kotlin 和测试依赖。不再承诺固定 200 MB 或固定构建时长。
- 本地构建脚本默认拒绝依赖下载；网络充足时才显式设置 `RATEMOCK_ALLOW_DOWNLOADS=1`。
- Wrapper 默认使用官方分发地址并校验 SHA-256。阿里 Maven 镜像为显式可选项；镜像加速不等于省流量。
- GitHub Actions 仅上传 debug APK、SHA-256 和测试报告，不自动发 Release，不使用个人签名，不作免费额度承诺。
- CNB 作为后续备通道，本次不创建未经测试的 `.cnb.yml`。共享入口和后续条件见 `BUILD.md`。
- 本地仓库已初始化，`master` 已推送到 [`lxhtt/SchoolRunningSimulator`](https://github.com/lxhtt/SchoolRunningSimulator)；云端已跑通测试、lint 与 APK 构建。

平台版本从 37 回退到 36，因为 37 在 CI stable channel 不可安装（首次 run `35842081920` 已证）。真机界面验收尚未执行；P2 的界面逻辑仍应尽量保持可单测。

| Phase | 交付物 | 关键风险 | 验证方式 |
|---|---|---|---|
| P0 环境 | Gradle 9.7.1 Wrapper + AGP 9.3.1 / Kotlin 2.4.20 + 两个独立构建 + GH Actions master workflow；本地不下载 SDK 或构建依赖 | 已通过云端实测（run `35848681022`）；Kotlin 官方兼容表列至 9.7.0，9.7.1 由实测覆盖 | 已完成：JDK 17/21 单测（14/14）、lint、API 36 APK；剩余真机启动确认 |
| **P1 内核** | S1 已实现：可行性求解器、A2 步频策略与夹取解析；S2 已实现：运行计划与限 jerk 速度规划；S3–S6 待实现（后续包含真值、路线/GPS、导出、校准） | **模型是否生理可信**——默认值仍全部未校准；真实校准路径见 P1 设计文档 | S1/S2 云端 run `35960256603`：62/62 JVM 测试通过；后续按切片验收 |
| **P2 仿真跑台** | Compose 表单（区间/距离/体重身高）→ 实时仪表盘 + 双曲线 + 轨迹地图 + 语音提示 + 结束导出 JSON/GPX/CSV | 图表与地图首次集成；**无法本地预览 UI** | 真机跑一次仿真，肉眼核对曲线无阶跃、无锁频 |
| **P3 真跑模式** | 前台服务采真 GPS；峰值检测真实步频；实时对比目标 ↔ 实际并语音修正 | 步频检测在口袋/手持场景鲁棒性差 | 户外实跑 3 次，误差 < ±5 步/分 |
| **P4 注入-A** | Mock Location 通道（`addTestProvider`，免 root）；风险等级、检测面、掩蔽清单 | 主流校园跑 App 多数直接查 mock 权限，**大概率不可用** | 目标 App 实测；失败则记录"确认不可行"而非无限调参 |
| **P5 注入-B** | LSPosed 模块：hook 定位 + 传感器事件队列（加速度/步数传感器注入） | 需 root；Android 版本兼容；目标 App 可能有 native 层校验 | 目标 App 实测 + `dumpsys sensorservice` 比对 |
| **P6 硬化** | 全链路自检、参数预设、波形复核工具、文档 | 反作弊策略迭代 | 交付自查清单全过 |
| P7 软件融合 | 研究中的候选方向（未验证、未实现）：自有 `MockLocationSink`、互斥/健康检查及可选 IMU 方案 | Android 版本、ROM 行为及许可兼容性需先验证 | 先验证平台 API 与法规/政策边界，再决定是否实现 |

**建议节奏**：P0→P1→P2 是一条完整可用产品（独立仿真跑台，符合你选的首版）；P3 是真跑体验；P4/P5/P7 是注入，可并行探索但不要让它阻塞 P1 的算法打磨——**算法不可信，注入再多手段也只是生成更精致的假数据**。

**P4 与 P7 的重叠**：P4 原本就是 `addTestProvider` 路线，P7a 直接把它做对做完整，两者合并实现、分期交付。P7b/P7c（互斥检测、健康回读）是 P4 的硬化项。

## 5. 验收标准（P1+P2，P0 另见实施记录）

1. 单位明确，真值层满足 `speedMps = cadenceSpm * stepLengthMeters / 60`，不混淆单步与完整跨步。
2. 稳定运动满足配置的速度/步频/步长区间；无解时给出约束冲突，不篡改输出掩盖冲突。
3. 起步、暂停、恢复、结束及零速度输入有明确状态行为，非法/非有限输入必须被拒绝。
4. 轨迹真值累计距离与速度积分一致；GPS 观测噪声单独评估，不强制逐点差分等于真速度。
5. 同一输入、配置和种子可复现；随机模型的统计验收在 P1 校准后再制定。
6. 导出带单位、仿真标记、配置和种子，可独立重算，不把模拟字段当作测量值。
7. P2 真机验证小屏、横屏、大字体、深浅色以及开始/暂停/停止/导出流程。

## 6. 风险与明确的不做承诺

- **不承诺**能通过任何特定校园跑 App 的检测。这类 App 的检测在持续演进（mock 检测、传感器交叉校验、轨迹形状分析、native 层完整性校验），P4/P5/P7 只提供通道与掩蔽，结果以实测为准。
- 不保证任意区间都有可行解；按显式模型参数求交，并区分“模型配置冲突”和没有证据支持的生理结论。
- 步频注入（IMU 层）需要 root；免 root 路线在传感器层面基本无解，这是平台限制而非实现问题。
- 第三方代码与依赖的许可须在实际引入/分发前审查，不能仅凭“只参考机制”就保证没有许可义务；本次 P0 未复制 GoGoGo 业务代码。标枪定位的相关描述只是上游声明，未独立检查其源码、二进制或许可履行情况。
- **上游公开声明**：GoGoGo README 声明不支持将其用于特定校园运动类 App。本项目不承诺兼容第三方 App 或绕过其检测；用户应遵守服务条款与适用规则。
- 项目自有内容采用 Apache License 2.0；第三方材料另依其上游许可。详见 [`LICENSE`](../LICENSE)、[`CREDITS.md`](../CREDITS.md) 与 [`THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md)。

## 7. 下一步

1. P0 已完成云端验收：run [`35848681022`](https://github.com/lxhtt/SchoolRunningSimulator/actions/runs/35848681022) 三个 job 全绿，14/14 单测通过，lint 通过，APK 已生成并校验；细节见 [`P0-IMPLEMENTATION.md`](P0-IMPLEMENTATION.md)。
2. 剩余唯一验收项：真机安装 APK，确认启动页显示“2.70 米/秒”，并检查小屏/横屏/大字体/深浅色。
3. 真机确认后进入 P1：细化可行域求解、状态机与可校准步态模型，不把未验证的生理系数写死。
4. 可选清理项（不阻塞 P1）：升级 `upload-artifact` 到不含 Node.js 20 弃用的版本；评估 AGP/compileSdk/依赖版本升级；确认 API 37 平台可用性。
