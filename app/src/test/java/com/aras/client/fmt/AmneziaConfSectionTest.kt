package com.aras.client.fmt

import com.aras.client.core.CoreOutboundBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A real AmneziaVPN `.conf` export, shaped exactly the way the app writes one.
 *
 * The obfuscation parameters live under **[Interface]**, because they are
 * device-level: the UAPI parser is in peer context after the first
 * `public_key=` line and rejects them there. The parser used to read only
 * `[Peer]`, so it found nothing, `applyDefaultJunkParams` substituted
 * AmneziaWG's stock values, and the server dropped every packet — the tunnel
 * established, the UI reported success, and nothing passed. Nothing surfaced
 * it because the defaults are valid-looking numbers, and because the project
 * had never been run on a device.
 *
 * The parameters travel to the core on the peer of the WireGuard outbound,
 * which is where the core lifts them back to device level when it writes the
 * UAPI — so these tests read them off the outbound the app builds.
 */
class AmneziaConfSectionTest {

    /** The layout AmneziaVPN produces: junk params under [Interface], peer keys under [Peer]. */
    private val exportedConf = """
        [Interface]
        PrivateKey = 8GYMxCC+QxurPSDGDrTnm2HmDROdYL5ZjYD3mBrnpEQ=
        Address = 172.16.0.2/32, 2606:4700:110:8c08:e42e:f0a6:3aa6:bbce/128
        DNS = 1.1.1.1, 1.0.0.1
        MTU = 1280
        Jc = 8
        Jmin = 49
        Jmax = 76
        S1 = 0
        S2 = 0
        S3 = 0
        S4 = 0
        H1 = 1
        H2 = 2
        H3 = 3
        H4 = 4

        [Peer]
        PublicKey = bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=
        AllowedIPs = 0.0.0.0/0, ::/0
        Endpoint = 188.114.99.151:2408
        PersistentKeepalive = 25
    """.trimIndent()

    private fun parsed() = AmneziawgFmt.parseAmneziaConfFile(exportedConf)

    private fun peer() = CoreOutboundBuilder.buildAmneziawgOutbound(parsed(), ipv6Enabled = false)
        ?.settings?.peers?.firstOrNull()

    @Test
    fun `obfuscation params are read from the interface section`() {
        val config = parsed()

        assertEquals("8", config.junkPacketCount)
        assertEquals("49", config.junkPacketMinSize)
        assertEquals("76", config.junkPacketMaxSize)
        assertEquals("0", config.initPacketJunkSize)
        assertEquals("0", config.responsePacketJunkSize)
        assertEquals("1", config.initPacketJunkHeader)
        assertEquals("2", config.responsePacketJunkHeader)
        assertEquals("3", config.cookiePacketJunkHeader)
        assertEquals("4", config.transportPacketJunkHeader)
    }

    @Test
    fun `a zero junk size survives instead of falling back to a default`() {
        // S1 = 0 and S2 = 0 are meaningful: they disable the handshake padding.
        // Treating them as "unset" is what silently re-enabled padding and broke
        // the handshake against a server configured with them off.
        val config = parsed()

        assertEquals("0", config.initPacketJunkSize)
        assertEquals("0", config.responsePacketJunkSize)
    }

    @Test
    fun `the exported values are not replaced by amneziawg defaults`() {
        val peer = peer()

        assertNotNull(peer)
        assertEquals(8, peer!!.junkPacketCount)
        assertEquals(49, peer.junkPacketMinSize)
        assertEquals(76, peer.junkPacketMaxSize)
        assertEquals(0, peer.initPacketJunkSize)
        assertEquals(0, peer.responsePacketJunkSize)
        // The defaults that used to be substituted in.
        assertTrue(peer.junkPacketCount != 4)
        assertTrue(peer.initPacketJunkSize != 15)
    }

    @Test
    fun `obfuscation params under the peer section are still accepted`() {
        val conf = """
            [Interface]
            PrivateKey = 8GYMxCC+QxurPSDGDrTnm2HmDROdYL5ZjYD3mBrnpEQ=
            Address = 172.16.0.2/32

            [Peer]
            PublicKey = bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=
            Jc = 3
            Jmin = 10
            Endpoint = 188.114.99.151:2408
        """.trimIndent()

        val config = AmneziawgFmt.parseAmneziaConfFile(conf)

        assertEquals("3", config.junkPacketCount)
        assertEquals("10", config.junkPacketMinSize)
    }

    @Test
    fun `the local address reaches the core as the tunnel address`() {
        val outbound = CoreOutboundBuilder.buildAmneziawgOutbound(parsed(), ipv6Enabled = false)

        val address = outbound?.settings?.address as? List<*>

        assertNotNull("expected a list of local addresses", address)
        assertTrue("expected the tunnel address, got $address",
            requireNotNull(address).contains("172.16.0.2/32"))
    }

    @Test
    fun `persistent keepalive is parsed and sent`() {
        assertEquals("25", parsed().keepAlive)

        assertEquals(25, peer()?.keepAlive)
    }

    @Test
    fun `a profile with no keepalive falls back to the default interval`() {
        val config = parsed().apply { keepAlive = null }

        val outbound = CoreOutboundBuilder.buildAmneziawgOutbound(config, ipv6Enabled = false)

        assertEquals(25, outbound?.settings?.peers?.firstOrNull()?.keepAlive)
    }

    @Test
    fun `the endpoint and keys are parsed from the peer section`() {
        val config = parsed()

        assertEquals("188.114.99.151", config.server)
        assertEquals("2408", config.serverPort)
        assertEquals("8GYMxCC+QxurPSDGDrTnm2HmDROdYL5ZjYD3mBrnpEQ=", config.secretKey)
        assertEquals("bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=", config.publicKey)
        assertEquals("1280", config.mtu?.toString())
    }

    @Test
    fun `an ipv6 endpoint keeps its address whole`() {
        val conf = """
            [Interface]
            PrivateKey = 8GYMxCC+QxurPSDGDrTnm2HmDROdYL5ZjYD3mBrnpEQ=
            Address = 172.16.0.2/32

            [Peer]
            PublicKey = bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=
            Endpoint = [2606:4700:110:8c08::1]:2408
        """.trimIndent()

        val config = AmneziawgFmt.parseAmneziaConfFile(conf)

        assertEquals("2606:4700:110:8c08::1", config.server)
        assertEquals("2408", config.serverPort)
    }
}
