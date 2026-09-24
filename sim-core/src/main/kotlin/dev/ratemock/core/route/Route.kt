package dev.ratemock.core.route

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/** Geographic polyline point. Altitude is optional and expressed in metres. */
data class GeoPoint(val latitudeDeg: Double, val longitudeDeg: Double, val altitudeM: Double = 0.0) {
    init {
        require(latitudeDeg.isFinite() && latitudeDeg in -90.0..90.0)
        require(longitudeDeg.isFinite() && longitudeDeg in -180.0..180.0)
        require(altitudeM.isFinite())
    }
}

enum class RouteMode { ONE_WAY, OUT_AND_BACK, LOOP }

data class LocalPoint(val eastM: Double, val northM: Double, val altitudeM: Double)

data class RoutePosition(
    val distanceM: Double,
    val point: GeoPoint,
    val eastM: Double,
    val northM: Double,
    val bearingDeg: Double,
)

/** A short-distance polyline represented in an equirectangular local frame. */
class Route(val points: List<GeoPoint>, val mode: RouteMode = RouteMode.ONE_WAY) {
    private val referenceLatitudeRad = points.firstOrNull()?.latitudeDeg?.toRadians() ?: 0.0
    private val origin = points.firstOrNull()
    private val localPoints: List<LocalPoint>
    private val cumulative: DoubleArray

    val lengthM: Double
        get() = cumulative.last()

    init {
        require(points.size >= 2) { "Route must contain at least two points" }
        require(points.zipWithNext().all { (a, b) -> a != b }) {
            "Route cannot contain consecutive duplicate points"
        }
        localPoints = points.map { toLocal(it) }
        cumulative = DoubleArray(localPoints.size)
        for (i in 1 until localPoints.size) {
            val a = localPoints[i - 1]
            val b = localPoints[i]
            cumulative[i] = cumulative[i - 1] + hypot(b.eastM - a.eastM, b.northM - a.northM)
        }
        require(lengthM > 0.0) { "Route length must be strictly positive" }
    }

    fun positionAt(distanceM: Double): RoutePosition = RouteProjector(this).positionAt(distanceM)

    internal fun localPoints(): List<LocalPoint> = localPoints
    internal fun cumulativeDistances(): DoubleArray = cumulative
    internal fun origin(): GeoPoint = origin!!

    private fun toLocal(point: GeoPoint): LocalPoint {
        val originPoint = origin ?: error("route origin unavailable")
        val earthRadiusM = 6_371_000.0
        val north = (point.latitudeDeg - originPoint.latitudeDeg).toRadians() * earthRadiusM
        val east = (point.longitudeDeg - originPoint.longitudeDeg).toRadians() * earthRadiusM * cos(referenceLatitudeRad)
        return LocalPoint(east, north, point.altitudeM)
    }

    private fun Double.toRadians(): Double = this * PI / 180.0
    private fun hypot(x: Double, y: Double): Double = sqrt(x * x + y * y)
}

class RouteProjector(private val route: Route) {
    fun positionAt(distanceM: Double): RoutePosition {
        require(distanceM.isFinite() && distanceM >= 0.0) {
            "Route distance must be finite and non-negative, got $distanceM"
        }
        val pathDistance = mapDistance(distanceM)
        val cumulative = route.cumulativeDistances()
        val local = route.localPoints()
        val segment = when {
            pathDistance >= route.lengthM -> local.lastIndex - 1
            else -> (0 until cumulative.lastIndex).first { pathDistance <= cumulative[it + 1] }
        }
        val startDistance = cumulative[segment]
        val segmentLength = cumulative[segment + 1] - startDistance
        val fraction = if (segmentLength == 0.0) 0.0 else ((pathDistance - startDistance) / segmentLength).coerceIn(0.0, 1.0)
        val a = local[segment]
        val b = local[segment + 1]
        val east = a.eastM + (b.eastM - a.eastM) * fraction
        val north = a.northM + (b.northM - a.northM) * fraction
        val altitude = a.altitudeM + (b.altitudeM - a.altitudeM) * fraction
        val bearing = bearingDeg(b.eastM - a.eastM, b.northM - a.northM)
        val origin = route.origin()
        val earthRadiusM = 6_371_000.0
        val latitude = origin.latitudeDeg + north / earthRadiusM * 180.0 / Math.PI
        val longitude = origin.longitudeDeg + east / (earthRadiusM * cos(origin.latitudeDeg * Math.PI / 180.0)) * 180.0 / Math.PI
        return RoutePosition(
            distanceM = pathDistance,
            point = GeoPoint(latitude, longitude, altitude),
            eastM = east,
            northM = north,
            bearingDeg = bearing,
        )
    }

    private fun mapDistance(distanceM: Double): Double = when (route.mode) {
        RouteMode.ONE_WAY -> distanceM.coerceAtMost(route.lengthM)
        RouteMode.LOOP -> distanceM % route.lengthM
        RouteMode.OUT_AND_BACK -> {
            val cycle = route.lengthM * 2.0
            val wrapped = distanceM % cycle
            if (wrapped <= route.lengthM) wrapped else cycle - wrapped
        }
    }

    private fun bearingDeg(east: Double, north: Double): Double =
        (Math.toDegrees(kotlin.math.atan2(east, north)) + 360.0) % 360.0
}
