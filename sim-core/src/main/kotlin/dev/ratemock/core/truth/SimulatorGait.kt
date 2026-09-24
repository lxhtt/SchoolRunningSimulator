package dev.ratemock.core.truth

import dev.ratemock.core.calibration.GaitMode
import dev.ratemock.core.feasibility.FixedCadence
import dev.ratemock.core.feasibility.GaitLimits
import dev.ratemock.core.feasibility.GaitResolver
import dev.ratemock.core.feasibility.GaitSample
import java.util.Locale

/** Explicit engineering defaults; no personal calibration is bundled. */
object SimulatorGait {
    const val MODE_BOUNDARY_MPS = 2.2
    const val MIN_SPEED_MPS = 0.8
    const val MAX_SPEED_MPS = 5.0
    const val MIN_CADENCE_SPM = 90.0
    const val MAX_CADENCE_SPM = 210.0
    const val MAX_FATIGUE_REDUCTION = 0.30

    private val walking = GaitLimits(90.0..170.0, 0.30..1.20)
    private val running = GaitLimits(130.0..210.0, 0.55..1.60)
    private val manual = GaitLimits(MIN_CADENCE_SPM..MAX_CADENCE_SPM, 0.30..1.60)

    fun modeFor(speedMps: Double, previous: GaitMode? = null): GaitMode = when (previous) {
        GaitMode.WALKING -> if (speedMps > MODE_BOUNDARY_MPS + 0.1) GaitMode.RUNNING else previous
        GaitMode.RUNNING -> if (speedMps < MODE_BOUNDARY_MPS - 0.1) GaitMode.WALKING else previous
        null -> if (speedMps < MODE_BOUNDARY_MPS) GaitMode.WALKING else GaitMode.RUNNING
    }

    fun resolver(mode: GaitMode, manualCadenceSpm: Double?): GaitResolver = GaitResolver(
        if (manualCadenceSpm == null) {
            if (mode == GaitMode.WALKING) walking else running
        } else manual,
        FixedCadence(manualCadenceSpm ?: if (mode == GaitMode.WALKING) 120.0 else 170.0),
    )

    /** Returns the steady gait or explains why manual cadence cannot hold it. */
    fun preview(speedMps: Double, manualCadenceSpm: Double?): GaitSample {
        require(speedMps.isFinite() && speedMps in MIN_SPEED_MPS..MAX_SPEED_MPS)
        if (manualCadenceSpm != null) {
            require(manualCadenceSpm.isFinite() && manualCadenceSpm in MIN_CADENCE_SPM..MAX_CADENCE_SPM)
            val stepLength = speedMps * 60.0 / manualCadenceSpm
            require(stepLength in 0.30..1.60) {
                "Manual cadence requires step length ${"%.2f".format(Locale.ROOT, stepLength)} m, outside 0.30..1.60 m"
            }
        }
        return resolver(modeFor(speedMps), manualCadenceSpm).resolve(speedMps)
    }

    fun validateProfile(speedMps: Double, manualCadenceSpm: Double?, fatigueReduction: Double) {
        require(fatigueReduction.isFinite() && fatigueReduction in 0.0..MAX_FATIGUE_REDUCTION)
        preview(speedMps, manualCadenceSpm)
        preview((speedMps * (1.0 - fatigueReduction)).coerceAtLeast(MIN_SPEED_MPS), manualCadenceSpm)
    }
}
