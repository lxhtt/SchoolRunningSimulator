package dev.ratemock.core.truth

import dev.ratemock.core.feasibility.GaitResolver
import dev.ratemock.core.plan.RunPlan
import dev.ratemock.core.plan.SegmentLimit
import dev.ratemock.core.plan.SpeedPlanner

/** One deterministic fixed-step truth simulation. */
class GaitEngine(
    private val plan: RunPlan,
    private val gaitResolver: GaitResolver,
    private val speedPlanner: SpeedPlanner,
    private val stepSeconds: Double,
) {
    private var segmentIndex = 0
    private var segmentElapsedSeconds = 0.0
    private var segmentDistanceMeters = 0.0
    private var timeSeconds = 0.0
    private var distanceMeters = 0.0
    private var stepRemainderMeters = 0.0
    private var state = RunState.STARTING
    private var stopping = false

    init {
        require(stepSeconds.isFinite() && stepSeconds > 0.0) {
            "Simulation step must be finite and strictly positive, got $stepSeconds"
        }
    }

    fun state(): RunState = state

    fun isFinished(): Boolean = state == RunState.FINISHED

    /** Advances one fixed step and returns the truth sample plus any footfalls. */
    fun advance(): EngineFrame {
        check(!isFinished()) { "Simulation is already finished" }

        val pause = plan.pauseAt(timeSeconds)
        val paused = pause != null
        state = when {
            paused -> RunState.PAUSED
            stopping -> RunState.RUNNING
            else -> RunState.RUNNING
        }

        val targetSpeed = when {
            paused || stopping -> 0.0
            else -> plan.segments[segmentIndex].targetSpeedMps
        }
        val speedSample = speedPlanner.advance(targetSpeed, stepSeconds)
        val gait = gaitResolver.resolveTransient(speedSample.speedMps)
        val movedMeters = speedSample.speedMps * stepSeconds
        timeSeconds += stepSeconds

        if (!paused && !stopping) {
            segmentElapsedSeconds += stepSeconds
            segmentDistanceMeters += movedMeters
        }
        distanceMeters += movedMeters
        val events = emitStepEvents(gait.stepLengthMeters, speedSample.speedMps)

        if (stopping && speedSample.speedMps == 0.0) {
            state = RunState.FINISHED
        } else if (!paused && !stopping && segmentIsComplete()) {
            if (segmentIndex == plan.segments.lastIndex) {
                stopping = true
            } else {
                segmentIndex += 1
                segmentElapsedSeconds = 0.0
                segmentDistanceMeters = 0.0
            }
        }

        val sample = TruthSample(
            timeSeconds = timeSeconds,
            distanceMeters = distanceMeters,
            eastMeters = distanceMeters,
            northMeters = 0.0,
            speedMps = speedSample.speedMps,
            cadenceSpm = gait.cadenceSpm,
            stepLengthMeters = gait.stepLengthMeters,
            state = state,
        )
        return EngineFrame(sample, events)
    }

    /** Runs until completion, bounded to protect callers from malformed plans. */
    fun run(maxSteps: Int = 100_000): List<EngineFrame> {
        require(maxSteps > 0) { "Maximum steps must be positive, got $maxSteps" }
        val frames = ArrayList<EngineFrame>()
        repeat(maxSteps) {
            if (isFinished()) return frames
            frames += advance()
        }
        error("Simulation did not finish within $maxSteps steps")
    }

    private fun segmentIsComplete(): Boolean {
        val limit = plan.segments[segmentIndex].limit
        return when (limit) {
            is SegmentLimit.Duration -> segmentElapsedSeconds + EPSILON >= limit.seconds
            is SegmentLimit.Distance -> segmentDistanceMeters + EPSILON >= limit.meters
        }
    }

    private fun emitStepEvents(stepLengthMeters: Double, speedMps: Double): List<StepEvent> {
        if (speedMps == 0.0 || stepLengthMeters == 0.0) return emptyList()
        stepRemainderMeters += speedMps * stepSeconds
        val events = ArrayList<StepEvent>()
        while (stepRemainderMeters + EPSILON >= stepLengthMeters) {
            stepRemainderMeters -= stepLengthMeters
            events += StepEvent(
                timeSeconds = timeSeconds,
                distanceMeters = distanceMeters - stepRemainderMeters,
                stepLengthMeters = stepLengthMeters,
                speedMps = speedMps,
            )
        }
        return events
    }

    companion object {
        private const val EPSILON = 1e-10
    }
}

data class EngineFrame(
    val truth: TruthSample,
    val steps: List<StepEvent>,
)
