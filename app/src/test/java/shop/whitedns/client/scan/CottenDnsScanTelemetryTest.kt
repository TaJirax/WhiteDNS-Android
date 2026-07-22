package shop.whitedns.client.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CottenDnsScanTelemetryTest {
    @Test
    fun parseCottenDnsScanLineParsesValidResolver() {
        val telemetry = parseCottenDnsScanLine("2026 WD_SCAN event=valid resolver=1.1.1.1:53")

        assertEquals(CottenDnsScanTelemetry.Valid("1.1.1.1:53"), telemetry)
    }

    @Test
    fun parseCottenDnsScanLineParsesRejectedResolver() {
        val telemetry = parseCottenDnsScanLine("2026 WD_SCAN event=rejected resolver=8.8.8.8:53")

        assertEquals(CottenDnsScanTelemetry.Rejected("8.8.8.8:53"), telemetry)
    }

    @Test
    fun parseCottenDnsScanLineParsesCompletion() {
        val telemetry = parseCottenDnsScanLine("WD_SCAN event=complete total=10 valid=3 rejected=7")

        assertEquals(CottenDnsScanTelemetry.Complete(total = 10, valid = 3, rejected = 7), telemetry)
    }

    @Test
    fun parseCottenDnsScanLineIgnoresOtherLines() {
        assertNull(parseCottenDnsScanLine("WD_PROGRESS phase=mtu percent=50"))
    }
}
