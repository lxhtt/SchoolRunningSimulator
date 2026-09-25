#!/usr/bin/env python3
"""Convert a RateMock S7 raw recorder CSV into an S6 calibration CSV.

The input contains locations and cumulative TYPE_STEP_COUNTER values. Output
contains only aggregate speed/cadence windows and must remain private when
created from personal recordings. GPS is used only as an observation of speed;
windows with gaps, poor accuracy, pauses, or unstable motion are rejected.
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import os
import statistics
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path

EARTH_RADIUS_M = 6_371_000.0
DEFAULT_WINDOW_S = 15.0
DEFAULT_MAX_GAP_S = 2.0
DEFAULT_MAX_ACCURACY_M = 30.0
DEFAULT_MIN_SPEED_MPS = 0.4
DEFAULT_MAX_SPEED_MPS = 6.5
DEFAULT_MIN_CADENCE_SPM = 50.0
DEFAULT_MAX_CADENCE_SPM = 240.0


@dataclass(frozen=True)
class Sample:
    timestamp_s: float
    latitude: float
    longitude: float
    accuracy_m: float | None
    supplied_speed_mps: float | None
    steps: float | None
    location_age_s: float | None = None


@dataclass(frozen=True)
class Window:
    speed_mps: float
    cadence_spm: float
    weight: float
    start_s: float
    end_s: float
    rows: int


def parse_time(value: str) -> float:
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.utcoffset() is None:
        raise ValueError("timestamp must include a UTC offset")
    return parsed.timestamp()


def finite(value: str | None) -> float | None:
    if value is None or not value.strip():
        return None
    number = float(value)
    return number if math.isfinite(number) else None


def read_samples(path: Path) -> list[Sample]:
    with path.open(newline="") as source:
        reader = csv.DictReader(source)
        required = {"timestamp_utc", "latitude_deg", "longitude_deg", "horizontal_accuracy_m", "speed_mps", "step_counter_total"}
        missing = required - set(reader.fieldnames or ())
        if missing:
            raise ValueError(f"missing columns: {', '.join(sorted(missing))}")
        rows = []
        for line, row in enumerate(reader, 2):
            try:
                latitude = float(row["latitude_deg"])
                longitude = float(row["longitude_deg"])
                timestamp = parse_time(row["timestamp_utc"])
            except (TypeError, ValueError) as error:
                raise ValueError(f"invalid location/time at row {line}: {error}") from error
            if not (math.isfinite(timestamp) and math.isfinite(latitude) and math.isfinite(longitude)
                    and -90 <= latitude <= 90 and -180 <= longitude <= 180):
                raise ValueError(f"invalid coordinates/time at row {line}")
            age = finite(row.get("location_age_s"))
            # A present but empty age field is not the legacy format.
            rows.append(Sample(
                timestamp, latitude, longitude,
                finite(row.get("horizontal_accuracy_m")),
                finite(row.get("speed_mps")),
                finite(row.get("step_counter_total")),
                age if age is not None or "location_age_s" not in reader.fieldnames else -1.0,
            ))
    if len(rows) < 2:
        raise ValueError("recording requires at least two samples")
    if any(second.timestamp_s <= first.timestamp_s for first, second in zip(rows, rows[1:])):
        raise ValueError("recording timestamps must be strictly increasing")
    return rows


def haversine_m(first: Sample, second: Sample) -> float:
    latitude_a, latitude_b = math.radians(first.latitude), math.radians(second.latitude)
    delta_lat = latitude_b - latitude_a
    delta_lon = math.radians(second.longitude - first.longitude)
    value = math.sin(delta_lat / 2) ** 2 + math.cos(latitude_a) * math.cos(latitude_b) * math.sin(delta_lon / 2) ** 2
    return 2 * EARTH_RADIUS_M * math.asin(min(1.0, math.sqrt(value)))


def interval_speed(first: Sample, second: Sample) -> float | None:
    duration = second.timestamp_s - first.timestamp_s
    if duration <= 0:
        return None
    if second.supplied_speed_mps is not None and second.supplied_speed_mps >= 0:
        return second.supplied_speed_mps
    return haversine_m(first, second) / duration


def stable_windows(
    samples: list[Sample],
    window_s: float = DEFAULT_WINDOW_S,
    max_gap_s: float = DEFAULT_MAX_GAP_S,
    max_accuracy_m: float = DEFAULT_MAX_ACCURACY_M,
) -> tuple[list[Window], dict[str, int]]:
    if not all(math.isfinite(value) and value > 0 for value in (window_s, max_gap_s, max_accuracy_m)) or window_s < 1.0:
        raise ValueError("window (at least 1 second), gap, and accuracy limits must be finite and positive")
    diagnostics = {"input_rows": len(samples), "gap_rejected": 0, "poor_accuracy_rejected": 0,
                   "stale_location_rejected": 0, "missing_steps_rejected": 0,
                   "step_reset_rejected": 0, "missing_speed_rejected": 0, "unstable_rejected": 0,
                   "out_of_bounds_rejected": 0, "accepted_windows": 0}
    windows: list[Window] = []
    begin = samples[0].timestamp_s
    end = samples[-1].timestamp_s
    cursor = begin
    while cursor + window_s <= end + 1e-9:
        rows = [sample for sample in samples if cursor <= sample.timestamp_s < cursor + window_s]
        cursor += window_s
        if len(rows) < 3 or rows[-1].timestamp_s - rows[0].timestamp_s < 0.7 * window_s:
            diagnostics["gap_rejected"] += 1
            continue
        if any(sample.timestamp_s - previous.timestamp_s > max_gap_s for previous, sample in zip(rows, rows[1:])):
            diagnostics["gap_rejected"] += 1
            continue
        if any(sample.accuracy_m is None or not 0 < sample.accuracy_m <= max_accuracy_m for sample in rows):
            diagnostics["poor_accuracy_rejected"] += 1
            continue
        # New recordings carry fix age; older recordings need coordinate changes
        # to establish that cached Location objects were not repeatedly sampled.
        ages = [sample.location_age_s for sample in rows]
        if (any(age is None for age in ages) and not all(age is None for age in ages)) or (
            all(age is None for age in ages) and
            any(first.latitude == second.latitude and first.longitude == second.longitude
                for first, second in zip(rows, rows[1:]))
        ) or any(age is not None and not 0 <= age <= max_gap_s for age in ages):
            diagnostics["stale_location_rejected"] += 1
            continue
        speeds = [interval_speed(first, second) for first, second in zip(rows, rows[1:])]
        speeds = [speed for speed in speeds if speed is not None and math.isfinite(speed)]
        if any(sample.steps is None or sample.steps < 0 for sample in rows):
            diagnostics["missing_steps_rejected"] += 1
            continue
        step_values = [sample.steps for sample in rows]
        if any(later < earlier for earlier, later in zip(step_values, step_values[1:])):
            diagnostics["step_reset_rejected"] += 1
            continue
        if len(speeds) < len(rows) - 1:
            diagnostics["missing_speed_rejected"] += 1
            continue
        duration = rows[-1].timestamp_s - rows[0].timestamp_s
        cadence = 60.0 * (step_values[-1] - step_values[0]) / duration
        speed = statistics.mean(speeds)
        ordered = sorted(speeds)
        spread = ordered[-1] - ordered[0]
        if not (DEFAULT_MIN_SPEED_MPS <= speed <= DEFAULT_MAX_SPEED_MPS and
                DEFAULT_MIN_CADENCE_SPM <= cadence <= DEFAULT_MAX_CADENCE_SPM):
            diagnostics["out_of_bounds_rejected"] += 1
            continue
        # A broad but explicit stability filter: reject pauses, starts, and sharp GPS spikes.
        if spread > max(0.75, 0.6 * max(speed, 0.1)):
            diagnostics["unstable_rejected"] += 1
            continue
        windows.append(Window(speed, cadence, duration / window_s, rows[0].timestamp_s, rows[-1].timestamp_s, len(rows)))
        diagnostics["accepted_windows"] += 1
    return windows, diagnostics


def convert(input_path: Path, output_path: Path, report_path: Path, **limits: float) -> dict[str, object]:
    if len({input_path.resolve(), output_path.resolve(), report_path.resolve()}) != 3:
        raise ValueError("input, calibration output, and report paths must be distinct")
    samples = read_samples(input_path)
    windows, diagnostics = stable_windows(samples, **limits)
    speeds = [window.speed_mps for window in windows]
    cadences = [window.cadence_spm for window in windows]
    s6_input_valid = len(windows) >= 8 and max(speeds, default=0) - min(speeds, default=0) >= 0.5
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("w", newline="") as target:
        writer = csv.writer(target)
        writer.writerow(("speed_mps", "cadence_spm", "weight"))
        writer.writerows((window.speed_mps, window.cadence_spm, window.weight) for window in windows)
    report: dict[str, object] = {
        "schema_version": 1,
        "source": "ratemock_s7_raw_csv",
        "output": "s6_calibration_csv",
        "validation_status": "exploratory" if s6_input_valid else "insufficient",
        "s6_input_valid": s6_input_valid,
        "windows": len(windows),
        "speed_range_mps": [min(speeds), max(speeds)] if speeds else None,
        "cadence_range_spm": [min(cadences), max(cadences)] if cadences else None,
        "diagnostics": diagnostics,
        "privacy": "Output contains aggregate calibration windows only; source recording stays private.",
    }
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(report, indent=2) + "\n")
    return report


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True, help="S7 recording CSV")
    parser.add_argument("--out", type=Path, required=True, help="S6 calibration CSV")
    parser.add_argument("--report", type=Path, required=True, help="quality report JSON")
    parser.add_argument("--window-s", type=float, default=DEFAULT_WINDOW_S)
    parser.add_argument("--max-gap-s", type=float, default=DEFAULT_MAX_GAP_S)
    parser.add_argument("--max-accuracy-m", type=float, default=DEFAULT_MAX_ACCURACY_M)
    args = parser.parse_args()
    if not args.input.is_file():
        parser.error("input recording does not exist")
    os.umask(0o077)
    report = convert(args.input, args.out, args.report, window_s=args.window_s, max_gap_s=args.max_gap_s, max_accuracy_m=args.max_accuracy_m)
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
