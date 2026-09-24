package dev.ratemock.core.feasibility

import kotlin.math.pow

/**
 * Maps a forward speed to a cadence.
 *
 * Implementations return the raw model cadence, before any feasibility
 * clamping. [GaitResolver] applies the clamp and records when it happened, so a
 * policy can stay a plain, testable function of speed.
 */
sealed interface CadencePolicy {
    fun modelCadenceSpm(speedMps: Double): Double
}

/**
 * `c = coefficient * v^exponent`.
 *
 * Project defaults are uncalibrated placeholders. Parameters fitted from real
 * runs are only supported inside the speed range that was actually fitted; see
 * the calibration notes in `docs/DESIGN-p1-sim-core.md`.
 */
class PowerLawCadence(
    val coefficient: Double,
    val exponent: Double,
) : CadencePolicy {
    init {
        require(coefficient.isFinite() && coefficient > 0.0) {
            "Power-law coefficient must be finite and strictly positive, got $coefficient"
        }
        require(exponent.isFinite() && exponent > 0.0 && exponent <= 1.0) {
            "Power-law exponent must be finite and within (0, 1], got $exponent"
        }
    }

    override fun modelCadenceSpm(speedMps: Double): Double {
        FeasibilitySolver.requireNonNegativeSpeed(speedMps)
        return coefficient * speedMps.pow(exponent)
    }
}

/**
 * `c = interceptSpm + slopeSpmPerMps * v`.
 *
 * Both parameters only need to be finite: a model that leaves the configured
 * cadence range is clamped and recorded rather than rejected, because the
 * clamp keeps the step identity exact. A model that becomes non-positive at
 * low speed is treated the same way and is visible through the recorded model
 * value on [GaitSample].
 */
class LinearCadence(
    val interceptSpm: Double,
    val slopeSpmPerMps: Double,
) : CadencePolicy {
    init {
        require(interceptSpm.isFinite()) {
            "Linear intercept must be finite, got $interceptSpm"
        }
        require(slopeSpmPerMps.isFinite()) {
            "Linear slope must be finite, got $slopeSpmPerMps"
        }
    }

    override fun modelCadenceSpm(speedMps: Double): Double {
        FeasibilitySolver.requireNonNegativeSpeed(speedMps)
        return interceptSpm + slopeSpmPerMps * speedMps
    }
}

/** Constant cadence regardless of speed; useful as a baseline and for tests. */
class FixedCadence(val cadenceSpm: Double) : CadencePolicy {
    init {
        require(cadenceSpm.isFinite() && cadenceSpm > 0.0) {
            "Fixed cadence must be finite and strictly positive, got $cadenceSpm"
        }
    }

    override fun modelCadenceSpm(speedMps: Double): Double {
        FeasibilitySolver.requireNonNegativeSpeed(speedMps)
        return cadenceSpm
    }
}
