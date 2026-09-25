# P5 安全范围：事件协议与本地回放

## 范围

本阶段完成 P5 的安全替代交付：定义合成事件协议、离线校验器、本地回放器和 Android 诊断展示。回放只读取应用私有文件并产生诊断结果，不调用 Android mock-location API，不注册或修改系统传感器，不包含 LSPosed/Xposed hook，也不针对第三方应用绕过检测。

## 事件协议

每个事件包含会话 ID、序号、单调时间（秒）、类型、数值、单位和 provenance。支持 `location`、`step_detector`、`step_counter` 三类事件。位置事件使用本地 east/north 米坐标，避免把模拟位置误认为 GPS；真实 GPS 记录继续使用 S7 独立 CSV，不进入这个协议。

允许的 provenance 为 `simulation` 和 `replay`。回放输出保留原始 provenance，并额外标记 `replay`。未知来源、负时间、非有限数值、错误单位、跨会话事件和序号倒退都会被拒绝或报告。

## 校验与回放

校验器按会话排序检查：序号重复/倒退、时间戳倒退、事件间隔过大、步数计数器回退、位置坐标跳变、事件类型与单位不匹配。校验结果分为 `VALID`、`WARNING`、`INVALID`，诊断项不修改输入。

回放器以调用者提供的单调时间推进，输出只读事件视图和统计摘要。它拒绝把回放事件转换成 Android `Location`、SensorEvent 或任何系统级数据源。

## Android 展示

模拟页增加“事件诊断”区域，展示当前私有模拟历史是否可读、样本数、时间跨度、校验状态和警告数。诊断只针对当前 simulation CSV，不读取 recordings 目录，不提供外部发送或注入按钮。

## 隐私与兼容性

协议样本和诊断输出留在应用私有目录；若产生导出文件，必须由用户明确选择位置。模拟、真实观测、回放三种来源分开标记。项目不声明任何第三方应用兼容性、Google Play 资格或绕过检测能力。

## 验收

- sim-core JVM 测试覆盖合法事件、时间/序号错误、计数器回退、跳变和回放统计。
- Android lint/debug APK 通过 CI。
- 离线结构检查确认没有新增系统注入调用或 LSPosed/Xposed 依赖。
- 不把个人 GPS、Apple Health、设备录制或校准输出写入仓库、CI 或 APK。
