package dev.ratemock.core.cli

import dev.ratemock.core.calibration.CalibrationCsvParser
import dev.ratemock.core.calibration.CalibrationFitter
import dev.ratemock.core.calibration.CalibrationJson
import dev.ratemock.core.calibration.CalibrationModel
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
import java.nio.file.Files
import java.nio.file.Path

/**
 * Dependency-free CLI:
 * `gradle :sim-core:run --args='--out build/ratemock-output'`
 * or `gradle :sim-core:run --args='calibrate --input data.csv --out calibration.json --model power'`.
 */
fun main(args: Array<String>) {
    if (args.firstOrNull() == "calibrate") {
        calibrate(args.drop(1))
    } else {
        simulate(args)
    }
}

private fun simulate(args: Array<String>) {
    val output = requiredPath(args.toList(), "--out")
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

private fun calibrate(args: List<String>) {
    val input = requiredPath(args, "--input")
    val output = requiredPath(args, "--out")
    val model = when (args.option("--model")?.lowercase()) {
        null, "power", "power_law" -> CalibrationModel.POWER_LAW
        "linear" -> CalibrationModel.LINEAR
        else -> error("--model must be power or linear")
    }
    val fit = CalibrationFitter.fit(CalibrationCsvParser.parse(input), model)
    Files.writeString(output, CalibrationJson.render(fit))
    println("RateMock calibration complete: ${fit.model} R²=${fit.rSquared} -> $output")
}

private fun requiredPath(args: List<String>, option: String): Path {
    val index = args.indexOf(option)
    require(index >= 0 && index + 1 < args.size) { "Missing $option" }
    return Path.of(args[index + 1])
}

private fun List<String>.option(option: String): String? {
    val index = indexOf(option)
    return if (index >= 0 && index + 1 < size) this[index + 1] else null
}
