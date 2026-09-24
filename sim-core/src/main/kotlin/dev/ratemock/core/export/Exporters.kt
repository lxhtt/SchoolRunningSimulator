package dev.ratemock.core.export

import dev.ratemock.core.route.GaussMarkovNoise
import dev.ratemock.core.route.PositionNoise
import dev.ratemock.core.route.Route
import dev.ratemock.core.route.RoutePosition
import dev.ratemock.core.route.RouteProjector
import dev.ratemock.core.truth.EngineFrame
import dev.ratemock.core.truth.StepEvent
import dev.ratemock.core.truth.TruthSample
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.hypot

/** GPS observation fields are deliberately separate from truth-layer samples. */
data class GpsObservation(
    val timeSeconds: Double,
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val altitudeM: Double,
    val horizontalAccuracyM: Double,
    val verticalAccuracyM: Double,
    val speedMps: Double,
    val bearingDeg: Double,
) {
    init {
        require(timeSeconds.isFinite() && timeSeconds >= 0.0)
        require(latitudeDeg.isFinite() && longitudeDeg.isFinite() && altitudeM.isFinite())
        require(horizontalAccuracyM.isFinite() && horizontalAccuracyM >= 0.0)
        require(verticalAccuracyM.isFinite() && verticalAccuracyM >= 0.0)
        require(speedMps.isFinite() && speedMps >= 0.0)
        require(bearingDeg.isFinite() && bearingDeg in 0.0..360.0)
    }
}

data class ExportBundle(
    val truth: List<TruthSample>,
    val steps: List<StepEvent>,
    val gps: List<GpsObservation>,
    val simulated: Boolean = true,
    val uncalibrated: Boolean = true,
)

/** Builds GPS observations from truth samples and optional local-coordinate noise. */
class GpsObservationBuilder(
    private val route: Route,
    private val noise: GaussMarkovNoise? = null,
) {
    private val projector = RouteProjector(route)

    fun build(samples: List<TruthSample>): List<GpsObservation> {
        require(samples.zipWithNext().all { (a, b) -> b.timeSeconds >= a.timeSeconds })
        var previousTime = 0.0
        return samples.map { sample ->
            val delta = sample.timeSeconds - previousTime
            val positionNoise = if (noise != null && delta > 0.0) {
                noise.next(delta)
            } else {
                PositionNoise(0.0, 0.0, 0.0)
            }
            val observation = projector.positionAt(sample.distanceMeters)
                .withNoise(positionNoise, sample)
            previousTime = sample.timeSeconds
            observation
        }
    }

    private fun RoutePosition.withNoise(noise: PositionNoise, sample: TruthSample): GpsObservation {
        val earthRadiusM = 6_371_000.0
        val latitude = point.latitudeDeg + noise.northM / earthRadiusM * 180.0 / Math.PI
        val longitude = point.longitudeDeg + noise.eastM /
            (earthRadiusM * kotlin.math.cos(point.latitudeDeg * Math.PI / 180.0)) * 180.0 / Math.PI
        val horizontal = hypot(noise.eastM, noise.northM)
        return GpsObservation(
            timeSeconds = sample.timeSeconds,
            latitudeDeg = latitude,
            longitudeDeg = longitude,
            altitudeM = point.altitudeM + noise.altitudeM,
            horizontalAccuracyM = horizontal,
            verticalAccuracyM = kotlin.math.abs(noise.altitudeM),
            speedMps = sample.speedMps,
            bearingDeg = bearingDeg,
        )
    }
}

object CsvExporter {
    fun truth(samples: List<TruthSample>): String = buildString {
        appendLine("t_s,distance_m,east_m,north_m,speed_mps,cadence_spm,step_length_m,state")
        samples.forEach { s ->
            appendLine(listOf(s.timeSeconds, s.distanceMeters, s.eastMeters, s.northMeters, s.speedMps, s.cadenceSpm, s.stepLengthMeters, s.state).joinToString(","))
        }
    }

    fun steps(steps: List<StepEvent>): String = buildString {
        appendLine("t_s,distance_m,step_length_m,speed_mps")
        steps.forEach { s -> appendLine("${s.timeSeconds},${s.distanceMeters},${s.stepLengthMeters},${s.speedMps}") }
    }

    fun gps(observations: List<GpsObservation>): String = buildString {
        appendLine("t_s,latitude_deg,longitude_deg,altitude_m,horizontal_accuracy_m,vertical_accuracy_m,speed_mps,bearing_deg")
        observations.forEach { o ->
            appendLine(listOf(o.timeSeconds, o.latitudeDeg, o.longitudeDeg, o.altitudeM, o.horizontalAccuracyM, o.verticalAccuracyM, o.speedMps, o.bearingDeg).joinToString(","))
        }
    }
}

object JsonExporter {
    fun summary(bundle: ExportBundle): String = buildString {
        val last = bundle.truth.lastOrNull()
        appendLine("{")
        appendLine("  \"schema_version\": 1,")
        appendLine("  \"simulated\": ${bundle.simulated},")
        appendLine("  \"uncalibrated\": ${bundle.uncalibrated},")
        appendLine("  \"sample_count\": ${bundle.truth.size},")
        appendLine("  \"step_count\": ${bundle.steps.size},")
        appendLine("  \"gps_count\": ${bundle.gps.size},")
        appendLine("  \"duration_s\": ${last?.timeSeconds ?: 0.0},")
        appendLine("  \"distance_m\": ${last?.distanceMeters ?: 0.0}")
        appendLine("}")
    }
}

object GpxExporter {
    fun track(observations: List<GpsObservation>): String = buildString {
        appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        appendLine("<gpx version=\"1.1\" creator=\"RateMock\" xmlns=\"http://www.topografix.com/GPX/1/1\">")
        appendLine("  <trk><name>RateMock simulated track</name><trkseg>")
        observations.forEach { o ->
            appendLine("    <trkpt lat=\"${o.latitudeDeg}\" lon=\"${o.longitudeDeg}\"><ele>${o.altitudeM}</ele><time>${o.timeSeconds}</time></trkpt>")
        }
        appendLine("  </trkseg></trk>")
        appendLine("</gpx>")
    }
}

object ExportWriter {
    fun writeDirectory(bundle: ExportBundle, directory: Path) {
        Files.createDirectories(directory)
        Files.writeString(directory.resolve("truth.csv"), CsvExporter.truth(bundle.truth))
        Files.writeString(directory.resolve("steps.csv"), CsvExporter.steps(bundle.steps))
        Files.writeString(directory.resolve("gps.csv"), CsvExporter.gps(bundle.gps))
        Files.writeString(directory.resolve("track.gpx"), GpxExporter.track(bundle.gps))
        Files.writeString(directory.resolve("summary.json"), JsonExporter.summary(bundle))
    }

    fun bundle(frames: List<EngineFrame>, gps: List<GpsObservation>): ExportBundle = ExportBundle(
        truth = frames.map { it.truth },
        steps = frames.flatMap { it.steps },
        gps = gps,
    )
}
