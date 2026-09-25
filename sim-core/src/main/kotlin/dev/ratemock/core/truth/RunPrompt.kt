package dev.ratemock.core.truth

/** High-level coaching intent for a real observation session. */
enum class RunPrompt {
    WAITING_FOR_CADENCE,
    GPS_STALE,
    INCREASE_CADENCE,
    DECREASE_CADENCE,
    HOLD_CADENCE,
}

data class PromptInput(
    val nowSeconds: Double,
    val targetCadenceSpm: Double,
    val measuredCadenceSpm: Double?,
    val cadenceFresh: Boolean,
    val gpsFresh: Boolean,
) {
    init {
        require(nowSeconds.isFinite() && nowSeconds >= 0.0)
        require(targetCadenceSpm.isFinite() && targetCadenceSpm > 0.0)
        require(measuredCadenceSpm == null || measuredCadenceSpm.isFinite() && measuredCadenceSpm > 0.0)
    }
}

/** De-bounced, deterministic prompt policy. It never consumes cumulative step counts. */
class RunPromptDecider(
    private val toleranceSpm: Double = 5.0,
    private val minimumPromptIntervalSeconds: Double = 15.0,
) {
    private var lastPrompt: RunPrompt? = null
    private var lastPromptAt = Double.NEGATIVE_INFINITY

    init {
        require(toleranceSpm.isFinite() && toleranceSpm >= 0.0)
        require(minimumPromptIntervalSeconds.isFinite() && minimumPromptIntervalSeconds >= 0.0)
    }

    fun decide(input: PromptInput): RunPrompt? {
        val candidate = when {
            !input.cadenceFresh || input.measuredCadenceSpm == null -> RunPrompt.WAITING_FOR_CADENCE
            !input.gpsFresh -> RunPrompt.GPS_STALE
            input.measuredCadenceSpm < input.targetCadenceSpm - toleranceSpm -> RunPrompt.INCREASE_CADENCE
            input.measuredCadenceSpm > input.targetCadenceSpm + toleranceSpm -> RunPrompt.DECREASE_CADENCE
            else -> RunPrompt.HOLD_CADENCE
        }
        if (candidate == lastPrompt && input.nowSeconds - lastPromptAt < minimumPromptIntervalSeconds) return null
        if (lastPrompt != candidate && lastPrompt != null && input.nowSeconds - lastPromptAt < minimumPromptIntervalSeconds) return null
        lastPrompt = candidate
        lastPromptAt = input.nowSeconds
        return candidate
    }

    fun reset() {
        lastPrompt = null
        lastPromptAt = Double.NEGATIVE_INFINITY
    }
}
