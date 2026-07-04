package shop.whitedns.client.cottendns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import shop.whitedns.client.model.ConnectionProfile
import shop.whitedns.client.model.ResolverProfile
import shop.whitedns.client.model.WhiteDnsSettings
import shop.whitedns.client.model.importAdvancedSettingsProfileFromToml

class CottenDnsConfigRendererTest {
    @Test
    fun renderClientTomlFromConnectionProfileIncludesCompleteServerInfo() {
        val resolverProfile = ResolverProfile(
            id = "resolver-main",
            name = "Main",
            resolverText = "1.1.1.1",
        )
        val connectionProfile = ConnectionProfile(
            id = "profile-main",
            name = "Main",
            customServerDomain = "server.example.com.",
            customServerEncryptionKey = "secret-key",
            customServerEncryptionMethod = 5,
            resolverProfileId = resolverProfile.id,
            connectionMode = "proxy",
        )
        val settings = WhiteDnsSettings(
            connectionProfiles = listOf(connectionProfile),
            resolverProfiles = listOf(resolverProfile),
            listenPort = "12345",
            httpProxyEnabled = false,
            uploadDuplication = "4",
            logLevel = "INFO",
        )

        val toml = CottenDnsConfigRenderer.renderClientToml(
            connectionProfile = connectionProfile,
            settings = settings,
        )

        assertTrue(toml.contains("""DOMAINS = ["server.example.com"]"""))
        assertTrue(toml.contains("DATA_ENCRYPTION_METHOD = 5"))
        assertTrue(toml.contains("ENCRYPTION_KEY = \"secret-key\""))
        assertTrue(toml.contains("LISTEN_PORT = 12345"))
        assertTrue(toml.contains("UPLOAD_PACKET_DUPLICATION_COUNT = 4"))
        assertTrue(toml.contains("STATS_REPORT_INTERVAL_SECONDS = 1.0"))
        assertTrue(toml.contains("LOG_LEVEL = \"INFO\""))
    }

    @Test
    fun renderAdvancedSettingsTomlExportsSettingsWithoutServerInfo() {
        val settings = WhiteDnsSettings(
            customServerDomain = "server.example.com",
            customServerEncryptionKey = "secret-key",
            listenIp = "0.0.0.0",
            listenPort = "12345",
            httpProxyEnabled = false,
            httpProxyPort = "12346",
            uploadDuplication = "4",
            tunnelPacketTimeoutSeconds = "11.5",
            trafficWarmupEnabled = false,
            trafficWarmupProbeCount = "2",
            autoTuneEnabled = true,
            logLevel = "INFO",
        )

        val toml = CottenDnsConfigRenderer.renderAdvancedSettingsToml(settings)

        assertTrue(toml.contains("LISTEN_IP = \"0.0.0.0\""))
        assertTrue(toml.contains("LISTEN_PORT = 12345"))
        assertTrue(toml.contains("HTTP_PROXY_ENABLED = false"))
        assertTrue(toml.contains("HTTP_PROXY_PORT = 12346"))
        assertTrue(toml.contains("UPLOAD_PACKET_DUPLICATION_COUNT = 4"))
        assertTrue(toml.contains("TUNNEL_PACKET_TIMEOUT_SECONDS = 11.5"))
        assertTrue(toml, toml.contains("TRAFFIC_WARMUP_ENABLED = false"))
        assertTrue(toml.contains("TRAFFIC_WARMUP_PROBE_COUNT = 2"))
        assertTrue(toml.contains("AUTO_TUNE_ENABLED = true"))
        assertTrue(toml.contains("LOG_LEVEL = \"INFO\""))
        assertFalse(toml.contains("DOMAINS"))
        assertFalse(toml.contains("DATA_ENCRYPTION_METHOD"))
        assertFalse(toml.contains("ENCRYPTION_KEY"))
        assertFalse(toml.contains("server.example.com"))
        assertFalse(toml.contains("secret-key"))

        val imported = WhiteDnsSettings().importAdvancedSettingsProfileFromToml("Imported", toml)
        assertEquals("12345", imported.listenPort)
        assertEquals(false, imported.httpProxyEnabled)
        assertEquals("4", imported.uploadDuplication)
        assertEquals("11.5", imported.tunnelPacketTimeoutSeconds)
        assertEquals(false, imported.trafficWarmupEnabled)
        assertEquals(true, imported.autoTuneEnabled)
    }

    @Test
    fun renderScanClientTomlDisablesLocalListenersAndUsesSingleProbeWorker() {
        val toml = CottenDnsConfigRenderer.renderScanClientToml(
            serverProfile = shop.whitedns.client.model.CottenDnsServerProfile(
                id = "server",
                label = "Server",
                domain = "scan.example.com",
                encryptionKey = "secret-key",
                encryptionMethod = 1,
                serverType = ConnectionProfile.ServerTypeCottenDns,
            ),
            settings = WhiteDnsSettings(
                listenPort = "10886",
                localDnsEnabled = true,
                localDnsPort = "10888",
                mtuTestParallelismResolvers = "50",
                startupMode = "logs",
                trafficWarmupEnabled = true,
            ),
        )

        assertTrue(toml.contains("LISTEN_PORT = 0"))
        assertTrue(toml.contains("LOCAL_DNS_ENABLED = false"))
        assertTrue(toml.contains("LOCAL_DNS_PORT = 0"))
        assertTrue(toml.contains("MTU_TEST_PARALLELISM_RESOLVERS = 1"))
        assertTrue(toml.contains("STARTUP_MODE = \"resolvers\""))
        assertTrue(toml.contains("LEGACY_SESSION_ID = false"))
        assertTrue(toml.contains("RESOLVER_TRANSPORT = \"auto\""))
        assertTrue(toml.contains("QUERY_TYPES = [\"TXT\"]"))
        assertTrue(toml.contains("DNS_RANDOMIZE_QUERY_ID = true"))
        assertTrue(toml.contains("DNS_EDNS_COOKIE = true"))
        assertTrue(toml.contains("RESOLVER_IGNORE_INJECTED_NXDOMAIN = true"))
        assertTrue(toml.contains("QNAME_LABEL_LENGTH = 63"))
        assertTrue(toml.contains("MTU_PROBE_SAMPLES = 1"))
        assertTrue(toml.contains("MTU_MAX_LOSS = 0.0"))
        assertTrue(toml.contains("MTU_ADAPTIVE_GROUPING = true"))
        assertTrue(toml.contains("MTU_GROUP_GAP_RATIO = 0.25"))
    }

    @Test
    fun renderScanClientTomlKeepsCottenDnsCompatibilityScanTxtOnly() {
        val toml = CottenDnsConfigRenderer.renderScanClientToml(
            serverProfile = shop.whitedns.client.model.CottenDnsServerProfile(
                id = "server",
                label = "Server",
                domain = "legacy.example.com",
                encryptionKey = "secret-key",
                encryptionMethod = 1,
                serverType = ConnectionProfile.ServerTypeCompatibility,
            ),
            settings = WhiteDnsSettings(),
        )

        assertTrue(toml.contains("LEGACY_SESSION_ID = true"))
        assertTrue(toml.contains("RESOLVER_TRANSPORT = \"udp\""))
        // Compatibility now emits an explicit TXT-only delivery set (safe for
        // legacy MasterDNS/StormDNS servers) rather than omitting QUERY_TYPES.
        assertTrue(toml.contains("QUERY_TYPES = [\"TXT\"]"))
    }
    @Test
    fun renderClientTomlAppliesTcpSurvivalPresetToRuntimeKeys() {
        val toml = CottenDnsConfigRenderer.renderClientToml(
            serverProfile = shop.whitedns.client.model.CottenDnsServerProfile(
                id = "server",
                label = "Server",
                domain = "tcp.example.com",
                encryptionKey = "secret-key",
                encryptionMethod = 1,
                serverType = ConnectionProfile.ServerTypeCottenDns,
            ),
            settings = WhiteDnsSettings(configPreset = "tcp-survival"),
        )

        assertTrue(toml.contains("CONFIG_PRESET = \"tcp-survival\""))
        assertTrue(toml.contains("RESOLVER_TRANSPORT = \"tcp\""))
        assertTrue(toml.contains("QUERY_TYPES = [\"TXT\", \"HTTPS\"]"))
        assertTrue(toml.contains("MTU_PROBE_SAMPLES = 1"))
    }

    @Test
    fun renderClientTomlAppliesSurvivalPresetShapeWithoutChangingCompatibilityMode() {
        val cottenDnsToml = CottenDnsConfigRenderer.renderClientToml(
            serverProfile = shop.whitedns.client.model.CottenDnsServerProfile(
                id = "server",
                label = "Server",
                domain = "survival.example.com",
                encryptionKey = "secret-key",
                encryptionMethod = 1,
                serverType = ConnectionProfile.ServerTypeCottenDns,
            ),
            settings = WhiteDnsSettings(configPreset = "survival"),
        )
        val compatibilityToml = CottenDnsConfigRenderer.renderClientToml(
            serverProfile = shop.whitedns.client.model.CottenDnsServerProfile(
                id = "server",
                label = "Server",
                domain = "legacy.example.com",
                encryptionKey = "secret-key",
                encryptionMethod = 1,
                serverType = ConnectionProfile.ServerTypeCompatibility,
            ),
            settings = WhiteDnsSettings(configPreset = "survival"),
        )

        assertTrue(cottenDnsToml.contains("CONFIG_PRESET = \"survival\""))
        assertTrue(cottenDnsToml.contains("QNAME_LABEL_LENGTH = 42"))
        assertTrue(cottenDnsToml.contains("EDNS_UDP_SIZE = 1232"))
        assertTrue(cottenDnsToml.contains("MTU_MAX_LOSS = 0.5"))
        // Legacy Master/Storm uses the classic single-probe, single-MTU scan.
        assertTrue(compatibilityToml.contains("MTU_ADAPTIVE_GROUPING = false"))
        assertTrue(compatibilityToml.contains("MTU_PROBE_SAMPLES = 1"))
        assertTrue(cottenDnsToml.contains("QUERY_TYPES = [\"TXT\", \"CNAME\", \"HTTPS\", \"A\"]"))
        // The server-transparent preset shape (EDNS, MTU) applies to the
        // compatibility path too, while the generation-sensitive delivery and
        // transport are forced to the safe TXT/UDP subset. QNAME reshaping is
        // forced off (classic 63-char labels) in compatibility mode to guarantee
        // connectivity with unverified legacy MasterDNS variants.
        assertTrue(compatibilityToml.contains("CONFIG_PRESET = \"survival\""))
        assertTrue(compatibilityToml.contains("QNAME_LABEL_LENGTH = 63"))
        assertTrue(compatibilityToml.contains("RESOLVER_TRANSPORT = \"udp\""))
        assertTrue(compatibilityToml.contains("QUERY_TYPES = [\"TXT\"]"))
    }
}
