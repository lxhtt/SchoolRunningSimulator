package dev.ratemock.core.calibration

import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private val CSV = buildString {
    appendLine("speed_mps,cadence_spm,weight")
    for (i in 0 until 8) {
        val speed = 2.0 + i * 0.1
        appendLine("$speed,${120.0 * speed.pow(0.3)},1.0")
    }
}

class CalibrationTest {
    @Test
    fun `csv parser reads weighted observations`() {
        val dataset = CalibrationCsvParser.parse(CSV)
        assertEquals(8, dataset.observations.size)
        assertEquals(2.0, dataset.minSpeedMps, 1e-12)
        assertEquals(2.7, dataset.maxSpeedMps, 1e-12)
        assertEquals(1.0, dataset.observations.first().weight, 1e-12)
    }

    @Test
    fun `power law fit recovers synthetic parameters`() {
        val fit = CalibrationFitter.fit(CalibrationCsvParser.parse(CSV), CalibrationModel.POWER_LAW)
        assertEquals(120.0, fit.parameters.getValue("coefficient"), 1e-8)
        assertEquals(0.3, fit.parameters.getValue("exponent"), 1e-8)
        assertEquals(1.0, fit.rSquared, 1e-10)
        assertTrue(fit.residualsSpm.all { kotlin.math.abs(it) < 1e-8 })
        assertTrue(!fit.warnsForSpeed(2.5))
        assertTrue(fit.warnsForSpeed(3.0))
    }

    @Test
    fun `linear fit returns parameters and residual report`() {
        val csv = buildString {
            appendLine("speed_mps,cadence_spm")
            for (i in 0 until 8) appendLine("${2.0 + i * 0.1},${150.0 + i * 5.0}")
        }
        val fit = CalibrationFitter.fit(CalibrationCsvParser.parse(csv), CalibrationModel.LINEAR)
        assertEquals(50.0, fit.parameters.getValue("slope"), 1e-8)
        assertEquals(50.0, fit.parameters.getValue("intercept"), 1e-8)
        assertEquals(1.0, fit.rSquared, 1e-10)
    }

    @Test
    fun `weights affect the fitted result`() {
        val csv = """
            speed_mps,cadence_spm,weight
            2.0,160.0,100.0
            2.1,165.0,100.0
            2.2,170.0,100.0
            2.3,175.0,100.0
            2.4,180.0,100.0
            2.5,185.0,100.0
            2.6,190.0,100.0
            2.7,250.0,0.01
        """.trimIndent()
        val fit = CalibrationFitter.fit(CalibrationCsvParser.parse(csv), CalibrationModel.LINEAR)
        assertTrue(fit.parameters.getValue("slope") < 60.0)
    }

    @Test
    fun `dataset rejects insufficient samples and speed span`() {
        val tooFew = "speed_mps,cadence_spm\n" + List(7) { "${2.0 + it * 0.1},170" }.joinToString("\n")
        assertFailsWith<IllegalArgumentException> { CalibrationCsvParser.parse(tooFew) }
        val narrow = "speed_mps,cadence_spm\n" + List(8) { "2.0,170" }.joinToString("\n")
        assertFailsWith<IllegalArgumentException> { CalibrationCsvParser.parse(narrow) }
    }

    @Test
    fun `parser rejects invalid header and values`() {
        assertFailsWith<IllegalArgumentException> { CalibrationCsvParser.parse("x,y\n2,170") }
        assertFailsWith<IllegalArgumentException> {
            CalibrationCsvParser.parse("speed_mps,cadence_spm\nNaN,170")
        }
        assertFailsWith<IllegalArgumentException> {
            CalibrationCsvParser.parse("speed_mps,cadence_spm\n2,0\n" + List(7) { "${2.1 + it * 0.1},170" }.joinToString("\n"))
        }
    }

    @Test
    fun `calibration json contains honest metadata and residuals`() {
        val fit = CalibrationFitter.fit(CalibrationCsvParser.parse(CSV), CalibrationModel.POWER_LAW)
        val json = CalibrationJson.render(fit)
        assertTrue(json.contains("\"uncalibrated\": false"))
        assertTrue(json.contains("fitted_speed_range_mps"))
        assertTrue(json.contains("residuals_spm"))
    }
}
