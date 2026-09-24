#!/usr/bin/env python3
"""Extract private walk/run speed-cadence pairs from an Apple Health export.

Pairs a workout's timed step-count intervals with its GPX route speeds. The
export stays local; output contains no locations, timestamps or source names.
"""

import argparse
from bisect import bisect_left, bisect_right
from collections import defaultdict
import csv
from datetime import datetime
import json
import os
from pathlib import Path
import statistics
import xml.etree.ElementTree as ET


STEP = "HKQuantityTypeIdentifierStepCount"
ACTIVITIES = {
    "HKWorkoutActivityTypeWalking": "walking",
    "HKWorkoutActivityTypeRunning": "running",
}
SPEED_BOUNDS = {"walking": (0.4, 2.5), "running": (1.5, 6.5)}
CADENCE_BOUNDS = {"walking": (50, 170), "running": (100, 240)}


def timestamp(value):
    return datetime.strptime(value, "%Y-%m-%d %H:%M:%S %z").timestamp()


def elements(path, tag):
    root = None
    for event, element in ET.iterparse(path, events=("start", "end")):
        if event == "start" and root is None:
            root = element
        if event == "end" and element.tag == tag:
            yield dict(element.attrib)
        if event == "end" and element.tag in ("Record", "Workout"):
            element.clear()
            root.clear()


def workouts_in(path):
    workouts = []
    for entry in elements(path, "Workout"):
        mode = ACTIVITIES.get(entry.get("workoutActivityType"))
        if mode is None:
            continue
        start, end = timestamp(entry["startDate"]), timestamp(entry["endDate"])
        if end - start >= 180:
            workouts.append((start, end, mode))
    return sorted(workouts)


def gpx_points(path):
    points = []
    for _, point in ET.iterparse(path, events=("end",)):
        if point.tag.rsplit("}", 1)[-1] != "trkpt":
            continue
        time = point.find("{*}time")
        speed = point.find(".//{*}speed")
        accuracy = point.find(".//{*}hAcc")
        if time is not None and speed is not None and accuracy is not None:
            value, horizontal = float(speed.text), float(accuracy.text)
            if 0 < horizontal <= 20 and 0 <= value <= 8:
                when = datetime.fromisoformat(time.text.replace("Z", "+00:00")).timestamp()
                points.append((when, value))
        point.clear()
    return sorted(points)


def routes_in(directory, workouts):
    routes = {}
    for path in directory.glob("*.gpx"):
        points = gpx_points(path)
        if len(points) < 30:
            continue
        start, end = points[0][0], points[-1][0]
        matches = [
            (index, min(end, workout[1]) - max(start, workout[0]))
            for index, workout in enumerate(workouts)
            if min(end, workout[1]) - max(start, workout[0]) >= 0.8 * (end - start)
        ]
        if matches:
            index = max(matches, key=lambda match: match[1])[0]
            if len(points) > len(routes.get(index, [])):
                routes[index] = points
    return routes


def route_speed(points, times, start, end, mode):
    sample = points[bisect_left(times, start):bisect_right(times, end)]
    duration = end - start
    if len(sample) < 20 or sample[-1][0] - sample[0][0] < 0.7 * duration:
        return None
    if max(b[0] - a[0] for a, b in zip(sample, sample[1:])) > 12:
        return None
    speeds = [point[1] for point in sample]
    speed = statistics.mean(speeds)
    ordered = sorted(speeds)
    spread = ordered[int(0.9 * (len(ordered) - 1))] - ordered[int(0.1 * (len(ordered) - 1))]
    if not SPEED_BOUNDS[mode][0] <= speed <= SPEED_BOUNDS[mode][1]:
        return None
    if spread > max(0.75, 0.6 * speed):
        return None
    return speed


def extract(xml_path, route_dir):
    workouts = workouts_in(xml_path)
    routes = routes_in(route_dir, workouts)
    starts = [workout[0] for workout in workouts]
    steps = defaultdict(lambda: defaultdict(dict))
    diagnostics = defaultdict(lambda: defaultdict(int))
    for entry in elements(xml_path, "Record"):
        if entry.get("type") != STEP or entry.get("unit") != "count":
            continue
        start, end = timestamp(entry["startDate"]), timestamp(entry["endDate"])
        index = bisect_right(starts, start) - 1
        if index < 0:
            continue
        mode = workouts[index][2]
        if index not in routes or end > workouts[index][1]:
            diagnostics[mode]["outside_route_workout"] += 1
            continue
        if not 0 < end - start <= 300:
            diagnostics[mode]["interval_over_300_rejected"] += 1
            continue
        diagnostics[mode]["candidate_step_intervals"] += 1
        value = float(entry["value"])
        if value > 0:
            # Identical exports from one device must not double-count steps.
            steps[index][entry.get("sourceName", "")].setdefault((start, end), value)

    results = {"walking": [], "running": []}
    report = {
        "workouts": {mode: sum(w[2] == mode for w in workouts) for mode in results},
        "route_workouts": {mode: sum(workouts[i][2] == mode for i in routes) for mode in results},
        "accepted_workouts": {mode: 0 for mode in results},
        "diagnostics": diagnostics,
    }
    for index, sources in steps.items():
        begin, end, mode = workouts[index]
        report["diagnostics"][mode]["with_candidate_steps"] += 1
        points = routes[index]
        times = [point[0] for point in points]
        candidates = []
        for source, intervals in sources.items():
            def non_overlapping(rows):
                accepted_rows = []
                previous_end = begin
                for row in sorted(rows):
                    if row[0] >= previous_end:
                        accepted_rows.append(row)
                        previous_end = row[1]
                return accepted_rows

            entries = [(start, stop, count) for (start, stop), count in intervals.items()]
            short = non_overlapping(row for row in entries if row[1] - row[0] < 30)
            long = non_overlapping(row for row in entries if row[1] - row[0] >= 30)
            accepted = []
            window = begin + 15
            while window + 60 <= end - 15:
                contained = [row for row in short if window <= row[0] and row[1] <= window + 60]
                duration = sum(row[1] - row[0] for row in contained)
                if duration >= 45 and len(contained) >= 3:
                    cadence = 60 * sum(row[2] for row in contained) / duration
                    speed = route_speed(points, times, window, window + 60, mode)
                    if CADENCE_BOUNDS[mode][0] <= cadence <= CADENCE_BOUNDS[mode][1] and speed is not None:
                        accepted.append((speed, cadence))
                window += 60
            if not accepted:
                for start, stop, count in long:
                    if start < begin + 15 or stop > end - 15:
                        continue
                    cadence = 60 * count / (stop - start)
                    if not CADENCE_BOUNDS[mode][0] <= cadence <= CADENCE_BOUNDS[mode][1]:
                        continue
                    speed = route_speed(points, times, start, stop, mode)
                    if speed is not None:
                        accepted.append((speed, cadence))
            candidates.append((accepted, source))
        if candidates:
            # One device per workout; never add overlapping phone and watch counts.
            accepted, _ = max(candidates, key=lambda item: len(item[0]))
            if accepted:
                report["accepted_workouts"][mode] += 1
                results[mode].extend((speed, cadence, 1 / len(accepted)) for speed, cadence in accepted)

    for mode, rows in results.items():
        speeds = [row[0] for row in rows]
        report[mode] = {
            "windows": len(rows),
            "speed_range_mps": [min(speeds), max(speeds)] if speeds else None,
            "median_speed_mps": statistics.median(speeds) if speeds else None,
            "s6_input_valid": len(rows) >= 8 and max(speeds) - min(speeds) >= 0.5 if speeds else False,
            "validation_status": "exploratory" if speeds else "insufficient",
        }
    return results, report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True, help="Apple Health export.xml")
    parser.add_argument("--routes", type=Path, required=True, help="Apple Health workout-routes directory")
    parser.add_argument("--out-dir", type=Path, required=True, help="Private local output directory")
    args = parser.parse_args()
    if not args.input.is_file() or not args.routes.is_dir():
        parser.error("input XML or GPX route directory does not exist")
    os.umask(0o077)
    args.out_dir.mkdir(parents=True, exist_ok=True)
    if args.out_dir.stat().st_mode & 0o077:
        parser.error("output directory must be private (chmod 700)")
    results, report = extract(args.input, args.routes)
    for mode, rows in results.items():
        with (args.out_dir / f"{mode}-calibration.csv").open("w", newline="") as output:
            writer = csv.writer(output)
            writer.writerow(("speed_mps", "cadence_spm", "weight"))
            writer.writerows(rows)
    (args.out_dir / "quality-report.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
