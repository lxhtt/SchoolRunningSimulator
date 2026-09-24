package dev.ratemock.core.truth

import dev.ratemock.core.feasibility.FixedCadence
import dev.ratemock.core.feasibility.GaitLimits
import dev.ratemock.core.feasibility.GaitResolver
import dev.ratemock.core.plan.DynamicsLimits
import dev.ratemock.core.plan.PauseWindow
import dev.ratemock.core.plan.RunPlan
import dev.ratemock.core.plan.Segment
import dev.ratemock.core.plan.SegmentLimit
import dev.ratemock.core.plan.SpeedPlanner
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private fun engine(
    plan: RunPlan,
    stepSeconds: Double = 0.1,
): GaitEngine = GaitEngine(
    plan = plan,
    gaitResolver = GaitResolver(
        GaitLimits(160.0..190.0, 0.7..1.3),
        FixedCadence(175.0),
    ),
    speedPlanner = SpeedPlanner(DynamicsLimits(2.0, 8.0)),
    stepSeconds = stepSeconds,
)

class TruthTypesTest {
    @Test
    fun `truth sample validates non negative fields and stationary convention`() {
        val sample = TruthSample(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, RunState.STARTING)
        assertEquals(RunState.STARTING, sample.state)
        assertFailsWith<IllegalArgumentException> {
            TruthSample(0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, RunState.RUNNING)
        }
        assertFailsWith<IllegalArgumentException> {
            StepEvent(0.0, 0.0, 0.0, 1.0)
        }
    }
}

class GaitEngineTest {
    @Test
    fun `duration plan reaches finished state and produces deterministic frames`() {
        val plan = RunPlan(listOf(Segment(2.5, SegmentLimit.Duration(1.0))))
        val first = engine(plan).run()
        val second = engine(plan).run()
        assertEquals(first, second)
        assertEquals(RunState.FINISHED, first.last().truth.state)
        assertTrue(first.last().truth.speedMps == 0.0)
        assertTrue(first.any { it.steps.isNotEmpty() })
    }

    @Test
    fun `truth gait identity holds for every moving frame`() {
        val frames = engine(RunPlan(listOf(Segment(2.5, SegmentLimit.Duration(2.0)))))
            .run()
        frames.map { it.truth }.filter { it.speedMps > 0.0 }.forEach { truth ->
            assertTrue(abs(truth.speedMps - truth.cadenceSpm * truth.stepLengthMeters / 60.0) < 1e-9)
        }
    }

    @Test
    fun `distance is the integral of sampled speed`() {
        val step = 0.1
        val frames = engine(
            RunPlan(listOf(Segment(2.5, SegmentLimit.Duration(2.0)))),
            step,
        ).run()
        frames.zipWithNext().forEach { (previous, current) ->
            assertTrue(current.truth.distanceMeters >= previous.truth.distanceMeters)
        }
        val integrated = frames.sumOf { it.truth.speedMps * step }
        assertEquals(integrated, frames.last().truth.distanceMeters, 1e-10)
    }

    @Test
    fun `pause holds position and marks paused state`() {
        val plan = RunPlan(
            segments = listOf(Segment(2.5, SegmentLimit.Duration(2.0))),
            pauses = listOf(PauseWindow(0.5, 0.5)),
        )
        val frames = engine(plan).run()
        val paused = frames.filter { it.truth.state == RunState.PAUSED }
        assertTrue(paused.isNotEmpty())
        paused.zipWithNext().forEach { (a, b) ->
            assertEquals(a.truth.distanceMeters, b.truth.distanceMeters, 1e-12)
        }
    }

    @Test
    fun `distance limited segment advances after its distance is reached`() {
        val plan = RunPlan(
            listOf(
                Segment(2.5, SegmentLimit.Distance(1.0)),
                Segment(3.0, SegmentLimit.Duration(0.5)),
            ),
        )
        val frames = engine(plan).run()
        assertEquals(RunState.FINISHED, frames.last().truth.state)
        assertTrue(frames.any { it.truth.speedMps > 2.6 })
        assertTrue(frames.last().truth.distanceMeters > 1.0)
    }

    @Test
    fun `step events are ordered and do not exceed truth distance`() {
        val frames = engine(RunPlan(listOf(Segment(2.5, SegmentLimit.Duration(3.0))))).run()
        val events = frames.flatMap { it.steps }
        assertTrue(events.isNotEmpty())
        events.zipWithNext().forEach { (a, b) ->
            assertTrue(b.timeSeconds >= a.timeSeconds)
            assertTrue(b.distanceMeters > a.distanceMeters)
        }
        events.forEach { event ->
            assertTrue(event.distanceMeters <= frames.last().truth.distanceMeters + 1e-9)
        }
    }

    @Test
    fun `engine rejects invalid step size and cannot advance after finish`() {
        val plan = RunPlan(listOf(Segment(2.5, SegmentLimit.Duration(0.1))))
        assertFailsWith<IllegalArgumentException> { engine(plan, 0.0) }
        val gait = engine(plan)
        gait.run()
        assertFailsWith<IllegalStateException> { gait.advance() }
    }
}
