package shop.whitedns.client.model

import java.util.Base64
import org.json.JSONObject

private const val StormDnsProfileScheme = "stormdns"
private const val CottenDnsProfileScheme = "cottendns"
private val SupportedProfileSchemes = listOf(StormDnsProfileScheme, CottenDnsProfileScheme)
private const val StormDnsProfileSchema = "whitedns.profile"
private const val StormDnsProfileVersion = 1

fun WhiteDnsSettings.exportStormDnsProfileLink(profile: ConnectionProfile = selectedConnectionProfile()): String {
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
        .put("schema", StormDnsProfileSchema)
        .put("version", StormDnsProfileVersion)
        .put("profile", profileJson)

    // Match the link scheme to the engine generation so an imported link selects
    // the right wire format even without the explicit server_type field.
    val scheme = if (normalizedProfile.serverType == ConnectionProfile.ServerTypeCottenDns) {
        CottenDnsProfileScheme
    } else {
        StormDnsProfileScheme
    }
    return "$scheme://${encodeProfilePayload(root)}"
}

fun WhiteDnsSettings.exportAllStormDnsProfileLinks(): String {
    val links = normalizedConnectionProfiles()
        .filter { profile ->
            profile.serverMode == "custom" &&
                profile.customServerDomain.isNotBlank() &&
                profile.customServerEncryptionKey.isNotBlank()
        }
        .map { profile -> exportStormDnsProfileLink(profile) }
    if (links.isEmpty()) {
        throw IllegalArgumentException("No custom profiles are available to export")
    }
    return links.joinToString(separator = "\n")
}

fun WhiteDnsSettings.importStormDnsProfileLinks(
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
        throw IllegalArgumentException("Enter at least one stormdns:// or cottendns:// profile link")
    }

    var nextSettings = this
    links.forEachIndexed { index, (lineNumber, link) ->
        nextSettings = runCatching {
            nextSettings.importStormDnsProfileLink(
                rawLink = link,
                nowMillis = nowMillis + index,
            )
        }.getOrElse { error ->
            throw IllegalArgumentException("Line $lineNumber: ${error.message ?: "Unable to import profile"}", error)
        }
    }
    return nextSettings
}

fun WhiteDnsSettings.importStormDnsProfileLink(
    rawLink: String,
    nowMillis: Long = System.currentTimeMillis(),
): WhiteDnsSettings {
    val root = decodeProfilePayload(rawLink)
    val schema = root.requiredString("schema")
    if (schema != StormDnsProfileSchema) {
        throw IllegalArgumentException("Unsupported profile schema")
    }
    val version = root.optionalInt("version") ?: StormDnsProfileVersion
    if (version != StormDnsProfileVersion) {
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
    // link scheme (cottendns:// -> CottenDns 2-byte, stormdns:// -> legacy 1-byte).
    val serverType = ConnectionProfile.normalizeServerType(
        serverJson.optionalString("server_type")
            ?: if (rawLink.trim().startsWith("$CottenDnsProfileScheme://")) {
                ConnectionProfile.ServerTypeCottenDns
            } else {
                ConnectionProfile.ServerTypeStormDns
            },
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
    // CottenDns is a StormDNS-protocol fork, so its profile links carry the same
    // payload and are accepted here alongside stormdns:// for interoperability.
    val scheme = SupportedProfileSchemes.firstOrNull { link.startsWith("$it://") }
        ?: throw IllegalArgumentException("Profile link must start with stormdns:// or cottendns://")
    val payload = link.removePrefix("$scheme://").trim()
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
