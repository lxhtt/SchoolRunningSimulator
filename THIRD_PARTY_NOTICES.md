# Third-party notices

## Scope

The repository-level [`LICENSE`](LICENSE) applies to RateMock-authored material except where an individual file or component is identified as third-party. It does not replace or override licenses for the Gradle Wrapper, workflow actions, or build/runtime dependencies. Review the actual dependency graph and each upstream license before redistributing a complete build.

## Gradle Wrapper

The checked-in `gradlew`, `gradlew.bat`, and `gradle/wrapper/gradle-wrapper.jar` are from the Gradle project's official [`v9.7.1` source tree](https://github.com/gradle/gradle/tree/v9.7.1). The Gradle Wrapper distribution is under the Apache License 2.0; the applicable upstream license notices are preserved in [`third_party/gradle/LICENSE`](third_party/gradle/LICENSE).

- Upstream source: <https://github.com/gradle/gradle/tree/v9.7.1>
- Official checksum registry: <https://gradle.org/release-checksums/>
- Wrapper JAR SHA-256: `7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d`
- Gradle 9.7.1 binary ZIP SHA-256: `acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a`

`distributionSha256Sum` verifies the Gradle distribution when the Wrapper downloads it. `scripts/check_project.py` verifies the checked-in Wrapper JAR without downloading the distribution.

## Other tools and dependencies

Kotlin, Android/Jetpack Compose, JUnit 5, and the pinned GitHub Actions used by this project retain their respective upstream terms. Their use is acknowledged in [`CREDITS.md`](CREDITS.md). Dependency versions are maintained in `gradle/libs.versions.toml`; transitive dependency licenses must be checked against the resolved dependency graph before redistribution.

The AMap Android map, location, and POI SDKs are runtime dependencies pinned in `gradle/libs.versions.toml`. Display coordinates use GCJ-02 while RateMock storage and Android Mock Location use WGS84; search and location results are converted back to WGS84 before entering application state. AMap SDK licenses, service terms, privacy requirements, and map attribution apply when these services are used. The API key is injected from untracked `local.properties` or CI secrets; never commit API keys, keystores, or signing passwords.

No GoGoGo application source, icons, signing keys, or Baidu SDK were copied into this project. Mention of a project in research documentation is not a code dependency or an endorsement.
