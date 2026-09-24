"""Synthetic checks for the offline Apple Health extractor; no personal data."""

from datetime import datetime, timedelta, timezone
from pathlib import Path
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))
import apple_health_calibration as apple


BASE = datetime(2026, 1, 1, tzinfo=timezone.utc)


def health_time(seconds):
    return (BASE + timedelta(seconds=seconds)).strftime("%Y-%m-%d %H:%M:%S %z")


def gpx_time(seconds):
    return (BASE + timedelta(seconds=seconds)).isoformat()


class AppleHealthCalibrationTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name)
        self.routes = self.base / "workout-routes"
        self.routes.mkdir()

    def fixture(self, poor_accuracy=False):
        health = ET.Element("HealthData")
        for offset, activity, cadence, speed in (
            (0, "Walking", 120, 1.5),
            (3600, "Running", 168, 3.0),
        ):
            for second in range(15, 195, 10):
                attrs = dict(
                    startDate=health_time(offset + second),
                    endDate=health_time(offset + second + 10),
                    type=apple.STEP,
                    unit="count",
                    value=str(cadence / 6),
                    sourceName="watch",
                )
                ET.SubElement(health, "Record", attrs)
                ET.SubElement(health, "Record", attrs.copy())
                ET.SubElement(health, "Record", {**attrs, "sourceName": "phone", "value": "200"})
            ET.SubElement(
                health,
                "Workout",
                workoutActivityType=f"HKWorkoutActivityType{activity}",
                startDate=health_time(offset),
                endDate=health_time(offset + 240),
            )
            gpx = ET.Element("gpx")
            track = ET.SubElement(ET.SubElement(gpx, "trk"), "trkseg")
            for second in range(241):
                point = ET.SubElement(track, "trkpt")
                ET.SubElement(point, "time").text = gpx_time(offset + second)
                extensions = ET.SubElement(point, "extensions")
                ET.SubElement(extensions, "speed").text = str(speed)
                ET.SubElement(extensions, "hAcc").text = "40" if poor_accuracy else "5"
            ET.ElementTree(gpx).write(self.routes / f"{activity}.gpx", encoding="utf-8")
        xml = self.base / "export.xml"
        ET.ElementTree(health).write(xml, encoding="utf-8")
        return xml

    def test_separate_workouts_and_deduplicate_sources(self):
        results, report = apple.extract(self.fixture(), self.routes)
        self.assertEqual(3, len(results["walking"]))
        self.assertEqual(3, len(results["running"]))
        self.assertEqual((1.5, 120.0), results["walking"][0][:2])
        self.assertEqual((3.0, 168.0), results["running"][0][:2])
        self.assertEqual(1, report["accepted_workouts"]["walking"])
        self.assertEqual(1, report["accepted_workouts"]["running"])

    def test_rejects_inaccurate_routes(self):
        results, report = apple.extract(self.fixture(poor_accuracy=True), self.routes)
        self.assertFalse(results["walking"])
        self.assertFalse(results["running"])
        self.assertEqual(0, report["route_workouts"]["walking"])


if __name__ == "__main__":
    unittest.main()
