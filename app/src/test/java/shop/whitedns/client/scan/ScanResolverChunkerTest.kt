package shop.whitedns.client.scan

import org.junit.Assert.assertEquals
import org.junit.Test

class ScanResolverChunkerTest {
    @Test
    fun chunkResolversRoundRobinDistributesResolversAcrossWorkers() {
        val chunks = chunkResolversRoundRobin(
            resolvers = listOf("1.1.1.1", "8.8.8.8", "9.9.9.9", "4.4.4.4", "5.5.5.5"),
            requestedWorkerCount = 2,
        )

        assertEquals(
            listOf(
                listOf("1.1.1.1", "9.9.9.9", "5.5.5.5"),
                listOf("8.8.8.8", "4.4.4.4"),
            ),
            chunks,
        )
    }

    @Test
    fun chunkResolversRoundRobinCapsWorkersAtResolverCount() {
        val chunks = chunkResolversRoundRobin(
            resolvers = listOf("1.1.1.1", "8.8.8.8"),
            requestedWorkerCount = 8,
        )

        assertEquals(listOf(listOf("1.1.1.1"), listOf("8.8.8.8")), chunks)
    }

    @Test
    fun normalizeScanResolverTextStripsPortsBeforeScanning() {
        val validation = WhiteDnsScannerResultStore.normalizeScanResolverText(
            """
            1.1.1.1:52
            8.8.8.8:53
            9.9.9.9:5353
            192.168.10.0/30:5300
            [2001:4860:4860::8888]:5353
            """.trimIndent(),
        )

        assertEquals(emptyList<String>(), validation.invalidEntries)
        assertEquals(
            listOf(
                "1.1.1.1",
                "8.8.8.8",
                "9.9.9.9",
                "192.168.10.0/30",
                "2001:4860:4860:0:0:0:0:8888",
            ),
            validation.normalizedResolvers,
        )
    }
}
