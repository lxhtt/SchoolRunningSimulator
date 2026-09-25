package dev.ratemock.core.truth

import dev.ratemock.core.calibration.GaitMode
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class InteractiveSimulationTest {
    @Test
    fun `automatic mode uses separate default gait parameters`() {
        assertEquals(GaitMode.WALKING, InteractiveSimulation(2.1, 20.0).mode)
        assertEquals(GaitMode.RUNNING, InteractiveSimulation(2.2, 20.0).mode)
        val walking = InteractiveSimulation(1.5, 20.0).advanceBy(3.0)
        val running = InteractiveSimulation(3.0, 20.0).advanceBy(3.0)
        assertTrue(walking.steps > 0)
        assertTrue(running.steps > 0)
        assertTrue(running.cadenceSpm > walking.cadenceSpm)
    }

    @Test
    fun `history samples once per second and freezes while paused`() {
        val session = InteractiveSimulation(2.7, 30.0)
        session.advanceBy(3.2)
        assertEquals(listOf(1, 2, 3), session.history().map { it.timeSeconds.roundToInt() })
        session.pause()
        session.advanceBy(5.0)
        assertEquals(3, session.history().size)
    }

    @Test
    fun `manual pause freezes elapsed time steps and distance until resume`() {
        val session = InteractiveSimulation(2.8, 30.0)
        val before = session.advanceBy(5.0)
        assertEquals(SimulationStatus.PAUSED, session.pause().status)
        val during = session.advanceBy(20.0)
        assertEquals(before.elapsedSeconds, during.elapsedSeconds, 1e-8)
        assertEquals(before.steps, during.steps)
        assertEquals(before.distanceMeters, during.distanceMeters, 1e-8)
        session.resume()
        assertTrue(session.advanceBy(1.0).elapsedSeconds > before.elapsedSeconds)
    }

    @Test
    fun `stop is terminal and preserves summary`() {
        val session = InteractiveSimulation(2.7, 30.0)
        session.advanceBy(5.0)
        val stopped = session.stop()
        assertEquals(SimulationStatus.STOPPED, stopped.status)
        assertEquals(stopped, session.advanceBy(100.0))
        assertEquals(stopped, session.resume())
    }

    @Test
    fun `natural completion terminates without overshooting a long delayed tick`() {
        val session = InteractiveSimulation(2.7, 10.0)
        val finished = session.advanceBy(60.0)
        assertEquals(SimulationStatus.COMPLETED, finished.status)
        assertTrue(finished.elapsedSeconds in 10.0..15.0)
        assertEquals(finished, session.advanceBy(60.0))
    }

    @Test
    fun `fractional clock advances without losing time`() {
        val session = InteractiveSimulation(2.7, 10.0)
        repeat(25) { session.advanceBy(0.04) }
        assertEquals(1.0, session.snapshot().elapsedSeconds, 1e-8)
    }

    @Test
    fun `manual cadence remains fixed while speed target changes`() {
        val session = InteractiveSimulation(2.7, 30.0, manualCadenceSpm = 140.0)
        val first = session.advanceBy(4.0)
        assertEquals(140.0, first.cadenceSpm, 1e-8)
        session.setTargetSpeed(2.3)
        val second = session.advanceBy(4.0)
        assertEquals(140.0, second.cadenceSpm, 1e-8)
        assertEquals(2.3, second.targetSpeedMps, 1e-8)
        assertTrue(second.distanceMeters > first.distanceMeters)
        assertFailsWith<IllegalArgumentException> { session.setTargetSpeed(5.0) }
        assertEquals(2.3, session.snapshot().targetSpeedMps, 1e-8)
    }

    @Test
    fun `automatic mode switches on a live target change`() {
        val session = InteractiveSimulation(1.5, 30.0)
        session.advanceBy(3.0)
        session.setTargetSpeed(3.0)
        val after = session.advanceBy(3.0)
        assertEquals(GaitMode.RUNNING, after.mode)
        assertEquals(170.0, after.cadenceSpm, 1e-8)
    }

    @Test
    fun `optional slowdown starts after seventy percent and never changes the base target`() {
        val session = InteractiveSimulation(3.0, 20.0, fatigueReduction = 0.2)
        val before = session.advanceBy(10.0)
        assertEquals(3.0, before.effectiveTargetSpeedMps, 1e-8)
        val after = session.advanceBy(9.0)
        assertTrue(after.effectiveTargetSpeedMps < 3.0)
        assertTrue(after.effectiveTargetSpeedMps > 2.4)
        assertEquals(3.0, after.targetSpeedMps, 1e-8)
        assertFailsWith<IllegalArgumentException> { InteractiveSimulation(2.0, 30.0, 100.0, 0.5) }
        assertFailsWith<IllegalArgumentException> { InteractiveSimulation(5.0, 30.0, 100.0) }
    }

    @Test
    fun `manual preview reports requested stride and does not silently clamp cadence`() {
        val gait = SimulatorGait.preview(2.7, 140.0)
        assertEquals(140.0, gait.cadenceSpm, 1e-8)
        assertEquals(2.7 * 60.0 / 140.0, gait.stepLengthMeters, 1e-8)
    }

    @Test
    fun `rejects invalid settings and elapsed time`() {
        assertFailsWith<IllegalArgumentException> { InteractiveSimulation(0.0, 30.0) }
        assertFailsWith<IllegalArgumentException> { InteractiveSimulation(5.1, 30.0) }
        assertFailsWith<IllegalArgumentException> { InteractiveSimulation(2.0, Double.NaN) }
        val session = InteractiveSimulation(2.0, 30.0)
        assertFailsWith<IllegalArgumentException> { session.advanceBy(Double.POSITIVE_INFINITY) }
        assertFailsWith<IllegalArgumentException> { session.advanceBy(-1.0) }
    }
}
