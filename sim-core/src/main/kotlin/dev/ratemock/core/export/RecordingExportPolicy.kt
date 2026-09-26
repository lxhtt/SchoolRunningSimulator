package dev.ratemock.core.export

/** Persistent recording state used to decide whether a real observation file is exportable. */
enum class RecordingState {
    IDLE,
    ACTIVE,
    STOPPING,
    STOPPED,
    ERROR,
}

data class RecordingExportCandidate(
    val state: RecordingState,
    val closedFileName: String?,
    val requestedFileName: String,
    val fileNameMatches: Boolean,
    val fileIsInsideRecordings: Boolean,
    val fileSnapshotUnchanged: Boolean,
    val parsedSuccessfully: Boolean,
)

data class RecordingExportDecision(
    val allowed: Boolean,
    val reason: Reason,
) {
    enum class Reason {
        ALLOWED,
        NOT_STOPPED,
        FILE_NAME_MISMATCH,
        FILE_OUTSIDE_RECORDINGS,
        FILE_CHANGED,
        INVALID_RECORDING,
    }
}

object RecordingExportPolicy {
    private val fileNamePattern = Regex("recording-[0-9]+\\.csv")

    fun decide(candidate: RecordingExportCandidate): RecordingExportDecision {
        if (candidate.state != RecordingState.STOPPED) {
            return RecordingExportDecision(false, RecordingExportDecision.Reason.NOT_STOPPED)
        }
        if (!candidate.fileIsInsideRecordings) {
            return RecordingExportDecision(false, RecordingExportDecision.Reason.FILE_OUTSIDE_RECORDINGS)
        }
        val nameMatches = candidate.closedFileName != null &&
            candidate.closedFileName == candidate.requestedFileName &&
            candidate.fileNameMatches && fileNamePattern.matches(candidate.requestedFileName)
        if (!nameMatches) {
            return RecordingExportDecision(false, RecordingExportDecision.Reason.FILE_NAME_MISMATCH)
        }
        if (!candidate.fileSnapshotUnchanged) {
            return RecordingExportDecision(false, RecordingExportDecision.Reason.FILE_CHANGED)
        }
        if (!candidate.parsedSuccessfully) {
            return RecordingExportDecision(false, RecordingExportDecision.Reason.INVALID_RECORDING)
        }
        return RecordingExportDecision(true, RecordingExportDecision.Reason.ALLOWED)
    }
}
