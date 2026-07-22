package shop.whitedns.client.scan

sealed class CottenDnsScanTelemetry {
    data class Valid(val resolver: String) : CottenDnsScanTelemetry()
    data class Rejected(val resolver: String) : CottenDnsScanTelemetry()
    data class Complete(
        val total: Int,
        val valid: Int,
        val rejected: Int,
    ) : CottenDnsScanTelemetry()
}

fun parseCottenDnsScanLine(line: String): CottenDnsScanTelemetry? {
    val cleanLine = line
        .replace(AnsiEscapeRegex, "")
        .trim()
    val markerIndex = cleanLine.indexOf(ScanMarker)
    if (markerIndex < 0) {
        return null
    }

    val fields = ScanFieldRegex.findAll(cleanLine.substring(markerIndex + ScanMarker.length))
        .associate { match -> match.groupValues[1] to match.groupValues[2] }
    return when (fields["event"]) {
        "valid" -> fields["resolver"]
            ?.takeIf(String::isNotBlank)
            ?.let(CottenDnsScanTelemetry::Valid)
        "rejected" -> fields["resolver"]
            ?.takeIf(String::isNotBlank)
            ?.let(CottenDnsScanTelemetry::Rejected)
        "complete" -> CottenDnsScanTelemetry.Complete(
            total = fields["total"].toIntOrZero(),
            valid = fields["valid"].toIntOrZero(),
            rejected = fields["rejected"].toIntOrZero(),
        )
        else -> null
    }
}

private fun String?.toIntOrZero(): Int {
    return this?.toIntOrNull() ?: 0
}

private const val ScanMarker = "WD_SCAN"
private val ScanFieldRegex = Regex("""(\w+)=([^\s]+)""")
private val AnsiEscapeRegex = Regex("${27.toChar()}\\[[;?0-9]*[ -/]*[@-~]")
