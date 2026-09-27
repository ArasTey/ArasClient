package com.aras.client.core.aether

import com.aras.client.dto.entities.ProfileItem
import com.aras.client.enums.AetherProtocol
import com.aras.client.enums.EConfigType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The command line an aether core is started with.
 *
 * These are the arguments a real profile produces; the core reads them, so a wrong
 * flag name here would be a silent failure at connect time rather than a compile error.
 */
class AetherCoreTest {

    private fun profile(block: ProfileItem.() -> Unit = {}) =
        ProfileItem(configType = EConfigType.AETHER).apply(block)

    @Test
    fun `a wg session carries its peer, scan mode and quick reconnect`() {
        val args = AetherCoreManager.buildArguments(
            profile {
                server = "198.51.100.9"
                serverPort = "2408"
                aetherProtocol = "wg"
                aetherScanMode = "thorough"
                aetherIpVersion = "v4"
            },
            port = 10819,
        )

        assertEquals("127.0.0.1:10819", args.valueOf("--bind"))
        assertEquals("wg", args.valueOf("--protocol"))
        assertEquals("thorough", args.valueOf("--scan"))
        assertEquals("v4", args.valueOf("--ip"))
        assertEquals("198.51.100.9:2408", args.valueOf("--peer"))
        assertTrue("--quick-reconnect" in args)
        assertTrue("--no-quick-reconnect" !in args)
    }

    @Test
    fun `a scan drops the peer and quick reconnect`() {
        val args = AetherCoreManager.buildArguments(
            profile {
                server = "198.51.100.9"
                serverPort = "2408"
                aetherProtocol = "wg"
            },
            port = 10819,
            scan = true,
        )

        assertNull(args.valueOf("--peer"))
        assertTrue("--no-quick-reconnect" in args)
    }

    @Test
    fun `automatic obfuscation says nothing, an explicit one is passed`() {
        val auto = AetherCoreManager.buildArguments(profile { aetherObfuscation = "auto" }, 10819)
        assertNull(auto.valueOf("--noize"))

        val firewall = AetherCoreManager.buildArguments(
            profile { aetherObfuscation = "firewall" }, 10819
        )
        assertEquals("firewall", firewall.valueOf("--noize"))
    }

    @Test
    fun `an ipv6 peer is bracketed the way the core reads it`() {
        val args = AetherCoreManager.buildArguments(
            profile {
                server = "2606:4700:d1::a29f:c0d1"
                serverPort = "928"
                aetherProtocol = "wg"
            },
            port = 10819,
        )

        assertEquals("[2606:4700:d1::a29f:c0d1]:928", args.valueOf("--peer"))
    }

    @Test
    fun `masque over h2 with fragmentation passes its own flags`() {
        val args = AetherCoreManager.buildArguments(
            profile {
                server = "198.51.100.7"
                serverPort = "443"
                aetherProtocol = "masque"
                aetherTransport = "h2"
                aetherFragment = true
                aetherFragmentSize = "64-128"
                aetherFragmentDelay = "10"
                aetherEch = true
            },
            port = 10819,
        )

        assertTrue("--h2" in args)
        assertTrue("--fragment" in args)
        assertEquals("64-128", args.valueOf("--fragment-size"))
        assertEquals("10", args.valueOf("--fragment-delay"))
        assertEquals("auto", args.valueOf("--ech"))
    }

    @Test
    fun `masque over h3 leaves the h2 flags out`() {
        val args = AetherCoreManager.buildArguments(
            profile {
                server = "198.51.100.7"
                serverPort = "443"
                aetherProtocol = "masque"
                aetherTransport = "h3"
            },
            port = 10819,
        )

        assertTrue("--h2" !in args)
        assertTrue("--fragment" !in args)
    }

    @Test
    fun `a two hop tunnel names both hops, or asks for a scan when it has neither`() {
        val named = AetherCoreManager.buildArguments(
            profile {
                aetherProtocol = "gool"
                aetherWiwOuter = "198.51.100.1:2408"
                aetherWiwInner = "198.51.100.2:2408"
            },
            port = 10819,
        )
        assertEquals("198.51.100.1:2408", named.valueOf("--wiw-outer"))
        assertEquals("198.51.100.2:2408", named.valueOf("--wiw-inner"))
        assertTrue("--wiw-scan" !in named)

        val empty = AetherCoreManager.buildArguments(
            profile { aetherProtocol = "gool" }, port = 10819
        )
        assertTrue("--wiw-scan" in empty)

        val mim = AetherCoreManager.buildArguments(
            profile { aetherProtocol = "mim" }, port = 10819
        )
        assertTrue("--mim-scan" in mim)
    }

    @Test
    fun `the listen port moves for a test without disturbing the other flags`() {
        val core = AetherCore(
            AetherCoreManager.buildArguments(
                profile {
                    server = "198.51.100.9"
                    serverPort = "2408"
                    aetherProtocol = "wg"
                },
                port = 10819,
            )
        )

        val moved = core.on(10999)

        assertEquals(10999, moved.port)
        assertEquals(10819, core.port)
    }

    @Test
    fun `a core reports the protocol it dials`() {
        val core = AetherCore(
            AetherCoreManager.buildArguments(profile { aetherProtocol = "masque" }, 10819)
        )

        assertEquals(AetherProtocol.MASQUE, core.protocol)
        assertTrue(core.command.startsWith("aether "))
    }

    @Test
    fun `a profile's own command is read back in place of its settings`() {
        val handWritten = "aether --bind 127.0.0.1:10825 --protocol wg --scan turbo --ip v4"
        val core = AetherCore.of(profile { aetherCommand = handWritten })

        assertEquals(10825, core?.port)
        assertEquals(handWritten, core?.command)
    }

    @Test
    fun `the listen port is read out of and written back into the arguments`() {
        val args = AetherCoreManager.buildArguments(profile(), 10833)

        assertEquals(10833, AetherCoreManager.listenerPortOf(args))
        assertEquals(
            "10840",
            AetherCoreManager.withListener(args, 10840).valueOf("--bind")?.substringAfterLast(':'),
        )
        assertNull(AetherCoreManager.listenerPortOf(listOf("--protocol", "wg")))
    }

    @Test
    fun `the log level option is stripped for a core that keeps its own`() {
        val args = AetherCoreManager.buildArguments(profile(), 10819, logLevel = "debug")

        assertEquals("debug", args.valueOf("--log-level"))
        assertTrue("--log-level" !in AetherCoreManager.withoutOption(args, "--log-level"))
    }

    @Test
    fun `the app log level maps onto the levels the core accepts`() {
        assertEquals("debug", AetherCoreManager.coreLogLevel("debug"))
        assertEquals("warn", AetherCoreManager.coreLogLevel("warning"))
        assertEquals("error", AetherCoreManager.coreLogLevel("error"))
        assertEquals("info", AetherCoreManager.coreLogLevel("nonsense"))
    }

    @Test
    fun `psiphon inside the tunnel takes over the port the app dials`() {
        val args = AetherCoreManager.buildArguments(
            profile {
                server = "198.51.100.9"
                serverPort = "2408"
                aetherProtocol = "wg"
                aetherPsiphon = "chain"
            },
            port = 10819,
        )

        // The app reaches Psiphon, so Psiphon takes the dialed port and the tunnel's
        // own listener moves up to the next one.
        assertEquals("127.0.0.1:10820", args.valueOf("--bind"))
        assertEquals("127.0.0.1:10819", args.valueOf("--psiphon-bind"))
        assertTrue("--psiphon" in args)
    }

    @Test
    fun `psiphon around the tunnel takes an ephemeral port of its own`() {
        val args = AetherCoreManager.buildArguments(
            profile {
                server = "198.51.100.9"
                serverPort = "2408"
                aetherProtocol = "wg"
                aetherPsiphon = "reverse"
            },
            port = 10819,
        )

        assertEquals("127.0.0.1:10819", args.valueOf("--bind"))
        assertEquals("127.0.0.1:0", args.valueOf("--psiphon-bind"))
        assertTrue("--psiphon-reverse" in args)
    }

    @Test
    fun `tor inside the tunnel takes the dialed port and the tunnel the next`() {
        val args = AetherCoreManager.buildArguments(
            profile {
                server = "198.51.100.9"
                serverPort = "2408"
                aetherProtocol = "wg"
                aetherTor = "chain"
            },
            port = 10819,
        )

        assertEquals("127.0.0.1:10820", args.valueOf("--bind"))
        assertEquals("127.0.0.1:10819", args.valueOf("--tor-bind"))
        assertTrue("--tor" in args)
    }

    @Test
    fun `tor bridges are passed only as their mode asks`() {
        fun bridgesFor(value: String) = AetherCoreManager.buildArguments(
            profile {
                aetherProtocol = "wg"
                aetherTor = "chain"
                aetherTorBridges = value
                aetherTorRelays = "only"
            },
            port = 10819,
        )

        // Told nothing, the core tries Tor plainly and turns to bridges later.
        assertTrue("--tor-bridges" !in bridgesFor("auto"))
        assertTrue("--no-tor-bridges" !in bridgesFor("auto"))
        assertTrue("--tor-relays" in bridgesFor("auto"))

        assertTrue("--tor-bridges" in bridgesFor("first"))
        assertTrue("--no-tor-bridges" in bridgesFor("never"))

        // With the profile's own lines, nothing is fetched at all.
        val own = AetherCoreManager.buildArguments(
            profile {
                aetherProtocol = "wg"
                aetherTor = "chain"
                aetherTorBridges = "own"
                aetherTorBridgeLines = "obfs4 1.2.3.4:443\n# a comment\nBridge snowflake 5.6.7.8:443"
            },
            port = 10819,
        )
        assertTrue("--tor-bridge" in own)
        assertTrue("--tor-relays" !in own)
    }

    @Test
    fun `psiphon-only and tor-only dial no tunnel at all`() {
        val psiphonOnly = AetherCoreManager.buildArguments(
            profile { aetherPsiphon = "only" }, port = 10819
        )
        assertTrue("--psiphon-only" in psiphonOnly)
        assertTrue("--protocol" !in psiphonOnly)
        assertTrue("--scan" !in psiphonOnly)

        val torOnly = AetherCoreManager.buildArguments(profile { aetherTor = "only" }, port = 10819)
        assertTrue("--tor-only" in torOnly)
        assertTrue("--protocol" !in torOnly)
    }

    @Test
    fun `the psiphon mode drops the fronting lists the direct shape never uses`() {
        val direct = AetherCoreManager.buildArguments(
            profile {
                aetherProtocol = "masque"
                aetherPsiphon = "chain"
                aetherPsiphonMode = "direct"
                aetherPsiphonCdnIps = "1.2.3.4"
                aetherPsiphonCdnSets = "cloudflare,github"
            },
            port = 10819,
        )
        assertEquals("direct", direct.valueOf("--psiphon-mode"))
        assertNull(direct.valueOf("--psiphon-cdn-ips"))

        val cdn = AetherCoreManager.buildArguments(
            profile {
                aetherProtocol = "masque"
                aetherPsiphon = "chain"
                aetherPsiphonMode = "cdn"
                aetherPsiphonCdnIps = "1.2.3.4"
                aetherPsiphonCdnSets = "cloudflare,github"
            },
            port = 10819,
        )
        assertEquals("1.2.3.4", cdn.valueOf("--psiphon-cdn-ips"))
        // aether 2.1 has no flag for the edge lists; --psiphon-mode and the client's
        // own built-in list decide. The profile keeps the field for link round-trip.
        assertTrue(cdn.none { it.startsWith("--psiphon-cdn-sets") })
    }

    @Test
    fun `the server list is never a flag the core would reject`() {
        // aether 2.1 takes the list through its psiphon config, not a flag, and
        // refuses the whole command line over one it does not know.
        val arguments = AetherCoreManager.buildArguments(
            profile {
                aetherProtocol = "wg"
                aetherPsiphon = "chain"
                aetherPsiphonBundledList = null
            },
            port = 10819,
        )
        assertTrue("--psiphon-server-entries" !in arguments)
        assertTrue("shipped-list" !in arguments)
    }

    @Test
    fun `a scan keeps a reverse carrier and drops one inside the tunnel`() {
        // A scan looks for WARP endpoints from where the session will, so a carrier
        // around the tunnel stays and one inside it has no part in it.
        val around = AetherCoreManager.buildArguments(
            profile {
                aetherProtocol = "wg"
                aetherPsiphon = "reverse"
                aetherTor = "reverse"
            },
            port = 10819, scan = true,
        )
        assertTrue("--psiphon-reverse" in around)
        assertTrue("--tor-reverse" in around)

        val inside = AetherCoreManager.buildArguments(
            profile {
                aetherProtocol = "wg"
                aetherPsiphon = "chain"
            },
            port = 10819, scan = true,
        )
        assertTrue("--psiphon" !in inside)
    }

    private fun List<String>.valueOf(flag: String): String? {
        val index = indexOf(flag)
        return if (index < 0 || index + 1 >= size) null else this[index + 1]
    }
}