# Documentation index

最新已验证基线：CI run [`36252420344`](https://github.com/lxhtt/SchoolRunningSimulator/actions/runs/36252420344) 的 JDK 17/21 核心测试、Android lint 和两个 debug APK 均通过。新增位置工作台尚未提交或由 CI/真机验证。个人健康数据不在仓库或 CI。

- [Project plan](PROJECT_PLAN.md) — product scope, staged roadmap, and open risks.
- [Build and verification](BUILD.md) — toolchain versions, low-bandwidth checks, CI workflow, verified results, and acceptance criteria.
- [P0 implementation record](P0-IMPLEMENTATION.md) — executed checks with measured results, and what remains.
- [P1 design: sim-core](DESIGN-p1-sim-core.md) — S1–S7 与 S7→S6 桥接已实现；S7 真机连续记录已验证，息屏限制和个人数据质量结论已记录。
- [Injection research notes](DESIGN-injection-fusion.md) — exploratory observations only; not an implementation specification or legal advice.
- [P3 run assistance design](superpowers/specs/2026-09-25-p3-run-assistance-design.md) — 实跑语音提示、真实观测独立导出与验收约束。
- [P5 safe replay design](superpowers/specs/2026-09-25-p5-safe-replay-design.md) — 本地事件协议、校验、离线回放与 Android 诊断；明确不实现系统注入。
- [Position workbench design](superpowers/specs/2026-09-27-position-workbench-design.md) — 原创地图/搜索、历史、摇杆与独立 Android Mock Location 服务；仍待 CI 和设备验收。

Repository-wide license, attribution, and third-party information is in the root [`LICENSE`](../LICENSE), [`CREDITS.md`](../CREDITS.md), and [`THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md).
