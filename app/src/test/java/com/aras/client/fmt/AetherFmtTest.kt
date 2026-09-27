package com.aras.client.fmt

import android.util.Log
import com.aras.client.dto.AetherEndpoint
import com.aras.client.dto.AetherRange
import com.aras.client.enums.AetherIpVersion
import com.aras.client.enums.AetherPsiphonCdnSet
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

    @Test
    fun `round-trips a psiphon and tor profile`() {
        // A comma in a query value is percent-encoded on the way out, so the link is
        // compared field by field rather than byte for byte.
        val link = "aether://198.51.100.9:2408?protocol=wg&scan=balanced&ip=v4" +
            "&listen=10825&psiphon=chain&psiphon_mode=cdn&cdn_ips=1.2.3.4" +
            "&cdn_sets=cloudflare,github&tor=reverse&tor_bridges=first" +
            "&tor_relays=only&bridges=obfs4 1.2.3.4:443#carried"

        val config = AetherFmt.parse(link)
        assertNotNull(config)
        assertEquals("chain", config!!.aetherPsiphon)
        assertEquals("cdn", config.aetherPsiphonMode)
        assertEquals("1.2.3.4", config.aetherPsiphonCdnIps)
        assertEquals("reverse", config.aetherTor)
        assertEquals("first", config.aetherTorBridges)
        assertEquals("only", config.aetherTorRelays)
        assertEquals("10825", config.aetherListenPort)
        assertEquals("obfs4 1.2.3.4:443", config.aetherTorBridgeLines)
        assertEquals("cloudflare,github", config.aetherPsiphonCdnSets)

        val again = AetherFmt.parse(AetherFmt.toUri(config))!!
        assertEquals(config.aetherPsiphon, again.aetherPsiphon)
        assertEquals(config.aetherPsiphonCdnSets, again.aetherPsiphonCdnSets)
        assertEquals(config.aetherTor, again.aetherTor)
        assertEquals(config.aetherTorBridgeLines, again.aetherTorBridgeLines)
    }

    @Test
    fun `psiphon and tor off are stored as absent, not as the word off`() {
        val config = AetherFmt.parse("aether://1.2.3.4:2408?protocol=wg&scan=balanced&ip=v4")!!

        assertNull(config.aetherPsiphon)
        assertNull(config.aetherTor)
    }

    @Test
    fun `normalizing refuses a reverse carrier on a wireguard profile`() {
        val config = AetherFmt.parse("aether://1.2.3.4:2408?protocol=wg&scan=balanced&ip=v4&psiphon=reverse")!!
        // Psiphon and Tor carry TCP alone; WARP WireGuard endpoints answer on UDP.
        assertEquals(AetherFmt.Problem.PSIPHON_NEEDS_MASQUE, AetherFmt.normalize(config))
    }

    @Test
    fun `normalizing allows a reverse carrier once the protocol is masque`() {
        // The MASQUE check is what lifts; reverse+chain is then the one pairing that nests.
        val config = AetherFmt.parse(
            "aether://1.2.3.4:443?protocol=masque&scan=balanced&ip=v4&psiphon=reverse&tor=chain"
        )!!
        assertNull(AetherFmt.normalize(config))
    }

    @Test
    fun `normalizing refuses two carriers on the same side of the tunnel`() {
        // Both inside the tunnel would leave the app nothing to dial them through.
        val both = AetherFmt.parse("aether://1.2.3.4:2408?protocol=wg&scan=balanced&ip=v4&psiphon=chain&tor=chain")!!
        assertEquals(AetherFmt.Problem.TOR_PSIPHON_CONFLICT, AetherFmt.normalize(both))

        // Two around it the core refuses outright.
        val bothOutside = AetherFmt.parse(
            "aether://1.2.3.4:443?protocol=masque&scan=balanced&ip=v4&psiphon=reverse&tor=reverse"
        )!!
        assertEquals(AetherFmt.Problem.TOR_PSIPHON_CONFLICT, AetherFmt.normalize(bothOutside))
    }

    @Test
    fun `normalizing accepts the one pairing that nests`() {
        val config = AetherFmt.parse(
            "aether://1.2.3.4:443?protocol=masque&scan=balanced&ip=v4&psiphon=reverse&tor=chain"
        )!!

        assertNull(AetherFmt.normalize(config))
    }

    @Test
    fun `normalizing insists on bridge lines when the profile says own`() {
        val config = AetherFmt.parse(
            "aether://1.2.3.4:2408?protocol=wg&scan=balanced&ip=v4&tor=chain&tor_bridges=own"
        )!!
        assertEquals(AetherFmt.Problem.TOR_BRIDGES_MISSING, AetherFmt.normalize(config))
    }

    @Test
    fun `normalizing reads bridge lines as a bridge file does`() {
        val config = AetherFmt.parse(
            "aether://1.2.3.4:2408?protocol=wg&scan=balanced&ip=v4&tor=chain&tor_bridges=own" +
                "&bridges=obfs4%201.2.3.4%3A443%3BBridge%20snowflake%205.6.7.8%3A443"
        )!!

        assertNull(AetherFmt.normalize(config))
        assertEquals(listOf("obfs4 1.2.3.4:443", "snowflake 5.6.7.8:443"),
            AetherFmt.bridgeLines(config.aetherTorBridgeLines))
    }

    @Test
    fun `normalizing rejects an exit rule that is not country codes`() {
        val bad = AetherFmt.parse("aether://1.2.3.4:2408?protocol=wg&scan=balanced&ip=v4&exit_loc=nowhere")!!
        assertEquals(AetherFmt.Problem.INVALID_EXIT_LOC, AetherFmt.normalize(bad))

        val good = AetherFmt.parse("aether://1.2.3.4:2408?protocol=wg&scan=balanced&ip=v4&exit_loc=de,nl")!!
        assertNull(AetherFmt.normalize(good))
        assertEquals("DE,NL", good.aetherExitLoc)
    }

    @Test
    fun `normalizing rejects a resolver that is neither an address nor an endpoint`() {
        val bad = AetherFmt.parse("aether://1.2.3.4:2408?protocol=wg&scan=balanced&ip=v4&dns=notadns")!!
        assertEquals(AetherFmt.Problem.INVALID_DNS, AetherFmt.normalize(bad))
    }

    @Test
    fun `the cdn sets are kept in their own order whatever order they arrive in`() {
        val parsed = AetherPsiphonCdnSet.parse("github, cloudflare ,stranger")
        assertEquals(listOf("cloudflare", "github"), parsed.map { it.type })
        assertEquals("cloudflare,github", AetherPsiphonCdnSet.join(parsed))
        assertNull(AetherPsiphonCdnSet.join(emptyList()))
    }
}
