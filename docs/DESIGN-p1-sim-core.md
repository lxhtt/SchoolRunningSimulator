# P1 设计：`sim-core` 仿真内核

> 状态：设计待评审。本文只描述设计，**未实现、未测试**。
> 前置：P0 已通过云端实测（run `35848681022`：JDK 17/21 单测 14/14、lint、debug APK）。
> 已确认的选择：**无真实校准数据**、**分段配速 + 平滑过渡**、**可配置折线路线**。

## 1. 范围

### 1.1 目标

让 `sim-core` 能独立产出**可解释、可重复校验**的仿真数据：给定速度/步频/步长约束与一条路线，生成真值轨迹、步事件、GPS 观测、以及可导出文件。全程纯 Kotlin/JVM，不依赖 Android，可本地单测（CI 上跑）。

### 1.2 明确不做

| 不做 | 原因 |
|---|---|
| 真实数据校准 | 用户暂无真实跑步数据；参数一律标注"未校准" |
| 心率、着地时间、垂直振幅 | 无来源，不臆造生理公式 |
| 卫星数模型、地形海拔变化 | 推迟到有实测依据时再做 |
| IMU 波形合成 | 属 P5/P7 |
| 地图导入、轨迹可视化 | 属 P2 |
| 定位注入（mock / LSPosed） | 属 P4/P5/P7 |
| 多线程/协程并发 | P1 只需确定性单线程纯函数；并发留给 P2 的 UI 侧 |

### 1.3 需要你拍板的两个选项

| 编号 | 选项 | 我的推荐 | 理由 |
|---|---|---|---|
| **A** | 步频策略：A1 固定目标步频（步长由恒等式导出）／A2 步频随速度变化（幂律或线性） | **A1** | 没有校准数据时，A2 的系数只能是臆测。A1 把不确定性集中到一个可解释参数上；接口留 `CadencePolicy`，有数据后再插 A2 |
| **B** | 交付形态：B1 库 + 单测 + 最小 CLI／B2 只交付库 + 单测 | **B1** | 计划里"10 次不同随机种子的曲线目视比对"需要能脱离 Android 跑出数据；CLI 可零新增依赖实现 |

## 1.4 建议交付顺序

P1 较大，按可单独验收的切片推进，每片都可以单独跑 CI：

| 切片 | 内容 | 验收 |
|---|---|---|
| **S1** | `FeasibilitySolver` + `CadencePolicy`（A1） | 端点边界与冲突消息测试全绿 |
| **S2** | `SimConfig` + `RunPlan` + `SpeedPlanner` 平滑 | 限加速度/限 jerk/收敛性测试 |
| **S3** | `RunState` + `GaitEngine` + `StepEvent`（真值层） | 恒等式、距离积分、状态机、可复现 |
| **S4** | `Route` + `RouteProjector` + `GpsStream` + `GaussMarkov` | 噪声统计与投影测试 |
| **S5** | `export/*` + `cli/Main` | 导出回读与端到端冒烟 |

前 4 片是纯 JVM 单测；第 5 片才需要 CLI。若某片暴露出设计问题，不会拖累已验收的切片。

## 2. 数学基础（可行性判据）

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

**结论：整个速度区间 `[vMin, vMax]` 可行 ⟺ `vMin ≥ vLo` 且 `vMax ≤ vHi`。** 求解器只需比较两个端点，冲突时能给出精确数字（例如"要求最低速度 1.50 m/s，低于模型可达下限 1.867 m/s"），不需要数值搜索。

选定策略 A1（固定步频 `cTarget`）后，还有一层更严格的检查：整段计划速度必须满足

```text
vMin ≥ sMin·cTarget / 60   且   vMax ≤ sMax·cTarget / 60
```

`FeasibilitySolver` 同时报告这两层结果，并区分"区间本身无解"与"当前步频策略下无解"。

## 3. 模块与包结构

全部放在 `sim-core`，不新增模块：

```text
dev.ratemock.core
├── GaitKinematics.kt          （P0 已有：单位换算与校验）
├── config/
│   ├── SimConfig.kt           配置数据类 + 文档化默认值
│   └── Presets.kt             示例预设（含示例折线路线）
├── feasibility/
│   ├── FeasibilitySolver.kt   §2 的判据与冲突报告
│   └── CadencePolicy.kt       接口 + FixedTargetCadence 实现
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
├── export/
│   ├── CsvWriter.kt
│   ├── JsonWriter.kt          手写，字段名带单位
│   └── GpxWriter.kt
└── cli/
    └── Main.kt                CLI 入口（选项 B1）
```

`sim-core/build.gradle.kts` 只增加 `application` 插件（不引入新依赖）。手写 CSV/JSON/GPX 序列化避免引入解析库，代价是需自己保证转义与格式正确——用单测覆盖。

## 4. 数据模型

```kotlin
data class SimConfig(
    val seed: Long,
    val schemaVersion: Int,                    // 配置结构版本，导出时写入，用于判断旧导出能否重算
    val cadenceRangeSpm: ClosedFloatingPointRange<Double>,
    val stepLengthRangeM: ClosedFloatingPointRange<Double>,
    val cadencePolicy: CadencePolicyConfig,    // 选项 A：FixedTarget(targetSpm) / 未来可加模型型
    val segments: List<Segment>,
    val pauses: List<PauseWindow>,             // 确定性暂停窗口
    val route: Route,
    val timebase: Timebase,
    val dynamics: DynamicsLimits,
    val gpsNoise: GaussMarkovConfig?,           // null = 只输出真值，不生成观测层
)

data class Segment(
    val targetSpeedMps: Double,
    val limit: SegmentLimit,                   // Duration(秒) 或 Distance(米)
)

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
- 非法输入（NaN、Infinity、负值、空段、区间反转）在构造 `SimConfig` 时即抛错，不做"默默夹取"。

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
| `summary.json` | 配置、种子、版本、可行性报告、达成距离/时长/步数 | 可独立重算 |

所有响应字段名带单位后缀；JSON 写入 `uncalibrated: true` 与 `simulated: true` 标记，避免被当作实测数据。

## 10. 参数默认值（全部标注"未校准"）

| 参数 | 默认值 | 来源标注 |
|---|---|---|
| `simStepHz` | 10 Hz | 工程默认（数值积分精度与耗时的折中） |
| `gpsSampleHz` | 1 Hz | 常见跑步应用采样率，非普适标准 |
| `maxAccelMps2` | 2.0 | 工程默认；公开运动学文献量级，**未针对本项目校准** |
| `maxJerkMps3` | 8.0 | 工程默认，无生理来源 |
| `horizontalSigmaM` | 4.0 | 与常见消费级定位精度量级一致，**未校准** |
| `verticalSigmaM` | 8.0 | 取水平 2 倍，**未校准** |
| `correlationTimeSeconds` | 30.0 | 工程默认，**未校准** |
| `cadenceRangeSpm`（示例） | 160–190 | **示例配置，非生理结论** |
| `stepLengthRangeM`（示例） | 0.70–1.30 | **示例配置，非生理结论** |
| `cadenceTargetSpm`（A1） | 区间中点（示例 175） | 示例配置 |
| `route`（示例） | 100 m × 100 m 方形环，锚点坐标标注为占位示例 | **非真实场地** |

这些量级参考了公开资料与常见设备行为，但**没有任何一项来自本项目自己的实测**。文档与导出都保留这一标注。

## 11. 测试与验收映射

| 计划验收项 | 对应测试 |
|---|---|
| §5.1 单位明确、真值层满足恒等式 | 每个真值样本断言 `abs(v − c·s/60) < 1e-9`（相对）；穷举参数网格 |
| §5.2 区间可行性、无解时给冲突 | `FeasibilitySolver` 端点边界测试；冲突消息包含精确数字；策略无解与区间无解分开断言 |
| §5.3 状态行为与非法输入 | 状态机转移表逐条测试；NaN/Infinity/负值/空段/区间反转必须抛错 |
| §5.4 累计距离与速度积分一致 | 距离由速度积分得到（残差累加器生成步事件），断言 `abs(distance − ∫v dt)` 在容差内；步事件数与 `∫c/60 dt` 一致 |
| §5.5 同种子可复现 | 相同 `(config, seed, version)` 逐字节相同；不同种子必须不同 |
| §5.6 导出带单位、可重算 | CSV/JSON/GPX 解析回读校验；字段名含单位；`uncalibrated`/`simulated` 标记存在 |
| 噪声模型 | 自相关、均值、方差、`τ→0` 退化、可复现 |
| 平滑限制 | `abs(a)` 与 `abs(jerk)` 上界；段切换无阶跃；稳定段收敛到目标 |

`scripts/check_project.py` 目前硬编码"14 个测试"，实现 P1 时必须同步更新该计数与检查项。

## 12. 风险

| 风险 | 影响 | 处置 |
|---|---|---|
| 默认参数无实测支撑，被误当作真实结论 | 用户误信 | 文档、代码注释、导出文件三处同时标注"未校准" |
| 平滑滤波器在段极短时超调或来回振荡 | 速度曲线不平滑 | 单测覆盖"极短段"与"频繁切换"；必要时限制段最小时长 |
| 等距圆柱投影在长距离失真 | 轨迹形状错误 | 文档写明适用距离；跨纬度场景报错而非静默失真 |
| 手写 JSON/CSV/GPX 转义出错 | 导出文件损坏 | 专门测试转义、空值、非 ASCII、超长小数 |
| 路线耗尽未报告 | 数据被静默截断 | `ROUTE_EXHAUSTED` 终止态 + summary 明确字段 |

## 13. 待评审确认

1. 选项 **A**（步频策略）取 A1 固定目标步频？
2. 选项 **B**（交付形态）取 B1 含最小 CLI？
3. §10 默认值的取舍是否接受（全部标注未校准）？
4. §1.2 "不做"清单是否需要增删？
