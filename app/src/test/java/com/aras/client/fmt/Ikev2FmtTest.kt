package com.aras.client.fmt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Ikev2FmtTest {

    @Test
    fun `parses user pass identity and remarks`() {
        val config = Ikev2Fmt.parse(
            "ikev2://20gb-unlimited:uXkMchL9czWBB938@51.20.181.166" +
                "?identity=20gb-unlimited&remote_id=vpn.example.com#Working"
        )!!
        assertEquals("51.20.181.166", config.server)
        assertEquals("20gb-unlimited", config.username)
        assertEquals("uXkMchL9czWBB938", config.password)
        assertEquals("20gb-unlimited", config.ikev2LocalId)
        assertEquals("vpn.example.com", config.ikev2RemoteId)
        assertEquals("Working", config.remarks)
        assertNull(config.ikev2AuthMode)
    }

    @Test
    fun `parses ca parameter as base64 DER`() {
        val config = Ikev2Fmt.parse(
            "ikev2://51.20.181.166?ca=MIIBkTCB%2BwIBATANBgkq#CA"
        )!!
        assertEquals("MIIBkTCB+wIBATANBgkq", config.ikev2CaCert)
    }

    @Test
    fun `ca parameter round trips through toUri`() {
        val ca = "MIIC5zCCAc+gAwIBAgIUX0nZ"
        val config = Ikev2Fmt.parse("ikev2://51.20.181.166?ca=$ca#C")!!
        val link = Ikev2Fmt.toUri(config)
        assertEquals(ca, Ikev2Fmt.parse("ikev2://$link")!!.ikev2CaCert)
    }

    @Test
    fun `blank ca becomes null`() {
        val config = Ikev2Fmt.parse("ikev2://51.20.181.166?ca=#C")!!
        assertNull(config.ikev2CaCert)
    }

    @Test
    fun `non ikev2 scheme is rejected`() {
        assertNull(Ikev2Fmt.parse("vless://example.com:443?a=b#X"))
    }
}
