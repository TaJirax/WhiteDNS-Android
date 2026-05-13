package shop.whitedns.client.scan

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import shop.whitedns.client.model.ResolverTextValidation
import shop.whitedns.client.model.validateResolverText

object WhiteDnsScannerResultStore {
    const val ResultFileName = "Scanner result"

    fun resultFile(context: Context): File {
        return File(resultDirectory(context), ResultFileName)
    }

    fun readValidResolvers(context: Context): List<String> {
        return runCatching {
            val file = resultFile(context)
            if (!file.isFile) {
                emptyList()
            } else {
                val text = AtomicFile(file).openRead().use { stream ->
                    stream.readBytes().toString(Charsets.UTF_8)
                }
                normalizeResolverText(text)
            }
        }.getOrDefault(emptyList())
    }

    fun mergeValidResolvers(context: Context, resolvers: Iterable<String>): List<String> {
        val incomingResolvers = normalizeResolverEntries(resolvers)
        if (incomingResolvers.isEmpty()) {
            return readValidResolvers(context)
        }
        val mergedResolvers = (readValidResolvers(context) + incomingResolvers).distinct()
        writeValidResolvers(context, mergedResolvers)
        return mergedResolvers
    }

    fun normalizeResolverText(rawText: String): List<String> {
        return normalizeScanResolverText(rawText).normalizedResolvers
    }

    fun normalizeScanResolverText(rawText: String): ResolverTextValidation {
        val validation = validateResolverText(rawText)
        return validation.copy(
            normalizedResolvers = validation.normalizedResolvers
                .map(::stripScanResolverPort)
                .distinct(),
        )
    }

    fun normalizeResolverEntries(resolvers: Iterable<String>): List<String> {
        return normalizeResolverText(resolvers.joinToString(separator = "\n"))
    }

    fun normalizeResolverEntry(resolver: String): String? {
        return normalizeResolverEntries(listOf(resolver)).firstOrNull()
    }

    private fun writeValidResolvers(context: Context, resolvers: List<String>) {
        val target = resultFile(context)
        target.parentFile?.mkdirs()
        val atomicFile = AtomicFile(target)
        var stream: FileOutputStream? = null
        try {
            stream = atomicFile.startWrite()
            stream.write(resolvers.joinToString(separator = "\n").toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(stream)
        } catch (error: IOException) {
            stream?.let(atomicFile::failWrite)
            throw error
        }
    }

    private fun resultDirectory(context: Context): File {
        return File(File(context.noBackupFilesDir, "stormdns"), "scan")
    }

    private fun stripScanResolverPort(resolver: String): String {
        val text = resolver.trim()
        val bracketedMatch = BracketedResolverPortRegex.matchEntire(text)
        if (bracketedMatch != null) {
            return bracketedMatch.groupValues[1]
        }
        val hostPortMatch = ResolverPortRegex.matchEntire(text)
        if (hostPortMatch != null) {
            return hostPortMatch.groupValues[1]
        }
        return text
    }

    private val BracketedResolverPortRegex = Regex("""^\[([^]]+)]:(\d{1,5})$""")
    private val ResolverPortRegex = Regex("""^([^:]+):(\d{1,5})$""")
}
