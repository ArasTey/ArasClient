package com.aras.client.fmt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * sing-box subscription JSON must import as well as a Clash YAML or a plain
 * link list does — the owner reported such .json sublinks importing nothing.
 * The converter emits the app's own share links, so every case below is
 * re-parsed with the fmt it lands on.
 */
class SingboxFmtTest {

    private val singbox = """
        {
          "log": {"level": "info"},
          "outbounds": [
            {"type": "vless", "tag": "node a", "server": "a.example.com", "server_port": 443,
             "uuid": "5e1f4e06-dd77-4a53-8f22-13fd4d5c3c3d", "flow": "xtls-rprx-vision",
             "tls": {"enabled": true, "server_name": "sni.example.com",
                     "reality": {"enabled": true, "public_key": "PBKKEY"}}},
            {"type": "trojan", "tag": "node b", "server": "b.example.com", "server_port": 443,
             "password": "pw-b", "tls": {"enabled": true, "server_name": "b.example.com"}},
            {"type": "shadowsocks", "tag": "node c", "server": "c.example.com", "server_port": 8388,
             "method": "aes-128-gcm", "password": "pw-c"},
            {"type": "hysteria2", "tag": "node d", "server": "d.example.com", "server_port": 8443,
             "password": "pw-d", "tls": {"enabled": true, "insecure": true, "server_name": "d.example.com"}},
            {"type": "selector", "tag": "PROXY", "outbounds": ["node a", "node b"]},
            {"type": "direct", "tag": "direct"},
            {"type": "block", "tag": "block"}
          ]
        }
    """.trimIndent()

    @Test
    fun `detects a sing-box document`() {
        assertTrue(SingboxFmt.isSingboxJson(singbox))
        assertFalse(SingboxFmt.isSingboxJson("""{"log":{"level":"info"}}"""))
    }

    @Test
    fun `group and meta outbounds are skipped`() {
        val links = SingboxFmt.toLinks(singbox)

        assertEquals(4, links.size)
        assertTrue(links.all { it.isNotBlank() })
    }

    @Test
    fun `vless reality fields survive on the emitted link`() {
        val links = SingboxFmt.toLinks(singbox)

        val vless = links.first { it.startsWith("vless://") }
        assertTrue(vless.contains("security=reality"))
        assertTrue(vless.contains("pbk=PBKKEY"))
        assertTrue(vless.contains("sni=sni.example.com"))
        assertTrue(vless.contains("flow=xtls-rprx-vision"))
        assertTrue(vless.contains("#node%20a"))
        // And the app's own parser reads it back.
        val config = VlessFmt.parse(vless)!!
        assertEquals("node a", config.remarks)
        assertEquals("a.example.com", config.server)
    }

    @Test
    fun `trojan shadowsocks and hysteria2 emit their own links`() {
        val links = SingboxFmt.toLinks(singbox)

        assertEquals("trojan://pw-b@b.example.com:443?sni=b.example.com#node%20b",
            links.first { it.startsWith("trojan://") })
        assertEquals("ss://###@c.example.com:8388#node%20c",
            links.first { it.startsWith("ss://") }.replace(Regex("ss://[^@]+@"), "ss://###@"))
        assertTrue(links.first { it.startsWith("hysteria2://") }.contains("pw-d@d.example.com:8443"))
        assertTrue(links.first { it.startsWith("hysteria2://") }.contains("insecure=1"))
    }

    @Test
    fun `a clash-style json with proxies is not mistaken for sing-box`() {
        val clash = """{"proxies":[{"name":"x","type":"ss","server":"s","port":1,"cipher":"rc4","password":"p"}]}"""

        assertTrue(com.aras.client.fmt.ClashYamlFmt.isClashYaml(clash))
        assertFalse(SingboxFmt.isSingboxJson(clash))
    }
}
