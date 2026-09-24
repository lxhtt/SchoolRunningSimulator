import csv
import json
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

from scripts.recording_to_calibration import convert


class RecordingBridgeTest(unittest.TestCase):
    def write_recording(self, path: Path, rows: list[tuple[float, float, float, float]]) -> None:
        start = datetime(2025, 1, 1, tzinfo=timezone.utc)
        with path.open("w", newline="") as output:
            writer = csv.writer(output)
            writer.writerow(("timestamp_utc", "elapsed_s", "latitude_deg", "longitude_deg",
                             "altitude_m", "horizontal_accuracy_m", "speed_mps", "step_counter_total"))
            for seconds, latitude, speed, steps in rows:
                writer.writerow(((start + timedelta(seconds=seconds)).isoformat(), seconds,
                                 35.0 + latitude, 139.0, "", 5.0, speed, steps))

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
            self.assertTrue(json.loads(report_path.read_text())["s6_input_valid"] is False)

    def test_gap_and_bad_accuracy_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            input_path, output_path, report_path = root / "raw.csv", root / "calibration.csv", root / "report.json"
            rows = [(float(index), index * 0.00001, 2.0, index * 2.0) for index in range(10)]
            rows += [(20.0 + index, (10 + index) * 0.00001, 2.0, (10 + index) * 2.0) for index in range(10)]
            self.write_recording(input_path, rows)
            content = input_path.read_text().replace(",5.0,2,", ",50.0,2,")
            input_path.write_text(content)
            report = convert(input_path, output_path, report_path)
            self.assertEqual(report["windows"], 0)
            self.assertGreater(report["diagnostics"]["gap_rejected"], 0)


if __name__ == "__main__":
    unittest.main()
