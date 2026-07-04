package shop.whitedns.client.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CottenDnsResolverStateTest {
    @Test
    fun parseCottenDnsResolverStateLineParsesResolverLists() {
        val state = parseCottenDnsResolverStateLine(
            "2026 WD_RESOLVERS active=1.1.1.1:53 standby=8.8.8.8:53,9.9.9.9:53 valid=1.1.1.1:53,8.8.8.8:53,9.9.9.9:53",
        )

        requireNotNull(state)
        assertEquals(listOf("1.1.1.1:53"), state.activeResolvers)
        assertEquals(listOf("8.8.8.8:53", "9.9.9.9:53"), state.standbyResolvers)
        assertEquals(
            listOf("1.1.1.1:53", "8.8.8.8:53", "9.9.9.9:53"),
            state.validResolvers,
        )
    }

    @Test
    fun parseCottenDnsResolverStateLineHandlesEmptyLists() {
        val state = parseCottenDnsResolverStateLine("WD_RESOLVERS active=- standby=- valid=-")

        requireNotNull(state)
        assertEquals(emptyList<String>(), state.activeResolvers)
        assertEquals(emptyList<String>(), state.standbyResolvers)
        assertEquals(emptyList<String>(), state.validResolvers)
    }

    @Test
    fun parseCottenDnsResolverStateLineIgnoresOtherLines() {
        assertNull(parseCottenDnsResolverStateLine("not resolver state"))
    }
}
