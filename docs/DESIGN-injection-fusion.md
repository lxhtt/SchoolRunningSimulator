# 定位注入机制调研：影梭 / 标枪定位

> 状态：探索性调研笔记，不是实现规范、产品承诺或法律意见。
>
> 初始调研基于公开上游声明及对 GoGoGo 某一版本源码的本地只读检查。本文不提供本机路径；结论不可推广到其他版本。不能据此断言标枪定位与影梭当前版本完全相同，也不能确认其授权、源码完整性或许可证履行情况。
>
> 本项目未复制 GoGoGo 业务代码、资源或二进制。对 Android 行为、mock app 检测、provider 参数及许可的判断都不是已验证结论。P7 开始前需按目标 Android API/设备和具体材料重新进行技术与许可审查。本文不主张规避第三方应用规则。

## 1. 先厘清一件事：标枪定位是什么

公开上游 README 声称标枪定位是影梭的修改版本，并提出相关许可质疑。此处只转述公开说法，尚未独立对照对应版本的源代码或二进制；因此不能断言二者实现完全相同、存在侵权或满足特定许可条件。

对后续计划的影响：不要引入或复制未经审查的第三方可执行体或代码。公开 Android API 的用法可作为研究起点，但“参考机制”并不自动排除版权或其他许可风险。

## 2. 影梭的真实实现（已读源码）

### 2.1 项目坐标

| 项 | 值 |
|---|---|
| 仓库 | [`ZCShou/GoGoGo`](https://github.com/ZCShou/GoGoGo)，本次观察版本 v1.12.3，Java；上游声明 GPL-3.0 |
| `compileSdk` / `targetSdk` / `minSdk` | 32 / 32 / 27 |
| AGP / Gradle | 8.12.1 / 8.13 |
| 地图 SDK | **百度地图 + 百度定位**（`com.baidu.location.f`，`:remote` 进程） |
| 规模 | 4615 行 Java，最大文件 `MainActivity.java` 1284 行 |
| 注入方式 | **Android 调试 API（mock location），免 root** |

### 2.2 注入核心（`service/ServiceGo.java`；本次观察版本为 338 行）

```
ServiceGo.onCreate()
├── removeTestProviderNetwork()  →  addTestProviderNetwork()
├── removeTestProviderGPS()      →  addTestProviderGPS()
├── initGoLocation()             →  HandlerThread "ServiceGoLocation"
├── initNotification()           →  startForeground(NOTE_ID=1)
└── initJoyStick()               →  TYPE_SYSTEM_ALERT 悬浮摇杆
```

注入循环：

```java
// HandlerThread 上自循环，固定 100 ms 间隔
public void handleMessage(Message msg) {
    Thread.sleep(100);
    if (!isStop) {
        setLocationNetwork();
        setLocationGPS();
        sendEmptyMessage(HANDLER_MSG_ID);   // 无限自触发
    }
}
```

`addTestProvider` 参数（`SDK >= S` 用 `ProviderProperties`）：

```java
// GPS  provider
addTestProvider(GPS_PROVIDER, false, true, false, false, true, true, true,
                ProviderProperties.POWER_USAGE_HIGH, ProviderProperties.ACCURACY_FINE);
// NETWORK provider
addTestProvider(NETWORK_PROVIDER, true, false, true, true, true, true, true,
                ProviderProperties.POWER_USAGE_LOW, ProviderProperties.ACCURACY_COARSE);
```

坐标推进（摇杆位移 → 经纬度，平面近似）：

```java
mCurLng += disLng / (111.320 * cos(|mCurLat| * PI / 180));   // 经度每度 ~111.32km
mCurLat += disLat / 110.574;                                  // 纬度每度 ~110.574km
```

写出的 `Location` 对象：

```java
loc.setAccuracy(Criteria.ACCURACY_FINE);   // ← 注意：等于 1.0f，被 setAccuracy(float) 覆写
loc.setAltitude(mCurAlt);
loc.setBearing(mCurBea);
loc.setSpeed((float) mSpeed);              // 摇杆速度，默认 1.2 m/s
loc.setTime(System.currentTimeMillis());
loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
Bundle b = new Bundle(); b.putInt("satellites", 7); loc.setExtras(b);   // 固定 7 颗
setTestProviderLocation(GPS_PROVIDER, loc);
```

### 2.3 权限与外部接口

| 项 | 状态 |
|---|---|
| `ACCESS_MOCK_LOCATION` | 已声明（`tools:ignore="MockLocation"`） |
| `SYSTEM_ALERT_WINDOW` | 已声明（悬浮摇杆） |
| `FOREGROUND_SERVICE` | 已声明 |
| `ServiceGo` | `exported=false` |
| `MainActivity` | `exported=false`（`singleInstance`） |
| AIDL | **无**（全仓 0 个 `.aidl`） |
| 对外 Broadcast | **无**（`NoteActionReceiver` 是给自家通知栏按钮用的） |

即：**影梭没有任何对外集成接口，是个封闭应用。**

## 3. Android 平台观察与待核对事项

### 3.1 当前观察与限制假设

下述结论针对本次观察版本，不代表所有 Android 版本或 ROM 的行为。mock location app 的选择和 test provider 接口应以对应 Android 版本的官方文档及设备实验为准。该应用未暴露给本项目使用的 IPC 接口；未来是否能通过维护 fork 扩展接口是另一问题。

其他备选通道及否决理由：

| 通道 | 本次观察 | 备注 |
|---|---|---|
| 直接复用或修改影梭源码 | 可能引入 GPL-3.0 等许可证义务，并带入地图 UI 等代码 | P0 未复制这些源码；未来应先审查具体材料和分发形式 |
| AIDL / Broadcast 调用 | 本次观察版本未找到供外部调用的接口 | 若维护 fork，可另行评估扩展；非当前范围 |
| 无障碍服务模拟点击影梭摇杆 | UI 自动化方式 | 可靠性、延迟和可访问性需实测，不作为当前设计 |
| HTTP/文件桥接 + LSPosed 改影梭读接口 | 需要额外 IPC/进程配合 | 复杂度和安全性需评估，不作为当前设计 |

### 3.2 Provider 属性须按平台文档与设备验证

不能仅凭某个上游实现推断参数是标准答案或必须照抄。Provider 属性应以 Android SDK 文档和目标系统行为为准，在计划实施时针对支持版本和设备进行测试；这里记录的源码观察不是推荐配置。

以下是基于该版本代码的初步观察，不应视为通用的真人数据标准或已验证的 RateMock 需求。GPS 精度、运动波形和步数受环境、设备与个体影响，未来模型须独立定义和验证。

| 影梭该版本的实现观察 | 验证注意事项 |
|---|---|
| `setAccuracy(ACCURACY_FINE)` 最终以 `1.0f` 写入 | 不可把某个范围说成所有真实 GPS 的精度标准 |
| 坐标由手动操作更新，速度值有默认值 | 不能据此推导真人加速度或 jerk 的统一上限 |
| extras 中的 `satellites` 使用固定值 | 该字段及卫星数量不能简单代表不同设备/环境的定位状态 |
| 海拔由应用当前变量写出 | 是否有地形变化取决于用户数据与场景 |
| 该版本未写出 vertical accuracy 字段 | 不代表平台或设备必然有某个比例关系 |
| location 时间字段在写入时更新 | 具体时基语义应查相应 API 版本并实测 |
| 未见加速度计或步数传感器注入 | 这是该源码观察，不是对所有 fork/版本的判断 |

RateMock 如需仿真这些量，应以公开资料和可复现实验建立独立模型，并清楚标注为模拟数据。

## 4. P7 探索性方案草图（未批准、未实现）

### 4.1 架构

```
┌──────────────────────────────────────────────────────┐
│ RateMock App（自己就是 mock location app）            │
│                                                      │
│  sim-core ──▶ GpsStream ──▶ MockLocationSink         │
│     │                        ├── Android test provider │  ← 参数待平台验证
│     │                        ├── setTestProviderLocation @ 1Hz
│     │                        └── 时变 accuracy/sat/alt/vAcc
│     └────────▶ ImuSynthesizer ──▶ SensorSink          │
│                                  └── LSPosed 层 hook  │  ← 影梭未覆盖的部分
└──────────────────────────────────────────────────────┘
```

### 4.2 分期

| 子阶段 | 内容 | 前置 |
|---|---|---|
| **P7a** | 评估自有 `MockLocationSink` 与位置数据输出 | P1 内核和 Android API/设备验证 |
| **P7b** | 评估 mock location 设置状态的交互与错误提示 | 先核实可用 API、权限与版本差异 |
| **P7c** | 评估 provider 健康检查和读取回验 | Android API/ROM 实测 |
| **P7d** | 评估传感器仿真路径 | 单独审查平台限制、权限与安全边界 |

### 4.3 与影梭共存策略

两者**不能同时被选为 mock app**。策略：

1. RateMock 内置"接管前自检"：若检测到当前 mock app 是 `com.zcshou.gogogo`，提示用户切换并告知影梭必须停止。
2. 不尝试与影梭进程通信（无接口，且会引入不必要的耦合）。
3. 若用户想保留影梭的摇杆手感，可在 RateMock 中提供等价的手动干预入口（后续项，非首版）。

## 5. 许可、合规与风险（研究笔记，非法律意见）

| 主题 | 说明 | 当前边界 |
|---|---|---|
| 第三方许可 | 复制、修改、链接或分发第三方材料的义务依具体材料和适用法律而定 | P0 未复制 GoGoGo 业务代码；未来引入前逐项审查，不以“参考机制”代替审查 |
| 上游公开声明 | GoGoGo README 声明不支持将其用于特定校园运动类 App | 本项目不声称合规或兼容，不宣传绕过检测；使用者应遵守服务条款与适用规则 |
| 百度 SDK | 所观察的上游版本使用百度地图/定位组件 | RateMock P0 未使用百度 SDK；未来地图方案仍未定 |
| 项目许可 | RateMock 自有内容按 Apache License 2.0 授权 | 该许可证不自动覆盖第三方材料，详见根目录 LICENSE 与 THIRD_PARTY_NOTICES.md |

## 6. 待验证事项（只有在重新评估 P7 时适用）

1. `targetSdk >= 31` 下 `addTestProvider` + 前台服务在 Android 12/13/14 的实际行为
2. 不同 ROM（MIUI / ColorOS / OriginOS）对 mock provider 的额外拦截
3. `FusedLocationProviderClient` 对 test provider 的透传行为（精度、速度字段是否被重算）
4. 回读校验：注入后 `getLastKnownLocation` 得到的字段是否与注入值一致
