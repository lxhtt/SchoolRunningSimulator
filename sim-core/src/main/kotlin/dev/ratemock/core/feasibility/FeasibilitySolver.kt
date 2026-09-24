package dev.ratemock.core.feasibility

import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** Reachable forward speeds implied by the configured gait ranges. */
data class ReachableSpeedRange(val minMps: Double, val maxMps: Double) {
    init {
        require(minMps.isFinite() && maxMps.isFinite() && minMps <= maxMps) {
            "Reachable speed range must be finite and ordered, got [$minMps, $maxMps]"
        }
    }

    fun contains(speedMps: Double): Boolean = speedMps in minMps..maxMps

    override fun toString(): String = "[${formatSpeed(minMps)}, ${formatSpeed(maxMps)}]"
}

enum class SpeedViolationKind {
    /** Requested speed is below the slowest steady speed the ranges allow. */
    BELOW_REACHABLE_MINIMUM,

    /** Requested speed is above the fastest steady speed the ranges allow. */
    ABOVE_REACHABLE_MAXIMUM,
}

/** One exact reason why a requested speed cannot be held with the given ranges. */
data class SpeedRangeViolation(
    val kind: SpeedViolationKind,
    val requestedSpeedMps: Double,
    val boundarySpeedMps: Double,
) {
    fun describe(): String = when (kind) {
        SpeedViolationKind.BELOW_REACHABLE_MINIMUM ->
            "requested speed ${formatSpeed(requestedSpeedMps)} is below the reachable minimum " +
                "${formatSpeed(boundarySpeedMps)} implied by the configured cadence and step-length ranges"

        SpeedViolationKind.ABOVE_REACHABLE_MAXIMUM ->
            "requested speed ${formatSpeed(requestedSpeedMps)} is above the reachable maximum " +
                "${formatSpeed(boundarySpeedMps)} implied by the configured cadence and step-length ranges"
    }
}

/** Outcome of checking a requested speed range against [GaitLimits]. */
data class FeasibilityReport(
    val requestedSpeedMps: ClosedFloatingPointRange<Double>,
    val reachableSpeedMps: ReachableSpeedRange,
    val violations: List<SpeedRangeViolation>,
) {
    val isFeasible: Boolean get() = violations.isEmpty()

    fun describe(): String = buildString {
        append("requested speed range [")
        append(formatSpeed(requestedSpeedMps.start))
        append(", ")
        append(formatSpeed(requestedSpeedMps.endInclusive))
        append("]; reachable ")
        append(reachableSpeedMps)
        if (isFeasible) {
            append("; feasible")
        } else {
            violations.forEach { append("; ").append(it.describe()) }
        }
    }
}

/**
 * Speed-range feasibility for the step identity `v = c * s / 60`.
 *
 * The reachable steady speeds are exactly `[vLo, vHi]` with
 *
 * ```text
 * vLo = sMin * cMin / 60
 * vHi = sMax * cMax / 60
 * ```
 *
 * because both feasible bounds grow monotonically with speed. Checking a
 * requested range costs two comparisons instead of a search, and a conflict can
 * be reported with exact numbers.
 *
 * For one speed, the feasible cadence and step-length intervals are non-empty
 * exactly when that speed lies inside `[vLo, vHi]`; the enclosing conditions
 * `cMin <= cMax` and `sMin <= sMax` already hold because [GaitLimits] validates
 * them. Emptiness is therefore decided against the same endpoint products used
 * by [reachableSpeedRange], which keeps a speed exactly on a boundary from
 * failing a division round trip.
 *
 * All quantities describe steady forward locomotion. Zero speed is a separate
 * stationary state, so it has no forward-gait interval and is reported as below
 * the reachable minimum rather than as feasible.
 */
object FeasibilitySolver {
    private const val SECONDS_PER_MINUTE = 60.0

    fun reachableSpeedRange(limits: GaitLimits): ReachableSpeedRange {
        val min = limits.stepLengthMeters.start * limits.cadenceSpm.start / SECONDS_PER_MINUTE
        val max = limits.stepLengthMeters.endInclusive * limits.cadenceSpm.endInclusive / SECONDS_PER_MINUTE
        return ReachableSpeedRange(min, max)
    }

    /**
     * Step lengths that satisfy both ranges at [speedMps], or null when no
     * steady gait exists at that speed (including zero speed).
     */
    fun feasibleStepLengthRange(
        speedMps: Double,
        limits: GaitLimits,
    ): ClosedFloatingPointRange<Double>? {
        requireNonNegativeSpeed(speedMps)
        if (speedMps == 0.0 || !reachableSpeedRange(limits).contains(speedMps)) return null
        val lower = max(
            limits.stepLengthMeters.start,
            SECONDS_PER_MINUTE * speedMps / limits.cadenceSpm.endInclusive,
        )
        val upper = min(
            limits.stepLengthMeters.endInclusive,
            SECONDS_PER_MINUTE * speedMps / limits.cadenceSpm.start,
        )
        return orderedRange(lower, upper)
    }

    /**
     * Cadences that satisfy both ranges at [speedMps], or null when no steady
     * gait exists at that speed (including zero speed).
     */
    fun feasibleCadenceRange(
        speedMps: Double,
        limits: GaitLimits,
    ): ClosedFloatingPointRange<Double>? {
        requireNonNegativeSpeed(speedMps)
        if (speedMps == 0.0 || !reachableSpeedRange(limits).contains(speedMps)) return null
        val lower = max(
            limits.cadenceSpm.start,
            SECONDS_PER_MINUTE * speedMps / limits.stepLengthMeters.endInclusive,
        )
        val upper = min(
            limits.cadenceSpm.endInclusive,
            SECONDS_PER_MINUTE * speedMps / limits.stepLengthMeters.start,
        )
        return orderedRange(lower, upper)
    }

    fun check(
        requestedSpeedMps: ClosedFloatingPointRange<Double>,
        limits: GaitLimits,
    ): FeasibilityReport {
        requireNonNegativeSpeed(requestedSpeedMps.start)
        requireNonNegativeSpeed(requestedSpeedMps.endInclusive)
        require(requestedSpeedMps.start <= requestedSpeedMps.endInclusive) {
            "Requested speed range lower bound ${requestedSpeedMps.start} must not exceed " +
                "its upper bound ${requestedSpeedMps.endInclusive}"
        }
        val reachable = reachableSpeedRange(limits)
        val violations = buildList {
            if (requestedSpeedMps.start < reachable.minMps) {
                add(
                    SpeedRangeViolation(
                        SpeedViolationKind.BELOW_REACHABLE_MINIMUM,
                        requestedSpeedMps.start,
                        reachable.minMps,
                    ),
                )
            }
            if (requestedSpeedMps.endInclusive > reachable.maxMps) {
                add(
                    SpeedRangeViolation(
                        SpeedViolationKind.ABOVE_REACHABLE_MAXIMUM,
                        requestedSpeedMps.endInclusive,
                        reachable.maxMps,
                    ),
                )
            }
        }
        return FeasibilityReport(requestedSpeedMps, reachable, violations)
    }

    /**
     * On a reachable boundary the two interval ends can cross by a few ulps
     * because the interval bound is divided while the boundary was multiplied.
     * Collapsing to a single point keeps the range usable there.
     */
    private fun orderedRange(
        lower: Double,
        upper: Double,
    ): ClosedFloatingPointRange<Double> =
        if (lower <= upper) lower..upper else lower..lower

    internal fun requireNonNegativeSpeed(speedMps: Double) {
        require(speedMps.isFinite() && speedMps >= 0.0) {
            "speedMps must be finite and non-negative, got $speedMps"
        }
    }
}

internal fun formatSpeed(value: Double): String =
    String.format(Locale.ROOT, "%.3f m/s", value)
