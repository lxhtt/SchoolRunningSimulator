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
        assertTrue(GpxExporter.track(bundle.gps).contains("<gpx"))
    }

    @Test
    fun `gps observations remain separate and match truth sample count`() {
        val bundle = sampleBundle()
        assertEquals(bundle.truth.size, bundle.gps.size)
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
