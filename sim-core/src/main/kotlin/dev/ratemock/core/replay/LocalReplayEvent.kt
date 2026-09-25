package dev.ratemock.core.replay

/** Provenance permitted in the local-only replay protocol. */
enum class ReplayProvenance { SIMULATION, REPLAY }

/** A local event. Positions are east/north metres, never latitude/longitude. */
sealed interface LocalReplayEvent {
    val sessionId: String
    val sequence: Long
    val monotonicSeconds: Double
    val provenance: ReplayProvenance
}

data class LocalPositionEvent(
    override val sessionId: String,
    override val sequence: Long,
    override val monotonicSeconds: Double,
    val eastMeters: Double,
    val northMeters: Double,
    override val provenance: ReplayProvenance = ReplayProvenance.SIMULATION,
) : LocalReplayEvent {
    init {
        validateHeader(sessionId, sequence, monotonicSeconds, provenance)
        require(eastMeters.isFinite() && northMeters.isFinite())
    }
}

data class LocalStepDetectorEvent(
    override val sessionId: String,
    override val sequence: Long,
    override val monotonicSeconds: Double,
    override val provenance: ReplayProvenance = ReplayProvenance.SIMULATION,
) : LocalReplayEvent {
    init {
        validateHeader(sessionId, sequence, monotonicSeconds, provenance)
    }
}

data class LocalStepCounterEvent(
    override val sessionId: String,
    override val sequence: Long,
    override val monotonicSeconds: Double,
    val totalSteps: Long,
    override val provenance: ReplayProvenance = ReplayProvenance.SIMULATION,
) : LocalReplayEvent {
    init {
        validateHeader(sessionId, sequence, monotonicSeconds, provenance)
        require(totalSteps >= 0L)
    }
}

private fun validateHeader(sessionId: String, sequence: Long, timeSeconds: Double, provenance: ReplayProvenance) {
    require(sessionId.isNotBlank())
    require(sequence >= 0L)
    require(timeSeconds.isFinite() && timeSeconds >= 0.0)
    require(provenance == ReplayProvenance.SIMULATION || provenance == ReplayProvenance.REPLAY)
}
