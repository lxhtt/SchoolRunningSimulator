package dev.ratemock.core.position

/** Strict, bounded reader for app-private, closed simulation CSV. Never reads real recordings. */
object SimulationRoute {
    data class Sample(val elapsedSeconds: Double, val eastMeters: Double, val northMeters: Double)

    fun parse(lines: Sequence<String>): List<Sample> {
        val iterator = lines.iterator()
        require(iterator.hasNext() && iterator.next() == "# provenance=simulation;calibration=uncalibrated") {
            "Missing simulation provenance"
        }
        require(iterator.hasNext() && iterator.next() == "elapsed_s,distance_m,speed_mps,cadence_spm,east_m,north_m") {
            "Invalid simulation CSV header"
        }
        val samples = ArrayList<Sample>()
        var previous = -1.0
        while (iterator.hasNext()) {
            require(samples.size < 10_000) { "Too many simulation samples" }
            val fields = iterator.next().split(',')
            require(fields.size == 6) { "Invalid simulation sample" }
            val values = fields.map { it.toDouble() }
            require(values.all { it.isFinite() }) { "Non-finite simulation sample" }
            val elapsed = values[0]
            require(elapsed >= previous && elapsed in 0.0..14_400.0) { "Invalid simulation time" }
            require(values[1] >= 0.0 && values[2] >= 0.0 && values[3] >= 0.0) { "Invalid simulation metrics" }
            require(kotlin.math.hypot(values[4], values[5]) <= 100_000.0) { "Simulation route is too large" }
            samples.add(Sample(elapsed, values[4], values[5]))
            previous = elapsed
        }
        require(samples.isNotEmpty()) { "Empty simulation route" }
        return samples
    }
}
