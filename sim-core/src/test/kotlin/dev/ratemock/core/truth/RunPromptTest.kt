package dev.ratemock.core.truth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RunPromptTest {
    private fun input(now: Double, cadence: Double? = 170.0, cadenceFresh: Boolean = true, gpsFresh: Boolean = true) =
        PromptInput(now, 170.0, cadence, cadenceFresh, gpsFresh)

    @Test fun waitsForCadenceBeforeCoaching() {
        val decider = RunPromptDecider()
        assertEquals(RunPrompt.WAITING_FOR_CADENCE, decider.decide(input(0.0, null, false)))
        assertNull(decider.decide(input(1.0, 150.0)))
        assertEquals(RunPrompt.INCREASE_CADENCE, decider.decide(input(16.0, 150.0)))
    }

    @Test fun prioritizesGpsStalenessAndDebounces() {
        val decider = RunPromptDecider()
        assertEquals(RunPrompt.GPS_STALE, decider.decide(input(0.0, gpsFresh = false)))
        assertNull(decider.decide(input(5.0, gpsFresh = false)))
        assertEquals(RunPrompt.HOLD_CADENCE, decider.decide(input(16.0)))
    }

    @Test fun reportsCadenceDirectionOnlyOutsideTolerance() {
        val decider = RunPromptDecider(minimumPromptIntervalSeconds = 0.0)
        assertEquals(RunPrompt.INCREASE_CADENCE, decider.decide(input(0.0, 160.0)))
        assertEquals(RunPrompt.HOLD_CADENCE, decider.decide(input(1.0, 174.0)))
        assertEquals(RunPrompt.DECREASE_CADENCE, decider.decide(input(2.0, 182.0)))
    }
}
