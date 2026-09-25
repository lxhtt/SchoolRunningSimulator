import csv
import json
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

from scripts.recording_to_calibration import Sample, convert, read_samples, stable_windows


class RecordingBridgeTest(unittest.TestCase):
    def write_recording(self, path: Path, rows: list[tuple[float, float, float, float]],
                        with_age: bool = False) -> None:
        start = datetime(2025, 1, 1, tzinfo=timezone.utc)
        with path.open("w", newline="") as output:
            writer = csv.writer(output)
            writer.writerow(("timestamp_utc", "elapsed_s", "latitude_deg", "longitude_deg",
                             "altitude_m", "horizontal_accuracy_m", "speed_mps", "step_counter_total")
                            + (("location_age_s",) if with_age else ()))
            for seconds, latitude, speed, steps in rows:
                writer.writerow(((start + timedelta(seconds=seconds)).isoformat(), seconds,
                                 35.0 + latitude, 139.0, "", 5.0, speed, steps)
                                + ((0.5,) if with_age else ()))

    def test_stable_windows_become_s6_csv(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            input_path, output_path, report_path = root / "raw.csv", root / "calibration.csv", root / "report.json"
            rows = [(float(index), index * 0.00001, 2.0, index * 2.0) for index in range(61)]
            self.write_recording(input_path, rows)
            report = convert(input_path, output_path, report_path)
            self.assertEqual(report["windows"], 4)
            with output_path.open(newline="") as stream:
                parsed = list(csv.DictReader(stream))
            self.assertEqual(len(parsed), 4)
            self.assertAlmostEqual(float(parsed[0]["cadence_spm"]), 120.0, places=5)
            self.assertTrue(float(parsed[0]["weight"]) > 0)
            self.assertFalse(json.loads(report_path.read_text())["s6_input_valid"])
            self.assertEqual(report["validation_status"], "insufficient")
            self.assertNotIn("latitude", output_path.read_text())

    def test_new_format_can_meet_s6_input_requirements(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            recording, output, report_path = root / "raw.csv", root / "calibration.csv", root / "report.json"
            rows = [(float(i), i * 0.00001, 1.5 if i < 60 else 2.2, i * 2.0)
                    for i in range(121)]
            self.write_recording(recording, rows, with_age=True)
            report = convert(recording, output, report_path)
            self.assertEqual(report["windows"], 8)
            self.assertTrue(report["s6_input_valid"])
            self.assertEqual(report["validation_status"], "exploratory")
            with output.open(newline="") as stream:
                self.assertEqual(len(list(csv.DictReader(stream))), 8)

    def test_stale_fix_and_missing_accuracy_do_not_become_calibration(self) -> None:
        samples = [Sample(float(i), 35.0, 139.0, 5.0, 2.0, 2.0 * i) for i in range(16)]
        windows, report = stable_windows(samples)
        self.assertEqual(windows, [])
        self.assertEqual(report["stale_location_rejected"], 1)
        fresh = [Sample(float(i), 35.0 + i * 0.00001, 139.0, 5.0, 2.0, 2.0 * i, 0.5)
                 for i in range(16)]
        windows, report = stable_windows(fresh)
        self.assertEqual(len(windows), 1)
        self.assertEqual(report["accepted_windows"], 1)
        missing_accuracy = list(fresh)
        missing_accuracy[5] = Sample(5.0, 35.00005, 139.0, None, 2.0, 10.0, 0.5)
        windows, report = stable_windows(missing_accuracy)
        self.assertEqual(windows, [])
        self.assertEqual(report["poor_accuracy_rejected"], 1)
        stale_age = list(fresh)
        stale_age[5] = Sample(5.0, 35.00005, 139.0, 5.0, 2.0, 10.0, 8.0)
        windows, report = stable_windows(stale_age)
        self.assertEqual(windows, [])
        self.assertEqual(report["stale_location_rejected"], 1)

        missing_age = list(fresh)
        missing_age[5] = Sample(5.0, 35.00005, 139.0, 5.0, 2.0, 10.0, -1.0)
        windows, report = stable_windows(missing_age)
        self.assertEqual(windows, [])
        self.assertEqual(report["stale_location_rejected"], 1)

    def test_step_reset_and_missing_steps_are_rejected(self) -> None:
        fresh = [Sample(float(i), 35.0 + i * 0.00001, 139.0, 5.0, 2.0, 2.0 * i, 0.5)
                 for i in range(16)]
        reset = list(fresh)
        reset[5] = Sample(5.0, 35.00005, 139.0, 5.0, 2.0, 0.0, 0.5)
        windows, report = stable_windows(reset)
        self.assertEqual(windows, [])
        self.assertEqual(report["step_reset_rejected"], 1)
        missing = list(fresh)
        missing[5] = Sample(5.0, 35.00005, 139.0, 5.0, 2.0, None, 0.5)
        windows, report = stable_windows(missing)
        self.assertEqual(windows, [])
        self.assertEqual(report["missing_steps_rejected"], 1)

    def test_missing_age_in_new_format_is_not_treated_as_legacy(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            recording = Path(directory) / "raw.csv"
            self.write_recording(recording, [(float(i), i * 0.00001, 2.0, i * 2.0)
                                             for i in range(16)], with_age=True)
            lines = recording.read_text().splitlines()
            lines[6] = lines[6].rsplit(",", 1)[0] + ","
            recording.write_text("\n".join(lines) + "\n")
            windows, diagnostics = stable_windows(read_samples(recording))
            self.assertEqual(windows, [])
            self.assertEqual(diagnostics["stale_location_rejected"], 1)

    def test_nonfinite_limits_are_rejected(self) -> None:
        samples = [Sample(float(i), 35.0 + i * 0.00001, 139.0, 5.0, 2.0, 2.0 * i, 0.5)
                   for i in range(16)]
        with self.assertRaises(ValueError):
            stable_windows(samples, window_s=float("nan"))

    def test_rejects_output_overwriting_recording_and_unsorted_timestamps(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            recording = root / "raw.csv"
            self.write_recording(recording, [(float(i), i * 0.00001, 2.0, i * 2.0)
                                             for i in range(16)])
            original = recording.read_bytes()
            with self.assertRaisesRegex(ValueError, "distinct"):
                convert(recording, recording, root / "report.json")
            self.assertEqual(recording.read_bytes(), original)
            self.write_recording(recording, [(1.0, 0.0, 2.0, 0.0),
                                             (0.0, 0.00001, 2.0, 2.0)])
            with self.assertRaisesRegex(ValueError, "strictly increasing"):
                convert(recording, root / "calibration.csv", root / "report.json")

    def test_gap_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            input_path, output_path, report_path = root / "raw.csv", root / "calibration.csv", root / "report.json"
            rows = [(float(index), index * 0.00001, 2.0, index * 2.0) for index in range(5)]
            rows += [(float(index), index * 0.00001, 2.0, index * 2.0) for index in range(9, 16)]
            self.write_recording(input_path, rows)
            report = convert(input_path, output_path, report_path)
            self.assertEqual(report["windows"], 0)
            self.assertGreater(report["diagnostics"]["gap_rejected"], 0)


if __name__ == "__main__":
    unittest.main()
