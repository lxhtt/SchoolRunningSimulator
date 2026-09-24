package dev.ratemock.core.truth

import dev.ratemock.core.calibration.GaitMode
import dev.ratemock.core.feasibility.FixedCadence
import dev.ratemock.core.feasibility.GaitLimits
import dev.ratemock.core.feasibility.GaitResolver
import dev.ratemock.core.plan.DynamicsLimits
import dev.ratemock.core.plan.RunPlan
import dev.ratemock.core.plan.Segment
import dev.ratemock.core.plan.SegmentLimit
import dev.ratemock.core.plan.SpeedPlanner

/** User-controlled, fixed-step simulation. All gait parameters here are uncalibrated defaults. */
class InteractiveSimulation(
    val targetSpeedMps: Double,
    val durationSeconds: Double,
) {
    val mode: GaitMode = if (targetSpeedMps < MODE_BOUNDARY_MPS) GaitMode.WALKING else GaitMode.RUNNING
    private val engine: GaitEngine
    private var pendingSeconds = 0.0
    private var elapsedSeconds = 0.0
    private var distanceMeters = 0.0
    private var speedMps = 0.0
    private var cadenceSpm = 0.0
    private var steps = 0L
    private var status = SimulationStatus.RUNNING

    init {
        require(targetSpeedMps.isFinite() && targetSpeedMps in MIN_SPEED_MPS..MAX_SPEED_MPS) {
            "Target speed must be within $MIN_SPEED_MPS..$MAX_SPEED_MPS m/s"
        }
        require(durationSeconds.isFinite() && durationSeconds in MIN_DURATION_SECONDS..MAX_DURATION_SECONDS) {
            "Duration must be within $MIN_DURATION_SECONDS..$MAX_DURATION_SECONDS seconds"
        }
        val limits = if (mode == GaitMode.WALKING) {
            GaitLimits(90.0..170.0, 0.30..1.20)
        } else {
            GaitLimits(130.0..210.0, 0.55..1.60)
        }
        val policy = FixedCadence(if (mode == GaitMode.WALKING) 120.0 else 170.0)
        engine = GaitEngine(
            RunPlan(listOf(Segment(targetSpeedMps, SegmentLimit.Duration(durationSeconds)))),
            GaitResolver(limits, policy),
            SpeedPlanner(DynamicsLimits(2.0, 8.0)),
            STEP_SECONDS,
        )
    }

    fun snapshot(): SimulationSnapshot = SimulationSnapshot(
        status, mode, targetSpeedMps, durationSeconds, elapsedSeconds,
        distanceMeters, speedMps, cadenceSpm, steps,
    )

    fun advanceBy(seconds: Double): SimulationSnapshot {
        require(seconds.isFinite() && seconds >= 0.0) { "Elapsed time must be finite and non-negative" }
        if (status != SimulationStatus.RUNNING) return snapshot()
        pendingSeconds += seconds
        while (pendingSeconds + 1e-9 >= STEP_SECONDS && !engine.isFinished()) {
            val frame = engine.advance()
            pendingSeconds -= STEP_SECONDS
            elapsedSeconds = frame.truth.timeSeconds
            distanceMeters = frame.truth.distanceMeters
            speedMps = frame.truth.speedMps
            cadenceSpm = frame.truth.cadenceSpm
            steps += frame.steps.size
        }
        if (engine.isFinished()) {
            status = SimulationStatus.COMPLETED
            pendingSeconds = 0.0
        }
        return snapshot()
    }

    fun pause(): SimulationSnapshot {
        if (status == SimulationStatus.RUNNING) status = SimulationStatus.PAUSED
        return snapshot()
    }

    fun resume(): SimulationSnapshot {
        if (status == SimulationStatus.PAUSED) status = SimulationStatus.RUNNING
        return snapshot()
    }

    fun stop(): SimulationSnapshot {
        if (status == SimulationStatus.RUNNING || status == SimulationStatus.PAUSED) {
            status = SimulationStatus.STOPPED
        }
        return snapshot()
    }

    companion object {
        const val MIN_SPEED_MPS = 0.8
        const val MAX_SPEED_MPS = 5.0
        const val MODE_BOUNDARY_MPS = 2.2
        const val MIN_DURATION_SECONDS = 10.0
        const val MAX_DURATION_SECONDS = 3_600.0
        private const val STEP_SECONDS = 0.1
    }
}

enum class SimulationStatus { RUNNING, PAUSED, STOPPED, COMPLETED }

data class SimulationSnapshot(
    val status: SimulationStatus,
    val mode: GaitMode,
    val targetSpeedMps: Double,
    val durationSeconds: Double,
    val elapsedSeconds: Double,
    val distanceMeters: Double,
    val speedMps: Double,
    val cadenceSpm: Double,
    val steps: Long,
)
