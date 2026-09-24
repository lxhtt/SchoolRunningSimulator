package dev.ratemock.core.plan

/** A speed target that lasts for a duration or until a distance is covered. */
data class Segment(
    val targetSpeedMps: Double,
    val limit: SegmentLimit,
) {
    init {
        require(targetSpeedMps.isFinite() && targetSpeedMps > 0.0) {
            "Segment target speed must be finite and strictly positive, got $targetSpeedMps"
        }
    }
}

sealed interface SegmentLimit {
    data class Duration(val seconds: Double) : SegmentLimit {
        init {
            require(seconds.isFinite() && seconds > 0.0) {
                "Segment duration must be finite and strictly positive, got $seconds"
            }
        }
    }

    data class Distance(val meters: Double) : SegmentLimit {
        init {
            require(meters.isFinite() && meters > 0.0) {
                "Segment distance must be finite and strictly positive, got $meters"
            }
        }
    }
}

data class PauseWindow(
    val startAfterSeconds: Double,
    val durationSeconds: Double,
) {
    val endAfterSeconds: Double get() = startAfterSeconds + durationSeconds

    init {
        require(startAfterSeconds.isFinite() && startAfterSeconds >= 0.0) {
            "Pause start must be finite and non-negative, got $startAfterSeconds"
        }
        require(durationSeconds.isFinite() && durationSeconds > 0.0) {
            "Pause duration must be finite and strictly positive, got $durationSeconds"
        }
    }
}

/** Limits for the causal speed planner. Values are explicit model parameters. */
data class DynamicsLimits(
    val maxAccelMps2: Double,
    val maxJerkMps3: Double,
) {
    init {
        require(maxAccelMps2.isFinite() && maxAccelMps2 > 0.0) {
            "Maximum acceleration must be finite and strictly positive, got $maxAccelMps2"
        }
        require(maxJerkMps3.isFinite() && maxJerkMps3 > 0.0) {
            "Maximum jerk must be finite and strictly positive, got $maxJerkMps3"
        }
    }
}

/** A validated sequence of positive-speed segments and non-overlapping pauses. */
class RunPlan(
    val segments: List<Segment>,
    val pauses: List<PauseWindow> = emptyList(),
) {
    init {
        require(segments.isNotEmpty()) { "Run plan must contain at least one segment" }
        require(pauses.zipWithNext().all { (left, right) -> left.endAfterSeconds <= right.startAfterSeconds }) {
            "Pause windows must be ordered and non-overlapping"
        }
    }

    /** Returns the pause containing [elapsedSeconds], if any. */
    fun pauseAt(elapsedSeconds: Double): PauseWindow? {
        require(elapsedSeconds.isFinite() && elapsedSeconds >= 0.0) {
            "Elapsed time must be finite and non-negative, got $elapsedSeconds"
        }
        return pauses.firstOrNull {
            elapsedSeconds >= it.startAfterSeconds && elapsedSeconds < it.endAfterSeconds
        }
    }
}
