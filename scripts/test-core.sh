#!/usr/bin/env bash
set -euo pipefail

# Even `gradlew --offline` can download Gradle when the wrapper cache is empty.
# Refuse local execution unless the user explicitly allows dependency downloads.
if [[ "${CI:-}" != "true" && "${RATEMOCK_ALLOW_DOWNLOADS:-}" != "1" ]]; then
    printf '%s\n' 'Local dependency downloads are disabled.' >&2
    printf '%s\n' 'Use CI, or explicitly set RATEMOCK_ALLOW_DOWNLOADS=1 when bandwidth is available.' >&2
    exit 2
fi

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
exec env -u ANDROID_HOME -u ANDROID_SDK_ROOT \
    ./gradlew --no-daemon --console=plain --stacktrace -p sim-core test "$@"
