package dev.ratemock.core.position

import kotlin.math.*

/** A user-selected synthetic position, never a real GPS observation. */
data class PositionPoint(val latitude: Double, val longitude: Double, val altitude: Double = 0.0) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
        require(altitude.isFinite() && altitude in -500.0..10_000.0)
    }

    fun moved(eastM: Double, northM: Double): PositionPoint {
        require(eastM.isFinite() && northM.isFinite() && hypot(eastM, northM) <= 10_000.0)
        val distance = hypot(eastM, northM) / 6_371_000.0
        if (distance == 0.0) return this
        val bearing = atan2(eastM, northM)
        val lat = Math.toRadians(latitude)
        val lon = Math.toRadians(longitude)
        val nextLat = asin((sin(lat) * cos(distance) + cos(lat) * sin(distance) * cos(bearing)).coerceIn(-1.0, 1.0))
        val nextLon = lon + atan2(sin(bearing) * sin(distance) * cos(lat), cos(distance) - sin(lat) * sin(nextLat))
        val wrapped = ((Math.toDegrees(nextLon) + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        return PositionPoint(Math.toDegrees(nextLat), wrapped, altitude)
    }

    fun distanceTo(other: PositionPoint): Double {
        val lat1 = Math.toRadians(latitude)
        val lat2 = Math.toRadians(other.latitude)
        val a = sin((lat2 - lat1) / 2).pow(2) + cos(lat1) * cos(lat2) *
            sin(Math.toRadians(other.longitude - longitude) / 2).pow(2)
        return 2 * 6_371_000.0 * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
}
