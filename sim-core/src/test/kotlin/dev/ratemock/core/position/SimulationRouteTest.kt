package dev.ratemock.core.position

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SimulationRouteTest {
    private fun csv(vararg rows: String) = sequenceOf(
        "# provenance=simulation;calibration=uncalibrated",
        "elapsed_s,distance_m,speed_mps,cadence_spm,east_m,north_m",
        *rows,
    )

    @Test fun `parses bounded closed simulation samples`() {
        val result = SimulationRoute.parse(csv("0.0,0.0,1.0,170.0,0.0,0.0", "1.0,1.0,1.0,170.0,0.5,0.8"))
        assertEquals(2, result.size)
        assertEquals(0.5, result[1].eastMeters)
    }

    @Test fun `rejects wrong provenance, invalid time and empty routes`() {
        assertFailsWith<IllegalArgumentException> { SimulationRoute.parse(sequenceOf("bad")) }
        assertFailsWith<IllegalArgumentException> { SimulationRoute.parse(csv("1.0,0,1,170,0,0", "0.5,0,1,170,0,0")) }
        assertFailsWith<IllegalArgumentException> { SimulationRoute.parse(csv()) }
    }

    @Test fun `rejects malformed coordinates and excessive route sizes`() {
        assertFailsWith<IllegalArgumentException> { SimulationRoute.parse(csv("0,0,1,170,NaN,0")) }
        assertFailsWith<IllegalArgumentException> { SimulationRoute.parse(csv("0,0,1,170,100001,0")) }
        assertFailsWith<IllegalArgumentException> { SimulationRoute.parse(csv("14401,0,1,170,0,0")) }
        assertFailsWith<IllegalArgumentException> {
            SimulationRoute.parse(csv(*Array(10_001) { "0,0,1,170,0,0" }))
        }
    }
}
