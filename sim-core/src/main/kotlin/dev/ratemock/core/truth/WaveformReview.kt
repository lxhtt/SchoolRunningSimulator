package dev.ratemock.core.truth

import kotlin.math.abs

/** Read-only checks for a synthetic history; this is not a real-world accuracy claim. */
enum class WaveformReviewStatus { EMPTY, INSUFFICIENT, READY, INVALID }

data class WaveformSample(
    val elapsedSeconds: Double,
    val speedMps: Double,
    val cadenceSpm: Double,
)

data class WaveformReviewResult(
    val status: WaveformReviewStatus,
    val sampleCount: Int,
    val durationSeconds: Double,
    val minSpeedMps: Double,
    val maxSpeedMps: Double,
    val minCadenceSpm: Double,
    val maxCadenceSpm: Double,
    val maxSpeedStepMps: Double,
    val maxCadenceStepSpm: Double,
)

object WaveformReview {
    fun review(samples: List<WaveformSample>): WaveformReviewResult {
        if (samples.isEmpty()) return emptyResult(WaveformReviewStatus.EMPTY)
        val invalid = samples.any { sample ->
            !sample.elapsedSeconds.isFinite() || !sample.speedMps.isFinite() || !sample.cadenceSpm.isFinite() ||
                sample.elapsedSeconds < 0.0 || sample.speedMps < 0.0 || sample.cadenceSpm < 0.0
        } || samples.zipWithNext().any { (before, after) -> after.elapsedSeconds <= before.elapsedSeconds }
        if (invalid) return emptyResult(WaveformReviewStatus.INVALID, samples.size)

        val speeds = samples.map { it.speedMps }
        val cadences = samples.map { it.cadenceSpm }
        val maxSpeedStep = samples.zipWithNext().maxOfOrNull { (before, after) -> abs(after.speedMps - before.speedMps) } ?: 0.0
        val maxCadenceStep = samples.zipWithNext().maxOfOrNull { (before, after) -> abs(after.cadenceSpm - before.cadenceSpm) } ?: 0.0
        return WaveformReviewResult(
            status = if (samples.size < 2) WaveformReviewStatus.INSUFFICIENT else WaveformReviewStatus.READY,
            sampleCount = samples.size,
            durationSeconds = samples.last().elapsedSeconds - samples.first().elapsedSeconds,
            minSpeedMps = speeds.minOrNull() ?: 0.0,
            maxSpeedMps = speeds.maxOrNull() ?: 0.0,
            minCadenceSpm = cadences.minOrNull() ?: 0.0,
            maxCadenceSpm = cadences.maxOrNull() ?: 0.0,
            maxSpeedStepMps = maxSpeedStep,
            maxCadenceStepSpm = maxCadenceStep,
        )
    }

    private fun emptyResult(status: WaveformReviewStatus, sampleCount: Int = 0) = WaveformReviewResult(
        status = status,
        sampleCount = sampleCount,
        durationSeconds = 0.0,
        minSpeedMps = 0.0,
        maxSpeedMps = 0.0,
        minCadenceSpm = 0.0,
        maxCadenceSpm = 0.0,
        maxSpeedStepMps = 0.0,
        maxCadenceStepSpm = 0.0,
    )
}
