package dev.ratemock.core.position

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PositionPointTest {
    @Test fun `rejects invalid positions and movement`() {
        assertFailsWith<IllegalArgumentException> { PositionPoint(Double.NaN, 0.0) }
        assertFailsWith<IllegalArgumentException> { PositionPoint(91.0, 0.0) }
        assertFailsWith<IllegalArgumentException> { PositionPoint(0.0, 181.0) }
        assertFailsWith<IllegalArgumentException> { PositionPoint(0.0, 0.0, Double.POSITIVE_INFINITY) }
        assertFailsWith<IllegalArgumentException> { PositionPoint(0.0, 0.0).moved(10_001.0, 0.0) }
    }

    @Test fun `moving east and north uses metres`() {
        val origin = PositionPoint(35.0, 139.0)
        val east = origin.moved(100.0, 0.0)
        val north = origin.moved(0.0, 100.0)
        assertTrue(east.longitude > origin.longitude)
        assertTrue(north.latitude > origin.latitude)
        assertEquals(100.0, origin.distanceTo(east), 0.01)
        assertEquals(100.0, origin.distanceTo(north), 0.01)
        assertEquals(0.0, origin.distanceTo(origin), 1e-8)
    }

    @Test fun `crosses dateline and poles without invalid coordinates`() {
        assertTrue(PositionPoint(0.0, 179.999).moved(500.0, 0.0).longitude < 0)
        assertTrue(PositionPoint(89.999, 0.0).moved(0.0, 500.0).latitude <= 90.0)
    }
}
