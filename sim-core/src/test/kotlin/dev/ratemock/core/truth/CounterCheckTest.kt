package dev.ratemock.core.truth

import org.junit.Assert.assertEquals
import org.junit.Test

class CounterCheckTest {
    @Test fun comparesAfterEnoughStepsAndFlagsDisagreement() {
        val check = StepCounterCheck()
        assertEquals(CounterQuality.BASELINE, check.observe(1.0, 100.0, 2))
        assertEquals(CounterQuality.BASELINE, check.observe(2.0, 110.0, 12))
        assertEquals(CounterQuality.CONSISTENT, check.observe(3.0, 120.0, 22))
        assertEquals(CounterQuality.DIFFERENT, check.observe(4.0, 122.0, 40))
        assertEquals(CounterQuality.STALE, check.qualityAt(10.0))
    }

    @Test fun ignoresDelayedCounterEventsAndLatchesReset() {
        val check = StepCounterCheck()
        check.observe(2.0, 500.0, 0)
        assertEquals(CounterQuality.BASELINE, check.observe(1.0, 0.0, 0))
        assertEquals(CounterQuality.RESET, check.observe(3.0, 2.0, 1))
        assertEquals(CounterQuality.RESET, check.observe(4.0, 4.0, 2))
        assertEquals(CounterQuality.RESET, check.qualityAt(15.0))
    }

    @Test fun waitingAndStaleAreExplicit() {
        val check = StepCounterCheck()
        assertEquals(CounterQuality.WAITING, check.qualityAt(30.0))
        check.observe(1.0, 1.0, 0)
        assertEquals(CounterQuality.STALE, check.qualityAt(6.1))
    }
}
