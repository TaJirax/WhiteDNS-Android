package shop.whitedns.client.runtime

import java.util.Locale
import kotlin.math.roundToLong

data class CottenDnsTrafficStats(
    val downloadBytes: Long,
    val uploadBytes: Long,
    val downloadSpeedBytesPerSecond: Long,
    val uploadSpeedBytesPerSecond: Long,
    val lossPercent: Double = 0.0,
    val activeResolvers: Int = 0,
    val transportSummary: String = "",
    val explorationCount: Long = 0,
    val restorationCount: Long = 0,
    val transportSwitchCount: Long = 0,
    val stripeCount: Long = 0,
    val redundancySavedCount: Long = 0,
    val txQueueDepth: Int = 0,
    val encodedTxQueueDepth: Int = 0,
    val rxQueueDepth: Int = 0,
    val rxDropCount: Long = 0,
    val txDropCount: Long = 0,
    val recoveryCount: Long = 0,
    val streamDialFailureCount: Long = 0,
    val streamWriteFailureCount: Long = 0,
) {
    fun hasTraffic(): Boolean {
        return downloadBytes > 0L ||
            uploadBytes > 0L ||
            downloadSpeedBytesPerSecond > 0L ||
            uploadSpeedBytesPerSecond > 0L
    }
}

class CottenDnsTrafficAccounting {
    private var lastRawStats: CottenDnsTrafficStats? = null
    private var accumulatedDownloadBytes: Long = 0L
    private var accumulatedUploadBytes: Long = 0L
    private var latestStats: CottenDnsTrafficStats? = null

    @Synchronized
    fun reset() {
        lastRawStats = null
        accumulatedDownloadBytes = 0L
        accumulatedUploadBytes = 0L
        latestStats = null
    }

    @Synchronized
    fun record(rawStats: CottenDnsTrafficStats): CottenDnsTrafficStats {
        val previous = lastRawStats
        accumulatedDownloadBytes += rawStats.downloadBytes.deltaSince(previous?.downloadBytes)
        accumulatedUploadBytes += rawStats.uploadBytes.deltaSince(previous?.uploadBytes)
        lastRawStats = rawStats
        return rawStats.copy(
            downloadBytes = accumulatedDownloadBytes,
            uploadBytes = accumulatedUploadBytes,
        ).also { latestStats = it }
    }

    @Synchronized
    fun latest(): CottenDnsTrafficStats? = latestStats

    private fun Long.deltaSince(previous: Long?): Long {
        if (previous == null) {
            return coerceAtLeast(0)
        }
        return if (this >= previous) {
            this - previous
        } else {
            coerceAtLeast(0)
        }
    }
}

fun parseCottenDnsTrafficStatsLine(line: String): CottenDnsTrafficStats? {
    val cleanLine = line
        .replace(AnsiEscapeRegex, "")
        .trim()
    parseCottenDnsMachineStatsLine(cleanLine)?.let { return it }
    val match = CottenDnsTrafficStatsRegex.find(cleanLine) ?: return null
    val uploadSpeed = parseDataAmount(
        value = match.groupValues[1],
        unit = match.groupValues[2],
    ) ?: return null
    val uploadTotal = parseDataAmount(
        value = match.groupValues[3],
        unit = match.groupValues[4],
    ) ?: return null
    val downloadSpeed = parseDataAmount(
        value = match.groupValues[5],
        unit = match.groupValues[6],
    ) ?: return null
    val downloadTotal = parseDataAmount(
        value = match.groupValues[7],
        unit = match.groupValues[8],
    ) ?: return null
    val pathEvents = PathEventsRegex.find(cleanLine)
    val queues = QueuesRegex.find(cleanLine)
    val drops = DropsRegex.find(cleanLine)
    val recoveries = RecoveriesRegex.find(cleanLine)
    val streamFailures = StreamFailuresRegex.find(cleanLine)

    return CottenDnsTrafficStats(
        downloadBytes = downloadTotal,
        uploadBytes = uploadTotal,
        downloadSpeedBytesPerSecond = downloadSpeed,
        uploadSpeedBytesPerSecond = uploadSpeed,
        lossPercent = match.groupValues.getOrNull(9)?.toDoubleOrNull()?.coerceIn(0.0, 100.0) ?: 0.0,
        activeResolvers = match.groupValues.getOrNull(10)?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
        transportSummary = TransportSummaryRegex.find(cleanLine)?.groupValues?.getOrNull(1)?.trim().orEmpty(),
        explorationCount = pathEvents?.longGroup(1) ?: 0,
        restorationCount = pathEvents?.longGroup(2) ?: 0,
        transportSwitchCount = pathEvents?.longGroup(3) ?: 0,
        stripeCount = pathEvents?.longGroup(4) ?: 0,
        redundancySavedCount = pathEvents?.longGroup(5) ?: 0,
        txQueueDepth = queues?.intGroup(1) ?: 0,
        encodedTxQueueDepth = queues?.intGroup(2) ?: 0,
        rxQueueDepth = queues?.intGroup(3) ?: 0,
        rxDropCount = drops?.longGroup(1) ?: 0,
        txDropCount = drops?.longGroup(2) ?: 0,
        recoveryCount = recoveries?.longGroup(1) ?: 0,
        streamDialFailureCount = streamFailures?.longGroup(1) ?: 0,
        streamWriteFailureCount = streamFailures?.longGroup(2) ?: 0,
    )
}

private fun parseCottenDnsMachineStatsLine(line: String): CottenDnsTrafficStats? {
    val match = CottenDnsMachineStatsRegex.find(line) ?: return null
    return CottenDnsTrafficStats(
        uploadSpeedBytesPerSecond = match.longGroup(1),
        uploadBytes = match.longGroup(2),
        downloadSpeedBytesPerSecond = match.longGroup(3),
        downloadBytes = match.longGroup(4),
        lossPercent = (match.longGroup(5) / 10.0).coerceIn(0.0, 100.0),
        activeResolvers = match.intGroup(6),
        transportSummary = match.groupValues[7],
        explorationCount = match.longGroup(8),
        restorationCount = match.longGroup(9),
        transportSwitchCount = match.longGroup(10),
        stripeCount = match.longGroup(11),
        redundancySavedCount = match.longGroup(12),
        txQueueDepth = match.intGroup(13),
        encodedTxQueueDepth = match.intGroup(14),
        rxQueueDepth = match.intGroup(15),
        rxDropCount = match.longGroup(16),
        txDropCount = match.longGroup(17),
        recoveryCount = match.longGroup(18),
        streamDialFailureCount = match.longGroup(19),
        streamWriteFailureCount = match.longGroup(20),
    )
}

fun formatTrafficSpeed(bytesPerSecond: Long): String {
    val units = listOf("B/s", "KB/s", "MB/s", "GB/s", "TB/s")
    var value = bytesPerSecond.coerceAtLeast(0).toDouble()
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex += 1
    }
    val pattern = if (unitIndex == 0 || value >= 100.0) "%.0f %s" else "%.1f %s"
    return String.format(Locale.US, pattern, value, units[unitIndex])
}

fun formatTrafficNotificationText(stats: CottenDnsTrafficStats): String {
    return buildString {
        append("Down ${formatTrafficSpeed(stats.downloadSpeedBytesPerSecond)}")
        append(" | Up ${formatTrafficSpeed(stats.uploadSpeedBytesPerSecond)}")
        append(" | Loss ${String.format(Locale.US, "%.1f%%", stats.lossPercent)}")
        if (stats.activeResolvers > 0) {
            append(" | DNS ${stats.activeResolvers}")
        }
    }
}

private fun parseDataAmount(
    value: String,
    unit: String,
): Long? {
    val amount = value.toDoubleOrNull() ?: return null
    val multiplier = when (unit.uppercase(Locale.US)) {
        "B" -> 1.0
        "KB" -> 1024.0
        "MB" -> 1024.0 * 1024.0
        "GB" -> 1024.0 * 1024.0 * 1024.0
        "TB" -> 1024.0 * 1024.0 * 1024.0 * 1024.0
        else -> return null
    }
    return (amount * multiplier).roundToLong().coerceAtLeast(0)
}

private val AnsiEscapeRegex = Regex("\\u001B\\[[;\\d]*m")
private val CottenDnsMachineStatsRegex = Regex(
    """WD_STATS\s+up_bps=(\d+)\s+up_total=(\d+)\s+down_bps=(\d+)\s+down_total=(\d+)\s+loss_pm=(\d+)\s+resolvers=(\d+)\s+transport="([^"]*)"\s+explore=(\d+)\s+restore=(\d+)\s+switch=(\d+)\s+stripe=(\d+)\s+saved=(\d+)\s+queue_tx=(\d+)\s+queue_encoded=(\d+)\s+queue_rx=(\d+)\s+drop_rx=(\d+)\s+drop_tx=(\d+)\s+recoveries=(\d+)\s+stream_dial_fail=(\d+)\s+stream_write_fail=(\d+)""",
)
private val CottenDnsTrafficStatsRegex = Regex(
    """([0-9]+(?:\.[0-9]+)?)\s*([KMGT]?B)/s\s*\(Total:\s*([0-9]+(?:\.[0-9]+)?)\s*([KMGT]?B)\)\s*\|\s*[^0-9]*([0-9]+(?:\.[0-9]+)?)\s*([KMGT]?B)/s\s*\(Total:\s*([0-9]+(?:\.[0-9]+)?)\s*([KMGT]?B)\)(?:.*?loss\s*([0-9]+(?:\.[0-9]+)?)%.*?resolvers\s*(\d+))?""",
    RegexOption.IGNORE_CASE,
)
private val TransportSummaryRegex = Regex("""transport\s+(.+?)\s*\|\s*path-events""", RegexOption.IGNORE_CASE)
private val PathEventsRegex = Regex(
    """path-events\s+explore=(\d+)\s+restore=(\d+)\s+switch=(\d+)\s+stripe=(\d+)\s+saved=(\d+)""",
    RegexOption.IGNORE_CASE,
)
private val QueuesRegex = Regex("""queues\s+(\d+)/(\d+)/(\d+)""", RegexOption.IGNORE_CASE)
private val DropsRegex = Regex("""drops\s+rx=(\d+)\s+tx=(\d+)""", RegexOption.IGNORE_CASE)
private val RecoveriesRegex = Regex("""recoveries\s+(\d+)""", RegexOption.IGNORE_CASE)
private val StreamFailuresRegex = Regex("""stream-fail\s+dial=(\d+)\s+write=(\d+)""", RegexOption.IGNORE_CASE)

private fun MatchResult.longGroup(index: Int): Long {
    return groupValues.getOrNull(index)?.toLongOrNull()?.coerceAtLeast(0) ?: 0
}

private fun MatchResult.intGroup(index: Int): Int {
    return groupValues.getOrNull(index)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
}
