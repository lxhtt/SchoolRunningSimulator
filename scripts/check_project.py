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
        "receiver-ui/build.gradle.kts", "receiver/build.gradle.kts",
        "receiver/src/main/AndroidManifest.xml",
        "receiver/src/main/kotlin/dev/ratemock/receiver/ReceiverActivity.kt",
        "receiver-ui/src/main/kotlin/dev/ratemock/receiverui/ReplayReceiverScreen.kt",
        "receiver-ui/src/main/res/values/strings.xml",
        "app/src/main/AndroidManifest.xml", "sim-core/settings.gradle.kts",
        "sim-core/build.gradle.kts", "scripts/test-core.sh",
        "scripts/build-android.sh", "scripts/update-gradle-wrapper.py",
        ".github/workflows/android.yml", "third_party/gradle/LICENSE",
        "LICENSE", "README.md", "CREDITS.md", "THIRD_PARTY_NOTICES.md",
        "docs/PROJECT_PLAN.md", "docs/BUILD.md", "docs/README.md",
        "docs/P0-IMPLEMENTATION.md", "docs/DESIGN-injection-fusion.md",
        "scripts/recording_to_calibration.py", "tests/test_recording_to_calibration.py",
        "docs/superpowers/specs/2026-09-25-p5-safe-replay-design.md",
        "docs/superpowers/specs/2026-09-25-p3-run-assistance-design.md",
        "docs/superpowers/specs/2026-09-26-recording-lifecycle-export-design.md",
        "docs/superpowers/specs/2026-09-26-p6-preflight-presets-design.md",
        "docs/superpowers/specs/2026-09-26-p7-platform-diagnostics-design.md",
        "sim-core/src/main/kotlin/dev/ratemock/core/diagnostics/SensorDiagnostics.kt",
        "sim-core/src/test/kotlin/dev/ratemock/core/diagnostics/SensorDiagnosticsTest.kt",
        "sim-core/src/main/kotlin/dev/ratemock/core/truth/SimulationPreflight.kt",
        "sim-core/src/test/kotlin/dev/ratemock/core/truth/SimulationPreflightTest.kt",
        "sim-core/src/main/kotlin/dev/ratemock/core/truth/WaveformReview.kt",
        "sim-core/src/test/kotlin/dev/ratemock/core/truth/WaveformReviewTest.kt",
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
    for path in ("build.gradle.kts", "app/build.gradle.kts", "receiver-ui/build.gradle.kts", "receiver/build.gradle.kts", "sim-core/build.gradle.kts"):
        for alias in re.findall(r"libs\.([a-zA-Z0-9_.]+)", text(path)):
            require(alias in aliases or alias.removeprefix("plugins.") in aliases, f"Unknown catalog alias in {path}: {alias}")

    settings = text("settings.gradle.kts")
    core_settings = text("sim-core/settings.gradle.kts")
    core_build = text("sim-core/build.gradle.kts")
    require('includeBuild("sim-core")' in settings, "Missing composite build")
    require('include(":app", ":receiver-ui", ":receiver")' in settings, "Missing Android modules")
    require('implementation(project(":receiver-ui"))' in text("app/build.gradle.kts") and
            'implementation(project(":receiver-ui"))' in text("receiver/build.gradle.kts"),
            "Both apps must use the shared receiver screen")
    require('ReplayReceiverScreen()' in text("receiver/src/main/kotlin/dev/ratemock/receiver/ReceiverActivity.kt") and
            'ReplayReceiverScreen()' in text("app/src/main/kotlin/dev/ratemock/app/MainActivity.kt"),
            "Both apps must render the shared receiver")
    require('rootProject.name = "sim-core"' in core_settings, "Wrong core project name")
    require('group = "dev.ratemock"' in core_build, "Wrong substitution group")
    require('implementation("dev.ratemock:sim-core:0.1.0")' in text("app/build.gradle.kts"), "Missing core dependency")
    require('from(files("../gradle/libs.versions.toml"))' in core_settings, "Core must share the catalog")
    require("android" not in core_build.lower(), "Core build must not apply Android plugins")
    require("google()" not in core_settings, "Core resolution must not require Google's Android repository")
    for path in (ROOT / "sim-core/src").rglob("*.kt"):
        require(not re.search(r"^import (android\.|androidx\.)", path.read_text(), re.M), f"Android import in {path}")

    replay_sources = list((ROOT / "sim-core/src/main/kotlin/dev/ratemock/core/replay").glob("*.kt"))
    require(replay_sources, "Missing P5 replay sources")
    for path in replay_sources:
        content = path.read_text()
        require("package dev.ratemock.core.replay" in content, f"Wrong replay package: {path}")
        require("android." not in content and "androidx." not in content, f"Android dependency in replay source: {path}")
    require("addTestProvider" not in text("app/src/main/kotlin/dev/ratemock/app/simulation/SimulatorScreen.kt"), "Simulation UI must not inject locations")
    real_export = text("sim-core/src/main/kotlin/dev/ratemock/core/export/RealRunExport.kt")
    real_ui = text("app/src/main/kotlin/dev/ratemock/app/MainActivity.kt")
    simulation_ui = text("app/src/main/kotlin/dev/ratemock/app/simulation/SimulatorScreen.kt")
    strings_text = text("app/src/main/res/values/strings.xml")
    recorder_source = text("app/src/main/kotlin/dev/ratemock/app/recording/RecorderService.kt")
    require('provenance=real_observation' in real_export and '"recordings"' in real_ui,
            "Real-run provenance or private recording path missing")
    require('"simulations"' not in real_export and 'loadStoppedRecording(context, fileName)' in real_ui,
            "Real-run export must not read simulation history")
    require("RecordingExportPolicy" in real_ui and "RecordingState.STOPPED" in real_ui,
            "Real export UI must require explicit stopped state policy")
    require('enabled = snapshot.canExport' in real_ui and 'loadStoppedRecording(context, file.name)' in real_ui,
            "Real export button must require a strictly parsed closed recording")
    require('ActivityResultContracts.CreateDocument' in real_ui,
            "Real export must require an explicit document destination")
    require('readRecorderSnapshot(context, verifyExport = false, readFile = false)' in real_ui and
            'withContext(Dispatchers.IO) { readRecorderSnapshot(context, verifyExport = true, readFile = true) }' in real_ui,
            "Initial recorder composition must not scan the recording file on the main thread")
    require('writeFailure = error' in recorder_source and 'if (closeError == null) closeError = writeFailure' in recorder_source,
            "A sample write failure must prevent a normal STOPPED state")
    diagnostic_ui = text("app/src/main/kotlin/dev/ratemock/app/DiagnosticScreen.kt")
    diagnostic_core = text("sim-core/src/main/kotlin/dev/ratemock/core/diagnostics/SensorDiagnostics.kt")
    require("DiagnosticScreen" in real_ui and "diagnostic_tab" in strings_text and "real_diagnostic" in diagnostic_ui,
            "P7 diagnostic UI and provenance are missing")
    require("SensorDiagnosticReport" in diagnostic_core and "MAX_DIAGNOSTIC_EVENTS" in diagnostic_ui,
            "P7 sensor diagnostic report or bounded event collection is missing")
    require('if (event == Lifecycle.Event.ON_STOP && latestObserving) {\n                    activeListener?.let(sensorManager::unregisterListener)' in diagnostic_ui,
            "P7 sensor listener must unregister synchronously when the app leaves the foreground")
    require("addTestProvider" not in diagnostic_ui and "setTestProviderLocation" not in diagnostic_ui,
            "P7 diagnostic UI must not inject locations")

    receiver_ui = text("receiver-ui/src/main/kotlin/dev/ratemock/receiverui/ReplayReceiverScreen.kt")
    receiver_manifest = ET.fromstring(text("receiver/src/main/AndroidManifest.xml"))
    require(not receiver_manifest.findall("uses-permission"), "Receiver must not request device permissions")
    receiver_strings = ET.fromstring(text("receiver-ui/src/main/res/values/strings.xml"))
    receiver_string_names = {element.attrib["name"] for element in receiver_strings}
    require(len(receiver_strings) == len(receiver_string_names), "Duplicate receiver string resources")
    for name in re.findall(r"R\.string\.(\w+)", receiver_ui):
        require(name in receiver_string_names, f"Missing receiver string resource: {name}")
    require(receiver_manifest.find("application").attrib[ANDROID + "label"] == "@string/receiver_app_name",
            "Receiver launcher label must come from shared resources")
    require('application/json' in receiver_ui and 'ActivityResultContracts.OpenDocument' in receiver_ui,
            "Receiver must use explicit document selection")
    require('MAX_INPUT_BYTES' in receiver_ui and 'LocalReplayValidator.validate(events)' in receiver_ui,
            "Receiver must bound input and validate local events")
    require('"ratemock.local-replay.v1"' in simulation_ui and '"ratemock.local-replay.v1"' in receiver_ui,
            "Simulation export and receiver must share the local event schema")
    require('"events" -> localReplayJson(history)' in simulation_ui,
            "Simulation history must expose a receiver-compatible local event export")
    for forbidden in ("addTestProvider(", "setTestProviderLocation(", "XposedBridge."):
        require(forbidden not in receiver_ui, "Receiver must not inject system data")

    require("WaveformReview" in simulation_ui and "sim_waveform_title" in strings_text,
            "Simulation UI must expose waveform review")
    require("sim_preset_walk" in strings_text and "sim_preset_steady" in strings_text and "sim_preset_tempo" in strings_text,
            "Simulation UI must expose bounded parameter presets")
    require('KEY_CLOSED_FILE_NAME' in recorder_source and 'RecordingState.STOPPING' in recorder_source,
            "Recorder must persist closed-file state and stop transition")
    require('val finalState = if (closeError == null && fileBeingClosed != null) RecordingState.STOPPED' in recorder_source,
            "Recorder must mark files stopped only after closing")
    require('System.currentTimeMillis() - preferences.getLong(RecorderService.KEY_HEARTBEAT_MS' not in real_ui,
            "Heartbeat must not decide real export eligibility")
    require("ACTION_STOP" in recorder_source and "PendingIntent.getService" in recorder_source,
            "Recorder notification must expose a stop action")
    require('return START_NOT_STICKY' in recorder_source,
            "Recorder must not restart an unresumable writer session")
    require('"Location permission is required"' in recorder_source,
            "Recorder must persist permission startup failures")
    require("RunPromptDecider" in recorder_source and "TYPE_STEP_DETECTOR" in recorder_source,
            "Real prompt must derive cadence from detector events")
    for path in (ROOT / "app/src/main/kotlin").rglob("*.kt"):
        content = path.read_text()
        for forbidden in ("addTestProvider(", "setTestProviderLocation(", "XposedBridge."):
            require(forbidden not in content, f"Injection API forbidden in {path}")

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
    require("location_age_s" in recorder_source, "Recorder CSV must include location age")
    require("elapsedRealtimeNanos" in recorder_source, "Recorder must calculate location age from monotonic time")
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
    require(':receiver:assembleDebug' in text("scripts/build-android.sh") and
            'receiver/build/outputs/apk/debug/receiver-debug.apk' in workflow,
            "CI must build and upload the independent receiver APK")
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
        len(re.findall(r"^\s*@Test\b", path.read_text(), re.M))
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
