package shop.whitedns.client.model

import java.util.Base64
import org.json.JSONObject

private const val CottenDnsProfileScheme = "CottenDns"
// Legacy schemes import as Storm/Master DNS (compatibility) profiles.
private val CompatibilityProfileSchemes = listOf("stormdns", "masterdns")
private val SupportedProfileSchemes =
    listOf(CottenDnsProfileScheme, "cottendns") + CompatibilityProfileSchemes

// Server type implied by the link scheme, used when the payload omits server_type.
private fun schemeServerType(rawLink: String): String {
    val lower = rawLink.trim().lowercase()
    return if (CompatibilityProfileSchemes.any { lower.startsWith("$it://") }) {
        ConnectionProfile.ServerTypeCompatibility
    } else {
        ConnectionProfile.ServerTypeCottenDns
    }
}
private const val CottenDnsProfileSchema = "whitedns.profile"
private const val CottenDnsProfileVersion = 1

fun WhiteDnsSettings.exportCottenDnsProfileLink(profile: ConnectionProfile = selectedConnectionProfile()): String {
    val normalizedProfile = profile.copy(
        name = profile.name.ifBlank { profile.customServerDomain.ifBlank { "WhiteDNS Profile" } },
        serverMode = "custom",
        customServerDomain = profile.customServerDomain.trim().trimEnd('.'),
        customServerEncryptionKey = profile.customServerEncryptionKey.trim(),
        customServerEncryptionMethod = profile.customServerEncryptionMethod.coerceIn(0, 5),
        serverType = ConnectionProfile.normalizeServerType(profile.serverType),
    )
    if (normalizedProfile.customServerDomain.isBlank() || normalizedProfile.customServerEncryptionKey.isBlank()) {
        throw IllegalArgumentException("Custom server domain and encryption key are required to export")
    }

    val profileJson = JSONObject()
        .put("name", normalizedProfile.name)
        .put(
            "server",
            JSONObject()
                .put("domain", normalizedProfile.customServerDomain)
                .put("encryption_key", normalizedProfile.customServerEncryptionKey)
                .put("encryption_method", normalizedProfile.customServerEncryptionMethod)
                .put("server_type", normalizedProfile.serverType),
        )

    val root = JSONObject()
        .put("schema", CottenDnsProfileSchema)
        .put("version", CottenDnsProfileVersion)
        .put("profile", profileJson)

    val scheme = CottenDnsProfileScheme
    return "$scheme://${encodeProfilePayload(root)}"
}

fun WhiteDnsSettings.exportAllCottenDnsProfileLinks(): String {
    val links = normalizedConnectionProfiles()
        .filter { profile ->
            profile.serverMode == "custom" &&
                profile.customServerDomain.isNotBlank() &&
                profile.customServerEncryptionKey.isNotBlank()
        }
        .map { profile -> exportCottenDnsProfileLink(profile) }
    if (links.isEmpty()) {
        throw IllegalArgumentException("No custom profiles are available to export")
    }
    return links.joinToString(separator = "\n")
}

fun WhiteDnsSettings.importCottenDnsProfileLinks(
    rawLinks: String,
    nowMillis: Long = System.currentTimeMillis(),
): WhiteDnsSettings {
    val links = rawLinks
        .lineSequence()
        .mapIndexedNotNull { index, line ->
            line.trim().takeIf(String::isNotEmpty)?.let { trimmedLine ->
                (index + 1) to trimmedLine
            }
        }
        .toList()
    if (links.isEmpty()) {
        throw IllegalArgumentException("Enter at least one cottendns:// / stormdns:// / masterdns:// profile link")
    }

    var nextSettings = this
    links.forEachIndexed { index, (lineNumber, link) ->
        nextSettings = runCatching {
            nextSettings.importCottenDnsProfileLink(
                rawLink = link,
                nowMillis = nowMillis + index,
            )
        }.getOrElse { error ->
            throw IllegalArgumentException("Line $lineNumber: ${error.message ?: "Unable to import profile"}", error)
        }
    }
    return nextSettings
}

fun WhiteDnsSettings.importCottenDnsProfileLink(
    rawLink: String,
    nowMillis: Long = System.currentTimeMillis(),
): WhiteDnsSettings {
    val root = decodeProfilePayload(rawLink)
    val schema = root.requiredString("schema")
    if (schema != CottenDnsProfileSchema) {
        throw IllegalArgumentException("Unsupported profile schema")
    }
    val version = root.optionalInt("version") ?: CottenDnsProfileVersion
    if (version != CottenDnsProfileVersion) {
        throw IllegalArgumentException("Unsupported profile version")
    }

    val profileJson = root.optJSONObject("profile")
        ?: throw IllegalArgumentException("Missing profile")
    val serverJson = profileJson.optJSONObject("server")
        ?: throw IllegalArgumentException("Missing server")
    val domain = serverJson.requiredString("domain").trim().trimEnd('.')
    val encryptionKey = serverJson.requiredString("encryption_key").trim()
    if (domain.isBlank()) {
        throw IllegalArgumentException("Server domain is required")
    }
    if (encryptionKey.isBlank()) {
        throw IllegalArgumentException("Server encryption key is required")
    }

    val profileName = profileJson.requiredString("name").trim()
    val profileId = uniqueImportedProfileId(normalizedConnectionProfiles(), nowMillis)
    val encryptionMethod = serverJson.requiredInt("encryption_method")
    if (encryptionMethod !in 0..5) {
        throw IllegalArgumentException("Server encryption method must be between 0 and 5")
    }
    // Prefer an explicit server_type in the payload; otherwise infer it from the
    // link scheme (stormdns:// / masterdns:// -> compatibility, cottendns:// -> native).
    val serverType = ConnectionProfile.normalizeServerType(
        serverJson.optionalString("server_type") ?: schemeServerType(rawLink),
    )
    val importedProfile = ConnectionProfile(
        id = profileId,
        name = profileName,
        serverMode = "custom",
        customServerDomain = domain,
        customServerEncryptionKey = encryptionKey,
        customServerEncryptionMethod = encryptionMethod,
        serverType = serverType,
        resolverProfileId = "",
        connectionMode = connectionMode,
    )

    return copy(
        selectedConnectionProfileId = profileId,
        connectionProfiles = normalizedConnectionProfiles() + importedProfile,
        serverMode = "custom",
        customServerDomain = domain,
        customServerEncryptionKey = encryptionKey,
        customServerEncryptionMethod = importedProfile.customServerEncryptionMethod,
    ).syncSelectedConnectionProfileFields()
}

private fun encodeProfilePayload(root: JSONObject): String {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(root.toString().toByteArray(Charsets.UTF_8))
}

private fun decodeProfilePayload(rawLink: String): JSONObject {
    val link = rawLink.trim()
    val scheme = SupportedProfileSchemes.firstOrNull { link.startsWith("$it://", ignoreCase = true) }
        ?: throw IllegalArgumentException("Profile link must start with cottendns://, stormdns://, or masterdns://")
    // Strip "scheme://" by length so mixed-case schemes are handled too.
    val payload = link.substring(scheme.length + 3).trim()
    if (payload.isBlank()) {
        throw IllegalArgumentException("Profile link is empty")
    }
    val decoded = decodeBase64Payload(payload.substringBefore('#').substringBefore('?'))
    return JSONObject(decoded)
}

private fun decodeBase64Payload(payload: String): String {
    val paddedPayload = payload.padEnd(payload.length + ((4 - payload.length % 4) % 4), '=')
    val bytes = runCatching {
        Base64.getUrlDecoder().decode(paddedPayload)
    }.recoverCatching {
        Base64.getDecoder().decode(paddedPayload)
    }.getOrElse {
        throw IllegalArgumentException("Profile link payload is not valid base64")
    }
    return bytes.toString(Charsets.UTF_8)
}

private fun uniqueImportedProfileId(
    profiles: List<ConnectionProfile>,
    nowMillis: Long,
): String {
    val existingIds = profiles.map { it.id }.toSet()
    val baseId = "profile-imported-$nowMillis"
    if (baseId !in existingIds) {
        return baseId
    }
    var suffix = 2
    while ("$baseId-$suffix" in existingIds) {
        suffix += 1
    }
    return "$baseId-$suffix"
}

private fun JSONObject.requiredString(name: String): String {
    return optionalString(name)?.takeIf(String::isNotBlank)
        ?: throw IllegalArgumentException("Missing $name")
}

private fun JSONObject.requiredInt(name: String): Int {
    return optionalInt(name) ?: throw IllegalArgumentException("Missing $name")
}

private fun JSONObject.optionalString(name: String): String? {
    if (!has(name) || isNull(name)) {
        return null
    }
    return opt(name)?.toString()
}

private fun JSONObject.optionalInt(name: String): Int? {
    if (!has(name) || isNull(name)) {
        return null
    }
    return when (val value = opt(name)) {
        is Number -> value.toInt()
        is String -> value.trim().toIntOrNull()
        else -> null
    }
}
