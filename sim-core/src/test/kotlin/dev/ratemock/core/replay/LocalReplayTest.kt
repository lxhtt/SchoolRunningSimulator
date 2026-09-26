package dev.ratemock.core.replay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalReplayTest {
    private fun events() = listOf<LocalReplayEvent>(
        LocalPositionEvent("s", 0, 0.0, 0.0, 0.0),
        LocalStepDetectorEvent("s", 1, 1.0),
        LocalStepCounterEvent("s", 2, 2.0, 1),
    )

    @Test fun validatesAndReplaysInMonotonicOrder() {
        val replay = LocalReplay(events())
        assertEquals(ReplayValidationStatus.VALID, replay.validation.status)
        assertEquals(2, replay.nextUntil(1.5).size)
        assertEquals(1, replay.nextUntil(2.0).size)
        assertEquals(3, replay.summary().eventCount)
    }

    @Test fun reportsCounterResetAndTimeOrder() {
        val invalid = listOf<LocalReplayEvent>(
            LocalStepCounterEvent("s", 0, 2.0, 8),
            LocalStepCounterEvent("s", 1, 1.0, 2),
        )
        val result = LocalReplayValidator.validate(invalid)
        assertEquals(ReplayValidationStatus.INVALID, result.status)
        assertTrue(result.issues.any { it.code == "COUNTER_RESET" })
        assertTrue(result.issues.any { it.code == "TIME_ORDER" })
    }

    @Test fun reportsLargeGapsAndPositionJumpsAsWarnings() {
        val result = LocalReplayValidator.validate(listOf(
            LocalPositionEvent("s", 0, 0.0, 0.0, 0.0),
            LocalPositionEvent("s", 1, 6.0, 101.0, 0.0),
        ))
        assertEquals(ReplayValidationStatus.WARNING, result.status)
        assertEquals(setOf("TIME_GAP", "POSITION_JUMP"), result.issues.map { it.code }.toSet())
    }

    @Test fun warnsForEmptyInput() {
        val result = LocalReplayValidator.validate(emptyList())
        assertEquals(ReplayValidationStatus.WARNING, result.status)
        assertEquals(0, result.eventCount)
        assertEquals("EMPTY", result.issues.single().code)
    }

    @Test fun rejectsDuplicateSequenceAndMixedSessions() {
        val result = LocalReplayValidator.validate(listOf(
            LocalPositionEvent("first", 0, 0.0, 0.0, 0.0),
            LocalStepDetectorEvent("second", 0, 1.0),
        ))
        assertEquals(ReplayValidationStatus.INVALID, result.status)
        assertEquals(setOf("SESSION_MISMATCH", "SEQUENCE_ORDER"), result.issues.map { it.code }.toSet())
    }

    @Test fun rejectsNonFiniteValuesAtConstruction() {
        try {
            LocalPositionEvent("s", 0, Double.NaN, 0.0, 0.0)
            error("Expected rejection")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
