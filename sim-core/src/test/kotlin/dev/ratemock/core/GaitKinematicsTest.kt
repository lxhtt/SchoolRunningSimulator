package dev.ratemock.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GaitKinematicsTest {
    @Test
    fun `cadence in steps per minute is converted to steps per second`() {
        assertEquals(2.7, GaitKinematics.speedMetersPerSecond(180.0, 0.9), 1e-12)
    }

    @Test
    fun `one step is not a complete two step stride`() {
        assertEquals(1.0, GaitKinematics.stepLengthMeters(3.0, 180.0), 1e-12)
    }

    @Test
    fun `short steps are not rejected using an invented physiological floor`() {
        assertEquals(120.0 / 172.0, GaitKinematics.stepLengthMeters(2.0, 172.0), 1e-12)
    }

    @Test
    fun `stationary state yields zero speed`() {
        assertEquals(0.0, GaitKinematics.speedMetersPerSecond(0.0, 0.0))
    }

    @Test
    fun `zero forward step length supports stepping in place`() {
        assertEquals(0.0, GaitKinematics.speedMetersPerSecond(180.0, 0.0))
        assertEquals(0.0, GaitKinematics.stepLengthMeters(0.0, 180.0))
    }

    @Test
    fun `zero speed and cadence use an explicit zero step convention`() {
        assertEquals(0.0, GaitKinematics.stepLengthMeters(0.0, 0.0))
    }

    @Test
    fun `motion with zero cadence cannot have a finite step length`() {
        assertFailsWith<IllegalArgumentException> {
            GaitKinematics.stepLengthMeters(2.0, 0.0)
        }
    }

    @Test
    fun `speed rejects invalid cadence`() {
        for (invalid in invalidValues) {
            assertFailsWith<IllegalArgumentException> {
                GaitKinematics.speedMetersPerSecond(invalid, 0.9)
            }
        }
    }

    @Test
    fun `speed rejects invalid step length`() {
        for (invalid in invalidValues) {
            assertFailsWith<IllegalArgumentException> {
                GaitKinematics.speedMetersPerSecond(180.0, invalid)
            }
        }
    }

    @Test
    fun `inverse rejects invalid speed`() {
        for (invalid in invalidValues) {
            assertFailsWith<IllegalArgumentException> {
                GaitKinematics.stepLengthMeters(invalid, 180.0)
            }
        }
    }

    @Test
    fun `inverse rejects invalid cadence even while stationary`() {
        for (invalid in invalidValues) {
            assertFailsWith<IllegalArgumentException> {
                GaitKinematics.stepLengthMeters(0.0, invalid)
            }
        }
    }

    @Test
    fun `speed overflow is rejected rather than exported as infinity`() {
        assertFailsWith<IllegalArgumentException> {
            GaitKinematics.speedMetersPerSecond(Double.MAX_VALUE, Double.MAX_VALUE)
        }
    }

    @Test
    fun `step length overflow is rejected rather than exported as infinity`() {
        assertFailsWith<IllegalArgumentException> {
            GaitKinematics.stepLengthMeters(Double.MAX_VALUE, 1.0)
        }
    }

    @Test
    fun `unit conversion round trips over a deterministic input grid`() {
        for (cadence in 60..240 step 3) {
            for (tenths in 1..30) {
                val stepLength = tenths / 10.0
                val speed = GaitKinematics.speedMetersPerSecond(cadence.toDouble(), stepLength)
                assertEquals(
                    stepLength,
                    GaitKinematics.stepLengthMeters(speed, cadence.toDouble()),
                    1e-12,
                    "cadence=$cadence, stepLength=$stepLength",
                )
            }
        }
    }

    private val invalidValues = listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)
}
