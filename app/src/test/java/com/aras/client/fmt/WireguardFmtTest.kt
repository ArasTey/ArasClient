package com.aras.client.fmt

import com.aras.client.dto.entities.ProfileItem
import com.aras.client.enums.EConfigType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The WireGuard `.conf` file and the `wireguard://` link.
 *
 * Both directions carry what the tunnel needs: the endpoint (an IPv6 one
 * whole), the keys, the local address, and the keep-alive — without which the
 * peer's mapping expires after a few minutes and the tunnel goes quiet.
 */
class WireguardFmtTest {

    /** toUri() returns the scheme-less body; the share path prepends the scheme. */
    private fun shareLink(config: ProfileItem) =
        EConfigType.WIREGUARD.protocolScheme + WireguardFmt.toUri(config)

    private val conf = """
        [Interface]
        PrivateKey = 8GYMxCC+QxurPSDGDrTnm2HmDROdYL5ZjYD3mBrnpEQ=
        Address = 172.16.0.2/32
        DNS = 1.1.1.1
        MTU = 1420
        Description = office

        [Peer]
        PublicKey = bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=
        AllowedIPs = 0.0.0.0/0, ::/0
        Endpoint = 188.114.99.151:2408
        PersistentKeepalive = 25
        Reserved = 1,2,3
    """.trimIndent()

    @Test
    fun `the conf file gives the profile what the tunnel needs`() {
        val config = WireguardFmt.parseWireguardConfFile(conf)

        assertEquals("188.114.99.151", config.server)
        assertEquals("2408", config.serverPort)
        assertEquals("8GYMxCC+QxurPSDGDrTnm2HmDROdYL5ZjYD3mBrnpEQ=", config.secretKey)
        assertEquals("bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=", config.publicKey)
        assertEquals("172.16.0.2/32", config.localAddress)
        assertEquals(1420, config.mtu)
        assertEquals("1,2,3", config.reserved)
        assertEquals("office", config.remarks)
    }

    @Test
    fun `the keepalive is read from the conf file`() {
        // The parser dropped it before, so every imported profile had none and
        // the tunnel went quiet after a few minutes.
        assertEquals("25", WireguardFmt.parseWireguardConfFile(conf).keepAlive)
    }

    @Test
    fun `an ipv6 endpoint keeps its address whole`() {
        val withIpv6 = conf.replace("Endpoint = 188.114.99.151:2408", "Endpoint = [2606:4700::1]:2408")

        val config = WireguardFmt.parseWireguardConfFile(withIpv6)

        assertEquals("2606:4700::1", config.server)
        assertEquals("2408", config.serverPort)
    }

    @Test
    fun `the keepalive survives a link round trip`() {
        val original = WireguardFmt.parseWireguardConfFile(conf)

        val reparsed = WireguardFmt.parse(shareLink(original))!!

        assertEquals("25", reparsed.keepAlive)
    }

    @Test
    fun `a link without a port is refused`() {
        // The URI parser answers -1 for a missing port, which became the
        // endpoint "host:-1" and a profile that can never connect.
        assertNull(WireguardFmt.parse("wireguard://cHfvEYAOf%2BZFEizLKhyJEDGhY9egzPxwIwlcb2ThTEo%3D@8.6.112.148" +
            "?publickey=bmXOC%2BF1FxEMF9dyiK2H5%2F1SUtzH0JuVo51h2wPfgyo%3D#test"))
    }
}
