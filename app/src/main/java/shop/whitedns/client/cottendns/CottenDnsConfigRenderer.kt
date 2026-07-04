package shop.whitedns.client.cottendns

import shop.whitedns.client.model.ConnectionProfile
import shop.whitedns.client.model.ResolvedWhiteDnsSettings
import shop.whitedns.client.model.CottenDnsServerProfile
import shop.whitedns.client.model.WhiteDnsSettings
import shop.whitedns.client.model.normalizedResolverProfiles
import shop.whitedns.client.model.resolve
import shop.whitedns.client.model.runtimeConnectionSettings

object CottenDnsConfigRenderer {

    fun renderClientToml(
        connectionProfile: ConnectionProfile,
        settings: WhiteDnsSettings,
    ): String {
        val resolverProfile = settings.normalizedResolverProfiles()
            .firstOrNull { it.id == connectionProfile.resolverProfileId }
        val exportSettings = settings.copy(
            selectedConnectionProfileId = connectionProfile.id,
            selectedResolverProfileId = resolverProfile?.id.orEmpty(),
            resolverText = resolverProfile?.resolverText ?: settings.resolverText,
            connectionMode = when (connectionProfile.connectionMode) {
                "proxy", "vpn" -> connectionProfile.connectionMode
                else -> settings.connectionMode
            },
        ).runtimeConnectionSettings()
        return renderClientToml(
            serverProfile = connectionProfile.toCottenDnsServerProfile(),
            settings = exportSettings,
        )
    }

    fun renderClientToml(
        serverProfile: CottenDnsServerProfile,
        settings: WhiteDnsSettings,
    ): String {
        val resolved = settings.resolve()

        return buildString {
            appendLine("""DOMAINS = ["${escape(serverProfile.domain)}"]""")
            appendLine("DATA_ENCRYPTION_METHOD = ${serverProfile.encryptionMethod}")
            appendLine("ENCRYPTION_KEY = \"${escape(serverProfile.encryptionKey)}\"")
            appendLine("PROTOCOL_TYPE = \"${escape(resolved.protocolType)}\"")
            appendServerTypeToml(serverProfile.serverType, resolved.configPreset)
            appendClientSettingsToml(resolved)
        }.trimEnd()
    }

    // Emits the wire-format + feature keys. serverType controls only the
    // session-ID wire width (the sole true incompatibility between CottenDns and
    // MasterDNS/StormDNS). Everything else is server-transparent (QNAME reshaping,
    // adaptive duplication, DNS hardening, MTU tuning) and is emitted for BOTH
    // server types. The two server-generation-sensitive knobs — response delivery
    // types and TCP/53 — are forced to a safe TXT/UDP subset in Compatibility mode
    // so old MasterDNS/StormDNS servers never receive a query type or transport
    // they cannot answer.
    private fun StringBuilder.appendServerTypeToml(
        serverType: String,
        configPreset: String,
    ) {
        val isCompatibility =
            ConnectionProfile.normalizeServerType(serverType) == ConnectionProfile.ServerTypeCompatibility
        val preset = normalizeConfigPreset(configPreset)

        // CONFIG_PRESET only accepts the engine's own presets; the app-level
        // "master-storm" preset expands to explicit legacy-safe keys over a
        // default base (explicit keys always win over the preset in the engine).
        appendLine("CONFIG_PRESET = \"${escape(enginePresetBase(preset))}\"")
        appendLine("LEGACY_SESSION_ID = $isCompatibility")

        // Server-generation-sensitive knobs. Compatibility forces the safe subset
        // regardless of preset so a MasterDNS/StormDNS server always works.
        val transport = if (isCompatibility) "udp" else resolverTransport(preset)
        val queryTypes = if (isCompatibility) listOf("TXT") else queryTypeSet(preset)
        appendLine("RESOLVER_TRANSPORT = \"$transport\"")
        appendLine("QUERY_TYPES = ${queryTypesToml(queryTypes)}")

        // Server-transparent features: identical behavior against either server
        // generation, so emit for both. QNAME reshaping is reassembled by the
        // server regardless of label lengths, and DNS query-id/EDNS/NXDOMAIN
        // hardening applies to the resolver hop, not the tunnel server.
        appendLine("QNAME_LABEL_LENGTH = ${qnameLabelLength(preset)}")
        appendLine("ADAPTIVE_DUPLICATION = true")
        appendLine("DUPLICATION_PREFER_DISTINCT_DOMAINS = true")
        appendLine("RESOLVER_RATE_LIMIT_ENABLED = true")
        appendLine("ADAPTIVE_DUPLICATION_TARGET_DELIVERY = ${adaptiveDuplicationTarget(preset)}")
        appendLine("DNS_RANDOMIZE_QUERY_ID = true")
        appendLine("DNS_EDNS_COOKIE = true")
        appendLine("DNS_QNAME_CASE_RANDOMIZATION = false")
        appendLine("EDNS_UDP_SIZE = ${ednsUdpSize(preset)}")
        appendLine("RESOLVER_IGNORE_INJECTED_NXDOMAIN = true")
        appendLine("MTU_PROBE_SAMPLES = ${mtuProbeSamples(preset)}")
        appendLine("MTU_MAX_LOSS = ${mtuMaxLoss(preset)}")
        appendLine("MTU_ADAPTIVE_GROUPING = true")
        appendLine("MTU_GROUP_GAP_RATIO = 0.25")
    }


    private fun normalizeConfigPreset(configPreset: String): String {
        return when (configPreset.trim().lowercase()) {
            "speed" -> "speed"
            "survival" -> "survival"
            "tcp", "tcp-survival", "tcp_survival" -> "tcp-survival"
            "master", "storm", "master-storm", "master_storm" -> "master-storm"
            else -> "default"
        }
    }

    // Maps an app-level preset to the engine's own CONFIG_PRESET vocabulary.
    private fun enginePresetBase(preset: String): String {
        return when (preset) {
            "speed", "survival", "tcp-survival" -> preset
            else -> "default" // "default" and "master-storm"
        }
    }

    private fun resolverTransport(configPreset: String): String {
        return when (configPreset) {
            "tcp-survival" -> "tcp"
            "master-storm" -> "udp"
            else -> "auto"
        }
    }

    private fun adaptiveDuplicationTarget(configPreset: String): String {
        return if (configPreset == "survival") "0.97" else "0.95"
    }

    private fun ednsUdpSize(configPreset: String): Int {
        return if (configPreset == "survival") 1232 else 4096
    }

    private fun qnameLabelLength(configPreset: String): Int {
        return if (configPreset == "survival") 42 else 63
    }

    private fun mtuProbeSamples(configPreset: String): Int {
        return when (configPreset) {
            "speed", "tcp-survival" -> 4
            "survival" -> 5
            else -> 6
        }
    }

    private fun mtuMaxLoss(configPreset: String): String {
        return if (configPreset == "survival") "0.2" else "0.25"
    }

    private fun queryTypeSet(configPreset: String): List<String> {
        return when (configPreset) {
            "speed", "tcp-survival" -> listOf("TXT", "HTTPS")
            "survival" -> listOf("TXT", "CNAME", "HTTPS", "A")
            "master-storm" -> listOf("TXT")
            else -> listOf("TXT", "CNAME", "NULL", "HTTPS")
        }
    }

    private fun queryTypesToml(types: List<String>): String {
        return types.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
    }

    fun renderScanClientToml(
        serverProfile: CottenDnsServerProfile,
        settings: WhiteDnsSettings,
    ): String {
        val resolved = settings.copy(
            startupMode = "resolvers",
            trafficWarmupEnabled = false,
        ).resolve()
        return buildString {
            appendLine("""DOMAINS = ["${escape(serverProfile.domain)}"]""")
            appendLine("DATA_ENCRYPTION_METHOD = ${serverProfile.encryptionMethod}")
            appendLine("ENCRYPTION_KEY = \"${escape(serverProfile.encryptionKey)}\"")
            appendLine("PROTOCOL_TYPE = \"${escape(resolved.protocolType)}\"")
            appendServerTypeToml(serverProfile.serverType, resolved.configPreset)
            appendClientSettingsToml(
                resolved = resolved,
                listenIp = "127.0.0.1",
                listenPort = 0,
                localDnsEnabled = false,
                localDnsPort = 0,
                mtuTestParallelismResolvers = 1,
            )
        }.trimEnd()
    }

    fun renderAdvancedSettingsToml(settings: WhiteDnsSettings): String {
        val resolved = settings.resolve()
        return buildString {
            appendAdvancedSettingsToml(resolved)
        }.trimEnd()
    }

    fun renderResolvers(settings: WhiteDnsSettings): String {
        return settings.resolve().resolverEntries.joinToString(separator = "\n")
    }

    private fun StringBuilder.appendClientSettingsToml(
        resolved: ResolvedWhiteDnsSettings,
        listenIp: String = resolved.listenIp,
        listenPort: Int = resolved.listenPort,
        localDnsEnabled: Boolean = resolved.localDnsEnabled,
        localDnsPort: Int = resolved.localDnsPort,
        mtuTestParallelismResolvers: Int = resolved.mtuTestParallelismResolvers,
    ) {
        appendLine("LISTEN_IP = \"${escape(listenIp)}\"")
        appendLine("LISTEN_PORT = $listenPort")
        appendLine("SOCKS5_AUTH = ${resolved.socks5Authentication}")
        appendLine("SOCKS5_USER = \"${escape(resolved.socksUsername)}\"")
        appendLine("SOCKS5_PASS = \"${escape(resolved.socksPassword)}\"")
        appendLine("LOCAL_DNS_ENABLED = $localDnsEnabled")
        appendLine("LOCAL_DNS_IP = \"127.0.0.1\"")
        appendLine("LOCAL_DNS_PORT = $localDnsPort")
        appendLine("RESOLVER_BALANCING_STRATEGY = ${resolved.balancingStrategy}")
        appendLine("UPLOAD_PACKET_DUPLICATION_COUNT = ${resolved.uploadDuplication}")
        appendLine("DOWNLOAD_PACKET_DUPLICATION_COUNT = ${resolved.downloadDuplication}")
        appendLine("UPLOAD_COMPRESSION_TYPE = ${resolved.uploadCompression}")
        appendLine("DOWNLOAD_COMPRESSION_TYPE = ${resolved.downloadCompression}")
        appendLine("BASE_ENCODE_DATA = ${resolved.baseEncodeData}")
        appendLine("MIN_UPLOAD_MTU = ${resolved.minUploadMtu}")
        appendLine("MIN_DOWNLOAD_MTU = ${resolved.minDownloadMtu}")
        appendLine("MAX_UPLOAD_MTU = ${resolved.maxUploadMtu}")
        appendLine("MAX_DOWNLOAD_MTU = ${resolved.maxDownloadMtu}")
        appendLine("MTU_TEST_RETRIES_RESOLVERS = ${resolved.mtuTestRetriesResolvers}")
        appendLine("MTU_TEST_TIMEOUT_RESOLVERS = ${resolved.mtuTestTimeoutResolvers}")
        appendLine("MTU_TEST_PARALLELISM_RESOLVERS = $mtuTestParallelismResolvers")
        appendLine("MTU_TEST_RETRIES_LOGS = ${resolved.mtuTestRetriesLogs}")
        appendLine("MTU_TEST_TIMEOUT_LOGS = ${resolved.mtuTestTimeoutLogs}")
        appendLine("MTU_TEST_PARALLELISM_LOGS = ${resolved.mtuTestParallelismLogs}")
        appendLine("RX_TX_WORKERS = ${resolved.rxTxWorkers}")
        appendLine("TUNNEL_PROCESS_WORKERS = ${resolved.tunnelProcessWorkers}")
        appendLine("TUNNEL_PACKET_TIMEOUT_SECONDS = ${resolved.tunnelPacketTimeoutSeconds}")
        appendLine("DISPATCHER_IDLE_POLL_INTERVAL_SECONDS = ${resolved.dispatcherIdlePollIntervalSeconds}")
        appendLine("TX_CHANNEL_SIZE = ${resolved.txChannelSize}")
        appendLine("RX_CHANNEL_SIZE = ${resolved.rxChannelSize}")
        appendLine("RESOLVER_UDP_CONNECTION_POOL_SIZE = ${resolved.resolverUdpConnectionPoolSize}")
        appendLine("STREAM_QUEUE_INITIAL_CAPACITY = ${resolved.streamQueueInitialCapacity}")
        appendLine("ORPHAN_QUEUE_INITIAL_CAPACITY = ${resolved.orphanQueueInitialCapacity}")
        appendLine("DNS_RESPONSE_FRAGMENT_STORE_CAPACITY = ${resolved.dnsResponseFragmentStoreCapacity}")
        appendLine("MAX_ACTIVE_STREAMS = ${resolved.maxActiveStreams}")
        appendLine("LOCAL_HANDSHAKE_TIMEOUT_SECONDS = ${resolved.localHandshakeTimeoutSeconds}")
        appendLine("SOCKS_UDP_ASSOCIATE_READ_TIMEOUT_SECONDS = ${resolved.socksUdpAssociateReadTimeoutSeconds}")
        appendLine("CLIENT_TERMINAL_STREAM_RETENTION_SECONDS = ${resolved.clientTerminalStreamRetentionSeconds}")
        appendLine("CLIENT_CANCELLED_SETUP_RETENTION_SECONDS = ${resolved.clientCancelledSetupRetentionSeconds}")
        appendLine("SESSION_INIT_RETRY_BASE_SECONDS = ${resolved.sessionInitRetryBaseSeconds}")
        appendLine("SESSION_INIT_RETRY_STEP_SECONDS = ${resolved.sessionInitRetryStepSeconds}")
        appendLine("SESSION_INIT_RETRY_LINEAR_AFTER = ${resolved.sessionInitRetryLinearAfter}")
        appendLine("SESSION_INIT_RETRY_MAX_SECONDS = ${resolved.sessionInitRetryMaxSeconds}")
        appendLine("SESSION_INIT_BUSY_RETRY_INTERVAL_SECONDS = ${resolved.sessionInitBusyRetryIntervalSeconds}")
        appendLine("STARTUP_MODE = \"${escape(resolved.startupMode)}\"")
        appendLine("LOG_SCAN_MAX_DAYS = 14")
        appendLine("LOG_SCAN_MAX_RESOLVERS = 128")
        appendLine("LOG_BASED_MTU_VERIFY = true")
        appendLine("STATS_REPORT_INTERVAL_SECONDS = 1.0")
        appendLine("PING_WATCHDOG_TIMEOUT_SECONDS = ${resolved.pingWatchdogSeconds}")
        appendLine("LOG_LEVEL = \"${escape(resolved.logLevel)}\"")
        appendLine("LOG_TO_FILE = true")
        appendLine("LOG_DIR = \"logs\"")
    }

    private fun StringBuilder.appendAdvancedSettingsToml(resolved: ResolvedWhiteDnsSettings) {
        appendLine("CONFIG_PRESET = \"${escape(resolved.configPreset)}\"")
        appendLine("LISTEN_IP = \"${escape(resolved.listenIp)}\"")
        appendLine("LISTEN_PORT = ${resolved.listenPort}")
        appendLine("HTTP_PROXY_ENABLED = ${resolved.httpProxyEnabled}")
        appendLine("HTTP_PROXY_PORT = ${resolved.httpProxyPort}")
        appendLine("SOCKS5_AUTH = ${resolved.socks5Authentication}")
        appendLine("SOCKS5_USER = \"${escape(resolved.socksUsername)}\"")
        appendLine("SOCKS5_PASS = \"${escape(resolved.socksPassword)}\"")
        appendLine("LOCAL_DNS_ENABLED = ${resolved.localDnsEnabled}")
        appendLine("LOCAL_DNS_PORT = ${resolved.localDnsPort}")
        appendLine("RESOLVER_BALANCING_STRATEGY = ${resolved.balancingStrategy}")
        appendLine("UPLOAD_PACKET_DUPLICATION_COUNT = ${resolved.uploadDuplication}")
        appendLine("DOWNLOAD_PACKET_DUPLICATION_COUNT = ${resolved.downloadDuplication}")
        appendLine("UPLOAD_COMPRESSION_TYPE = ${resolved.uploadCompression}")
        appendLine("DOWNLOAD_COMPRESSION_TYPE = ${resolved.downloadCompression}")
        appendLine("BASE_ENCODE_DATA = ${resolved.baseEncodeData}")
        appendLine("MIN_UPLOAD_MTU = ${resolved.minUploadMtu}")
        appendLine("MIN_DOWNLOAD_MTU = ${resolved.minDownloadMtu}")
        appendLine("MAX_UPLOAD_MTU = ${resolved.maxUploadMtu}")
        appendLine("MAX_DOWNLOAD_MTU = ${resolved.maxDownloadMtu}")
        appendLine("MTU_TEST_RETRIES_RESOLVERS = ${resolved.mtuTestRetriesResolvers}")
        appendLine("MTU_TEST_TIMEOUT_RESOLVERS = ${resolved.mtuTestTimeoutResolvers}")
        appendLine("MTU_TEST_PARALLELISM_RESOLVERS = ${resolved.mtuTestParallelismResolvers}")
        appendLine("MTU_TEST_RETRIES_LOGS = ${resolved.mtuTestRetriesLogs}")
        appendLine("MTU_TEST_TIMEOUT_LOGS = ${resolved.mtuTestTimeoutLogs}")
        appendLine("MTU_TEST_PARALLELISM_LOGS = ${resolved.mtuTestParallelismLogs}")
        appendLine("RX_TX_WORKERS = ${resolved.rxTxWorkers}")
        appendLine("TUNNEL_PROCESS_WORKERS = ${resolved.tunnelProcessWorkers}")
        appendLine("TUNNEL_PACKET_TIMEOUT_SECONDS = ${resolved.tunnelPacketTimeoutSeconds}")
        appendLine("DISPATCHER_IDLE_POLL_INTERVAL_SECONDS = ${resolved.dispatcherIdlePollIntervalSeconds}")
        appendLine("TX_CHANNEL_SIZE = ${resolved.txChannelSize}")
        appendLine("RX_CHANNEL_SIZE = ${resolved.rxChannelSize}")
        appendLine("RESOLVER_UDP_CONNECTION_POOL_SIZE = ${resolved.resolverUdpConnectionPoolSize}")
        appendLine("STREAM_QUEUE_INITIAL_CAPACITY = ${resolved.streamQueueInitialCapacity}")
        appendLine("ORPHAN_QUEUE_INITIAL_CAPACITY = ${resolved.orphanQueueInitialCapacity}")
        appendLine("DNS_RESPONSE_FRAGMENT_STORE_CAPACITY = ${resolved.dnsResponseFragmentStoreCapacity}")
        appendLine("MAX_ACTIVE_STREAMS = ${resolved.maxActiveStreams}")
        appendLine("LOCAL_HANDSHAKE_TIMEOUT_SECONDS = ${resolved.localHandshakeTimeoutSeconds}")
        appendLine("SOCKS_UDP_ASSOCIATE_READ_TIMEOUT_SECONDS = ${resolved.socksUdpAssociateReadTimeoutSeconds}")
        appendLine("CLIENT_TERMINAL_STREAM_RETENTION_SECONDS = ${resolved.clientTerminalStreamRetentionSeconds}")
        appendLine("CLIENT_CANCELLED_SETUP_RETENTION_SECONDS = ${resolved.clientCancelledSetupRetentionSeconds}")
        appendLine("SESSION_INIT_RETRY_BASE_SECONDS = ${resolved.sessionInitRetryBaseSeconds}")
        appendLine("SESSION_INIT_RETRY_STEP_SECONDS = ${resolved.sessionInitRetryStepSeconds}")
        appendLine("SESSION_INIT_RETRY_LINEAR_AFTER = ${resolved.sessionInitRetryLinearAfter}")
        appendLine("SESSION_INIT_RETRY_MAX_SECONDS = ${resolved.sessionInitRetryMaxSeconds}")
        appendLine("SESSION_INIT_BUSY_RETRY_INTERVAL_SECONDS = ${resolved.sessionInitBusyRetryIntervalSeconds}")
        appendLine("STARTUP_MODE = \"${escape(resolved.startupMode)}\"")
        appendLine("PING_WATCHDOG_TIMEOUT_SECONDS = ${resolved.pingWatchdogSeconds}")
        appendLine("TRAFFIC_WARMUP_ENABLED = ${resolved.trafficWarmupEnabled}")
        appendLine("TRAFFIC_WARMUP_PROBE_COUNT = ${resolved.trafficWarmupProbeCount}")
        appendLine("TRAFFIC_KEEPALIVE_INTERVAL_SECONDS = ${resolved.trafficKeepaliveIntervalSeconds}")
        appendLine("AUTO_TUNE_ENABLED = ${resolved.autoTuneEnabled}")
        appendLine("LOG_LEVEL = \"${escape(resolved.logLevel)}\"")
    }

    private fun escape(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
    }

    private fun ConnectionProfile.toCottenDnsServerProfile(): CottenDnsServerProfile {
        val domain = customServerDomain.trim().trimEnd('.')
        val encryptionKey = customServerEncryptionKey.trim()
        if (domain.isBlank() || encryptionKey.isBlank()) {
            throw IllegalArgumentException("Custom server domain and encryption key are required to export TOML")
        }
        return CottenDnsServerProfile(
            id = id.ifBlank { "custom" },
            label = name.ifBlank { "Custom CottenDns Server" },
            domain = domain,
            encryptionKey = encryptionKey,
            encryptionMethod = customServerEncryptionMethod.coerceIn(0, 5),
            serverType = ConnectionProfile.normalizeServerType(serverType),
        )
    }
}
