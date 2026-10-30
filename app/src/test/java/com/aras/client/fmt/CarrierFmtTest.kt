package com.aras.client.fmt

import android.util.Log
import com.aras.client.enums.EConfigType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.MockedStatic
import org.mockito.Mockito.mockStatic

/**
 * A Psiphon or Tor profile is the carrier alone: its link carries the carrier's own
 * settings and nothing an Aether link carries. AngConfigManager prepends the scheme
 * as it does for every other format, so a link that parses here has to round-trip
 * back to the same thing.
 */
class CarrierFmtTest {

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
    fun `parses a psiphon link with a region`() {
        val link = "psiphon://?region=de&mode=cdn&listen=10825#Germany"

        val config = CarrierFmt.parse(link)

        assertNotNull(config)
        assertEquals(EConfigType.PSIPHON, config!!.configType)
        assertEquals("only", config.aetherPsiphon)
        assertEquals("cdn", config.aetherPsiphonMode)
        // Country codes are upper case wherever they came from.
        assertEquals("DE", config.aetherPsiphonRegion)
        assertEquals("10825", config.aetherListenPort)
        assertEquals("Germany", config.remarks)
    }

    @Test
    fun `round-trips a psiphon link`() {
        val link = "psiphon://?region=DE&mode=cdn&listen=10825#Germany"

        assertEquals(link.removePrefix("psiphon://"), CarrierFmt.toUri(CarrierFmt.parse(link)!!))
    }

    @Test
    fun `round-trips a tor link with its own bridge lines`() {
        val link = "tor://?bridges=own&relays=only" +
            "&bridges_own=obfs4%201.2.3.4%3A443%3Bsnowflake%205.6.7.8%3A443" +
            "&listen=10820#Own%20bridges"

        val config = CarrierFmt.parse(link)

        assertNotNull(config)
        assertEquals(EConfigType.TOR, config!!.configType)
        assertEquals("only", config.aetherTor)
        assertEquals("own", config.aetherTorBridges)
        assertEquals("only", config.aetherTorRelays)
        // The link separates bridge lines with a semicolon; the profile keeps one per line.
        assertEquals("obfs4 1.2.3.4:443\nsnowflake 5.6.7.8:443", config.aetherTorBridgeLines)
        assertEquals("10820", config.aetherListenPort)
        assertEquals("Own bridges", config.remarks)

        assertEquals(link.removePrefix("tor://"), CarrierFmt.toUri(config))
    }

    @Test
    fun `a carrier profile with no name is given one`() {
        val psiphon = CarrierFmt.parse("psiphon://?region=DE")!!
        assertEquals("Psiphon DE", psiphon.remarks)
        assertEquals("?region=DE&mode=auto#Psiphon%20DE", CarrierFmt.toUri(psiphon))

        assertEquals("Psiphon", CarrierFmt.parse("psiphon://?mode=auto")!!.remarks)
        assertEquals("Tor", CarrierFmt.parse("tor://?bridges=auto")!!.remarks)
    }

    @Test
    fun `the default listen port stays out of the link`() {
        // A profile saved with the default is the same profile as one saved before
        // the port could be chosen, so neither carries it.
        val config = CarrierFmt.parse("psiphon://?listen=10819")!!

        assertNull(config.aetherListenPort)
        assertEquals("?mode=auto#Psiphon", CarrierFmt.toUri(config))
    }

    @Test
    fun `unknown carrier settings fall back to the core's own choice`() {
        val psiphon = CarrierFmt.parse("psiphon://?mode=nope&region=")!!
        assertEquals("auto", psiphon.aetherPsiphonMode)
        assertNull(psiphon.aetherPsiphonRegion)

        val tor = CarrierFmt.parse("tor://?bridges=nope&relays=nope")!!
        assertEquals("auto", tor.aetherTorBridges)
        assertEquals("auto", tor.aetherTorRelays)
    }

    @Test
    fun `only psiphon and tor links are carriers`() {
        assertNull(CarrierFmt.parse("aether://198.51.100.7:2408?protocol=wg&scan=balanced&ip=v4"))
        assertNull(CarrierFmt.parse("vmess://eyJhZGQiOiIxLjIuMy40In0="))
        // A malformed carrier link is rejected rather than thrown at the importer.
        assertNull(CarrierFmt.parse("tor://"))
    }

    @Test
    fun `a shared carrier link carries the scheme exactly once`() {
        val config = CarrierFmt.parse("psiphon://?region=JP&mode=direct#Japan")!!

        assertEquals("psiphon://?region=JP&mode=direct#Japan", "psiphon://" + CarrierFmt.toUri(config))
    }
}
