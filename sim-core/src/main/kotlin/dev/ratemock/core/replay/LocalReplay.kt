package dev.ratemock.core.replay

data class ReplaySummary(
    val sessionId: String?,
    val eventCount: Int,
    val durationSeconds: Double,
    val positions: Int,
    val stepDetectorEvents: Int,
    val stepCounterEvents: Int,
    val validation: ReplayValidation,
)

/** Deterministic, read-only replay over local events. It has no Android integration. */
class LocalReplay(private val events: List<LocalReplayEvent>) {
    private var index = 0

    val validation: ReplayValidation get() = LocalReplayValidator.validate(events)

    fun nextUntil(monotonicSeconds: Double): List<LocalReplayEvent> {
        require(monotonicSeconds.isFinite() && monotonicSeconds >= 0.0)
        val start = index
        while (index < events.size && events[index].monotonicSeconds <= monotonicSeconds) index++
        return events.subList(start, index).toList()
    }

    fun reset() {
        index = 0
    }

    fun summary(): ReplaySummary = ReplaySummary(
        events.firstOrNull()?.sessionId,
        events.size,
        validation.durationSeconds,
        events.count { it is LocalPositionEvent },
        events.count { it is LocalStepDetectorEvent },
        events.count { it is LocalStepCounterEvent },
        validation,
    )
}
