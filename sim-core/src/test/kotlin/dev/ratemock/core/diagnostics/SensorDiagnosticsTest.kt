package dev.ratemock.core.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SensorDiagnosticsTest {
    @Test
    fun emptyReportStillShowsDeclaredSensorCapabilities() {
        val report = SensorDiagnosticReport.summarize(
            availableSensors = setOf(DiagnosticSensor.STEP_DETECTOR),
            events = emptyList(),
        )

        assertTrue(report.first { it.sensor == DiagnosticSensor.STEP_DETECTOR }.available)
        assertFalse(report.first { it.sensor == DiagnosticSensor.STEP_COUNTER }.available)
        assertEquals(0, report.sumOf { it.eventCount })
    }

    @Test
    fun reportMeasuresEventGapsAndCounterMonotonicity() {
        val report = SensorDiagnosticReport.summarize(
            availableSensors = DiagnosticSensor.entries.toSet(),
            events = listOf(
                SensorDiagnosticEvent(DiagnosticSensor.STEP_DETECTOR, 1_000_000_000L),
                SensorDiagnosticEvent(DiagnosticSensor.STEP_DETECTOR, 2_500_000_000L),
                SensorDiagnosticEvent(DiagnosticSensor.STEP_COUNTER, 1_000_000_000L, 10.0),
                SensorDiagnosticEvent(DiagnosticSensor.STEP_COUNTER, 3_000_000_000L, 9.0),
            ),
        )

        val detector = report.first { it.sensor == DiagnosticSensor.STEP_DETECTOR }
        assertEquals(2, detector.eventCount)
        assertEquals(1.5, detector.maxGapSeconds)
        assertTrue(detector.timestampsIncreasing)
        assertNull(detector.counterMonotonic)
        assertFalse(report.first { it.sensor == DiagnosticSensor.STEP_COUNTER }.counterMonotonic!!)
    }

    @Test
    fun nonFiniteCounterValueIsFlagged() {
        val report = SensorDiagnosticReport.summarize(
            availableSensors = setOf(DiagnosticSensor.STEP_COUNTER),
            events = listOf(SensorDiagnosticEvent(DiagnosticSensor.STEP_COUNTER, 10L, Double.NaN)),
        )

        assertFalse(report.first { it.sensor == DiagnosticSensor.STEP_COUNTER }.counterMonotonic!!)
    }

    @Test
    fun nonIncreasingTimestampsAreFlagged() {
        val report = SensorDiagnosticReport.summarize(
            availableSensors = emptySet(),
            events = listOf(
                SensorDiagnosticEvent(DiagnosticSensor.ACCELEROMETER, 10L, 1.0),
                SensorDiagnosticEvent(DiagnosticSensor.ACCELEROMETER, 10L, 2.0),
            ),
        )

        assertFalse(report.first { it.sensor == DiagnosticSensor.ACCELEROMETER }.timestampsIncreasing)
    }
}
