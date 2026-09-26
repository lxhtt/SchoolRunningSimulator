package dev.ratemock.core.diagnostics

/** Sensor kinds used by the device capability and callback diagnostic. */
enum class DiagnosticSensor {
    STEP_DETECTOR,
    STEP_COUNTER,
    ACCELEROMETER,
}

data class SensorDiagnosticEvent(
    val sensor: DiagnosticSensor,
    val timestampNs: Long,
    val value: Double? = null,
)

data class SensorDiagnosticSummary(
    val sensor: DiagnosticSensor,
    val available: Boolean,
    val eventCount: Int,
    val firstTimestampNs: Long?,
    val lastTimestampNs: Long?,
    val maxGapSeconds: Double?,
    val timestampsIncreasing: Boolean,
    val counterMonotonic: Boolean?,
) {
    val observed: Boolean get() = eventCount > 0
}

object SensorDiagnosticReport {
    fun summarize(
        availableSensors: Set<DiagnosticSensor>,
        events: List<SensorDiagnosticEvent>,
    ): List<SensorDiagnosticSummary> = DiagnosticSensor.entries.map { sensor ->
        val sensorEvents = events.filter { it.sensor == sensor }
        val timestamps = sensorEvents.map { it.timestampNs }
        val increasing = timestamps.zipWithNext().all { (before, after) -> after > before }
        val maxGapSeconds = timestamps.zipWithNext()
            .map { (before, after) -> (after - before).coerceAtLeast(0L) / 1_000_000_000.0 }
            .maxOrNull()
        val values = sensorEvents.mapNotNull { it.value }
        val counterMonotonic = if (sensor == DiagnosticSensor.STEP_COUNTER && values.isNotEmpty()) {
            values.all { it.isFinite() } && values.zipWithNext().all { (before, after) -> after >= before }
        } else {
            null
        }
        SensorDiagnosticSummary(
            sensor = sensor,
            available = sensor in availableSensors,
            eventCount = sensorEvents.size,
            firstTimestampNs = timestamps.firstOrNull(),
            lastTimestampNs = timestamps.lastOrNull(),
            maxGapSeconds = maxGapSeconds,
            timestampsIncreasing = increasing,
            counterMonotonic = counterMonotonic,
        )
    }
}
