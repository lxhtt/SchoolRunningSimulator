package dev.ratemock.core.feasibility

import dev.ratemock.core.GaitKinematics

/**
 * One resolved instant of steady locomotion. The step identity
 * `speedMps = cadenceSpm * stepLengthMeters / 60` holds exactly.
 *
 * [modelCadenceSpm] is what the policy asked for before clamping, so a consumer
 * can always tell how far the resolved value deviated from the model.
 */
data class GaitSample(
    val speedMps: Double,
    val modelCadenceSpm: Double,
    val cadenceSpm: Double,
    val stepLengthMeters: Double,
    val clamped: Boolean,
) {
    /** Relative deviation of the resolved cadence from the model, in [0, 1]. */
    val clampDeviationFraction: Double
        get() = if (modelCadenceSpm <= 0.0) 0.0 else (cadenceSpm - modelCadenceSpm) / modelCadenceSpm
}

/**
 * Resolves a speed into a consistent `(cadence, step length)` pair.
 *
 * The policy supplies a model cadence, which is clamped into the interval that
 * satisfies both configured ranges at that speed. Clamping is a recorded policy
 * deviation, not a feasibility failure: the identity still holds exactly and the
 * step length still lies inside its range. Speeds that admit no steady gait at
 * all are rejected instead of being silently clamped to something unreachable.
 */
class GaitResolver(
    private val limits: GaitLimits,
    private val policy: CadencePolicy,
) {
    private val reachable = FeasibilitySolver.reachableSpeedRange(limits)

    /**
     * Resolves a non-negative speed during an acceleration/deceleration
     * transient. Stable speeds retain [resolve]'s strict reachable-range
     * contract; transient speeds use a boundary cadence and derive the step
     * length so the kinematic identity remains exact.
     */
    fun resolveTransient(speedMps: Double): GaitSample {
        FeasibilitySolver.requireNonNegativeSpeed(speedMps)
        if (speedMps == 0.0) return resolve(0.0)
        val reachableSpeed = speedMps.coerceIn(reachable.start, reachable.endInclusive)
        val boundary = resolve(reachableSpeed)
        val stepLengthMeters = GaitKinematics.stepLengthMeters(speedMps, boundary.cadenceSpm)
        return GaitSample(
            speedMps = speedMps,
            modelCadenceSpm = boundary.modelCadenceSpm,
            cadenceSpm = boundary.cadenceSpm,
            stepLengthMeters = stepLengthMeters,
            clamped = true,
        )
    }

    /**
     * Resolves [speedMps]. Exactly zero returns the stationary convention
     * `(0, 0, 0)`, which is outside the steady-locomotion ranges by design.
     */
    fun resolve(speedMps: Double): GaitSample {
        FeasibilitySolver.requireNonNegativeSpeed(speedMps)
        if (speedMps == 0.0) {
            return GaitSample(
                speedMps = 0.0,
                modelCadenceSpm = 0.0,
                cadenceSpm = 0.0,
                stepLengthMeters = 0.0,
                clamped = false,
            )
        }
        val feasibleCadence = FeasibilitySolver.feasibleCadenceRange(speedMps, limits)
            ?: throw IllegalArgumentException(
                "speed ${formatSpeed(speedMps)} admits no steady gait with reachable range $reachable",
            )
        val modelCadenceSpm = policy.modelCadenceSpm(speedMps)
        require(modelCadenceSpm.isFinite()) {
            "Cadence model returned $modelCadenceSpm at ${formatSpeed(speedMps)}; " +
                "check the policy parameters for this speed"
        }
        val cadenceSpm = modelCadenceSpm.coerceIn(feasibleCadence)
        val stepLengthMeters = GaitKinematics.stepLengthMeters(speedMps, cadenceSpm)

        val tolerance = STEP_LENGTH_TOLERANCE * limits.stepLengthMeters.endInclusive
        check(
            stepLengthMeters >= limits.stepLengthMeters.start - tolerance &&
                stepLengthMeters <= limits.stepLengthMeters.endInclusive + tolerance,
        ) {
            "internal invariant violated: resolved step length $stepLengthMeters m is outside " +
                "${limits.stepLengthMeters} at ${formatSpeed(speedMps)}"
        }
        return GaitSample(
            speedMps = speedMps,
            modelCadenceSpm = modelCadenceSpm,
            cadenceSpm = cadenceSpm,
            stepLengthMeters = stepLengthMeters,
            clamped = cadenceSpm != modelCadenceSpm,
        )
    }

    private companion object {
        /** Guards only against floating-point drift, not against model error. */
        const val STEP_LENGTH_TOLERANCE = 1e-9
    }
}
