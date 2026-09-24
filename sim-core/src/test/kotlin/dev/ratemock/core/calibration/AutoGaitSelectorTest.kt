package dev.ratemock.core.calibration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoGaitSelectorTest {
    private fun fit(start: Double, coefficient: Double): CalibrationFit {
        val dataset = CalibrationDataset((0 until 8).map { index ->
            val speed = start + index * 0.1
            CalibrationObservation(speed, coefficient + 10 * speed)
        })
        return CalibrationFitter.fit(dataset, CalibrationModel.LINEAR)
    }

    @Test
    fun `automatic mode selects distinct fitted models`() {
        val selector = AutoGaitSelector(fit(1.0, 90.0), fit(2.3, 140.0))
        assertEquals(2.0, selector.switchSpeedMps, 1e-9)
        val walk = selector.select(1.5)
        val run = selector.select(2.5)
        assertEquals(GaitMode.WALKING, walk.mode)
        assertEquals(GaitMode.RUNNING, run.mode)
        assertEquals(105.0, walk.cadenceSpm, 1e-8)
        assertEquals(165.0, run.cadenceSpm, 1e-8)
        assertFalse(walk.outsideFittedRange)
        assertFalse(run.outsideFittedRange)
    }

    @Test
    fun `previous mode keeps transition from oscillating`() {
        val selector = AutoGaitSelector(fit(1.0, 90.0), fit(2.3, 140.0))
        assertEquals(GaitMode.WALKING, selector.select(2.05, GaitMode.WALKING).mode)
        assertEquals(GaitMode.RUNNING, selector.select(2.11, GaitMode.WALKING).mode)
        assertEquals(GaitMode.RUNNING, selector.select(1.95, GaitMode.RUNNING).mode)
        assertEquals(GaitMode.WALKING, selector.select(1.89, GaitMode.RUNNING).mode)
        assertTrue(selector.select(2.05, GaitMode.WALKING).outsideFittedRange)
    }

    @Test
    fun `invalid speed and reversed fitted ranges are rejected`() {
        val selector = AutoGaitSelector(fit(1.0, 90.0), fit(2.3, 140.0))
        assertFailsWith<IllegalArgumentException> { selector.select(Double.NaN) }
        assertFailsWith<IllegalArgumentException> { selector.select(-1.0) }
        assertFailsWith<IllegalArgumentException> { AutoGaitSelector(fit(2.3, 140.0), fit(1.0, 90.0)) }
        assertFailsWith<IllegalArgumentException> { AutoGaitSelector(fit(1.0, 90.0), fit(2.3, 140.0), -0.1) }
    }
}
