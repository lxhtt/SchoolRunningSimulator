package dev.ratemock.core.feasibility

/**
 * Shared fixture for the S1 tests.
 *
 * Every number here is an uncalibrated placeholder used to exercise the
 * arithmetic, not a claim about any population or individual. The reachable
 * speed endpoints are `sMin * cMin / 60` and `sMax * cMax / 60`.
 */
internal val EXAMPLE_LIMITS = GaitLimits(
    cadenceSpm = 160.0..190.0,
    stepLengthMeters = 0.70..1.30,
)

internal val EXAMPLE_MIN_SPEED_MPS = 0.70 * 160.0 / 60.0

internal val EXAMPLE_MAX_SPEED_MPS = 1.30 * 190.0 / 60.0

/** Anchored at about 175 spm for 2.5 m/s; the anchor is a placeholder. */
internal val EXAMPLE_POLICY = PowerLawCadence(coefficient = 127.0, exponent = 0.35)
