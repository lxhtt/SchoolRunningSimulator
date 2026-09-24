package dev.ratemock.core.truth

import dev.ratemock.core.calibration.GaitMode
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
    fun `rejects invalid settings and elapsed time`() {
        assertFailsWith<IllegalArgumentException> { InteractiveSimulation(0.0, 30.0) }
        assertFailsWith<IllegalArgumentException> { InteractiveSimulation(5.1, 30.0) }
        assertFailsWith<IllegalArgumentException> { InteractiveSimulation(2.0, Double.NaN) }
        val session = InteractiveSimulation(2.0, 30.0)
        assertFailsWith<IllegalArgumentException> { session.advanceBy(Double.POSITIVE_INFINITY) }
        assertFailsWith<IllegalArgumentException> { session.advanceBy(-1.0) }
    }
}
