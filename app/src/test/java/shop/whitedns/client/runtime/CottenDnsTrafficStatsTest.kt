package shop.whitedns.client.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CottenDnsTrafficStatsTest {
    @Test
    fun parseCottenDnsTrafficStatsLineReadsDirectionsAndUnits() {
        val stats = parseCottenDnsTrafficStatsLine(
            "INFO \uD83D\uDCCA ↑ 1.50 KB/s (Total: 3.00 KB) | ↓ 2.00 MB/s (Total: 4.50 MB)",
        )

        requireNotNull(stats)
        assertEquals(4_718_592L, stats.downloadBytes)
        assertEquals(3_072L, stats.uploadBytes)
        assertEquals(2_097_152L, stats.downloadSpeedBytesPerSecond)
        assertEquals(1_536L, stats.uploadSpeedBytesPerSecond)
    }

    @Test
    fun parseCottenDnsTrafficStatsLineIgnoresNonTrafficLogs() {
        assertNull(parseCottenDnsTrafficStatsLine("CottenDns client started"))
    }

    @Test
    fun parseCottenDnsTrafficStatsLineHandlesTimestampedAnsiOutput() {
        val stats = parseCottenDnsTrafficStatsLine(
            "2026/05/15 13:10:11 CottenDns \u001B[32mINFO\u001B[0m " +
                "\uD83D\uDCCA \u001B[36m↑\u001B[0m \u001B[33m256 B/s\u001B[0m " +
                "(Total: \u001B[33m512 B\u001B[0m) | \u001B[36m↓\u001B[0m " +
                "\u001B[33m1.00 KB/s\u001B[0m (Total: \u001B[33m2.00 KB\u001B[0m)",
        )

        requireNotNull(stats)
        assertEquals(2_048L, stats.downloadBytes)
        assertEquals(512L, stats.uploadBytes)
        assertEquals(1_024L, stats.downloadSpeedBytesPerSecond)
        assertEquals(256L, stats.uploadSpeedBytesPerSecond)
    }

    @Test
    fun parseCottenDnsTrafficStatsLineReadsLossAndResolverHealth() {
        val stats = parseCottenDnsTrafficStatsLine(
            "↑ 1.00 KB/s (Total: 2.00 KB) | ↓ 3.00 KB/s (Total: 4.00 KB) | loss 12.5% | resolvers 7",
        )

        requireNotNull(stats)
        assertEquals(12.5, stats.lossPercent, 0.001)
        assertEquals(7, stats.activeResolvers)
    }

    @Test
    fun parseCottenDnsMachineStatsLineReadsAllNativePathTelemetry() {
        val stats = parseCottenDnsTrafficStatsLine(
            "2026/07/29 20:00:00 [CottenDns Client] [INFO] " +
                "WD_STATS up_bps=101 up_total=202 down_bps=303 down_total=404 " +
                "loss_pm=125 resolvers=7 transport=\"adaptive UDP=5 TCP=2 DoT=0 DoH=0\" " +
                "explore=8 restore=9 switch=10 stripe=11 saved=12 " +
                "queue_tx=13 queue_encoded=14 queue_rx=15 drop_rx=16 drop_tx=17 " +
                "recoveries=18 stream_dial_fail=19 stream_write_fail=20",
        )

        requireNotNull(stats)
        assertEquals(404L, stats.downloadBytes)
        assertEquals(202L, stats.uploadBytes)
        assertEquals(303L, stats.downloadSpeedBytesPerSecond)
        assertEquals(101L, stats.uploadSpeedBytesPerSecond)
        assertEquals(12.5, stats.lossPercent, 0.001)
        assertEquals(7, stats.activeResolvers)
        assertEquals("adaptive UDP=5 TCP=2 DoT=0 DoH=0", stats.transportSummary)
        assertEquals(8L, stats.explorationCount)
        assertEquals(9L, stats.restorationCount)
        assertEquals(10L, stats.transportSwitchCount)
        assertEquals(11L, stats.stripeCount)
        assertEquals(12L, stats.redundancySavedCount)
        assertEquals(13, stats.txQueueDepth)
        assertEquals(14, stats.encodedTxQueueDepth)
        assertEquals(15, stats.rxQueueDepth)
        assertEquals(16L, stats.rxDropCount)
        assertEquals(17L, stats.txDropCount)
        assertEquals(18L, stats.recoveryCount)
        assertEquals(19L, stats.streamDialFailureCount)
        assertEquals(20L, stats.streamWriteFailureCount)
    }

    @Test
    fun parseHumanTrafficStatsLineReadsNewPathSuffix() {
        val stats = parseCottenDnsTrafficStatsLine(
            "↑ 1 KB/s (Total: 2 KB) | ↓ 3 KB/s (Total: 4 KB) | " +
                "loss 2.5% | resolvers 6 | transport adaptive UDP=4 TCP=2 DoT=0 DoH=0 | " +
                "path-events explore=1 restore=2 switch=3 stripe=4 saved=5 | " +
                "queues 6/7/8 | drops rx=9 tx=10 | recoveries 11 | stream-fail dial=12 write=13",
        )

        requireNotNull(stats)
        assertEquals("adaptive UDP=4 TCP=2 DoT=0 DoH=0", stats.transportSummary)
        assertEquals(2L, stats.restorationCount)
        assertEquals(3L, stats.transportSwitchCount)
        assertEquals(6, stats.txQueueDepth)
        assertEquals(9L, stats.rxDropCount)
        assertEquals(13L, stats.streamWriteFailureCount)
    }

    @Test
    fun trafficAccountingKeepsSessionTotalsAcrossRawCounterResets() {
        val accounting = CottenDnsTrafficAccounting()

        val first = accounting.record(
            CottenDnsTrafficStats(
                downloadBytes = 1_000L,
                uploadBytes = 500L,
                downloadSpeedBytesPerSecond = 100L,
                uploadSpeedBytesPerSecond = 50L,
            ),
        )
        assertEquals(1_000L, first.downloadBytes)
        assertEquals(500L, first.uploadBytes)

        val second = accounting.record(
            CottenDnsTrafficStats(
                downloadBytes = 1_300L,
                uploadBytes = 700L,
                downloadSpeedBytesPerSecond = 120L,
                uploadSpeedBytesPerSecond = 60L,
            ),
        )
        assertEquals(1_300L, second.downloadBytes)
        assertEquals(700L, second.uploadBytes)

        val afterRestart = accounting.record(
            CottenDnsTrafficStats(
                downloadBytes = 200L,
                uploadBytes = 50L,
                downloadSpeedBytesPerSecond = 80L,
                uploadSpeedBytesPerSecond = 20L,
            ),
        )
        assertEquals(1_500L, afterRestart.downloadBytes)
        assertEquals(750L, afterRestart.uploadBytes)
        assertEquals(80L, afterRestart.downloadSpeedBytesPerSecond)
        assertEquals(20L, afterRestart.uploadSpeedBytesPerSecond)

        val duplicateRestartSample = accounting.record(
            CottenDnsTrafficStats(
                downloadBytes = 200L,
                uploadBytes = 50L,
                downloadSpeedBytesPerSecond = 0L,
                uploadSpeedBytesPerSecond = 0L,
            ),
        )
        assertEquals(1_500L, duplicateRestartSample.downloadBytes)
        assertEquals(750L, duplicateRestartSample.uploadBytes)
    }

    @Test
    fun trafficAccountingCanResetSession() {
        val accounting = CottenDnsTrafficAccounting()
        accounting.record(
            CottenDnsTrafficStats(
                downloadBytes = 1_000L,
                uploadBytes = 500L,
                downloadSpeedBytesPerSecond = 100L,
                uploadSpeedBytesPerSecond = 50L,
            ),
        )

        accounting.reset()

        val next = accounting.record(
            CottenDnsTrafficStats(
                downloadBytes = 25L,
                uploadBytes = 10L,
                downloadSpeedBytesPerSecond = 5L,
                uploadSpeedBytesPerSecond = 2L,
            ),
        )
        assertEquals(25L, next.downloadBytes)
        assertEquals(10L, next.uploadBytes)
    }
}
