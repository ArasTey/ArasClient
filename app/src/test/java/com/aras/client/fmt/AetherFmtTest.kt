package com.aras.client.fmt

import android.util.Log
import com.aras.client.dto.AetherEndpoint
import com.aras.client.dto.AetherRange
import com.aras.client.enums.AetherIpVersion
import com.aras.client.enums.AetherProtocol
import com.aras.client.enums.AetherScanMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.MockedStatic
import org.mockito.Mockito.mockStatic

/**
 * The Aether share format is shared with the Aether app and PattNG, so the wire
 * values are the aether core's own: protocol `wg` not `wireguard`, IP version `v6`
 * not `ipv6`, obfuscation carried under `noize`. A link that parses here has to
 * round-trip back to the same thing.
 */
class AetherFmtTest {

    private lateinit var mockLog: MockedStatic<Log>

    @Before
    fun setUp() {
        mockLog = mockStatic(Log::class.java)
    }

    @After
    fun tearDown() {
        mockLog.close()
    }

    @Test
    fun `parses a real wg link with an ipv6 endpoint`() {
        val link = "aether://[2606:4700:d1::a29f:c0d1]:928?protocol=wg&scan=balanced" +
            "&ip=v6#%D8%AA%D8%B3%D8%AA"

        val config = AetherFmt.parse(link)

        assertNotNull(config)
        assertEquals("تست", config!!.remarks)
        // The bracketed literal is canonicalised and the brackets dropped.
        assertEquals("2606:4700:d1::a29f:c0d1", config.server)
        assertEquals("928", config.serverPort)
        assertEquals("wg", config.aetherProtocol)
        assertEquals("balanced", config.aetherScanMode)
        assertEquals("v6", config.aetherIpVersion)
        // No `noize` in the link, so the core keeps its own choice.
        assertEquals("auto", config.aetherObfuscation)
    }

    @Test
    fun `round-trips a wg link through the profile`() {
        val link = "aether://[2606:4700:d1::a29f:c0d1]:928?protocol=wg&scan=balanced" +
            "&ip=v6#%D8%AA%D8%B3%D8%AA"

        val uri = AetherFmt.toUri(AetherFmt.parse(link)!!)

        assertEquals(link, uri)
    }

    @Test
    fun `round-trips a masque link with fragmentation and ech`() {
        // The endpoint must be an IP literal: the core scans addresses, so a hostname
        // is not something this format can carry.
        val link = "aether://198.51.100.7:443?protocol=masque&scan=verified" +
            "&noize=firewall&ip=v4&dns=1.1.1.1&exit_loc=DE" +
            "&transport=h2&fragment=1&fragment_size=64-128&fragment_delay=10&ech=1#warp"

        val config = AetherFmt.parse(link)
        assertNotNull(config)
        assertEquals("masque", config!!.aetherProtocol)
        assertEquals("198.51.100.7", config.server)
        assertEquals("443", config.serverPort)
        assertEquals("firewall", config.aetherObfuscation)
        assertEquals("h2", config.aetherTransport)
        assertEquals(true, config.aetherFragment)
        assertEquals("64-128", config.aetherFragmentSize)
        assertEquals("10", config.aetherFragmentDelay)
        assertEquals(true, config.aetherEch)
        assertEquals("1.1.1.1", config.aetherDns)
        assertEquals("DE", config.aetherExitLoc)

        assertEquals(link, AetherFmt.toUri(config))
    }

    @Test
    fun `a hostname endpoint is rejected, as the core scans addresses only`() {
        assertNull(AetherEndpoint.of("example.org", "443"))
        assertNotNull(AetherEndpoint.of("198.51.100.7", "443"))
    }

    @Test
    fun `a two hop link keeps both gateways and no address`() {
        val link = "aether://?protocol=gool&scan=balanced&ip=v4" +
            "&outer=198.51.100.1:2408&inner=198.51.100.2:2408#nested"

        val config = AetherFmt.parse(link)

        assertNotNull(config)
        assertEquals("gool", config!!.aetherProtocol)
        assertEquals("198.51.100.1:2408", config.aetherWiwOuter)
        assertEquals("198.51.100.2:2408", config.aetherWiwInner)
        assertNull(config.server)
    }

    @Test
    fun `a two hop link whose hops are the same host drops the inner one`() {
        val link = "aether://?protocol=mim&scan=balanced&ip=v4" +
            "&outer=198.51.100.1:2408&inner=198.51.100.1:928#same"

        val config = AetherFmt.parse(link)

        assertEquals("198.51.100.1:2408", config!!.aetherWiwOuter)
        assertNull(config.aetherWiwInner)
    }

    @Test
    fun `an unknown protocol falls back to wg and unknown scan to balanced`() {
        val config = AetherFmt.parse("aether://1.2.3.4:2408?protocol=nope&scan=nope")

        assertEquals(AetherProtocol.WIREGUARD.type, config!!.aetherProtocol)
        assertEquals(AetherScanMode.BALANCED.type, config.aetherScanMode)
        assertEquals(AetherIpVersion.V4.type, config.aetherIpVersion)
    }

    @Test
    fun `the legacy stealth scan name still reads as verified`() {
        val config = AetherFmt.parse("aether://1.2.3.4:2408?protocol=wg&scan=stealth")

        assertEquals(AetherScanMode.VERIFIED.type, config!!.aetherScanMode)
    }

    @Test
    fun `endpoints canonicalise ipv6 and reject malformed addresses`() {
        assertEquals("[2606:4700:d1::a29f:c0d1]:928",
            AetherEndpoint.parse("[2606:4700:d1::a29f:c0d1]:928").toString())
        // Compressed back to the longest zero run.
        assertEquals("2001:db8::1", AetherEndpoint.parse("[2001:0db8:0000:0000:0000:0000:0000:0001]:1")?.host)
        assertNull(AetherEndpoint.of("999.1.1.1", "2408"))
        assertNull(AetherEndpoint.of("1.2.3.4", "70000"))
        assertNull(AetherEndpoint.parse("no-port-here"))
    }

    @Test
    fun `fragment ranges are bounded`() {
        assertEquals("64", AetherRange.parse("64", AetherRange.FRAGMENT_SIZE)?.toString())
        assertEquals("64-128", AetherRange.parse("64-128", AetherRange.FRAGMENT_SIZE)?.toString())
        // Above the 4096 bound the whole setting is dropped rather than clamped.
        assertNull(AetherRange.parse("9000", AetherRange.FRAGMENT_SIZE))
        assertNull(AetherRange.parse("0", AetherRange.FRAGMENT_SIZE))
        assertTrue(AetherRange.FRAGMENT_DELAY.contains(1000))
    }
}
