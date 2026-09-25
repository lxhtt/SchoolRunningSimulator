package dev.ratemock.core.truth

import java.util.ArrayDeque

/** Estimates cadence from timestamped step-detector events over a bounded window. */
class CadenceEstimator(
    private val windowSeconds: Double = 10.0,
    private val minimumSteps: Int = 3,
) {
    private val timestamps = ArrayDeque<Double>()

    init {
        require(windowSeconds.isFinite() && windowSeconds > 0.0)
        require(minimumSteps >= 2)
    }

    fun addStep(timestampSeconds: Double): Double? {
        require(timestampSeconds.isFinite() && timestampSeconds >= 0.0)
        if (timestamps.isNotEmpty() && timestampSeconds < timestamps.peekLast()) return null
        timestamps.addLast(timestampSeconds)
        while (timestamps.isNotEmpty() && timestampSeconds - timestamps.peekFirst() > windowSeconds) {
            timestamps.removeFirst()
        }
        if (timestamps.size < minimumSteps) return null
        val duration = timestampSeconds - timestamps.peekFirst()
        if (duration <= 0.0) return null
        return (timestamps.size - 1) * 60.0 / duration
    }

    fun reset() = timestamps.clear()
}
