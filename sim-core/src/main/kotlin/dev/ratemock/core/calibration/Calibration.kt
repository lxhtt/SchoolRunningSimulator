package dev.ratemock.core.calibration

import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.ln

/** One stable speed/cadence observation used for fitting. */
data class CalibrationObservation(
    val speedMps: Double,
    val cadenceSpm: Double,
    val weight: Double = 1.0,
) {
    init {
        require(speedMps.isFinite() && speedMps > 0.0)
        require(cadenceSpm.isFinite() && cadenceSpm > 0.0)
        require(weight.isFinite() && weight > 0.0)
    }
}

class CalibrationDataset(val observations: List<CalibrationObservation>) {
    val minSpeedMps: Double get() = observations.minOf { it.speedMps }
    val maxSpeedMps: Double get() = observations.maxOf { it.speedMps }

    init {
        require(observations.isNotEmpty()) { "Calibration requires at least one observation" }
        require(observations.size >= MIN_SAMPLES) {
            "Calibration requires at least $MIN_SAMPLES observations, got ${observations.size}"
        }
        require(maxSpeedMps - minSpeedMps >= MIN_SPEED_SPAN_MPS) {
            "Calibration speed span must be at least $MIN_SPEED_SPAN_MPS m/s, got ${maxSpeedMps - minSpeedMps}"
        }
        require(logVariance(observations.map { it.speedMps }) > 0.0) {
            "Calibration speeds must have non-zero ln(speed) variance"
        }
    }

    private fun logVariance(values: List<Double>): Double {
        val mean = values.map(::ln).average()
        return values.sumOf { (ln(it) - mean) * (ln(it) - mean) }
    }

    companion object {
        const val MIN_SAMPLES = 8
        const val MIN_SPEED_SPAN_MPS = 0.5
    }
}

object CalibrationCsvParser {
    fun parse(text: String): CalibrationDataset {
        val rows = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
        require(rows.isNotEmpty()) { "Calibration CSV is empty" }
        val header = rows.first().split(',').map { it.trim().lowercase() }
        require(header.size >= 2 && header[0] == "speed_mps" && header[1] == "cadence_spm") {
            "Calibration CSV header must start with speed_mps,cadence_spm"
        }
        val weightIndex = header.indexOf("weight")
        val observations = rows.drop(1).mapIndexed { index, row ->
            val fields = row.split(',').map { it.trim() }
            require(fields.size >= 2) { "Calibration row ${index + 2} has fewer than two columns" }
            CalibrationObservation(
                speedMps = fields[0].toDoubleOrNull() ?: error("Invalid speed at row ${index + 2}"),
                cadenceSpm = fields[1].toDoubleOrNull() ?: error("Invalid cadence at row ${index + 2}"),
                weight = if (weightIndex >= 0) {
                    require(weightIndex < fields.size) { "Missing weight at row ${index + 2}" }
                    fields[weightIndex].toDoubleOrNull() ?: error("Invalid weight at row ${index + 2}")
                } else 1.0,
            )
        }
        return CalibrationDataset(observations)
    }

    fun parse(path: Path): CalibrationDataset = parse(Files.readString(path))
}

enum class CalibrationModel { POWER_LAW, LINEAR }

data class CalibrationFit(
    val model: CalibrationModel,
    val parameters: Map<String, Double>,
    val rSquared: Double,
    val residualsSpm: List<Double>,
    val sampleCount: Int,
    val fittedSpeedRangeMps: ClosedFloatingPointRange<Double>,
    val uncalibrated: Boolean = false,
) {
    init {
        require(rSquared.isFinite())
        require(parameters.values.all { it.isFinite() })
        require(residualsSpm.size == sampleCount)
    }

    fun warnsForSpeed(speedMps: Double): Boolean = speedMps !in fittedSpeedRangeMps
}

object CalibrationFitter {
    fun fit(dataset: CalibrationDataset, model: CalibrationModel): CalibrationFit = when (model) {
        CalibrationModel.POWER_LAW -> fitPowerLaw(dataset)
        CalibrationModel.LINEAR -> fitLinear(dataset)
    }

    private fun fitPowerLaw(dataset: CalibrationDataset): CalibrationFit {
        val x = dataset.observations.map { ln(it.speedMps) }
        val y = dataset.observations.map { ln(it.cadenceSpm) }
        val coefficients = weightedRegression(x, y, dataset.observations.map { it.weight })
        val intercept = coefficients.first
        val slope = coefficients.second
        val residuals = dataset.observations.mapIndexed { i, o -> o.cadenceSpm - kotlin.math.exp(intercept + slope * x[i]) }
        return CalibrationFit(
            CalibrationModel.POWER_LAW,
            mapOf("coefficient" to kotlin.math.exp(intercept), "exponent" to slope),
            rSquared(dataset.observations.map { it.cadenceSpm }, residuals),
            residuals,
            dataset.observations.size,
            dataset.minSpeedMps..dataset.maxSpeedMps,
        )
    }

    private fun fitLinear(dataset: CalibrationDataset): CalibrationFit {
        val x = dataset.observations.map { it.speedMps }
        val y = dataset.observations.map { it.cadenceSpm }
        val coefficients = weightedRegression(x, y, dataset.observations.map { it.weight })
        val intercept = coefficients.first
        val slope = coefficients.second
        val residuals = dataset.observations.mapIndexed { i, o -> o.cadenceSpm - (intercept + slope * x[i]) }
        return CalibrationFit(
            CalibrationModel.LINEAR,
            mapOf("intercept" to intercept, "slope" to slope),
            rSquared(y, residuals),
            residuals,
            dataset.observations.size,
            dataset.minSpeedMps..dataset.maxSpeedMps,
        )
    }

    private fun weightedRegression(x: List<Double>, y: List<Double>, weights: List<Double>): Pair<Double, Double> {
        val total = weights.sum()
        val meanX = x.indices.sumOf { x[it] * weights[it] } / total
        val meanY = y.indices.sumOf { y[it] * weights[it] } / total
        val denominator = x.indices.sumOf { weights[it] * (x[it] - meanX) * (x[it] - meanX) }
        require(denominator > 0.0) { "Calibration predictor variance must be non-zero" }
        val slope = x.indices.sumOf { weights[it] * (x[it] - meanX) * (y[it] - meanY) } / denominator
        return (meanY - slope * meanX) to slope
    }

    private fun rSquared(actual: List<Double>, residuals: List<Double>): Double {
        val mean = actual.average()
        val total = actual.sumOf { (it - mean) * (it - mean) }
        return if (total == 0.0) 1.0 else 1.0 - residuals.sumOf { it * it } / total
    }
}

object CalibrationJson {
    fun render(fit: CalibrationFit): String = buildString {
        appendLine("{")
        appendLine("  \"schema_version\": 1,")
        appendLine("  \"model\": \"${fit.model.name.lowercase()}\",")
        appendLine("  \"uncalibrated\": false,")
        appendLine("  \"sample_count\": ${fit.sampleCount},")
        appendLine("  \"r_squared\": ${fit.rSquared},")
        appendLine("  \"fitted_speed_range_mps\": {\"min\": ${fit.fittedSpeedRangeMps.start}, \"max\": ${fit.fittedSpeedRangeMps.endInclusive}},")
        appendLine("  \"parameters\": {${fit.parameters.entries.joinToString(", ") { "\"${it.key}\": ${it.value}" }}},")
        appendLine("  \"residuals_spm\": [${fit.residualsSpm.joinToString(", ")}]")
        appendLine("}")
    }
}
