package com.aras.client.radar.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class RadarConfigParserTest {
    @Test
    fun extractsMultipleDirectLinksAndNormalizesProtocol() {
        val raw = """
            vless://id@example.com:443?security=tls#DE-1
            trojan://secret@example.net:8443#NL-2
        """.trimIndent()
        val result = RadarConfigParser.extract(raw, "fixture")
        assertEquals(2, result.size)
        assertEquals("vless", result[0].protocol)
        assertEquals("example.com", result[0].host)
        assertEquals(443, result[0].port)
        assertEquals("DE-1", result[0].remark)
        assertEquals("trojan", result[1].protocol)
    }

    @Test
    fun decodesBase64SubscriptionFeeds() {
        val feed = "vless://id@example.com:443#node\ntrojan://secret@example.net:443#node2"
        val encoded = Base64.getEncoder().encodeToString(feed.toByteArray())
        assertEquals(2, RadarConfigParser.extract(encoded, "base64-source").size)
    }

    @Test
    fun parsesVmessJsonEndpoint() {
        val json = """{"add":"vmess.example.org","port":"8443","ps":"Germany"}"""
        val encoded = Base64.getEncoder().encodeToString(json.toByteArray())
        val result = RadarConfigParser.parse("vmess://$encoded", "vmess-source")
        assertEquals("vmess.example.org", result?.host)
        assertEquals(8443, result?.port)
        assertEquals("Germany", result?.remark)
    }

    @Test
    fun parsesBracketedIpv6Authorities() {
        val result = RadarConfigParser.parse("vless://id@[2001:db8::1]:443#ipv6", "fixture")
        assertEquals("2001:db8::1", result?.host)
        assertEquals(443, result?.port)
    }

    @Test
    fun classifiesUdpAndQuicProtocolsForCoreTesting() {
        val hysteria = RadarConfigParser.parse("hysteria2://secret@example.com:443#h2", "fixture")
        val tuic = RadarConfigParser.parse("tuic://secret@example.com:443#tuic", "fixture")
        val vless = RadarConfigParser.parse("vless://id@example.com:443#vless", "fixture")
        assertTrue(hysteria?.usesUdpTransport == true)
        assertTrue(tuic?.usesUdpTransport == true)
        assertTrue(vless?.usesUdpTransport == false)
    }

    @Test
    fun rejectsInvalidPortsAndUnknownSchemes() {
        assertNull(RadarConfigParser.parse("vless://id@example.com:70000", "fixture"))
        assertNull(RadarConfigParser.parse("unknown://id@example.com:443", "fixture"))
        assertNull(RadarConfigParser.parse("vless://id@example.com:notaport", "fixture"))
    }

    @Test
    fun dedupeIdentityIgnoresProviderRemark() {
        val first = RadarConfigParser.parse("vless://id@example.com:443?security=tls#A", "one")
        val second = RadarConfigParser.parse("vless://id@example.com:443?security=tls#B", "two")
        assertTrue(first != null && second != null)
        assertEquals(first?.identity, second?.identity)
    }
}
