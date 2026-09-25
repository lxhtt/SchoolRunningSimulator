package dev.ratemock.core.export

import java.time.Instant

/** Parses the app-private S7 CSV, never simulation history. */
object RealRunExport {
    private const val HEADER = "timestamp_utc,elapsed_s,latitude_deg,longitude_deg,altitude_m,horizontal_accuracy_m,speed_mps,step_counter_total,location_age_s"
    private const val MAX_ROWS = 36_000

    data class Row(
        val timestamp: Instant,
        val elapsedSeconds: Double,
        val latitude: Double,
        val longitude: Double,
        val altitude: Double?,
        val accuracy: Double?,
        val speed: Double?,
        val counter: Double?,
        val locationAgeSeconds: Double?,
    ) {
        val freshGps: Boolean get() = locationAgeSeconds != null && locationAgeSeconds in 0.0..5.0 &&
            accuracy != null && accuracy in 0.0..25.0
    }

    data class Recording(val rows: List<Row>, val originalCsv: String) {
        val freshFixes: Int get() = rows.count { it.freshGps }
        val durationSeconds: Double get() = (rows.last().elapsedSeconds - rows.first().elapsedSeconds).coerceAtLeast(0.0)
    }

    fun parse(csv: String): Recording {
        val lines = csv.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }.toList()
        require(lines.firstOrNull() == HEADER) { "Invalid S7 recording header" }
        require(lines.size in 2..(MAX_ROWS + 1)) { "Recording is empty or too large" }
        val rows = lines.drop(1).mapIndexed { index, line ->
            val fields = line.split(',', limit = 10)
            require(fields.size == 9) { "Malformed row ${index + 2}" }
            val timestamp = Instant.parse(fields[0])
            fun required(column: Int): Double = fields[column].toDouble().also { require(it.isFinite()) }
            fun optional(column: Int): Double? = fields[column].takeIf { it.isNotBlank() }?.toDouble()
                ?.also { require(it.isFinite()) }
            Row(timestamp, required(1), required(2), required(3), optional(4), optional(5),
                optional(6), optional(7), optional(8)).also {
                require(it.elapsedSeconds >= 0.0 && it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0)
                require(it.altitude == null || it.altitude in -12_000.0..12_000.0)
                require(it.accuracy == null || it.accuracy >= 0.0)
                require(it.speed == null || it.speed >= 0.0)
                require(it.counter == null || it.counter >= 0.0)
                require(it.locationAgeSeconds == null || it.locationAgeSeconds >= 0.0)
            }
        }
        require(rows.zipWithNext().all { (first, second) ->
            second.timestamp > first.timestamp && second.elapsedSeconds > first.elapsedSeconds
        }) { "Recording times are not increasing" }
        require(rows.any { it.freshGps }) { "No fresh GPS fixes" }
        return Recording(rows, csv)
    }

    fun csv(recording: Recording): String = "# provenance=real_observation\n" +
        recording.originalCsv.lineSequence().filter { !it.startsWith("#") }.joinToString("\n").trimEnd() + "\n"

    fun json(recording: Recording): String = buildString {
        append("{\"schema_version\":1,\"provenance\":\"real_observation\",\"sample_count\":${recording.rows.size},")
        append("\"fresh_gps_count\":${recording.freshFixes},\"duration_s\":${recording.durationSeconds},\"samples\":[")
        recording.rows.forEachIndexed { index, row ->
            if (index > 0) append(',')
            append("{\"timestamp_utc\":\"${row.timestamp}\",\"elapsed_s\":${row.elapsedSeconds},")
            append("\"latitude_deg\":${row.latitude},\"longitude_deg\":${row.longitude},")
            append("\"altitude_m\":${row.altitude},\"horizontal_accuracy_m\":${row.accuracy},")
            append("\"speed_mps\":${row.speed},\"step_counter_total\":${row.counter},")
            append("\"location_age_s\":${row.locationAgeSeconds},\"fresh_gps\":${row.freshGps}}")
        }
        append("]}\n")
    }

    fun gpx(recording: Recording): String = buildString {
        appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        appendLine("<gpx version=\"1.1\" creator=\"RateMock\" xmlns=\"http://www.topografix.com/GPX/1/1\">")
        appendLine("  <trk><name>RateMock real GPS observation</name><desc>Real observations only; fresh fixes</desc><trkseg>")
        var lastFix: Instant? = null
        recording.rows.filter { it.freshGps }.forEach { row ->
            val fixTime = row.timestamp.minusMillis((row.locationAgeSeconds!! * 1000).toLong())
            if (lastFix?.let { fixTime > it } ?: true) {
                append("    <trkpt lat=\"${row.latitude}\" lon=\"${row.longitude}\">")
                row.altitude?.let { append("<ele>$it</ele>") }
                appendLine("<time>$fixTime</time></trkpt>")
                lastFix = fixTime
            }
        }
        appendLine("  </trkseg></trk>")
        appendLine("</gpx>")
    }
}
