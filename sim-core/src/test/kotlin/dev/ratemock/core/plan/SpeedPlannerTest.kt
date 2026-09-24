package dev.ratemock.core.plan

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val EXAMPLE_DYNAMICS = DynamicsLimits(maxAccelMps2 = 2.0, maxJerkMps3 = 8.0)

class RunPlanTest {
    @Test
    fun `a plan accepts duration and distance segments`() {
        val plan = RunPlan(
            segments = listOf(
                Segment(2.5, SegmentLimit.Duration(30.0)),
                Segment(3.0, SegmentLimit.Distance(400.0)),
            ),
            pauses = listOf(PauseWindow(10.0, 5.0)),
        )
        assertEquals(2, plan.segments.size)
        assertNotNull(plan.pauseAt(10.0))
        assertNotNull(plan.pauseAt(14.999))
        assertNull(plan.pauseAt(15.0))
    }

    @Test
    fun `segments reject invalid values`() {
        assertFailsWith<IllegalArgumentException> { Segment(0.0, SegmentLimit.Duration(1.0)) }
        assertFailsWith<IllegalArgumentException> { Segment(-1.0, SegmentLimit.Duration(1.0)) }
        assertFailsWith<IllegalArgumentException> { Segment(2.0, SegmentLimit.Duration(0.0)) }
        assertFailsWith<IllegalArgumentException> { Segment(2.0, SegmentLimit.Distance(Double.NaN)) }
        assertFailsWith<IllegalArgumentException> { RunPlan(emptyList()) }
    }

    @Test
    fun `pauses must be ordered and non overlapping`() {
        assertFailsWith<IllegalArgumentException> {
            RunPlan(
                segments = listOf(Segment(2.0, SegmentLimit.Duration(1.0))),
                pauses = listOf(PauseWindow(5.0, 2.0), PauseWindow(6.0, 2.0)),
            )
        }
        assertFailsWith<IllegalArgumentException> { PauseWindow(-1.0, 1.0) }
        assertFailsWith<IllegalArgumentException> { PauseWindow(1.0, 0.0) }
    }

    @Test
    fun `pause lookup rejects invalid elapsed time`() {
        val plan = RunPlan(listOf(Segment(2.0, SegmentLimit.Duration(1.0))))
        assertFailsWith<IllegalArgumentException> { plan.pauseAt(-1.0) }
        assertFailsWith<IllegalArgumentException> { plan.pauseAt(Double.NaN) }
    }

    @Test
    fun `dynamics limits reject invalid values`() {
        assertFailsWith<IllegalArgumentException> { DynamicsLimits(0.0, 8.0) }
        assertFailsWith<IllegalArgumentException> { DynamicsLimits(2.0, Double.POSITIVE_INFINITY) }
    }
}

class SpeedPlannerTest {
    @Test
    fun `planner starts with jerk limited acceleration`() {
        val sample = SpeedPlanner(EXAMPLE_DYNAMICS).advance(2.5, 0.1)
        assertEquals(0.8, sample.accelerationMps2, 1e-12)
        assertEquals(0.08, sample.speedMps, 1e-12)
        assertEquals(8.0, sample.jerkMps3, 1e-12)
        assertFalse(sample.terminalReset)
    }

    @Test
    fun `acceleration and jerk limits hold away from terminal reset`() {
        val planner = SpeedPlanner(EXAMPLE_DYNAMICS)
        repeat(30) {
            val sample = planner.advance(2.5, 0.1)
            if (!sample.terminalReset) {
                assertTrue(kotlin.math.abs(sample.accelerationMps2) <= 2.0 + 1e-12)
                assertTrue(kotlin.math.abs(sample.jerkMps3) <= 8.0 + 1e-9)
            }
            assertTrue(sample.speedMps >= 0.0)
        }
    }

    @Test
    fun `planner converges to a steady target without persistent oscillation`() {
        val planner = SpeedPlanner(EXAMPLE_DYNAMICS)
        val samples = (0 until 100).map { planner.advance(2.5, 0.1) }
        assertEquals(2.5, samples.last().speedMps, 1e-9)
        assertEquals(0.0, samples.last().accelerationMps2, 1e-9)
        assertTrue(samples.takeLast(20).all { kotlin.math.abs(it.speedMps - 2.5) < 1e-9 })
    }

    @Test
    fun `planner can decelerate to rest and marks the terminal reset`() {
        val planner = SpeedPlanner(EXAMPLE_DYNAMICS)
        repeat(40) { planner.advance(2.5, 0.1) }
        val stopping = (0 until 80).map { planner.advance(0.0, 0.1) }
        assertTrue(stopping.any { it.terminalReset })
        assertEquals(0.0, stopping.last().speedMps, 1e-12)
        assertEquals(0.0, stopping.last().accelerationMps2, 1e-12)
        assertTrue(stopping.last().speedMps >= 0.0)
    }

    @Test
    fun `target changes remain causal and non negative`() {
        val planner = SpeedPlanner(EXAMPLE_DYNAMICS)
        val targets = listOf(1.0, 3.0, 0.5, 2.0, 0.0)
        for (target in targets) {
            repeat(30) {
                val sample = planner.advance(target, 0.1)
                assertEquals(target, sample.targetSpeedMps, 0.0)
                assertTrue(sample.speedMps >= 0.0)
            }
        }
    }

    @Test
    fun `reset returns the planner to its deterministic initial state`() {
        val planner = SpeedPlanner(EXAMPLE_DYNAMICS)
        planner.advance(2.5, 0.1)
        planner.reset()
        val afterReset = planner.advance(2.5, 0.1)
        assertEquals(0.08, afterReset.speedMps, 1e-12)
        assertEquals(0.8, afterReset.accelerationMps2, 1e-12)
    }

    @Test
    fun `planner rejects invalid targets and steps`() {
        val planner = SpeedPlanner(EXAMPLE_DYNAMICS)
        assertFailsWith<IllegalArgumentException> { planner.advance(-1.0, 0.1) }
        assertFailsWith<IllegalArgumentException> { planner.advance(Double.NaN, 0.1) }
        assertFailsWith<IllegalArgumentException> { planner.advance(2.0, 0.0) }
        assertFailsWith<IllegalArgumentException> { planner.advance(2.0, Double.POSITIVE_INFINITY) }
    }
}
