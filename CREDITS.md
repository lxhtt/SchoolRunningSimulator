# Credits and acknowledgements

RateMock is built with the following projects and services. This acknowledgement is informational and does not imply sponsorship, review, or endorsement.

## Languages, platform, and build tools

- [Kotlin](https://kotlinlang.org/) — implementation language and Kotlin/JVM tooling.
- [Android](https://developer.android.com/) and [Jetpack Compose](https://developer.android.com/compose) — Android platform and UI toolkit.
- [Gradle](https://gradle.org/) — build system. The checked-in Wrapper files come from the official [`v9.7.1` source tree](https://github.com/gradle/gradle/tree/v9.7.1); see [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
- [JUnit 5](https://junit.org/junit5/) — JVM test framework.
- [Eclipse Temurin](https://adoptium.net/temurin/) — JDK distribution configured for GitHub-hosted CI runners.
- [GitHub Actions](https://github.com/features/actions) — configured cloud CI service. Workflow actions are pinned to immutable commit SHAs in `.github/workflows/android.yml`.

## Third-party materials

The repository-level Apache License 2.0 applies to RateMock-authored materials except where an individual file or component is identified as third-party. The Gradle Wrapper, workflow actions, and resolved dependencies retain their respective upstream licenses. Review the relevant notices before redistributing them; see [`LICENSE`](LICENSE) and [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

No GoGoGo application source, assets, signing keys, or Baidu SDK are included in this P0 project. The separate injection research note records exploratory observations, not an implementation dependency or endorsement by any project discussed there.

When adding or redistributing a third-party component, update the notices with its name, version, source, and verified license before release.
