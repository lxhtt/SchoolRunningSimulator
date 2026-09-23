#!/usr/bin/env python3
"""Update the small official Gradle 9.7.1 wrapper files; never download Gradle itself."""
from concurrent.futures import ThreadPoolExecutor, as_completed
import hashlib
from pathlib import Path
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
BASE = "https://raw.githubusercontent.com/gradle/gradle/v9.7.1/"
FILES = {
    "gradlew": BASE + "gradlew",
    "gradlew.bat": BASE + "gradlew.bat",
    "gradle/wrapper/gradle-wrapper.jar": BASE + "gradle/wrapper/gradle-wrapper.jar",
}
EXPECTED_JAR = "7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d"


def fetch(entry: tuple[str, str]) -> tuple[str, bytes]:
    name, url = entry
    request = urllib.request.Request(url, headers={"User-Agent": "RateMock-Gradle-Wrapper-Refresh"})
    with urllib.request.urlopen(request, timeout=20) as response:
        payload = response.read(131073)
    if not payload or len(payload) > 131072:
        raise ValueError(f"Refusing unexpected response size for {name}: {len(payload)}")
    return name, payload


def main() -> None:
    fetched: dict[str, bytes] = {}
    errors = []
    with ThreadPoolExecutor(max_workers=3) as executor:
        jobs = {executor.submit(fetch, item): item[0] for item in FILES.items()}
        for job in as_completed(jobs):
            name = jobs[job]
            try:
                fetched[name] = job.result()[1]
            except Exception as error:  # Report every failed upstream object without partial writes.
                errors.append(f"{name}: {error}")
    if errors:
        raise SystemExit("Wrapper refresh aborted; no files written:\n" + "\n".join(errors))

    jar_hash = hashlib.sha256(fetched["gradle/wrapper/gradle-wrapper.jar"]).hexdigest()
    if jar_hash != EXPECTED_JAR:
        raise SystemExit(f"Official Gradle 9.7.1 Wrapper JAR checksum mismatch: {jar_hash}")
    for name, payload in fetched.items():
        target = ROOT / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(payload)
        print(f"Updated {name}: {len(payload)} bytes")
    (ROOT / "gradlew").chmod(0o755)
    print("Verified official Gradle 9.7.1 Wrapper JAR SHA-256; Gradle distribution was not downloaded.")


if __name__ == "__main__":
    main()
