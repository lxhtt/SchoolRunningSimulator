package dev.ratemock.core.route

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private val START = GeoPoint(35.0, 139.0, 10.0)
private val EAST = GeoPoint(35.0, 139.001, 12.0)
private val NORTH = GeoPoint(35.001, 139.001, 14.0)

class RouteTest {
    @Test
    fun `route converts polyline to local metres and projects endpoints`() {
        val route = Route(listOf(START, EAST, NORTH))
        val start = route.positionAt(0.0)
        val end = route.positionAt(route.lengthM)
        assertEquals(0.0, start.eastM, 1e-8)
        assertEquals(0.0, start.northM, 1e-8)
        assertEquals(EAST.latitudeDeg, route.positionAt(route.positionAt(0.0).distanceM).point.latitudeDeg, 1e-8)
        assertEquals(NORTH.latitudeDeg, end.point.latitudeDeg, 1e-8)
        assertEquals(NORTH.longitudeDeg, end.point.longitudeDeg, 1e-8)
        assertTrue(route.lengthM > 200.0)
    }

    @Test
    fun `one way clamps while loop wraps and out and back reflects`() {
        val oneWay = Route(listOf(START, EAST), RouteMode.ONE_WAY)
        assertEquals(oneWay.lengthM, oneWay.positionAt(oneWay.lengthM * 2.0).distanceM, 1e-8)
        val loop = Route(listOf(START, EAST), RouteMode.LOOP)
        assertEquals(loop.positionAt(0.0).point, loop.positionAt(loop.lengthM).point)
        val outBack = Route(listOf(START, EAST), RouteMode.OUT_AND_BACK)
        assertEquals(START, outBack.positionAt(outBack.lengthM * 2.0).point)
        assertNotEquals(START, outBack.positionAt(outBack.lengthM).point)
    }

    @Test
    fun `route rejects malformed points and distances`() {
        assertFailsWith<IllegalArgumentException> { Route(listOf(START)) }
        assertFailsWith<IllegalArgumentException> { Route(listOf(START, START)) }
        val route = Route(listOf(START, EAST))
        assertFailsWith<IllegalArgumentException> { route.positionAt(-1.0) }
        assertFailsWith<IllegalArgumentException> { route.positionAt(Double.NaN) }
    }

    @Test
    fun `bearing uses clockwise degrees from north`() {
        val route = Route(listOf(START, EAST))
        assertEquals(90.0, route.positionAt(route.lengthM / 2.0).bearingDeg, 1e-6)
    }
}

class GaussMarkovNoiseTest {
    private val config = GaussMarkovConfig(4.0, 8.0, 30.0)

    @Test
    fun `same seed and steps are exactly reproducible`() {
        val a = GaussMarkovNoise(config, 42L)
        val b = GaussMarkovNoise(config, 42L)
        repeat(20) {
            assertEquals(a.next(1.0), b.next(1.0))
        }
    }

    @Test
    fun `different seeds produce different observations`() {
        val a = GaussMarkovNoise(config, 1L).next(1.0)
        val b = GaussMarkovNoise(config, 2L).next(1.0)
        assertNotEquals(a, b)
    }

    @Test
    fun `long stream has approximately configured stationary variance`() {
        val noise = GaussMarkovNoise(config, 99L)
        val values = (0 until 5000).map { noise.next(1.0).eastM }.drop(500)
        val mean = values.average()
        val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
        assertTrue(abs(mean) < 0.35)
        assertTrue(abs(sqrt(variance) - config.horizontalSigmaM) < 0.45)
    }

    @Test
    fun `noise rejects invalid steps and validates configuration`() {
        assertFailsWith<IllegalArgumentException> { GaussMarkovConfig(-1.0, 1.0, 30.0) }
        assertFailsWith<IllegalArgumentException> { GaussMarkovConfig(1.0, 1.0, 0.0) }
        val noise = GaussMarkovNoise(config, 7L)
        assertFailsWith<IllegalArgumentException> { noise.next(0.0) }
        assertFailsWith<IllegalArgumentException> { noise.next(Double.NaN) }
    }

    @Test
    fun `reset restarts the seeded sequence`() {
        val noise = GaussMarkovNoise(config, 7L)
        val first = noise.next(1.0)
        noise.next(1.0)
        noise.reset()
        assertEquals(first, noise.next(1.0))
    }
}
