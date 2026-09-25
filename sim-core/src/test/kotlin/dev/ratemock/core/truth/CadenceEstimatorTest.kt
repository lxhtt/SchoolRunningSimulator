package dev.ratemock.core.truth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class CadenceEstimatorTest {
    @Test
    fun estimatesCadenceFromRecentStepEvents() {
        val estimator = CadenceEstimator(windowSeconds = 10.0, minimumSteps = 3)
        assertNull(estimator.addStep(0.0))
        assertNull(estimator.addStep(0.5))
        assertEquals(120.0, assertNotNull(estimator.addStep(1.0)), 1e-9)
        assertEquals(120.0, assertNotNull(estimator.addStep(1.5)), 1e-9)
    }

    @Test
    fun dropsEventsOutsideWindow() {
        val estimator = CadenceEstimator(windowSeconds = 2.0, minimumSteps = 3)
        estimator.addStep(0.0)
        estimator.addStep(0.5)
        estimator.addStep(1.0)
        assertNull(estimator.addStep(3.1))
    }
}
