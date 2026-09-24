package dev.ratemock.core.truth

import dev.ratemock.core.calibration.GaitMode
import dev.ratemock.core.plan.DynamicsLimits
import dev.ratemock.core.plan.RunPlan
import dev.ratemock.core.plan.Segment
import dev.ratemock.core.plan.SegmentLimit
import dev.ratemock.core.plan.SpeedPlanner

/** User-controlled, fixed-step simulation. All gait parameters here are uncalibrated defaults. */
class InteractiveSimulation(
    initialTargetSpeedMps: Double,
    val durationSeconds: Double,
    val manualCadenceSpm: Double? = null,
    val fatigueReduction: Double = 0.0,
) {
    var targetSpeedMps: Double = initialTargetSpeedMps
        private set
    var mode: GaitMode = SimulatorGait.modeFor(targetSpeedMps)
        private set
    private val engine: GaitEngine
    private var effectiveTargetSpeedMps = initialTargetSpeedMps
    private var pendingSeconds = 0.0
    private var elapsedSeconds = 0.0
    private var distanceMeters = 0.0
    private var speedMps = 0.0
    private var cadenceSpm = 0.0
    private var steps = 0L
    private var status = SimulationStatus.RUNNING

    init {
        require(initialTargetSpeedMps.isFinite() && initialTargetSpeedMps in MIN_SPEED_MPS..MAX_SPEED_MPS) {
            "Target speed must be within $MIN_SPEED_MPS..$MAX_SPEED_MPS m/s"
        }
        require(durationSeconds.isFinite() && durationSeconds in MIN_DURATION_SECONDS..MAX_DURATION_SECONDS) {
            "Duration must be within $MIN_DURATION_SECONDS..$MAX_DURATION_SECONDS seconds"
        }
        SimulatorGait.validateProfile(initialTargetSpeedMps, manualCadenceSpm, fatigueReduction)
        engine = GaitEngine(
            RunPlan(listOf(Segment(initialTargetSpeedMps, SegmentLimit.Duration(durationSeconds)))),
            SimulatorGait.resolver(mode, manualCadenceSpm),
            SpeedPlanner(DynamicsLimits(2.0, 8.0)),
            STEP_SECONDS,
        )
    }

    fun snapshot(): SimulationSnapshot = SimulationSnapshot(
        status, mode, targetSpeedMps, durationSeconds, elapsedSeconds,
        distanceMeters, speedMps, cadenceSpm, steps, effectiveTargetSpeedMps,
        manualCadenceSpm, fatigueReduction,
    )

    fun advanceBy(seconds: Double): SimulationSnapshot {
        require(seconds.isFinite() && seconds >= 0.0) { "Elapsed time must be finite and non-negative" }
        if (status != SimulationStatus.RUNNING) return snapshot()
        pendingSeconds += seconds
        while (pendingSeconds + 1e-9 >= STEP_SECONDS && !engine.isFinished()) {
            val progress = (elapsedSeconds / durationSeconds - 0.7).coerceIn(0.0, 0.3) / 0.3
            effectiveTargetSpeedMps = (targetSpeedMps * (1.0 - fatigueReduction * progress))
                .coerceAtLeast(MIN_SPEED_MPS)
            mode = SimulatorGait.modeFor(effectiveTargetSpeedMps, mode)
            engine.setTargetSpeed(effectiveTargetSpeedMps, SimulatorGait.resolver(mode, manualCadenceSpm))
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

    /** Rejects infeasible speed changes without altering an active session. */
    fun setTargetSpeed(speedMps: Double): SimulationSnapshot {
        check(status == SimulationStatus.RUNNING || status == SimulationStatus.PAUSED)
        SimulatorGait.validateProfile(speedMps, manualCadenceSpm, fatigueReduction)
        targetSpeedMps = speedMps
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
        const val MIN_SPEED_MPS = SimulatorGait.MIN_SPEED_MPS
        const val MAX_SPEED_MPS = SimulatorGait.MAX_SPEED_MPS
        const val MODE_BOUNDARY_MPS = SimulatorGait.MODE_BOUNDARY_MPS
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
    val effectiveTargetSpeedMps: Double,
    val manualCadenceSpm: Double?,
    val fatigueReduction: Double,
)
