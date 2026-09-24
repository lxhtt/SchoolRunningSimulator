#!/usr/bin/env python3
"""Offline P0 structural checks; deliberately does not invoke Gradle or Kotlin."""

import hashlib
import os
from pathlib import Path
import re
import subprocess
import sys
import tomllib
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ANDROID = "{http://schemas.android.com/apk/res/android}"
CHECKS = 0


def require(condition: bool, message: str) -> None:
    global CHECKS
    CHECKS += 1
    if not condition:
        raise ValueError(message)


def text(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def main() -> None:
    required = [
        "gradlew", "gradlew.bat", "gradle/wrapper/gradle-wrapper.jar",
        "gradle/wrapper/gradle-wrapper.properties", "gradle/libs.versions.toml",
        "settings.gradle.kts", "build.gradle.kts", "app/build.gradle.kts",
        "app/src/main/AndroidManifest.xml", "sim-core/settings.gradle.kts",
        "sim-core/build.gradle.kts", "scripts/test-core.sh",
        "scripts/build-android.sh", "scripts/update-gradle-wrapper.py",
        ".github/workflows/android.yml", "third_party/gradle/LICENSE",
        "LICENSE", "README.md", "CREDITS.md", "THIRD_PARTY_NOTICES.md",
        "docs/PROJECT_PLAN.md", "docs/BUILD.md", "docs/README.md",
        "docs/P0-IMPLEMENTATION.md", "docs/DESIGN-injection-fusion.md",
        "scripts/recording_to_calibration.py", "tests/test_recording_to_calibration.py",
    ]
    for path in required:
        require((ROOT / path).is_file(), f"Missing file: {path}")

    jar = ROOT / "gradle/wrapper/gradle-wrapper.jar"
    require(
        hashlib.sha256(jar.read_bytes()).hexdigest()
        == "7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d",
        "Wrapper JAR differs from the official Gradle 9.7.1 checksum",
    )
    with zipfile.ZipFile(jar) as archive:
        require(archive.testzip() is None, "Corrupt wrapper archive")
        require(
            "org/gradle/wrapper/GradleWrapperMain.class" in archive.namelist(),
            "Missing wrapper entrypoint",
        )
    properties = dict(
        line.split("=", 1)
        for line in text("gradle/wrapper/gradle-wrapper.properties").splitlines()
        if line and not line.startswith("#")
    )
    require(properties["distributionUrl"].endswith("gradle-9.7.1-bin.zip"), "Wrong distribution")
    require(
        properties["distributionSha256Sum"]
        == "acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a",
        "Wrong Gradle distribution checksum",
    )
    require(os.access(ROOT / "gradlew", os.X_OK), "gradlew must be executable")
    require(os.access(ROOT / "scripts/recording_to_calibration.py", os.X_OK), "Bridge script must be executable")

    catalog = tomllib.loads(text("gradle/libs.versions.toml"))
    for section in ("libraries", "plugins"):
        for name, dependency in catalog[section].items():
            version = dependency.get("version")
            if isinstance(version, dict):
                require(version["ref"] in catalog["versions"], f"Bad version reference: {name}")
    aliases = {key.replace("-", ".") for key in catalog["libraries"]}
    aliases |= {key.replace("-", ".") for key in catalog["plugins"]}
    aliases |= {"plugins." + key.replace("-", ".") for key in catalog["plugins"]}
    for path in ("build.gradle.kts", "app/build.gradle.kts", "sim-core/build.gradle.kts"):
        for alias in re.findall(r"libs\.([a-zA-Z0-9_.]+)", text(path)):
            require(alias in aliases or alias.removeprefix("plugins.") in aliases, f"Unknown catalog alias in {path}: {alias}")

    settings = text("settings.gradle.kts")
    core_settings = text("sim-core/settings.gradle.kts")
    core_build = text("sim-core/build.gradle.kts")
    require('includeBuild("sim-core")' in settings, "Missing composite build")
    require('include(":app")' in settings, "Missing Android app")
    require('rootProject.name = "sim-core"' in core_settings, "Wrong core project name")
    require('group = "dev.ratemock"' in core_build, "Wrong substitution group")
    require('implementation("dev.ratemock:sim-core:0.1.0")' in text("app/build.gradle.kts"), "Missing core dependency")
    require('from(files("../gradle/libs.versions.toml"))' in core_settings, "Core must share the catalog")
    require("android" not in core_build.lower(), "Core build must not apply Android plugins")
    require("google()" not in core_settings, "Core resolution must not require Google's Android repository")
    for path in (ROOT / "sim-core/src").rglob("*.kt"):
        require(not re.search(r"^import (android\.|androidx\.)", path.read_text(), re.M), f"Android import in {path}")

    manifest = ET.fromstring(text("app/src/main/AndroidManifest.xml"))
    permissions = {element.attrib.get(ANDROID + "name") for element in manifest.findall("uses-permission")}
    require(
        {
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACTIVITY_RECOGNITION",
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_LOCATION",
            "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.WAKE_LOCK",
        }.issubset(permissions),
        "S7/P2 foreground-service permissions are incomplete",
    )
    activity = manifest.find("application/activity")
    require(activity is not None, "Missing launcher activity")
    require(activity.attrib[ANDROID + "name"] == ".MainActivity", "Wrong launcher class")
    require(activity.attrib[ANDROID + "exported"] == "true", "Launcher must be exported")
    services = {service.attrib[ANDROID + "name"]: service for service in manifest.findall("application/service")}
    require(services[".recording.RecorderService"].attrib[ANDROID + "foregroundServiceType"] == "location", "Recorder type changed")
    simulator = services[".simulation.SimulatorService"]
    require(simulator.attrib[ANDROID + "exported"] == "false", "Simulator must not be exported")
    require(simulator.attrib[ANDROID + "foregroundServiceType"] == "specialUse", "Simulator service type changed")
    subtype = simulator.find("property")
    require(subtype is not None and subtype.attrib.get(ANDROID + "name") == "android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE", "Missing simulator subtype")
    for path in (ROOT / "app/src/main/res").rglob("*.xml"):
        ET.parse(path)

    strings = ET.fromstring(text("app/src/main/res/values/strings.xml"))
    string_names = {element.attrib["name"] for element in strings}
    require(len(string_names) == len(strings), "Duplicate string resources")
    for path in (ROOT / "app/src/main/kotlin").rglob("*.kt"):
        for name in re.findall(r"R\.string\.(\w+)", path.read_text()):
            require(name in string_names, f"Missing string resource: {name}")

    license_text = text("LICENSE")
    require("Apache License" in license_text and "Version 2.0" in license_text, "Missing Apache-2.0 project license")
    require(license_text.startswith("Copyright 2026 RateMock contributors"), "Missing project copyright notice")

    # Check local inline Markdown links without network access.
    for markdown in ROOT.rglob("*.md"):
        content = markdown.read_text(encoding="utf-8")
        for target in re.findall(r"(?<!)\[[^\]]+\]\(([^)]+)\)", content):
            target = target.split("#", 1)[0]
            if not target or "://" in target or target.startswith("mailto:"):
                continue
            require((markdown.parent / target).resolve().exists(), f"Broken Markdown link in {markdown.relative_to(ROOT)}: {target}")

    workflow = text(".github/workflows/android.yml")
    actions = re.findall(r"uses:\s*(\S+)", workflow)
    require(bool(actions), "Missing CI actions")
    for action in actions:
        require(re.fullmatch(r"[\w./-]+@[0-9a-f]{40}", action) is not None, f"Action is not SHA-pinned: {action}")
    require("contents: read" in workflow and "contents: write" not in workflow, "Unexpected workflow write permissions")
    require("pull_request_target" not in workflow, "Do not execute untrusted PR code with privileged context")
    require("needs: core" in workflow, "APK job must wait for core tests")
    require('"platforms;android-36" "build-tools;36.0.0"' in workflow, "CI SDK version drift")

    for shell, path in [("sh", "gradlew"), ("bash", "scripts/test-core.sh"), ("bash", "scripts/build-android.sh")]:
        subprocess.run([shell, "-n", str(ROOT / path)], check=True)
        require(True, f"Syntax check: {path}")
    env = os.environ.copy()
    env.pop("CI", None)
    env.pop("RATEMOCK_ALLOW_DOWNLOADS", None)
    for path in ("scripts/test-core.sh", "scripts/build-android.sh"):
        result = subprocess.run(["bash", str(ROOT / path)], cwd=ROOT, env=env, capture_output=True, text=True, timeout=5)
        require(result.returncode == 2 and "disabled" in result.stderr, f"Local download guard failed: {path}")

    for script in ("scripts/apple_health_calibration.py", "scripts/recording_to_calibration.py"):
        result = subprocess.run([sys.executable, "-m", "py_compile", str(ROOT / script)], cwd=ROOT, capture_output=True, text=True)
        require(result.returncode == 0, f"Python syntax check failed: {script}")

    p0_tests = text("sim-core/src/test/kotlin/dev/ratemock/core/GaitKinematicsTest.kt")
    p0_test_count = len(re.findall(r"^\s*@Test\s*$", p0_tests, re.M))
    require(p0_test_count == 14, "P0 GaitKinematics test inventory changed")
    all_tests = list((ROOT / "sim-core/src/test").rglob("*.kt"))
    total_test_count = sum(
        len(re.findall(r"^\s*@Test\s*$", path.read_text(), re.M))
        for path in all_tests
    )
    require(total_test_count >= p0_test_count, "Invalid Kotlin test inventory")
    print(f"PASS: {CHECKS} offline structural checks; XML/TOML, aliases, wrapper, scripts and download guards.")
    print(f"Found {total_test_count} Kotlin test definitions ({p0_test_count} P0 baseline); Kotlin compilation/JUnit/Android lint were NOT executed.")
    print("This checker did not invoke Gradle or access the network.")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, OSError, subprocess.SubprocessError, ET.ParseError, zipfile.BadZipFile) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        sys.exit(1)
