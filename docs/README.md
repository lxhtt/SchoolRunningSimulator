# Documentation index

最新验证状态：CI run [`36055213349`](https://github.com/lxhtt/SchoolRunningSimulator/actions/runs/36055213349) 的 JDK 17/21 核心测试、Python 桥接测试、Android lint 和 debug APK 均通过。S7 连续写入已在真机验证；设备 ROM 的息屏省电限制与 S7→S6 桥接质量结果见 P1 设计文档。个人健康数据不在仓库或 CI。

- [Project plan](PROJECT_PLAN.md) — product scope, staged roadmap, and open risks.
- [Build and verification](BUILD.md) — toolchain versions, low-bandwidth checks, CI workflow, verified results, and acceptance criteria.
- [P0 implementation record](P0-IMPLEMENTATION.md) — executed checks with measured results, and what remains.
- [P1 design: sim-core](DESIGN-p1-sim-core.md) — S1–S7 与 S7→S6 桥接已实现；S7 真机连续记录已验证，息屏限制和个人数据质量结论已记录。
- [Injection research notes](DESIGN-injection-fusion.md) — exploratory observations only; not an implementation specification or legal advice.
- [P5 safe replay design](superpowers/specs/2026-09-25-p5-safe-replay-design.md) — 本地事件协议、校验、离线回放与 Android 诊断；明确不实现系统注入。

Repository-wide license, attribution, and third-party information is in the root [`LICENSE`](../LICENSE), [`CREDITS.md`](../CREDITS.md), and [`THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md).
