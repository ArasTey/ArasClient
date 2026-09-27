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

    private fun List<String>.valueOf(flag: String): String? {
        val index = indexOf(flag)
        return if (index < 0 || index + 1 >= size) null else this[index + 1]
    }
}
