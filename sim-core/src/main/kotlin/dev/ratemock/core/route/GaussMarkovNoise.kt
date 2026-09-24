package dev.ratemock.core.route

import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.random.Random

/** Parameters for a stationary first-order Gauss–Markov position error. */
data class GaussMarkovConfig(
    val horizontalSigmaM: Double,
    val verticalSigmaM: Double,
    val correlationTimeSeconds: Double,
) {
    init {
        require(horizontalSigmaM.isFinite() && horizontalSigmaM >= 0.0)
        require(verticalSigmaM.isFinite() && verticalSigmaM >= 0.0)
        require(correlationTimeSeconds.isFinite() && correlationTimeSeconds > 0.0)
    }
}

data class PositionNoise(val eastM: Double, val northM: Double, val altitudeM: Double)

/** Deterministic AR(1) noise generator; each axis is independent. */
class GaussMarkovNoise(
    private val config: GaussMarkovConfig,
    seed: Long,
) {
    private val seed: Long = seed
    private var random = Random(seed)
    private var east = 0.0
    private var north = 0.0
    private var altitude = 0.0

    fun reset() {
        random = Random(seed)
        east = 0.0
        north = 0.0
        altitude = 0.0
    }

    fun next(deltaSeconds: Double): PositionNoise {
        require(deltaSeconds.isFinite() && deltaSeconds > 0.0)
        val decay = exp(-deltaSeconds / config.correlationTimeSeconds)
        val innovationScale = sqrt((1.0 - decay * decay).coerceAtLeast(0.0))
        east = decay * east + config.horizontalSigmaM * innovationScale * random.nextGaussian()
        north = decay * north + config.horizontalSigmaM * innovationScale * random.nextGaussian()
        altitude = decay * altitude + config.verticalSigmaM * innovationScale * random.nextGaussian()
        return PositionNoise(east, north, altitude)
    }

    private fun Random.nextGaussian(): Double {
        var u: Double
        var v: Double
        do {
            u = nextDouble()
            v = nextDouble()
        } while (u <= Double.MIN_VALUE)
        return sqrt(-2.0 * kotlin.math.ln(u)) * kotlin.math.cos(2.0 * Math.PI * v)
    }
}
