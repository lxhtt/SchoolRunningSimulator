package dev.ratemock.core.truth

/** Lifecycle state of a deterministic truth-layer simulation. */
enum class RunState {
    STARTING,
    RUNNING,
    PAUSED,
    FINISHED,
}

/** A continuous truth sample in the local route coordinate system. */
data class TruthSample(
    val timeSeconds: Double,
    val distanceMeters: Double,
    val eastMeters: Double,
    val northMeters: Double,
    val speedMps: Double,
    val cadenceSpm: Double,
    val stepLengthMeters: Double,
    val state: RunState,
) {
    init {
        require(timeSeconds.isFinite() && timeSeconds >= 0.0)
        require(distanceMeters.isFinite() && distanceMeters >= 0.0)
        require(eastMeters.isFinite() && northMeters.isFinite())
        require(speedMps.isFinite() && speedMps >= 0.0)
        require(cadenceSpm.isFinite() && cadenceSpm >= 0.0)
        require(stepLengthMeters.isFinite() && stepLengthMeters >= 0.0)
        require(speedMps == 0.0 || stepLengthMeters > 0.0)
    }
}

/** A discrete footfall event derived from the same truth gait sample. */
data class StepEvent(
    val timeSeconds: Double,
    val distanceMeters: Double,
    val stepLengthMeters: Double,
    val speedMps: Double,
) {
    init {
        require(timeSeconds.isFinite() && timeSeconds >= 0.0)
        require(distanceMeters.isFinite() && distanceMeters >= 0.0)
        require(stepLengthMeters.isFinite() && stepLengthMeters > 0.0)
        require(speedMps.isFinite() && speedMps > 0.0)
    }
}
