package dev.ratemock.core.cli

import dev.ratemock.core.export.ExportWriter
import dev.ratemock.core.export.GpsObservationBuilder
import dev.ratemock.core.feasibility.FixedCadence
import dev.ratemock.core.feasibility.GaitLimits
import dev.ratemock.core.feasibility.GaitResolver
import dev.ratemock.core.plan.DynamicsLimits
import dev.ratemock.core.plan.RunPlan
import dev.ratemock.core.plan.Segment
import dev.ratemock.core.plan.SegmentLimit
import dev.ratemock.core.plan.SpeedPlanner
import dev.ratemock.core.route.GaussMarkovConfig
import dev.ratemock.core.route.GaussMarkovNoise
import dev.ratemock.core.route.GeoPoint
import dev.ratemock.core.route.Route
import dev.ratemock.core.truth.GaitEngine
import java.nio.file.Path

/** Minimal dependency-free CLI: `gradle :sim-core:run --args='--out build/ratemock-output'`. */
fun main(args: Array<String>) {
    val output = parseOutput(args)
    val route = Route(
        listOf(
            GeoPoint(35.000000, 139.000000),
            GeoPoint(35.000000, 139.001000),
            GeoPoint(35.001000, 139.001000),
        ),
    )
    val plan = RunPlan(listOf(Segment(2.5, SegmentLimit.Duration(30.0))))
    val engine = GaitEngine(
        plan = plan,
        gaitResolver = GaitResolver(GaitLimits(160.0..190.0, 0.70..1.30), FixedCadence(175.0)),
        speedPlanner = SpeedPlanner(DynamicsLimits(2.0, 8.0)),
        stepSeconds = 0.1,
    )
    val frames = engine.run()
    val gps = GpsObservationBuilder(
        route,
        GaussMarkovNoise(GaussMarkovConfig(4.0, 8.0, 30.0), seed = 42L),
    ).build(frames.map { it.truth })
    ExportWriter.writeDirectory(ExportWriter.bundle(frames, gps), output)
    println("RateMock export complete: ${frames.size} truth samples, ${frames.flatMap { it.steps }.size} steps -> $output")
}

private fun parseOutput(args: Array<String>): Path {
    val index = args.indexOf("--out")
    require(index >= 0 && index + 1 < args.size) {
        "Usage: --out <directory>"
    }
    return Path.of(args[index + 1])
}
