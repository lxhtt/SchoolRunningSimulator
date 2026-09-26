#!/usr/bin/env bash
set -euo pipefail

if [[ "${CI:-}" != "true" && "${RATEMOCK_ALLOW_DOWNLOADS:-}" != "1" ]]; then
    printf '%s\n' 'Local dependency downloads are disabled. Build the APK in CI.' >&2
    exit 2
fi

if [[ ! -d "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" ]]; then
    printf '%s\n' 'Android SDK not found. This command is intended for the CI Android job.' >&2
    exit 2
fi

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
exec ./gradlew --no-daemon --console=plain --stacktrace :app:lintDebug :receiver-ui:lintDebug :receiver:lintDebug :app:assembleDebug :receiver:assembleDebug "$@"
