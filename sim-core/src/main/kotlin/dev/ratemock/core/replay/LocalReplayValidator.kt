package dev.ratemock.core.replay

enum class ReplayIssueSeverity { WARNING, INVALID }

data class ReplayIssue(
    val severity: ReplayIssueSeverity,
    val code: String,
    val sequence: Long,
)

enum class ReplayValidationStatus { VALID, WARNING, INVALID }

data class ReplayValidation(
    val status: ReplayValidationStatus,
    val issues: List<ReplayIssue>,
    val eventCount: Int,
    val durationSeconds: Double,
)

/** Validates an ordered local replay stream without sorting or mutating it. */
object LocalReplayValidator {
    private const val MAX_GAP_SECONDS = 5.0
    private const val MAX_POSITION_JUMP_METERS = 100.0

    fun validate(events: List<LocalReplayEvent>): ReplayValidation {
        if (events.isEmpty()) return ReplayValidation(ReplayValidationStatus.WARNING, listOf(
            ReplayIssue(ReplayIssueSeverity.WARNING, "EMPTY", -1L),
        ), 0, 0.0)
        val issues = ArrayList<ReplayIssue>()
        val session = events.first().sessionId
        var previousSequence = -1L
        var previousTime = -1.0
        var previousEast = 0.0
        var previousNorth = 0.0
        var havePosition = false
        var previousCounter: Long? = null
        events.forEach { event ->
            if (event.sessionId != session) issues += ReplayIssue(ReplayIssueSeverity.INVALID, "SESSION_MISMATCH", event.sequence)
            if (event.provenance != ReplayProvenance.SIMULATION && event.provenance != ReplayProvenance.REPLAY) {
                issues += ReplayIssue(ReplayIssueSeverity.INVALID, "PROVENANCE", event.sequence)
            }
            if (event.sequence <= previousSequence) issues += ReplayIssue(ReplayIssueSeverity.INVALID, "SEQUENCE_ORDER", event.sequence)
            if (previousTime >= 0.0) {
                val gap = event.monotonicSeconds - previousTime
                if (gap < 0.0) issues += ReplayIssue(ReplayIssueSeverity.INVALID, "TIME_ORDER", event.sequence)
                else if (gap > MAX_GAP_SECONDS) issues += ReplayIssue(ReplayIssueSeverity.WARNING, "TIME_GAP", event.sequence)
            }
            if (event is LocalPositionEvent) {
                if (havePosition) {
                    val jump = hypot(event.eastMeters - previousEast, event.northMeters - previousNorth)
                    if (jump > MAX_POSITION_JUMP_METERS) issues += ReplayIssue(ReplayIssueSeverity.WARNING, "POSITION_JUMP", event.sequence)
                }
                previousEast = event.eastMeters
                previousNorth = event.northMeters
                havePosition = true
            }
            if (event is LocalStepCounterEvent) {
                if (previousCounter != null && event.totalSteps < previousCounter!!) {
                    issues += ReplayIssue(ReplayIssueSeverity.INVALID, "COUNTER_RESET", event.sequence)
                }
                previousCounter = event.totalSteps
            }
            previousSequence = event.sequence
            previousTime = event.monotonicSeconds
        }
        val status = when {
            issues.any { it.severity == ReplayIssueSeverity.INVALID } -> ReplayValidationStatus.INVALID
            issues.isNotEmpty() -> ReplayValidationStatus.WARNING
            else -> ReplayValidationStatus.VALID
        }
        return ReplayValidation(status, issues, events.size, events.last().monotonicSeconds - events.first().monotonicSeconds)
    }

    private fun hypot(east: Double, north: Double): Double = kotlin.math.sqrt(east * east + north * north)
}
