package dev.ratemock.core.calibration

import dev.ratemock.core.feasibility.CadencePolicy
import dev.ratemock.core.feasibility.LinearCadence
import dev.ratemock.core.feasibility.PowerLawCadence

/** Separate empirical gait models; a walking fit must never absorb running samples. */
enum class GaitMode { WALKING, RUNNING }

data class GaitSelection(
    val mode: GaitMode,
    val cadenceSpm: Double,
    val outsideFittedRange: Boolean,
)

/** Selects an explicitly supplied pair of fits without silently extrapolating their provenance. */
class AutoGaitSelector(
    walking: CalibrationFit,
    running: CalibrationFit,
    val hysteresisMps: Double = 0.1,
) {
    private val walkingPolicy = walking.toPolicy()
    private val runningPolicy = running.toPolicy()
    private val walkingRange = walking.fittedSpeedRangeMps
    private val runningRange = running.fittedSpeedRangeMps

    val switchSpeedMps: Double = (walkingRange.endInclusive + runningRange.start) / 2.0

    init {
        require(hysteresisMps.isFinite() && hysteresisMps >= 0.0)
        require(walkingRange.start < runningRange.start && walkingRange.endInclusive < runningRange.endInclusive) {
            "Walking and running fitted speed ranges must be ordered"
        }
    }

    /** Pass the previous selection for hysteresis; null starts from the speed boundary. */
    fun select(speedMps: Double, previousMode: GaitMode? = null): GaitSelection {
        require(speedMps.isFinite() && speedMps >= 0.0) { "Speed must be finite and non-negative" }
        val mode = when (previousMode) {
            GaitMode.WALKING -> if (speedMps > switchSpeedMps + hysteresisMps) GaitMode.RUNNING else GaitMode.WALKING
            GaitMode.RUNNING -> if (speedMps < switchSpeedMps - hysteresisMps) GaitMode.WALKING else GaitMode.RUNNING
            null -> if (speedMps < switchSpeedMps) GaitMode.WALKING else GaitMode.RUNNING
        }
        val range = if (mode == GaitMode.WALKING) walkingRange else runningRange
        val policy = if (mode == GaitMode.WALKING) walkingPolicy else runningPolicy
        return GaitSelection(mode, policy.modelCadenceSpm(speedMps), speedMps !in range)
    }

    fun policyFor(mode: GaitMode): CadencePolicy = if (mode == GaitMode.WALKING) walkingPolicy else runningPolicy
}

private fun CalibrationFit.toPolicy(): CadencePolicy {
    require(!uncalibrated && sampleCount >= CalibrationDataset.MIN_SAMPLES) { "A fitted model is required" }
    require(fittedSpeedRangeMps.start.isFinite() && fittedSpeedRangeMps.start > 0.0)
    require(fittedSpeedRangeMps.endInclusive.isFinite() && fittedSpeedRangeMps.endInclusive > fittedSpeedRangeMps.start)
    return when (model) {
        CalibrationModel.POWER_LAW -> PowerLawCadence(parameters.getValue("coefficient"), parameters.getValue("exponent"))
        CalibrationModel.LINEAR -> LinearCadence(parameters.getValue("intercept"), parameters.getValue("slope"))
    }
}
