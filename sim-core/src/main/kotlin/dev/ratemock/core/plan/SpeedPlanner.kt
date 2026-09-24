package dev.ratemock.core.plan

/** One causal planner output at the end of a simulation step. */
data class SpeedSample(
    val targetSpeedMps: Double,
    val speedMps: Double,
    val accelerationMps2: Double,
    val jerkMps3: Double,
    /** True when the discrete planner reached rest and reset its dynamics. */
    val terminalReset: Boolean,
)

/**
 * Causal speed planner with acceleration and jerk limits.
 *
 * Each call advances one fixed simulation interval. A terminal reset at rest
 * is explicit: the planner cannot represent the instantaneous boundary reset as
 * a physical jerk, so that transition is marked rather than reported as a
 * misleading finite jerk sample.
 */
class SpeedPlanner(private val limits: DynamicsLimits) {
    private var speedMps = 0.0
    private var accelerationMps2 = 0.0

    fun reset() {
        speedMps = 0.0
        accelerationMps2 = 0.0
    }

    fun advance(targetSpeedMps: Double, deltaSeconds: Double): SpeedSample {
        require(targetSpeedMps.isFinite() && targetSpeedMps >= 0.0) {
            "Target speed must be finite and non-negative, got $targetSpeedMps"
        }
        require(deltaSeconds.isFinite() && deltaSeconds > 0.0) {
            "Simulation step must be finite and strictly positive, got $deltaSeconds"
        }

        val desiredAcceleration = ((targetSpeedMps - speedMps) / deltaSeconds)
            .coerceIn(-limits.maxAccelMps2, limits.maxAccelMps2)
        val maxAccelerationChange = limits.maxJerkMps3 * deltaSeconds
        val nextAcceleration = (
            accelerationMps2 +
                (desiredAcceleration - accelerationMps2)
                    .coerceIn(-maxAccelerationChange, maxAccelerationChange)
            ).coerceIn(-limits.maxAccelMps2, limits.maxAccelMps2)
        val unconstrainedSpeed = speedMps + nextAcceleration * deltaSeconds
        val terminalReset = unconstrainedSpeed <= 0.0 && targetSpeedMps == 0.0 && speedMps > 0.0
        val resolvedSpeed = if (terminalReset) 0.0 else maxOf(0.0, unconstrainedSpeed)
        val resolvedAcceleration = if (terminalReset) 0.0 else nextAcceleration
        val resolvedJerk = if (terminalReset) {
            0.0
        } else {
            (resolvedAcceleration - accelerationMps2) / deltaSeconds
        }

        speedMps = resolvedSpeed
        accelerationMps2 = resolvedAcceleration
        return SpeedSample(
            targetSpeedMps = targetSpeedMps,
            speedMps = resolvedSpeed,
            accelerationMps2 = resolvedAcceleration,
            jerkMps3 = resolvedJerk,
            terminalReset = terminalReset,
        )
    }
}
