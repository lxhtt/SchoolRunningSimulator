package dev.ratemock.core.feasibility

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Example configuration documented as uncalibrated, not as physiology. */
class GaitLimitsTest {
    @Test
    fun `example limits produce the documented reachable speed range`() {
        val reachable = FeasibilitySolver.reachableSpeedRange(EXAMPLE_LIMITS)
        assertEquals(EXAMPLE_MIN_SPEED_MPS, reachable.minMps, 0.0)
        assertEquals(EXAMPLE_MAX_SPEED_MPS, reachable.maxMps, 0.0)
        assertEquals(1.8667, reachable.minMps, 1e-4)
        assertEquals(4.1167, reachable.maxMps, 1e-4)
    }

    @Test
    fun `limits reject non finite bounds`() {
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> {
                GaitLimits(cadenceSpm = invalid..190.0, stepLengthMeters = 0.70..1.30)
            }
            assertFailsWith<IllegalArgumentException> {
                GaitLimits(cadenceSpm = 160.0..invalid, stepLengthMeters = 0.70..1.30)
            }
            assertFailsWith<IllegalArgumentException> {
                GaitLimits(cadenceSpm = 160.0..190.0, stepLengthMeters = 0.70..invalid)
            }
        }
    }

    @Test
    fun `limits reject zero and negative bounds because rest is a separate state`() {
        assertFailsWith<IllegalArgumentException> {
            GaitLimits(cadenceSpm = 0.0..190.0, stepLengthMeters = 0.70..1.30)
        }
        assertFailsWith<IllegalArgumentException> {
            GaitLimits(cadenceSpm = 160.0..190.0, stepLengthMeters = -0.1..1.30)
        }
    }

    @Test
    fun `limits reject a reversed range`() {
        assertFailsWith<IllegalArgumentException> {
            GaitLimits(cadenceSpm = 190.0..160.0, stepLengthMeters = 0.70..1.30)
        }
    }
}

class FeasibilitySolverTest {
    @Test
    fun `a requested range inside the reachable range is feasible`() {
        val report = FeasibilitySolver.check(2.0..3.5, EXAMPLE_LIMITS)
        assertTrue(report.isFeasible)
        assertTrue(report.violations.isEmpty())
        assertTrue(report.describe().contains("feasible"))
    }

    @Test
    fun `the reachable boundaries are inclusive`() {
        val report = FeasibilitySolver.check(
            EXAMPLE_MIN_SPEED_MPS..EXAMPLE_MAX_SPEED_MPS,
            EXAMPLE_LIMITS,
        )
        assertTrue(report.isFeasible, report.describe())
    }

    @Test
    fun `a requested minimum below the reachable minimum reports the exact boundary`() {
        val report = FeasibilitySolver.check(1.5..3.0, EXAMPLE_LIMITS)
        assertFalse(report.isFeasible)
        assertEquals(1, report.violations.size)
        val violation = report.violations.single()
        assertEquals(SpeedViolationKind.BELOW_REACHABLE_MINIMUM, violation.kind)
        assertEquals(1.5, violation.requestedSpeedMps, 0.0)
        assertEquals(EXAMPLE_MIN_SPEED_MPS, violation.boundarySpeedMps, 1e-12)
        assertTrue(violation.describe().contains("1.500"))
        assertTrue(violation.describe().contains("1.867"))
    }

    @Test
    fun `a requested maximum above the reachable maximum reports the exact boundary`() {
        val report = FeasibilitySolver.check(3.0..4.5, EXAMPLE_LIMITS)
        assertFalse(report.isFeasible)
        val violation = report.violations.single()
        assertEquals(SpeedViolationKind.ABOVE_REACHABLE_MAXIMUM, violation.kind)
        assertEquals(4.5, violation.requestedSpeedMps, 0.0)
        assertEquals(EXAMPLE_MAX_SPEED_MPS, violation.boundarySpeedMps, 1e-12)
    }

    @Test
    fun `both ends can conflict at once`() {
        val report = FeasibilitySolver.check(1.0..5.0, EXAMPLE_LIMITS)
        assertEquals(2, report.violations.size)
        assertEquals(
            listOf(
                SpeedViolationKind.BELOW_REACHABLE_MINIMUM,
                SpeedViolationKind.ABOVE_REACHABLE_MAXIMUM,
            ),
            report.violations.map { it.kind },
        )
    }

    @Test
    fun `zero speed is stationary rather than a reachable forward speed`() {
        val report = FeasibilitySolver.check(0.0..2.0, EXAMPLE_LIMITS)
        assertFalse(report.isFeasible)
        assertEquals(SpeedViolationKind.BELOW_REACHABLE_MINIMUM, report.violations.single().kind)
        assertNull(FeasibilitySolver.feasibleCadenceRange(0.0, EXAMPLE_LIMITS))
        assertNull(FeasibilitySolver.feasibleStepLengthRange(0.0, EXAMPLE_LIMITS))
    }

    @Test
    fun `requests reject non finite and reversed bounds`() {
        assertFailsWith<IllegalArgumentException> {
            FeasibilitySolver.check(Double.NaN..3.0, EXAMPLE_LIMITS)
        }
        assertFailsWith<IllegalArgumentException> {
            FeasibilitySolver.check(3.0..1.0, EXAMPLE_LIMITS)
        }
        assertFailsWith<IllegalArgumentException> {
            FeasibilitySolver.feasibleCadenceRange(-1.0, EXAMPLE_LIMITS)
        }
    }

    @Test
    fun `feasible cadence interval matches the identity at both ends`() {
        val range = assertNotNull(FeasibilitySolver.feasibleCadenceRange(2.5, EXAMPLE_LIMITS))
        assertEquals(160.0, range.start, 1e-12)
        assertEquals(190.0, range.endInclusive, 1e-12)
        for (cadence in listOf(range.start, range.endInclusive)) {
            val stepLength = 60.0 * 2.5 / cadence
            assertTrue(stepLength in 0.70..1.30, "step length $stepLength out of range")
        }
    }

    @Test
    fun `feasible step length interval matches the identity at both ends`() {
        val range = assertNotNull(FeasibilitySolver.feasibleStepLengthRange(2.5, EXAMPLE_LIMITS))
        assertEquals(0.7894736842105263, range.start, 1e-12)
        assertEquals(0.9375, range.endInclusive, 1e-12)
        for (stepLength in listOf(range.start, range.endInclusive)) {
            val cadence = 60.0 * 2.5 / stepLength
            assertTrue(cadence in 160.0..190.0, "cadence $cadence out of range")
        }
    }

    @Test
    fun `a speed at the exact reachable maximum still has a usable interval`() {
        // 60 * vHi / sMax rounds to just above cMax, so this is the regression
        // guard for the boundary that naively compared as an empty interval.
        val cadence = assertNotNull(
            FeasibilitySolver.feasibleCadenceRange(EXAMPLE_MAX_SPEED_MPS, EXAMPLE_LIMITS),
        )
        assertEquals(190.0, cadence.start, 1e-9)
        assertEquals(190.0, cadence.endInclusive, 1e-9)
        val stepLength = assertNotNull(
            FeasibilitySolver.feasibleStepLengthRange(EXAMPLE_MAX_SPEED_MPS, EXAMPLE_LIMITS),
        )
        assertEquals(1.30, stepLength.start, 1e-9)
    }

    @Test
    fun `a speed at the exact reachable minimum still has a usable interval`() {
        val cadence = assertNotNull(
            FeasibilitySolver.feasibleCadenceRange(EXAMPLE_MIN_SPEED_MPS, EXAMPLE_LIMITS),
        )
        assertEquals(160.0, cadence.start, 1e-9)
        assertEquals(160.0, cadence.endInclusive, 1e-9)
    }

    @Test
    fun `inside the reachable range every speed admits some gait and outside none does`() {
        var speedMps = EXAMPLE_MIN_SPEED_MPS
        while (speedMps <= EXAMPLE_MAX_SPEED_MPS) {
            val cadence = FeasibilitySolver.feasibleCadenceRange(speedMps, EXAMPLE_LIMITS)
            val stepLength = FeasibilitySolver.feasibleStepLengthRange(speedMps, EXAMPLE_LIMITS)
            assertNotNull(cadence, "no cadence interval at $speedMps m/s")
            assertNotNull(stepLength, "no step-length interval at $speedMps m/s")
            for (candidate in listOf(cadence.start, cadence.endInclusive)) {
                assertTrue(
                    candidate in 160.0..190.0,
                    "cadence $candidate out of range at $speedMps m/s",
                )
            }
            speedMps += 0.05
        }
        assertNull(FeasibilitySolver.feasibleCadenceRange(EXAMPLE_MIN_SPEED_MPS - 1e-6, EXAMPLE_LIMITS))
        assertNull(FeasibilitySolver.feasibleCadenceRange(EXAMPLE_MAX_SPEED_MPS + 1e-6, EXAMPLE_LIMITS))
    }
}
