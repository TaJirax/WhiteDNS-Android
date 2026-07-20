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
            fastConnectEnabled = true,
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
        assertTrue(toml.contains("FAST_CONNECT = true"))
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
        assertFalse(toml.contains("FAST_CONNECT"))
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
    fun renderScanClientTomlDisablesLocalListenersAndUsesConfiguredProbeParallelism() {
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
                mtuTestParallelismResolvers = "100",
                scanResolverParallelism = "50",
                startupMode = "logs",
                trafficWarmupEnabled = true,
            ),
        )

        assertTrue(toml.contains("LISTEN_PORT = 0"))
        assertTrue(toml.contains("LOCAL_DNS_ENABLED = false"))
        assertTrue(toml.contains("LOCAL_DNS_PORT = 0"))
        // Scanner uses the dedicated scanResolverParallelism, not the connect-time value.
        assertTrue(toml.contains("MTU_TEST_PARALLELISM_RESOLVERS = 50"))
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
    fun renderClientTomlKeepsFastConnectForStormMasterCompatibilityProfiles() {
        val toml = CottenDnsConfigRenderer.renderClientToml(
            serverProfile = shop.whitedns.client.model.CottenDnsServerProfile(
                id = "server",
                label = "Storm Master",
                domain = "legacy.example.com",
                encryptionKey = "secret-key",
                encryptionMethod = 1,
                serverType = ConnectionProfile.ServerTypeCompatibility,
            ),
            settings = WhiteDnsSettings(fastConnectEnabled = true),
        )

        assertTrue(toml.contains("LEGACY_SESSION_ID = true"))
        assertTrue(toml.contains("RESOLVER_TRANSPORT = \"udp\""))
        assertTrue(toml.contains("QUERY_TYPES = [\"TXT\"]"))
        assertTrue(toml.contains("MTU_ADAPTIVE_GROUPING = false"))
        assertTrue(toml.contains("FAST_CONNECT = true"))
    }

    private fun encryptedServerProfile() = shop.whitedns.client.model.CottenDnsServerProfile(
        id = "server",
        label = "Encrypted",
        domain = "tunnel.example.com, alt.example.com",
        encryptionKey = "secret-key",
        encryptionMethod = 3,
    )

    // A public resolver (Cloudflare, Google, Quad9) is reached at its own IP and
    // its certificate carries that IP as a SAN, so no hostname must be emitted by
    // default. Sending the tunnel domain as SNI to a public resolver would fail
    // verification and silently fall back to UDP/TCP.
    @Test
    fun renderClientTomlOmitsServerNameSoPublicResolversVerifyByTheirOwnIdentity() {
        listOf("dot", "doh").forEach { mode ->
            val toml = CottenDnsConfigRenderer.renderClientToml(
                serverProfile = encryptedServerProfile(),
                settings = WhiteDnsSettings(transportMode = mode),
            )

            assertTrue(toml.contains("RESOLVER_TRANSPORT = \"$mode\""))
            assertTrue(!toml.contains("RESOLVER_TLS_SERVER_NAME"))
        }
    }

    @Test
    fun renderClientTomlEmitsOnlyTheSelectedEncryptedTransportKeys() {
        val dot = CottenDnsConfigRenderer.renderClientToml(
            serverProfile = encryptedServerProfile(),
            settings = WhiteDnsSettings(transportMode = "dot", resolverDoTPort = "8853"),
        )
        assertTrue(dot.contains("RESOLVER_DOT_PORT = 8853"))
        assertTrue(!dot.contains("RESOLVER_DOH_PORT"))
        assertTrue(!dot.contains("RESOLVER_DOH_PATH"))

        val doh = CottenDnsConfigRenderer.renderClientToml(
            serverProfile = encryptedServerProfile(),
            settings = WhiteDnsSettings(
                transportMode = "doh",
                resolverDoHPort = "8443",
                resolverDoHPath = "resolve",
            ),
        )
        assertTrue(doh.contains("RESOLVER_DOH_PORT = 8443"))
        // A path typed without the leading slash is still emitted as a valid path.
        assertTrue(doh.contains("RESOLVER_DOH_PATH = \"/resolve\""))
        assertTrue(!doh.contains("RESOLVER_DOT_PORT"))
    }

    @Test
    fun renderClientTomlPrefersExplicitServerNameAndEmitsPinWhenSet() {
        val toml = CottenDnsConfigRenderer.renderClientToml(
            serverProfile = encryptedServerProfile(),
            settings = WhiteDnsSettings(
                transportMode = "doh",
                resolverTlsServerName = "Doh.Example.Com.",
                resolverTlsPin = "abc123",
            ),
        )

        assertTrue(toml.contains("RESOLVER_TLS_SERVER_NAME = \"doh.example.com\""))
        assertTrue(toml.contains("RESOLVER_TLS_PIN = \"abc123\""))
    }

    @Test
    fun renderClientTomlOmitsPinWhenUnset() {
        val toml = CottenDnsConfigRenderer.renderClientToml(
            serverProfile = encryptedServerProfile(),
            settings = WhiteDnsSettings(transportMode = "dot"),
        )

        assertTrue(!toml.contains("RESOLVER_TLS_PIN"))
    }

    @Test
    fun renderClientTomlOmitsEncryptedResolverNameForPlainTransports() {
        listOf("udp", "tcp", "auto").forEach { mode ->
            val toml = CottenDnsConfigRenderer.renderClientToml(
                serverProfile = shop.whitedns.client.model.CottenDnsServerProfile(
                    id = "server",
                    label = "Plain",
                    domain = "tunnel.example.com",
                    encryptionKey = "secret-key",
                    encryptionMethod = 3,
                ),
                settings = WhiteDnsSettings(transportMode = mode),
            )

            assertTrue(toml.contains("RESOLVER_TRANSPORT = \"$mode\""))
            assertTrue(!toml.contains("RESOLVER_TLS_SERVER_NAME"))
        }
    }

    @Test
    fun renderClientTomlKeepsFastConnectForMasterStormPreset() {
        val toml = CottenDnsConfigRenderer.renderClientToml(
            serverProfile = shop.whitedns.client.model.CottenDnsServerProfile(
                id = "server",
                label = "Master Storm",
                domain = "master.example.com",
                encryptionKey = "secret-key",
                encryptionMethod = 1,
                serverType = ConnectionProfile.ServerTypeCompatibility,
            ),
            settings = WhiteDnsSettings(
                configPreset = "master-storm",
                fastConnectEnabled = true,
            ),
        )

        assertTrue(toml.contains("CONFIG_PRESET = \"default\""))
        assertTrue(toml.contains("LEGACY_SESSION_ID = true"))
        assertTrue(toml.contains("RESOLVER_TRANSPORT = \"udp\""))
        assertTrue(toml.contains("QUERY_TYPES = [\"TXT\"]"))
        assertTrue(toml.contains("FAST_CONNECT = true"))
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
        assertTrue(cottenDnsToml.contains("ADAPTIVE_DUPLICATION = true"))
        assertTrue(cottenDnsToml.contains("DUPLICATION_PREFER_DISTINCT_DOMAINS = true"))
        assertTrue(cottenDnsToml.contains("DNS_EDNS_COOKIE = true"))
        // Legacy Master/Storm uses the classic single-probe, single-MTU scan.
        assertTrue(compatibilityToml.contains("MTU_ADAPTIVE_GROUPING = false"))
        assertTrue(compatibilityToml.contains("MTU_PROBE_SAMPLES = 1"))
        assertTrue(cottenDnsToml.contains("QUERY_TYPES = [\"TXT\", \"CNAME\", \"HTTPS\", \"A\"]"))
        // A CottenDns preset must not leak into the compatibility path. Safe
        // client-side protections stay enabled, while traffic-amplifying and
        // wire-changing native features are explicitly disabled.
        assertTrue(compatibilityToml.contains("CONFIG_PRESET = \"default\""))
        assertTrue(compatibilityToml.contains("QNAME_LABEL_LENGTH = 63"))
        assertTrue(compatibilityToml.contains("RESOLVER_TRANSPORT = \"udp\""))
        assertTrue(compatibilityToml.contains("QUERY_TYPES = [\"TXT\"]"))
        assertTrue(compatibilityToml.contains("ADAPTIVE_DUPLICATION = false"))
        assertTrue(compatibilityToml.contains("DUPLICATION_PREFER_DISTINCT_DOMAINS = false"))
        assertTrue(compatibilityToml.contains("DNS_EDNS_COOKIE = false"))
        assertTrue(compatibilityToml.contains("RESOLVER_RATE_LIMIT_ENABLED = true"))
        assertTrue(compatibilityToml.contains("DNS_RANDOMIZE_QUERY_ID = true"))
        assertTrue(compatibilityToml.contains("RESOLVER_IGNORE_INJECTED_NXDOMAIN = true"))
    }
}
