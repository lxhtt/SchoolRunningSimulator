package dev.ratemock.core.feasibility

/**
 * Caller-supplied bounds for stable forward locomotion.
 *
 * These are explicit model parameters, not physiological constants: nothing
 * here claims a universal minimum or maximum for any population. They describe
 * steady running only. Rest is expressed by the zero-speed convention in
 * [GaitResolver], so both bounds must be strictly positive.
 */
class GaitLimits(
    val cadenceSpm: ClosedFloatingPointRange<Double>,
    val stepLengthMeters: ClosedFloatingPointRange<Double>,
) {
    init {
        requireStrictlyPositiveRange("cadenceSpm", cadenceSpm)
        requireStrictlyPositiveRange("stepLengthMeters", stepLengthMeters)
    }

    override fun toString(): String =
        "GaitLimits(cadenceSpm=[${cadenceSpm.start}, ${cadenceSpm.endInclusive}], " +
            "stepLengthMeters=[${stepLengthMeters.start}, ${stepLengthMeters.endInclusive}])"

    internal companion object {
        fun requireStrictlyPositiveRange(
            name: String,
            range: ClosedFloatingPointRange<Double>,
        ) {
            require(range.start.isFinite() && range.start > 0.0) {
                "$name lower bound must be finite and strictly positive"
            }
            require(range.endInclusive.isFinite() && range.endInclusive > 0.0) {
                "$name upper bound must be finite and strictly positive"
            }
            require(range.start <= range.endInclusive) {
                "$name lower bound ${range.start} must not exceed its upper bound ${range.endInclusive}"
            }
        }
    }
}
