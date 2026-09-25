package dev.ratemock.core.truth

/** Counter is a cross-check only; cadence always comes from timestamped detector events. */
enum class CounterQuality { WAITING, BASELINE, CONSISTENT, DIFFERENT, RESET, STALE }

class StepCounterCheck {
    private var previousTotal: Double? = null
    private var baselineTotal = 0.0
    private var baselineDetected = 0L
    private var lastTimestampSeconds = Double.NaN
    private var quality = CounterQuality.WAITING

    fun observe(timestampSeconds: Double, total: Double, detectedSteps: Long): CounterQuality {
        require(timestampSeconds.isFinite() && timestampSeconds >= 0.0)
        require(total.isFinite() && total >= 0.0 && detectedSteps >= 0L)
        if (lastTimestampSeconds.isFinite() && timestampSeconds <= lastTimestampSeconds) return quality
        if (previousTotal != null && total < previousTotal!!) quality = CounterQuality.RESET
        else if (previousTotal == null) {
            baselineTotal = total
            baselineDetected = detectedSteps
            quality = CounterQuality.BASELINE
        } else if (quality != CounterQuality.RESET) {
            val detectorDelta = detectedSteps - baselineDetected
            val counterDelta = total - baselineTotal
            quality = if (detectorDelta < MIN_COMPARISON_STEPS) CounterQuality.BASELINE
                else if (kotlin.math.abs(counterDelta - detectorDelta) > MAX_DIFFERENCE) CounterQuality.DIFFERENT
                else CounterQuality.CONSISTENT
        }
        previousTotal = total
        lastTimestampSeconds = timestampSeconds
        return quality
    }

    fun qualityAt(nowSeconds: Double): CounterQuality {
        require(nowSeconds.isFinite() && nowSeconds >= 0.0)
        if (quality == CounterQuality.RESET) return quality
        return if (lastTimestampSeconds.isFinite() && nowSeconds - lastTimestampSeconds > STALE_SECONDS)
            CounterQuality.STALE else quality
    }

    companion object {
        private const val MIN_COMPARISON_STEPS = 15
        private const val MAX_DIFFERENCE = 6.0
        private const val STALE_SECONDS = 5.0
    }
}
