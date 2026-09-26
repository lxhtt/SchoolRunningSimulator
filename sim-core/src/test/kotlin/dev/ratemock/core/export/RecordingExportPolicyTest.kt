package dev.ratemock.core.export

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecordingExportPolicyTest {
    private fun candidate(
        state: RecordingState = RecordingState.STOPPED,
        closedFileName: String? = "recording-123.csv",
        requestedFileName: String = "recording-123.csv",
        inside: Boolean = true,
        unchanged: Boolean = true,
        parsed: Boolean = true,
    ) = RecordingExportCandidate(
        state = state,
        closedFileName = closedFileName,
        requestedFileName = requestedFileName,
        fileNameMatches = requestedFileName == closedFileName,
        fileIsInsideRecordings = inside,
        fileSnapshotUnchanged = unchanged,
        parsedSuccessfully = parsed,
    )

    @Test fun onlyStoppedValidUnchangedPrivateFileIsAllowed() {
        val decision = RecordingExportPolicy.decide(candidate())
        assertTrue(decision.allowed)
        assertEquals(RecordingExportDecision.Reason.ALLOWED, decision.reason)
    }

    @Test fun activeStoppingAndErrorAreNeverExportable() {
        RecordingState.entries.filter { it != RecordingState.STOPPED }.forEach { state ->
            val decision = RecordingExportPolicy.decide(candidate(state = state))
            assertFalse(decision.allowed)
            assertEquals(RecordingExportDecision.Reason.NOT_STOPPED, decision.reason)
        }
    }

    @Test fun rejectsPathNameSnapshotAndParseFailures() {
        assertEquals(RecordingExportDecision.Reason.FILE_OUTSIDE_RECORDINGS,
            RecordingExportPolicy.decide(candidate(inside = false)).reason)
        assertEquals(RecordingExportDecision.Reason.FILE_NAME_MISMATCH,
            RecordingExportPolicy.decide(candidate(requestedFileName = "../recording-123.csv")).reason)
        assertEquals(RecordingExportDecision.Reason.FILE_CHANGED,
            RecordingExportPolicy.decide(candidate(unchanged = false)).reason)
        assertEquals(RecordingExportDecision.Reason.INVALID_RECORDING,
            RecordingExportPolicy.decide(candidate(parsed = false)).reason)
    }
}
