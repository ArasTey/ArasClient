package com.aras.client.fmt

import com.aras.client.enums.EConfigType
import com.aras.client.util.MimicPackets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mimic packets through the share link and the .conf file.
 *
 * Both directions have to agree with the UAPI names the core reads. A mimic spec
 * that survives neither is the same as having no feature, so the round trip is
 * pinned here rather than left to a device test.
 */
class AmneziawgFmtTest {

    /** toUri() returns the scheme-less body; the share path prepends the scheme. */
    private fun shareLink(config: com.aras.client.dto.entities.ProfileItem) =
        EConfigType.AMNEZIAWG.protocolScheme + AmneziawgFmt.toUri(config)

    private val link = "awg://cHfvEYAOf%2BZFEizLKhyJEDGhY9egzPxwIwlcb2ThTEo%3D@8.6.112.148:891" +
        "?publickey=bmXOC%2BF1FxEMF9dyiK2H5%2F1SUtzH0JuVo51h2wPfgyo%3D" +
        "&address=10.2.0.2%2F32&junkPacketCount=5#1788707473291"

    @Test
    fun `mimic packets round trip through an awg link`() {
        val original = AmneziawgFmt.parse(link)!!
        original.mimicPackets = "<b 4f5054>\n<t>"

        val reparsed = AmneziawgFmt.parse(shareLink(original))!!

        assertEquals("<b 4f5054>\n<t>", reparsed.mimicPackets)
    }

    @Test
    fun `the SIP preset survives a link round trip`() {
        val preset = MimicPackets.sipPreset()
        val original = AmneziawgFmt.parse(link)!!.apply { mimicPackets = preset }

        val reparsed = AmneziawgFmt.parse(shareLink(original))!!

        assertEquals(preset, reparsed.mimicPackets)
        // And the value still parses as a spec the core will accept.
        assertNull(MimicPackets.validate(reparsed.mimicPackets!!))
    }

    @Test
    fun `a link with no mimic packets carries none`() {
        val config = AmneziawgFmt.parse(shareLink(AmneziawgFmt.parse(link)!!))!!

        assertNull(config.mimicPackets)
    }

    @Test
    fun `mimic i1 to i5 are read from a conf file`() {
        val conf = """
            [Interface]
            PrivateKey = cHfvEYAOf+ZFEizLKhyJEDGhY9egzPxwIwlcb2ThTEo=
            Address = 10.2.0.2/32
            DNS = 1.1.1.1

            [Peer]
            PublicKey = bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=
            Jc = 5
            I1 = <b 4f5054>
            I2 = <t>
        """.trimIndent()

        val config = AmneziawgFmt.parseAmneziaConfFile(conf)

        assertEquals("<b 4f5054>\n<t>", config.mimicPackets)
        assertEquals("5", config.junkPacketCount)
    }

    @Test
    fun `a conf file without mimic packets leaves the field unset`() {
        val conf = """
            [Interface]
            PrivateKey = cHfvEYAOf+ZFEizLKhyJEDGhY9egzPxwIwlcb2ThTEo=
            Address = 10.2.0.2/32

            [Peer]
            PublicKey = bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=
            Jc = 5
        """.trimIndent()

        assertNull(AmneziawgFmt.parseAmneziaConfFile(conf).mimicPackets)
    }

    @Test
    fun `the link keeps the junk parameters it already carried`() {
        val config = AmneziawgFmt.parse(shareLink(AmneziawgFmt.parse(link)!!))!!

        assertEquals("5", config.junkPacketCount)
        assertTrue(config.remarks.isNotBlank())
    }

    @Test
    fun `the keepalive survives a link round trip`() {
        // Without the keep-alive the peer's mapping expires after a few minutes
        // and the tunnel goes quiet, so a share/import cycle must not lose it.
        val original = AmneziawgFmt.parse(link)!!.apply { keepAlive = "45" }

        val reparsed = AmneziawgFmt.parse(shareLink(original))!!

        assertEquals("45", reparsed.keepAlive)
    }
}
