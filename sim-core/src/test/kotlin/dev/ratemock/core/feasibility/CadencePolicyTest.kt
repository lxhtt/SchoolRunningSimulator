package dev.ratemock.core.feasibility

import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CadencePolicyTest {
    @Test
    fun `power law matches the documented anchored value`() {
        assertEquals(175.018, EXAMPLE_POLICY.modelCadenceSpm(2.5), 0.01)
    }

    @Test
    fun `power law is monotonic in speed`() {
        var previous = EXAMPLE_POLICY.modelCadenceSpm(0.5)
        var speedMps = 0.75
        while (speedMps <= 5.0) {
            val current = EXAMPLE_POLICY.modelCadenceSpm(speedMps)
            assertTrue(current > previous, "cadence did not increase at $speedMps m/s")
            previous = current
            speedMps += 0.25
        }
    }

    @Test
    fun `power law rejects parameters outside the documented families`() {
        assertFailsWith<IllegalArgumentException> { PowerLawCadence(0.0, 0.35) }
        assertFailsWith<IllegalArgumentException> { PowerLawCadence(-127.0, 0.35) }
        assertFailsWith<IllegalArgumentException> { PowerLawCadence(Double.NaN, 0.35) }
        assertFailsWith<IllegalArgumentException> { PowerLawCadence(127.0, 0.0) }
        assertFailsWith<IllegalArgumentException> { PowerLawCadence(127.0, 1.01) }
        assertFailsWith<IllegalArgumentException> { PowerLawCadence(127.0, Double.POSITIVE_INFINITY) }
    }

    @Test
    fun `linear and fixed policies evaluate as documented`() {
        assertEquals(175.0, LinearCadence(interceptSpm = 100.0, slopeSpmPerMps = 30.0).modelCadenceSpm(2.5), 1e-12)
        assertEquals(175.0, FixedCadence(175.0).modelCadenceSpm(2.5), 0.0)
        assertEquals(175.0, FixedCadence(175.0).modelCadenceSpm(4.1), 0.0)
    }

    @Test
    fun `linear accepts finite parameters without asserting positivity`() {
        // A line that leaves the usable band is a policy problem, not a
        // construction error; the resolver clamps and records it.
        val policy = LinearCadence(interceptSpm = -50.0, slopeSpmPerMps = 5.0)
        assertEquals(-37.5, policy.modelCadenceSpm(2.5), 1e-12)
        assertFailsWith<IllegalArgumentException> { LinearCadence(Double.NaN, 30.0) }
        assertFailsWith<IllegalArgumentException> { LinearCadence(100.0, Double.NEGATIVE_INFINITY) }
    }

    @Test
    fun `fixed cadence rejects non positive values`() {
        assertFailsWith<IllegalArgumentException> { FixedCadence(0.0) }
        assertFailsWith<IllegalArgumentException> { FixedCadence(-175.0) }
    }

    @Test
    fun `policies reject invalid speeds`() {
        for (invalid in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0)) {
            assertFailsWith<IllegalArgumentException> { EXAMPLE_POLICY.modelCadenceSpm(invalid) }
        }
    }
}

class GaitResolverTest {
    private val resolver = GaitResolver(EXAMPLE_LIMITS, EXAMPLE_POLICY)

    @Test
    fun `zero speed uses the stationary convention`() {
        val sample = resolver.resolve(0.0)
        assertEquals(0.0, sample.speedMps)
        assertEquals(0.0, sample.modelCadenceSpm)
        assertEquals(0.0, sample.cadenceSpm)
        assertEquals(0.0, sample.stepLengthMeters)
        assertFalse(sample.clamped)
    }

    @Test
    fun `the step identity holds across the whole reachable range`() {
        var speedMps = EXAMPLE_MIN_SPEED_MPS
        while (speedMps <= EXAMPLE_MAX_SPEED_MPS) {
            val sample = resolver.resolve(speedMps)
            val expected = sample.cadenceSpm * sample.stepLengthMeters / 60.0
            assertEquals(1.0, sample.speedMps / expected, 1e-9, "identity broken at $speedMps m/s")
            assertTrue(
                sample.stepLengthMeters in 0.70..1.30,
                "step length ${sample.stepLengthMeters} out of range at $speedMps m/s",
            )
            assertTrue(
                sample.cadenceSpm in 160.0..190.0,
                "cadence ${sample.cadenceSpm} out of range at $speedMps m/s",
            )
            speedMps += 0.05
        }
    }

    @Test
    fun `a cadence model inside the feasible band is used unchanged`() {
        for (speedMps in listOf(2.5, 3.0)) {
            val sample = resolver.resolve(speedMps)
            assertFalse(sample.clamped, "unexpected clamp at $speedMps m/s")
            assertEquals(EXAMPLE_POLICY.modelCadenceSpm(speedMps), sample.cadenceSpm, 0.0)
            assertEquals(0.0, sample.clampDeviationFraction, 0.0)
        }
    }

    @Test
    fun `a slow cadence model is clamped up to the feasible minimum`() {
        val sample = resolver.resolve(1.9)
        assertTrue(sample.clamped)
        assertEquals(160.0, sample.cadenceSpm, 1e-12)
        assertEquals(0.7125, sample.stepLengthMeters, 1e-12)
        assertTrue(sample.modelCadenceSpm < sample.cadenceSpm)
        assertTrue(sample.clampDeviationFraction > 0.0)
    }

    @Test
    fun `a fast cadence model is clamped down to the feasible maximum`() {
        val sample = resolver.resolve(4.0)
        assertTrue(sample.clamped)
        assertEquals(190.0, sample.cadenceSpm, 1e-12)
        assertEquals(1.263157894736842, sample.stepLengthMeters, 1e-12)
        assertTrue(sample.modelCadenceSpm > sample.cadenceSpm)
        assertTrue(sample.clampDeviationFraction < 0.0)
    }

    @Test
    fun `the model value is preserved even when it is clamped`() {
        val sample = resolver.resolve(4.0)
        assertEquals(206.31210867448382, sample.modelCadenceSpm, 1e-9)
        // The recorded model value must stay untouched, so consumers can see
        // how far the resolved gait deviated from the model.
        assertTrue(sample.modelCadenceSpm > 200.0)
    }

    @Test
    fun `a fixed cadence outside the feasible band is clamped and flagged`() {
        val fixed = GaitResolver(EXAMPLE_LIMITS, FixedCadence(150.0))
        val sample = fixed.resolve(2.5)
        assertTrue(sample.clamped)
        assertEquals(160.0, sample.cadenceSpm, 1e-12)
        assertEquals(150.0, sample.modelCadenceSpm, 0.0)
    }

    @Test
    fun `a linear policy that goes non positive is clamped instead of producing an invalid step`() {
        val linear = GaitResolver(EXAMPLE_LIMITS, LinearCadence(interceptSpm = -50.0, slopeSpmPerMps = 5.0))
        val sample = linear.resolve(2.5)
        assertTrue(sample.clamped)
        assertEquals(160.0, sample.cadenceSpm, 1e-12)
        assertTrue(sample.stepLengthMeters > 0.0)
    }

    @Test
    fun `speeds outside the reachable range are rejected with the reachable bounds`() {
        for (speedMps in listOf(1.0, 5.0)) {
            val error = assertFailsWith<IllegalArgumentException> { resolver.resolve(speedMps) }
            val message = error.message.orEmpty()
            assertTrue(message.contains("1.867"), "missing reachable minimum in: $message")
            assertTrue(message.contains("4.117"), "missing reachable maximum in: $message")
        }
    }

    @Test
    fun `a non finite model output is reported instead of leaking a NaN gait`() {
        val overflowing = GaitResolver(
            EXAMPLE_LIMITS,
            LinearCadence(interceptSpm = 1e308, slopeSpmPerMps = 1e308),
        )
        val error = assertFailsWith<IllegalArgumentException> { overflowing.resolve(2.5) }
        assertTrue(error.message.orEmpty().contains("Cadence model returned"))
    }

    @Test
    fun `resolver rejects invalid speeds`() {
        for (invalid in listOf(Double.NaN, Double.NEGATIVE_INFINITY, -0.5)) {
            assertFailsWith<IllegalArgumentException> { resolver.resolve(invalid) }
        }
    }

    @Test
    fun `clamping is continuous in speed`() {
        // Guard against a discontinuous jump: neighbouring speeds must not
        // produce wildly different step lengths at the clamp boundary.
        var previous = resolver.resolve(EXAMPLE_MIN_SPEED_MPS)
        var speedMps = EXAMPLE_MIN_SPEED_MPS + 0.01
        while (speedMps <= EXAMPLE_MAX_SPEED_MPS) {
            val current = resolver.resolve(speedMps)
            val jump = abs(current.stepLengthMeters - previous.stepLengthMeters)
            assertTrue(jump < 0.02, "step length jumped by $jump m at $speedMps m/s")
            previous = current
            speedMps += 0.01
        }
    }

    @Test
    fun `step length stays inside its range even with an extreme fixed cadence`() {
        for (cadence in listOf(1.0, 1000.0)) {
            val extreme = GaitResolver(EXAMPLE_LIMITS, FixedCadence(cadence))
            var speedMps = EXAMPLE_MIN_SPEED_MPS
            while (speedMps <= EXAMPLE_MAX_SPEED_MPS) {
                val sample = extreme.resolve(speedMps)
                val tolerance = 1e-9 * max(1.0, speedMps)
                assertTrue(
                    sample.stepLengthMeters >= 0.70 - tolerance &&
                        sample.stepLengthMeters <= 1.30 + tolerance,
                    "step length ${sample.stepLengthMeters} out of range at $speedMps m/s",
                )
                speedMps += 0.25
            }
        }
    }
}
