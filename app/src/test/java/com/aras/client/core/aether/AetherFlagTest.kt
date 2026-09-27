package com.aras.client.core.aether

import com.aras.client.dto.entities.ProfileItem
import com.aras.client.enums.EConfigType
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The core refuses a command line it does not understand, and it refuses the whole
 * one rather than the flag it did not know: one unknown option and the process exits
 * with its help text instead of running.
 *
 * These are the flags aether 2.1.0 — the build that ships in the app — accepts. The
 * list was taken by running the real binary with each flag; two that an earlier
 * version of this app emitted, `--psiphon-server-entries` and `--psiphon-cdn-sets`,
 * are rejected by it and must not come back.
 */
class AetherFlagTest {

    /** Verified against aether 2.1.0, not taken from anyone's documentation. */
    private val accepted = setOf(
        "--bind", "--http-proxy", "--upstream", "--mark", "--exit-loc", "--exit-loc-secs",
        "--stats", "--stats-secs", "--quick-reconnect", "--no-quick-reconnect",
        "-4", "-6", "--dual", "--ip", "--peer", "--wg-peer", "--h2-peer",
        "--masque", "--wg", "--gool", "--mim", "--protocol",
        "--wiw-outer", "--wiw-inner", "--wiw-peers", "--wiw-scan",
        "--mim-outer", "--mim-inner", "--mim-peers", "--mim-scan",
        "--scan", "--turbo", "--balanced", "--thorough", "--verified", "--ironclad",
        "--noize", "--h2", "--h3", "--no-quic-v2", "--ech", "--no-data-check",
        "--validate-secs", "--startup-secs", "--reconnect-secs", "--dns",
        "--fragment", "--fragment-size", "--fragment-delay",
        "--keepalive", "--no-profile-retry",
        "--tor", "--tor-reverse", "--tor-only", "--tor-bind", "--tor-http",
        "--tor-bridge-file", "--tor-relays", "--tor-relay-ports",
        "--tor-bridges", "--no-tor-bridges", "--tor-bridge",
        "--tor-pt", "--tor-pt-dir", "--tor-dir",
        "--psiphon", "--psiphon-reverse", "--psiphon-only", "--psiphon-mode",
        "--psiphon-config", "--psiphon-cdn-ips", "--psiphon-cdn-sni", "--psiphon-bind",
        "--psiphon-http", "--psiphon-region", "--psiphon-dir", "--psiphon-bin",
        "--team", "--access-id", "--access-secret", "--access-email", "--access-token",
        "--gateway", "--route-block", "--route-direct", "--routes",
        "--config", "--wg-config", "--masque-config",
        "--tls-groups", "--perf", "--log-level", "--verbose",
    )

    /** Flags this app used to emit, which aether 2.1.0 rejects outright. */
    private val rejected = setOf("--psiphon-server-entries", "--psiphon-cdn-sets")

    private fun profile(block: ProfileItem.() -> Unit = {}) =
        ProfileItem(configType = EConfigType.AETHER).apply(block)

    private fun assertOnlyAcceptedFlags(arguments: List<String>) {
        val flags = arguments.filter { it.startsWith("-") }
        flags.forEach { flag ->
            assertTrue(
                "aether 2.1.0 rejects '$flag' and refuses the whole command line with it",
                flag in accepted,
            )
            assertTrue("'$flag' is one this app was rejected for", flag !in rejected)
        }
    }

    @Test
    fun `a plain wireguard session emits only flags the core accepts`() {
        assertOnlyAcceptedFlags(
            AetherCoreManager.buildArguments(
                profile {
                    server = "198.51.100.9"
                    serverPort = "2408"
                    aetherProtocol = "wg"
                    aetherScanMode = "balanced"
                    aetherIpVersion = "v4"
                },
                port = 10819,
            )
        )
    }

    @Test
    fun `a masque session with fragmentation emits only accepted flags`() {
        assertOnlyAcceptedFlags(
            AetherCoreManager.buildArguments(
                profile {
                    server = "198.51.100.7"
                    serverPort = "443"
                    aetherProtocol = "masque"
                    aetherTransport = "h2"
                    aetherFragment = true
                    aetherFragmentSize = "64-128"
                    aetherFragmentDelay = "10"
                    aetherEch = true
                    aetherObfuscation = "firewall"
                },
                port = 10819,
            )
        )
    }

    @Test
    fun `a psiphon profile emits only accepted flags`() {
        // The one that used to break: the server list is no longer a flag, and the
        // CDN edge lists have no flag at all in this core.
        val arguments = AetherCoreManager.buildArguments(
            profile {
                server = "198.51.100.9"
                serverPort = "2408"
                aetherProtocol = "wg"
                aetherPsiphon = "chain"
                aetherPsiphonMode = "cdn"
                aetherPsiphonCdnIps = "1.2.3.4"
                aetherPsiphonCdnSni = "example.org"
                aetherPsiphonCdnSets = "cloudflare,github"
                aetherPsiphonRegion = "DE"
            },
            port = 10819,
        )
        assertOnlyAcceptedFlags(arguments)
        assertTrue("--psiphon" in arguments)
        assertTrue("1.2.3.4" in arguments)
    }

    @Test
    fun `a tor profile emits only accepted flags`() {
        assertOnlyAcceptedFlags(
            AetherCoreManager.buildArguments(
                profile {
                    server = "198.51.100.9"
                    serverPort = "2408"
                    aetherProtocol = "masque"
                    aetherTor = "chain"
                    aetherTorBridges = "own"
                    aetherTorBridgeLines = "obfs4 1.2.3.4:443"
                    aetherTorRelays = "only"
                },
                port = 10819,
            )
        )
    }

    @Test
    fun `a two hop tunnel emits only accepted flags`() {
        listOf("gool", "mim").forEach { protocol ->
            assertOnlyAcceptedFlags(
                AetherCoreManager.buildArguments(
                    profile {
                        aetherProtocol = protocol
                        aetherWiwOuter = "198.51.100.1:2408"
                        aetherWiwInner = "198.51.100.2:2408"
                    },
                    port = 10819,
                )
            )
        }
    }

    @Test
    fun `a scan emits only accepted flags`() {
        assertOnlyAcceptedFlags(
            AetherCoreManager.buildArguments(
                profile {
                    server = "198.51.100.9"
                    serverPort = "2408"
                    aetherProtocol = "wg"
                    aetherExitLoc = "DE"
                    aetherDns = "1.1.1.1"
                },
                port = 10819,
                scan = true,
            )
        )
    }
}
