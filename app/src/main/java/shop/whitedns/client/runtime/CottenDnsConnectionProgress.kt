package shop.whitedns.client.runtime

import kotlin.math.roundToInt
import shop.whitedns.client.model.ConnectionProgressState

fun parseCottenDnsConnectionProgressLine(line: String): ConnectionProgressState? {
    val cleanLine = line
        .replace(AnsiEscapeRegex, "")
        .trim()
    val markerIndex = cleanLine.indexOf(ProgressMarker)
    if (markerIndex < 0) {
        return parseHumanMtuProgressLine(cleanLine)
    }

    val fields = ProgressFieldRegex.findAll(cleanLine.substring(markerIndex + ProgressMarker.length))
        .associate { match -> match.groupValues[1] to match.groupValues[2] }
    val phase = fields["phase"]?.lowercase().orEmpty()
    if (phase.isBlank()) {
        return null
    }

    val completed = fields["completed"].toIntOrZero()
    val total = fields["total"].toIntOrZero()
    val valid = fields["valid"].toIntOrZero()
    val rejected = fields["rejected"].toIntOrZero()
    val percent = fields["percent"]?.toIntOrNull()
        ?: inferProgressPercent(phase, completed, total)

    return ConnectionProgressState(
        phase = phase,
        percent = percent.coerceIn(0, 100),
        completed = completed,
        total = total,
        valid = valid,
        rejected = rejected,
    )
}

fun parseCottenDnsActiveResolverCountLine(line: String): Int? {
    val cleanLine = line
        .replace(AnsiEscapeRegex, "")
        .trim()
    return ActiveResolverCountRegex.find(cleanLine)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
}

private fun inferProgressPercent(
    phase: String,
    completed: Int,
    total: Int,
): Int {
    return when {
        phase == "mtu" && total > 0 -> (10f + (completed.coerceIn(0, total).toFloat() / total) * 70f).roundToInt()
        phase == "starting" -> 5
        phase == "selecting" -> 85
        phase == "session" -> 90
        phase == "runtime" -> 98
        phase == "connected" -> 100
        else -> 0
    }
}

private fun String?.toIntOrZero(): Int {
    return this?.toIntOrNull() ?: 0
}

private fun parseHumanMtuProgressLine(cleanLine: String): ConnectionProgressState? {
    val match = HumanMtuProgressRegex.find(cleanLine) ?: return null
    val completed = match.groupValues[1].toIntOrZero()
    val total = match.groupValues[2].toIntOrZero()
    val valid = match.groupValues[3].toIntOrZero()
    val rejected = match.groupValues[4].toIntOrZero()
    return ConnectionProgressState(
        phase = "mtu",
        percent = inferProgressPercent("mtu", completed, total).coerceIn(0, 100),
        completed = completed,
        total = total,
        valid = valid,
        rejected = rejected,
    )
}

private const val ProgressMarker = "WD_PROGRESS"

private val ProgressFieldRegex = Regex("""(\w+)=([^\s]+)""")

private val HumanMtuProgressRegex = Regex(
    """Rejected\s*\((\d+)/(\d+)\).*totals:\s*valid=(\d+),\s*rejected=(\d+)""",
    RegexOption.IGNORE_CASE,
)

private val ActiveResolverCountRegex = Regex(
    """Active\s+Resolvers:\s*(\d+)""",
    RegexOption.IGNORE_CASE,
)

private val AnsiEscapeRegex = Regex("\\u001B\\[[;\\d]*m")
