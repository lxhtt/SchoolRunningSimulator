# Documentation index

Status statements in these documents are snapshots. The latest verified state is CI run [`35848681022`](https://github.com/lxhtt/SchoolRunningSimulator/actions/runs/35848681022): all three jobs passed (JDK 17 and 21 core tests, Android lint, debug APK). Kotlin compilation, 14/14 unit tests, and Android lint have actually run. Device installation and on-screen verification are still outstanding.

- [Project plan](PROJECT_PLAN.md) — product scope, staged roadmap, and open risks.
- [Build and verification](BUILD.md) — toolchain versions, low-bandwidth checks, CI workflow, verified results, and acceptance criteria.
- [P0 implementation record](P0-IMPLEMENTATION.md) — executed checks with measured results, and what remains.
- [P1 design: sim-core](DESIGN-p1-sim-core.md) — S1、S2、S3 已实现并通过云端验证；S4–S7 按设计切片推进。
- [Injection research notes](DESIGN-injection-fusion.md) — exploratory observations only; not an implementation specification or legal advice.

Repository-wide license, attribution, and third-party information is in the root [`LICENSE`](../LICENSE), [`CREDITS.md`](../CREDITS.md), and [`THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md).
