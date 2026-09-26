package dev.ratemock.core.truth

import kotlin.test.Test
import kotlin.test.assertEquals

class WaveformReviewTest {
    @Test
    fun emptyHistoryIsReported() {
        assertEquals(WaveformReviewStatus.EMPTY, WaveformReview.review(emptyList()).status)
    }

    @Test
    fun oneSampleIsInsufficient() {
        val result = WaveformReview.review(listOf(WaveformSample(0.0, 2.0, 150.0)))

        assertEquals(WaveformReviewStatus.INSUFFICIENT, result.status)
        assertEquals(1, result.sampleCount)
    }

    @Test
    fun reviewReportsRangesAndJumps() {
        val result = WaveformReview.review(
            listOf(
                WaveformSample(0.0, 1.0, 100.0),
                WaveformSample(1.0, 1.5, 120.0),
                WaveformSample(2.5, 1.2, 110.0),
            ),
        )

        assertEquals(WaveformReviewStatus.READY, result.status)
        assertEquals(2.5, result.durationSeconds)
        assertEquals(1.0, result.minSpeedMps)
        assertEquals(1.5, result.maxSpeedMps)
        assertEquals(0.5, result.maxSpeedStepMps)
        assertEquals(20.0, result.maxCadenceStepSpm)
    }

    @Test
    fun nonIncreasingTimeIsInvalid() {
        val result = WaveformReview.review(
            listOf(WaveformSample(0.0, 1.0, 100.0), WaveformSample(0.0, 1.1, 101.0)),
        )

        assertEquals(WaveformReviewStatus.INVALID, result.status)
    }

    @Test
    fun nonFiniteValueIsInvalid() {
        val result = WaveformReview.review(
            listOf(WaveformSample(0.0, Double.NaN, 100.0), WaveformSample(1.0, 1.1, 101.0)),
        )

        assertEquals(WaveformReviewStatus.INVALID, result.status)
    }
}
