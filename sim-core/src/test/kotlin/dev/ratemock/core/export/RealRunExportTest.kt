package dev.ratemock.core.export

import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RealRunExportTest {
    private val header = "timestamp_utc,elapsed_s,latitude_deg,longitude_deg,altitude_m,horizontal_accuracy_m,speed_mps,step_counter_total,location_age_s"
    private fun recording(age: String = "0.5", accuracy: String = "8") = "$header\n" +
        "2026-01-01T12:00:00Z,0,35.0,139.0,10,$accuracy,2.0,100,$age\n" +
        "2026-01-01T12:00:01Z,1,35.1,139.1,11,10,2.1,101,0.5\n"

    @Test fun exportsOnlyRealFreshFixes() {
        val run = RealRunExport.parse(recording(age = "7"))
        assertEquals(1, run.freshFixes)
        assertTrue(RealRunExport.csv(run).startsWith("# provenance=real_observation\n$header"))
        assertTrue(RealRunExport.json(run).contains("\"provenance\":\"real_observation\""))
        assertTrue(RealRunExport.json(run).contains("\"location_age_s\":7.0"))
        val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(RealRunExport.gpx(run).byteInputStream())
        assertEquals(1, xml.getElementsByTagName("trkpt").length)
        assertEquals(Instant.parse("2026-01-01T12:00:00.500Z"),
            Instant.parse(xml.getElementsByTagName("time").item(0).textContent))
    }

    @Test fun rejectsMissingFreshGpsAndMalformedInput() {
        assertFailsWith<IllegalArgumentException> { RealRunExport.parse("$header\n") }
        assertFailsWith<IllegalArgumentException> { RealRunExport.parse(recording(age = "7").replace(",101,0.5", ",101,9")) }
        assertFailsWith<IllegalArgumentException> { RealRunExport.parse(recording().replace("35.0", "101.0")) }
        assertFailsWith<IllegalArgumentException> { RealRunExport.parse("simulation,coordinates\n1,2\n") }
    }

    @Test fun readsTaggedExportWithoutDuplicatingTag() {
        val tagged = RealRunExport.csv(RealRunExport.parse(recording()))
        assertEquals(1, "# provenance=real_observation".toRegex().findAll(RealRunExport.csv(RealRunExport.parse(tagged))).count())
    }
}
