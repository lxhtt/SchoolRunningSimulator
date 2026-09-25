package dev.ratemock.core.export

import dev.ratemock.core.feasibility.FixedCadence
import dev.ratemock.core.feasibility.GaitLimits
import dev.ratemock.core.feasibility.GaitResolver
import dev.ratemock.core.plan.DynamicsLimits
import dev.ratemock.core.plan.RunPlan
import dev.ratemock.core.plan.Segment
import dev.ratemock.core.plan.SegmentLimit
import dev.ratemock.core.plan.SpeedPlanner
import dev.ratemock.core.route.GeoPoint
import dev.ratemock.core.route.Route
import dev.ratemock.core.truth.GaitEngine
import java.nio.file.Files
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private fun sampleBundle(): ExportBundle {
    val engine = GaitEngine(
        RunPlan(listOf(Segment(2.5, SegmentLimit.Duration(1.0)))),
        GaitResolver(GaitLimits(160.0..190.0, 0.70..1.30), FixedCadence(175.0)),
        SpeedPlanner(DynamicsLimits(2.0, 8.0)),
        0.1,
    )
    val frames = engine.run()
    val route = Route(listOf(GeoPoint(35.0, 139.0), GeoPoint(35.0, 139.01)))
    val gps = GpsObservationBuilder(route).build(frames.map { it.truth })
    return ExportWriter.bundle(frames, gps)
}

class ExportersTest {
    @Test
    fun `bundle exports all csv headers and markers`() {
        val bundle = sampleBundle()
        assertTrue(CsvExporter.truth(bundle.truth).startsWith("t_s,distance_m"))
        assertTrue(CsvExporter.steps(bundle.steps).startsWith("t_s,distance_m"))
        assertTrue(CsvExporter.gps(bundle.gps).contains("latitude_deg"))
        assertTrue(JsonExporter.summary(bundle).contains("\"simulated\": true"))
        assertTrue(JsonExporter.summary(bundle).contains("\"uncalibrated\": true"))
        assertTrue(JsonExporter.summary(bundle, stepCount = 42, durationSeconds = 5.0, distanceMeters = 10.0)
            .contains("\"step_count\": 42"))
        assertTrue(GpxExporter.track(bundle.gps).contains("1970-01-01T00:00:01Z"))
    }

    @Test
    fun `gpx timestamps are parseable ISO instants`() {
        val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(GpxExporter.track(sampleBundle()).byteInputStream())
        val times = xml.getElementsByTagName("time")
        assertTrue(times.length > 0)
        Instant.parse(times.item(0).textContent)
        val anchored = GpxExporter.track(sampleBundle().gps, Instant.parse("2026-01-01T12:00:00Z"))
        assertTrue(anchored.contains("2026-01-01T12:00:00.100Z"))
    }

    @Test
    fun `gps observations remain separate and match truth sample count`() {
        val bundle = sampleBundle()
        assertTrue(bundle.gps.all { it.latitudeDeg.isFinite() && it.longitudeDeg.isFinite() })
    }

    @Test
    fun `writer creates complete output directory`() {
        val directory = Files.createTempDirectory("ratemock-export")
        ExportWriter.writeDirectory(sampleBundle(), directory)
        listOf("truth.csv", "steps.csv", "gps.csv", "track.gpx", "summary.json").forEach {
            assertTrue(Files.exists(directory.resolve(it)), it)
        }
    }

    @Test
    fun `gps builder rejects non chronological truth`() {
        val route = Route(listOf(GeoPoint(35.0, 139.0), GeoPoint(35.0, 139.01)))
        val first = sampleBundle().truth.first()
        assertFailsWith<IllegalArgumentException> {
            GpsObservationBuilder(route).build(listOf(first.copy(timeSeconds = 2.0), first.copy(timeSeconds = 1.0)))
        }
    }
}
