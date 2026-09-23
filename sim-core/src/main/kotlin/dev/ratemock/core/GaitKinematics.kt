package dev.ratemock.core

/**
 * Unit conversions for forward movement, not a physiological prediction model.
 *
 * Cadence counts individual steps per minute (spm). Step length is forward
 * distance per step, not the distance of a complete left/right gait cycle.
 */
object GaitKinematics {
    private const val SECONDS_PER_MINUTE = 60.0

    /** v (m/s) = cadence (steps/min) * step length (m/step) / 60. */
    fun speedMetersPerSecond(cadenceSpm: Double, stepLengthMeters: Double): Double {
        requireFiniteNonNegative("cadenceSpm", cadenceSpm)
        requireFiniteNonNegative("stepLengthMeters", stepLengthMeters)
        val speed = cadenceSpm / SECONDS_PER_MINUTE * stepLengthMeters
        require(speed.isFinite()) { "Speed is outside the supported numeric range" }
        return speed
    }

    /**
     * Infers average forward distance per step from speed and cadence.
     *
     * Returns zero for zero forward speed, including an explicit stationary
     * (zero speed, zero cadence) convention. Positive speed with zero cadence
     * is undefined and rejected instead of returning infinity.
     */
    fun stepLengthMeters(speedMetersPerSecond: Double, cadenceSpm: Double): Double {
        requireFiniteNonNegative("speedMetersPerSecond", speedMetersPerSecond)
        requireFiniteNonNegative("cadenceSpm", cadenceSpm)
        if (speedMetersPerSecond == 0.0) return 0.0
        require(cadenceSpm > 0.0) { "Positive speed requires positive cadence" }
        val stepLength = speedMetersPerSecond / cadenceSpm * SECONDS_PER_MINUTE
        require(stepLength.isFinite()) { "Step length is outside the supported numeric range" }
        return stepLength
    }

    private fun requireFiniteNonNegative(name: String, value: Double) {
        require(value.isFinite() && value >= 0.0) { "$name must be finite and non-negative" }
    }
}
