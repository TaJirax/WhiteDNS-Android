package shop.whitedns.client.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CottenDnsConnectionProgressTest {
    @Test
    fun parseCottenDnsConnectionProgressLineParsesMtuProgress() {
        val state = parseCottenDnsConnectionProgressLine(
            "2026 WD_PROGRESS phase=mtu percent=45 completed=27 total=54 valid=20 rejected=7",
        )

        requireNotNull(state)
        assertEquals("mtu", state.phase)
        assertEquals(45, state.percent)
        assertEquals(27, state.completed)
        assertEquals(54, state.total)
        assertEquals(20, state.valid)
        assertEquals(7, state.rejected)
        assertEquals("Scanning 27/54", state.label)
    }

    @Test
    fun parseCottenDnsConnectionProgressLineInfersMtuPercentWhenMissing() {
        val state = parseCottenDnsConnectionProgressLine(
            "WD_PROGRESS phase=mtu completed=27 total=54 valid=20 rejected=7",
        )

        requireNotNull(state)
        assertEquals(45, state.percent)
    }

    @Test
    fun parseCottenDnsConnectionProgressLineParsesHumanRejectedMtuLogs() {
        val state = parseCottenDnsConnectionProgressLine(
            "2026/07/04 16:08:43 [CottenDns Client] [WARN] Rejected (21/62): " +
                "v.ashentajir.sbs via 188.213.65.54:53 | reason=DOWNLOAD_MTU | value=0 | " +
                "totals: valid=1, rejected=20",
        )

        requireNotNull(state)
        assertEquals("mtu", state.phase)
        assertEquals(37, state.percent)
        assertEquals(21, state.completed)
        assertEquals(62, state.total)
        assertEquals(1, state.valid)
        assertEquals(20, state.rejected)
    }

    @Test
    fun parseCottenDnsActiveResolverCountLineParsesTimeoutWindowLogs() {
        assertEquals(
            33,
            parseCottenDnsActiveResolverCountLine(
                "2026/07/04 16:06:19 [CottenDns Client] [WARN] DNS server 1.1.1.1:53 " +
                    "disabled due to: 100% timeout window | Active Resolvers: 33",
            ),
        )
    }

    @Test
    fun parseCottenDnsConnectionProgressLineIgnoresOtherLines() {
        assertNull(parseCottenDnsConnectionProgressLine("not progress"))
    }
}
