# P1 设计：`sim-core` 仿真内核

> 状态：S1 已实现并通过云端验证；S2–S7 仍是设计/待实现。
> 本文持续作为 P1 的设计基线；已实现代码以 `sim-core/src/main/kotlin/dev/ratemock/core/feasibility/` 为准。
> 前置：P0 已通过云端实测（run `35848681022`：JDK 17/21 单测 14/14、lint、debug APK）。S1 验证 run：[`35958878288`](https://github.com/lxhtt/SchoolRunningSimulator/actions/runs/35958878288)。

## 1. 范围

### 1.1 目标

让 `sim-core` 能独立产出**可解释、可重复校验**的仿真数据：给定速度/步频/步长约束与一条路线，生成真值轨迹、步事件、GPS 观测、以及可导出文件。S1–S6 全程纯 Kotlin/JVM，不依赖 Android，可在 CI 上单测。

同时提供**校准基础设施**：导入真实跑步数据、拟合步频模型参数、报告拟合质量与适用范围。校准数据的采集方式见 §11，其中 S7 是新增的 Android 端记录器。

### 1.2 明确不做

| 不做 | 原因 |
|---|---|
| 声称参数已校准 | 目前没有任何真实数据；拟合机制先做好，数据由实跑补齐 |
| 心率、着地时间、垂直振幅 | 无来源，不臆造生理公式 |
| 卫星数模型、地形海拔变化 | 推迟到有实测依据时再做 |
| IMU 波形合成 | 属 P5/P7 |
| 地图导入、轨迹可视化 | 属 P2 |
| 定位注入（mock / LSPosed） | 属 P4/P5/P7 |
| 多线程/协程并发 | P1 只需确定性单线程纯函数；并发留给 P2 的 UI 侧 |

### 1.3 已定决策

| 编号 | 决策 | 说明 |
|---|---|---|
| A | **A2：步频随速度变化** | 幂律或线性模型，参数可校准；见 §2.2、§10 |
| B | **B1：库 + 单测 + 最小 CLI** | `sim-core` 加 `application` 插件；手写序列化，零新增依赖 |
| C | 默认参数全部标注"未校准" | 文档、代码注释、导出文件三处同时标注 |
| D | 校准走实跑数据 | 拟合与导入在 P1（S6）实现 |
| E | 采集方式：**前台服务记录器（可息屏）** | 新增 S7：Android 端 GPS + `TYPE_STEP_COUNTER` 同步记录，导出 CSV |
| F | Apple Health 作为并行输入源 | 本机脚本流式解析 `export.xml`，推导（速度, 步频）对；见 §11.4 |

### 1.4 建议交付顺序

P1 较大，按可单独验收的切片推进，每片都可以单独跑 CI。

> S1–S7 代码已完成并通过 run `35999139396` 的编译/lint/APK 验证；S7 的定位、步数权限和息屏行为仍需真机验收。

| 切片 | 内容 | 验收 |
|---|---|---|
| **S1** | `FeasibilitySolver` + `CadencePolicy`（A2 幂律 + 区间夹取 + 夹取计数） | 端点边界、冲突消息、夹取记录测试全绿 |
| **S2** | `SimConfig` + `RunPlan` + `SpeedPlanner` 平滑 | 限加速度/限 jerk/收敛性测试 |
| **S3** | `RunState` + `GaitEngine` + `StepEvent`（真值层） | 恒等式、距离积分、状态机、可复现 |
| **S4** | `Route` + `RouteProjector` + `GpsStream` + `GaussMarkov` | 噪声统计与投影测试 |
| **S5** | `export/*` + `cli/Main` | 导出回读与端到端冒烟 |
| **S6** | `calibration/*`：CSV 导入 + 拟合 + 报告 + CLI 子命令 | 合成数据可还原参数；样本不足被拒绝 |
| **S7** | Android 记录器：前台服务（type=location）+ `TYPE_STEP_COUNTER` → CSV | 真机跑一次，导出的 CSV 能被 S6 直接导入并拟合 |

S1–S6 是纯 JVM 单测；S5、S6 才用到 CLI；S7 是 Android 工作，需要真机验证。

### 1.5 S1 实施结果

S1 已落地并由 GitHub Actions run [`35958878288`](https://github.com/lxhtt/SchoolRunningSimulator/actions/runs/35958878288) 实测通过：

- `GaitLimits`：严格校验稳定步频/步长区间，静止状态单独处理。
- `FeasibilitySolver`：端点可行性判据、精确冲突报告、单速度的可行步频/步长区间；包含浮点边界回归保护。
- `CadencePolicy`：`PowerLawCadence`、`LinearCadence`、`FixedCadence`。
- `GaitResolver`：策略输出夹取到可行区间，保留原始模型值和 `clamped` 标记；恒等式仍成立。
- `RunPlan`：严格校验正速度段、按时间/距离的段限制，以及有序不重叠暂停窗口。
- `SpeedPlanner`：固定步长下的因果速度规划，限制加速度和 jerk；到达静止边界时显式报告 `terminalReset`。
- `TruthSample`、`StepEvent`：真值连续采样与离散步事件，带单位字段和状态校验。
- `GaitEngine`：确定性单线程推进，支持时间/距离段、暂停、停止和步事件累积；瞬态速度使用独立解析路径，稳态解析仍保持严格可行域校验。
- `Route`、`RouteProjector`：短距离等距圆柱局部米制坐标、折线线性投影、ONE_WAY/OUT_AND_BACK/LOOP 路线模式和方位角。
- `GaussMarkovNoise`：带种子的三轴一阶 AR(1) GPS 位置噪声，支持重置并保持稳定方差。
- `GpsObservation`、`CsvExporter`、`JsonExporter`、`GpxExporter`：无新增运行时依赖的 truth/steps/GPS/GPX/summary 导出，明确单位和 simulated/uncalibrated 标记。
- `ExportWriter` 与最小 CLI：可将一次确定性示例仿真写入完整输出目录；CLI 入口为 `dev.ratemock.core.cli.MainKt`。
- `CalibrationCsvParser`、`CalibrationFitter`：校验至少 8 条、速度跨度和正权重，支持幂律/线性加权最小二乘、R²、残差和拟合速度范围。
- `CalibrationJson` 与 CLI `calibrate` 子命令：输出参数来源范围和 `uncalibrated=false` 标记，超出拟合范围可由调用方显式警告。
- S7 `RecorderService`：Android 前台 `location` 服务，同时记录 GPS 和 `TYPE_STEP_COUNTER`，CSV 写入 app 私有 `recordings/` 目录；界面提供权限申请、开始/停止控制。实现已通过云端 Android lint/debug APK 构建，尚未完成真机测试。
- 测试报告：`GaitLimitsTest` 4、`GaitKinematicsTest` 14、`FeasibilitySolverTest` 12、`CadencePolicyTest` 7、`GaitResolverTest` 13、`RunPlanTest` 5、`SpeedPlannerTest` 7、`TruthTypesTest` 1、`GaitEngineTest` 7、`RouteTest` 4、`GaussMarkovNoiseTest` 5、`ExportersTest` 4、`CalibrationTest` 7，共 **90/90 通过**；JDK 17 与 JDK 21 结果一致。
- 同一 workflow 的 Android lint 与 debug APK job 也通过；S1 没有增加 Android 权限或改变 P0 APK 行为。

## 2. 数学基础（可行性判据）

### 2.1 可行性判据

单位恒等式：

```text
v = c · s / 60        v: m/s, c: 步/分, s: m/步
```

给定速度 `v`，步伐必须同时落在两个区间里：

- `s ∈ [sMin, sMax]`
- `c = 60v/s ∈ [cMin, cMax]` ⟺ `s ∈ [60v/cMax, 60v/cMin]`

所以该速度下的可行步长区间是：

```text
s ∈ [ max(sMin, 60v/cMax),  min(sMax, 60v/cMin) ]
```

非空条件（`60v/cMax` 与 `60v/cMin` 都随 `v` 单调增）化简为：

```text
v ≥ sMin·cMin / 60        （下界 vLo）
v ≤ sMax·cMax / 60        （上界 vHi）
```

**结论：速度区间 `[vMin, vMax]` 可行 ⟺ `vMin ≥ vLo` 且 `vMax ≤ vHi`。** 求解器只需比较两个端点，冲突时给出精确数字（例如"要求最低速度 1.50 m/s，低于模型可达下限 1.867 m/s"），不需要数值搜索。

等价地，给定 `v` 时的可行步频区间是：

```text
c ∈ [ max(cMin, 60v/sMax),  min(cMax, 60v/sMin) ]
```

### 2.2 A2 步频策略与夹取

步频由模型给出，再夹取到上述可行区间：

```text
cModel(v) = coefficient · v^exponent          （幂律族）
c(v)      = clamp(cModel(v), feasible(v))      （feasible(v) 按 §2.1）
s(v)      = 60·v / c(v)
```

关键性质：**只要 `v ∈ [vLo, vHi]`，`feasible(v)` 必非空，夹取后的 `c(v)` 仍在该区间内**，因此恒等式精确成立、步长必定落在 `[sMin, sMax]`。也就是说 A2 下可行性完全由 §2.1 的两个端点决定，模型本身不会让区间变得不可行。

夹取是**策略偏离**，不是可行性失败，必须如实记录：`summary.json` 输出 `clampedSampleCount` 与 `clampedFraction`，避免"模型想要的速度-步频组合被静默改成区间边缘"。

模型族（配置二选一）：

| 族 | 形式 | 参数 |
|---|---|---|
| `PowerLaw` | `c = k · v^α` | `k > 0`，`α ∈ (0, 1]` |
| `Linear` | `c = c0 + c1 · v` | 需保证区间内 `c > 0` |

`FixedTarget`（固定步频）仍保留为第三种策略，便于对照与回归测试。

## 3. 模块与包结构

全部放在 `sim-core`，不新增模块：

```text
dev.ratemock.core
├── GaitKinematics.kt          （P0 已有：单位换算与校验）
├── config/
│   ├── SimConfig.kt           配置数据类 + 文档化默认值
│   └── Presets.kt             示例预设（含示例折线路线）
├── feasibility/
│   ├── FeasibilitySolver.kt   §2.1 的判据与冲突报告
│   └── CadencePolicy.kt       策略接口 + PowerLaw / Linear / FixedTarget
├── plan/
│   ├── RunPlan.kt             段序列 + 暂停窗口 + 会话级约束
│   └── SpeedPlanner.kt        jerk 限制平滑（§5）
├── engine/
│   ├── GaitEngine.kt          主循环：时间推进 → 速度 → 步事件 → 位置
│   ├── RunState.kt            状态机（§7）
│   └── StepEvent.kt           步事件与真值采样
├── route/
│   ├── Route.kt               折线路线（点、模式、长度）
│   └── RouteProjector.kt      经纬度 ↔ 局部米制投影 + 切向方位角
├── gps/
│   ├── GaussMarkov.kt         一阶自回归噪声（§6）
│   └── GpsStream.kt           真值 → 观测（采样率、精度字段）
├── calibration/
│   ├── CalibrationCsv.kt      解析 (speed, cadence) 样本
│   ├── CadenceFitter.kt       幂律/线性最小二乘拟合
│   └── CalibrationReport.kt   拟合质量、残差、适用范围
├── export/
│   ├── CsvWriter.kt
│   ├── JsonWriter.kt          手写，字段名带单位
│   └── GpxWriter.kt
└── cli/
    └── Main.kt                CLI 入口（B1）
```

`sim-core/build.gradle.kts` 只增加 `application` 插件（不引入新依赖）。手写 CSV/JSON/GPX 序列化避免引入解析库，代价是需自己保证转义与格式正确——用单测覆盖。

## 4. 数据模型

```kotlin
data class SimConfig(
    val seed: Long,
    val schemaVersion: Int,                    // 配置结构版本，导出时写入，用于判断旧导出能否重算
    val cadenceRangeSpm: ClosedFloatingPointRange<Double>,
    val stepLengthRangeM: ClosedFloatingPointRange<Double>,
    val cadencePolicy: CadencePolicyConfig,    // §2.2：PowerLaw / Linear / FixedTarget
    val segments: List<Segment>,
    val pauses: List<PauseWindow>,             // 确定性暂停窗口
    val route: Route,
    val timebase: Timebase,
    val dynamics: DynamicsLimits,
    val gpsNoise: GaussMarkovConfig?,          // null = 只输出真值，不生成观测层
)

sealed interface CadencePolicyConfig {
    data class PowerLaw(val coefficient: Double, val exponent: Double) : CadencePolicyConfig
    data class Linear(val intercept: Double, val slope: Double) : CadencePolicyConfig
    data class FixedTarget(val cadenceSpm: Double) : CadencePolicyConfig
}

data class Segment(val targetSpeedMps: Double, val limit: SegmentLimit)
sealed interface SegmentLimit {
    data class Duration(val seconds: Double) : SegmentLimit
    data class Distance(val meters: Double) : SegmentLimit
}

data class PauseWindow(val startAfterSeconds: Double, val durationSeconds: Double)
data class Timebase(val simStepHz: Double, val gpsSampleHz: Double)
data class DynamicsLimits(val maxAccelMps2: Double, val maxJerkMps3: Double)

data class GaussMarkovConfig(
    val horizontalSigmaM: Double,
    val verticalSigmaM: Double,
    val correlationTimeSeconds: Double,
)
```

真值层与观测层严格分离：

- `TruthSample(t, distanceM, eastM, northM, speedMps, cadenceSpm, stepLengthM, state)` —— 满足恒等式；`eastM`/`northM` 是相对路线起点的局部米制坐标。
- `StepEvent(t, distanceM, stepLengthM, speedMps)` —— 离散步事件。
- `GpsObservation(t, lat, lon, altitudeM, horizontalAccuracyM, verticalAccuracyM, speedMps, bearingDeg)` —— 真值叠加噪声，**只标为观测值**。

`SegmentLimit.Distance` 用累计距离自然终止，不需要迭代求解——因为平滑滤波器是因果的。

## 5. 速度平滑（分段配速 → 连续可导速度）

状态为 `(v, a)`，每个仿真步 `Δt` 推进：

```text
1. 目标速度 vTarget ← 当前段（含暂停 → 0）
2. 期望加速度 aWish = clamp((vTarget - v) / Δt, -maxAccel, +maxAccel)
3. jerk 限制：a ← a + clamp(aWish - a, -maxJerk·Δt, +maxJerk·Δt)
4. 再次夹取 abs(a) ≤ maxAccel
5. v ← max(0, v + a·Δt)     // 速度不允许为负，触地即置 0 并清 a
```

选择因果滤波器而不是解析 S 曲线的原因：P2/P3 需要"实时"推进（UI 刷新与真跑助手），因果滤波器天然可复用；代价是段切换处不是解析最优轨迹，但满足"限加速度、限 jerk、无阶跃"的验收要求。

单测断言：`abs(a) ≤ maxAccel`、`abs(jerk) ≤ maxJerk`（含段切换与暂停时刻）、`v ≥ 0`、以及长时间稳定后 `v → vTarget`。

## 6. GPS 观测层

一阶 Gauss-Markov（等价 AR(1)），对东/北/垂直三个分量独立：

```text
x[k+1] = x[k]·exp(-Δt/τ) + σ·sqrt(1 - exp(-2Δt/τ))·N(0,1)
```

- 平稳方差 `σ²`、相关时间 `τ`；`τ → 0` 退化为白噪声。
- `N(0,1)` 来自单一种子的 `Random`，保证可复现。
- 噪声加在**局部米制坐标**上，再投影回经纬度，避免直接扰动经纬度导致各向异性。

观测层额外字段：`horizontalAccuracyM`、`verticalAccuracyM` 由噪声幅度导出（不是真值精度），`speedMps`/`bearingDeg` 取自真值（真实 GPS 也会给出速度与航向，此处不做二次噪声，明确记为简化）。

单测断言：样本自相关接近理论 `exp(-Δt/τ)`（容差内）、均值接近 0、方差接近 `σ²`、`τ→0` 时退化为不相关、同种子完全可复现。

## 7. 状态机

```text
STARTING ──▶ RUNNING ──▶ PAUSED ──▶ RUNNING ──▶ FINISHED
    │            │                                  ▲
    └────────────┴──────────────────────────────────┘
```

- 零速度约定：`v = 0` 时 `c = 0`、`s = 0`，恒等式两侧同为 0（与 P0 现有校验一致，不引入"零步频非零速度"的非法态）。
- 暂停：段计时与 GPS 采样暂停，`state = PAUSED`，真值位置不变；恢复后继续。
- 结束条件：段序列走完，或路线在 `ONE_WAY` 模式下耗尽（终止态 `ROUTE_EXHAUSTED`，在 summary 中报告，不静默截断）。
- 非法输入（NaN、Infinity、负值、空段、区间反转、非正模型参数）在构造 `SimConfig` 时即抛错，不做"默默夹取"。

## 8. 路线

- 折线点 `(lat, lon, alt?)`，支持 `ONE_WAY`、`OUT_AND_BACK`、`LOOP`。
- 投影用等距圆柱近似（参考纬度处固定比例），文档写明这是**局部近似**及其适用距离（数公里内误差可忽略；跨纬度长距离不适用）。
- 方位角取当前折线段的切向；`alt` 缺省时高度为常量 0。
- 逐段线性插值得到局部米制位置，再由真值距离查表得到经纬度。
- `ONE_WAY` 且路线长度小于规划距离时，按 §7 报 `ROUTE_EXHAUSTED`。

## 9. 导出

CLI 一次运行输出一组文件：

| 文件 | 内容 | 用途 |
|---|---|---|
| `truth.csv` | `t, distance_m, east_m, north_m, speed_mps, cadence_spm, step_length_m, state` | 曲线目视比对、恒等式核验 |
| `steps.csv` | `t, distance_m, step_length_m, speed_mps` | 步事件序列 |
| `gps.csv` | `t, lat, lon, altitude_m, horizontal_accuracy_m, vertical_accuracy_m, speed_mps, bearing_deg` | 观测层核验 |
| `track.gpx` | 观测轨迹（标准 GPX） | 后续注入与地图核验 |
| `summary.json` | 配置、种子、schema 版本、可行性报告、可行速度区间、夹取比例、达成距离/时长/步数 | 可独立重算 |

所有响应字段名带单位后缀；JSON 写入 `uncalibrated: true` 与 `simulated: true` 标记，避免被当作实测数据。若配置使用了拟合参数，summary 同时记录参数来源与拟合时的适用速度范围。

## 10. 参数默认值（全部标注"未校准"）

| 参数 | 默认值 | 来源标注 |
|---|---|---|
| `simStepHz` | 10 Hz | 工程默认（数值积分精度与耗时的折中） |
| `gpsSampleHz` | 1 Hz | 常见跑步应用采样率，非普适标准 |
| `maxAccelMps2` | 2.0 | 工程默认；公开运动学量级，**未针对本项目校准** |
| `maxJerkMps3` | 8.0 | 工程默认，无生理来源 |
| `horizontalSigmaM` | 4.0 | 与常见消费级定位精度量级一致，**未校准** |
| `verticalSigmaM` | 8.0 | 取水平 2 倍，**未校准** |
| `correlationTimeSeconds` | 30.0 | 工程默认，**未校准** |
| `cadenceRangeSpm`（示例） | 160–190 | **示例配置，非生理结论** |
| `stepLengthRangeM`（示例） | 0.70–1.30 | **示例配置，非生理结论** |
| `PowerLaw.coefficient` | 127.0 | **未校准**；以"2.5 m/s 时约 175 步/分"锚定 |
| `PowerLaw.exponent` | 0.35 | **未校准**；量级取自公开步态讨论，非本项目实测 |
| `route`（示例） | 100 m × 100 m 方形环，锚点坐标标注为占位示例 | **非真实场地** |

用这组示例参数算出的可达速度区间是 `vLo = 0.70×160/60 = 1.867 m/s`、`vHi = 1.30×190/60 = 4.117 m/s`。幂律在区间两端会被夹取（低于 1.9 m/s 触 `cMin`，高于约 3.5 m/s 触 `cMax`），这是刻意的：它正好演示"夹取要被记录"这条规则。

这些量级参考了公开资料与常见设备行为，但**没有任何一项来自本项目自己的实测**。文档与导出都保留这一标注。

## 11. 校准

### 11.1 输入契约

校准输入是一张 CSV，每行一个"稳定速度 ↔ 步频"观测：

```text
speed_mps,cadence_spm,weight
2.50,172.0,1.0
3.10,178.5,1.0
```

- 至少 8 行，且 `ln v` 方差非零、覆盖速度跨度不少于 0.5 m/s；否则拒绝并说明原因。
- 允许可选 `weight` 列；缺省等权。
- 采样协议要求：每行取**稳定段**的平均速度与平均步频，不含起步加速与暂停，避免把过渡过程当成稳态关系拟合。

### 11.2 拟合

- 幂律：对 `ln c = ln k + α·ln v` 做（加权）最小二乘。
- 线性：对 `c = c0 + c1·v` 做（加权）最小二乘。
- 报告 `k`/`α` 或 `c0`/`c1`、`R²`、残差序列、样本数、覆盖速度区间，并写入 `calibration.json`。

### 11.3 诚实性约束

- 拟合结果**只在覆盖速度区间内**可用；summary 必须带 `fittedSpeedRangeMps`，配置若超出该范围要给出显式警告。
- 不允许把 `R²` 高当作"模型正确"的证据；报告保留残差图数据，供人工检查系统性偏差。
- 校准产物与"未校准默认值"在导出中必须可区分（记录参数来源字段）。

### 11.4 数据从哪来（已定）

两条并行路径，互补：

#### 路径一：Apple Health 历史数据（本机脚本）

不需要写 Android 代码，且能拿到**历史多次跑步**，比单次实验更可信。

- 导出：iPhone 健康 App → 头像 → 导出所有健康数据，得到 `export.xml` 与每次训练的 `workout-routes/route_*.gpx`。
- 可用字段：`HKQuantityTypeIdentifierStepCount`、`DistanceWalkingRunning`；Apple Watch 可能还提供 `RunningSpeed` 与 `RunningStrideLength`。
- 推导方式：步频 = 时间段内步数 / 持续时间；当前实现的速度来自同次运动的 GPX 点速度（仅采用时间连续且水平精度合格的点）。没有匹配路线的 iPhone/Watch 历史记录暂不参与此脚本，`DistanceWalkingRunning` 的无路线回退尚未实现。
- 实现：`scripts/apple_health_calibration.py` 两次流式扫描大 XML，按步行/跑步 Workout 分类，同一来源的短步数记录按窗口聚合，与对应 GPX 中有时间戳且水平精度合格的速度配对；速度不稳定、轨迹缺口、暂停/起止边缘均剔除。输出 `walking-calibration.csv`、`running-calibration.csv` 和无坐标的质量报告。缺少匹配 GPX 的运动不会参与这一版提取。
- 使用（全部在本机运行，不调用 Gradle、不上传数据）：

  ```bash
  umask 077
  python3 scripts/apple_health_calibration.py --input '/path/to/apple_health_export/导出.xml' --routes '/path/to/apple_health_export/workout-routes' --out-dir '/path/to/private-output'
  ```

- 只有样本数与速度跨度满足 S6 的最低输入条件，**不代表个人模型已验收**；质量报告标记为探索性。不同运动次数、残差和适用范围还须人工检查，不能将这种拟合直接当作默认生理参数。
- 步行和跑步分别用 S6 `calibrate` 拟合。`AutoGaitSelector` 在目标速度超过两档拟合范围中点时选跑步模型；有上一档状态时使用 0.1 m/s 滞回，并显式返回是否超出所选档的实测速度范围。P2 模拟器界面接入尚未完成。
- 已知局限：
  - 窗口平均粒度粗（步数记录是分批上报的），不如 S7 的逐秒采样干净。
  - 没有匹配 GPX 时当前脚本不会拟合该运动；设备上报的步数批次可能跨越暂停，稳态筛选只是启发式，不是生理真实性证明。
  - 步数会包含热身、走路、休息段，必须按窗口筛稳定段（与 §11.1 的采样协议一致）。
  - 无可用 GPX、不同来源的步数重复记录、定位精度差或长时间轨迹缺口都会减少可用样本；**不能**为凑满拟合最低行数而填补或合并这些记录。
  - 原始导出包含其他敏感健康信息，不放进项目目录、CI、Git 或任何公共产物。提取 CSV、质量报告及个人拟合 JSON 仅保存在本机私有目录。

#### 路径二：App 内前台服务记录器（S7）

单次、但采样干净：GPS 与步数传感器同步逐秒记录，正是校准需要的稳态关系数据。

- 组件：前台服务（`foregroundServiceType="location"`）+ `LocationManager`/`FusedLocationProvider` 与 `TYPE_STEP_COUNTER`。
- 权限：`ACCESS_FINE_LOCATION`、`ACTIVITY_RECOGNITION`（Android 10+ 读步数所需）、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_LOCATION`。
- 输出：带时间戳的 `(lat, lon, speed_mps, accuracy_m, cumulative_steps)` 原始 CSV；再由同一个离线脚本或 S6 的窗口化逻辑转成 §11.1 的配对 CSV。
- 顺带价值：给 P3 真跑模式踩坑（前台服务生命周期、权限、传感器可用性）。
- 注意：记录的是用户自己的运动数据，只存在本机，不上传；仍需在文档与界面上说明这一点。

两种路径的输出最终都汇入 `calibration.csv`，由 S6 拟合，区别只在采样粒度与覆盖范围。

## 12. 测试与验收映射

| 计划验收项 | 对应测试 |
|---|---|
| §5.1 单位明确、真值层满足恒等式 | 每个真值样本断言 `abs(v − c·s/60) < 1e-9`（相对）；穷举参数网格 |
| §5.2 区间可行性、无解时给冲突 | `FeasibilitySolver` 端点边界测试；冲突消息包含精确数字；区间无解与策略夹取分开断言 |
| §5.3 状态行为与非法输入 | 状态机转移表逐条测试；NaN/Infinity/负值/空段/区间反转/非正模型参数必须抛错 |
| §5.4 累计距离与速度积分一致 | 距离由速度积分得到（残差累加器生成步事件），断言 `abs(distance − ∫v dt)` 在容差内；步事件数与 `∫c/60 dt` 一致 |
| §5.5 同种子可复现 | 相同 `(config, seed, schemaVersion)` 逐字节相同；不同种子必须不同 |
| §5.6 导出带单位、可重算 | CSV/JSON/GPX 解析回读校验；字段名含单位；`uncalibrated`/`simulated` 标记存在 |
| 噪声模型 | 自相关、均值、方差、`τ→0` 退化、可复现 |
| 平滑限制 | `abs(a)` 与 `abs(jerk)` 上界；段切换无阶跃；稳定段收敛到目标 |
| A2 夹取 | 步长始终在区间内；夹取计数与实际夹取样本一致；夹取后恒等式仍精确成立 |
| 校准拟合 | 由已知 `(k, α)` 合成含噪样本可还原参数；样本不足/跨度不足/退化方差必须拒绝；残差被报告 |

`scripts/check_project.py` 保留 P0 的 14 项基线测试不变，同时统计所有 Kotlin 测试；实现 S1 后该检查会报告总数，不能再把总数硬编码为 14。

## 13. 风险

| 风险 | 影响 | 处置 |
|---|---|---|
| 默认参数无实测支撑，被误当作真实结论 | 用户误信 | 文档、代码注释、导出文件三处同时标注"未校准"；参数来源字段可机器区分 |
| A2 在区间两端被大量夹取，实际退化成固定步频 | 模型名不副实 | 导出夹取比例；若比例过高在 summary 中提示"该速度段实际由区间边界主导" |
| 校准样本只覆盖窄速度段 | 外推错误 | 强制记录 `fittedSpeedRangeMps`；超出范围时显式警告 |
| 平滑滤波器在段极短时超调或来回振荡 | 速度曲线不平滑 | 单测覆盖"极短段"与"频繁切换"；必要时限制段最小时长 |
| 等距圆柱投影在长距离失真 | 轨迹形状错误 | 文档写明适用距离；跨纬度场景报错而非静默失真 |
| 手写 JSON/CSV/GPX 转义出错 | 导出文件损坏 | 专门测试转义、空值、非 ASCII、超长小数 |
| 路线耗尽未报告 | 数据被静默截断 | `ROUTE_EXHAUSTED` 终止态 + summary 明确字段 |
| Apple Health 导出文件过大导致脚本内存溢出 | 无法处理数据 | 强制流式解析，不一次性读入；设定可配置的窗口大小 |
| 步数记录是分批上报的，差分得到的步频有阶梯 | 拟合偏差 | 窗口化与稳定段筛选；报告窗口长度与阶梯现象；优先用 S7 的逐秒数据交叉验证 |
| S7 权限被拒或传感器不可用 | 记录器失效 | 明确提示缺哪个权限/传感器；不静默降级成"只有 GPS"却仍叫校准数据 |
| S7 改动 Android 权限，与 P0 的"零权限"断言冲突 | 检查脚本失败 | P0 的零权限只是 P0 验收项；S7 落地时同步更新 `scripts/check_project.py` 与文档描述 |

## 14. 待评审确认

1. §2.2 的模型族与夹取规则是否接受（夹取要记录、恒等式仍精确成立）？
2. §11.4 的两条采集路径是否接受（Apple Health 脚本 + S7 前台服务记录器，两者都要）？
3. §10 默认值是否接受（全部标注未校准）？
4. S1–S7 的切片顺序是否接受？是否先做 S1–S6（纯 JVM），把 S7 放到校准数据真正需要时再做？
5. §1.2 "不做"清单是否需要增删？
