package com.aras.client.fmt

import com.aras.client.handler.AngConfigManager
import org.junit.Assert.*
import org.junit.Test
import java.net.URI
import java.net.URLDecoder
import java.util.Base64

class ClashYamlFmtTest {
    @Test
    fun importsBlockYamlThroughExistingVlessDispatch() {
        val yaml = """
            proxies:
              - name: Netherlands
                type: vless
                server: example.com
                port: 443
                uuid: 11111111-1111-1111-1111-111111111111
                tls: true
                servername: example.com
                network: ws
                ws-opts:
                  path: /proxy
                  headers:
                    Host: example.com
        """.trimIndent()
        assertTrue(ClashYamlFmt.isClashYaml(yaml))
        val profile = AngConfigManager.parseAnyLink(ClashYamlFmt.toLinks(yaml).single())!!
        assertEquals("example.com", profile.server)
        assertEquals("ws", profile.network)
        assertEquals("tls", profile.security)
        assertEquals("/proxy", profile.path)
        assertEquals(false, profile.insecure)
    }

    @Test
    fun importsFlowYamlAndEscapesCredentials() {
        val link = ClashYamlFmt.toLinks("proxies: [{name: 'A B', type: trojan, server: '2001:db8::1', port: 443, password: 'p@ss# word', skip-cert-verify: true}]").single()
        val uri = URI(link)
        assertEquals("p@ss# word", uri.userInfo)
        assertTrue(uri.rawQuery.contains("allowInsecure=1"))
        assertEquals("A B", URLDecoder.decode(uri.rawFragment, "UTF-8"))
    }

    @Test
    fun convertsShadowsocksAndVmess() {
        val links = ClashYamlFmt.toLinks("""
            proxies:
              - {name: SS, type: ss, server: example.com, port: 8388, cipher: aes-128-gcm, password: secret}
              - {name: VMess, type: vmess, server: example.com, port: 443, uuid: abc, alterId: 0, tls: true}
        """.trimIndent())
        assertEquals("aes-128-gcm:secret", String(Base64.getDecoder().decode(links[0].substringAfter("ss://").substringBefore('@'))))
        val json = String(Base64.getDecoder().decode(links[1].substringAfter("vmess://")))
        assertTrue(json.contains("\"tls\":\"tls\""))
        assertTrue(json.contains("\"insecure\":\"0\""))
    }

    @Test
    fun preservesRealityParameters() {
        val profile = AngConfigManager.parseAnyLink(ClashYamlFmt.toLinks("""
            proxies:
              - {type: vless, server: example.com, port: 443, uuid: abc, tls: true, reality-opts: {public-key: test-key, short-id: ab}, flow: xtls-rprx-vision}
        """.trimIndent()).single())!!
        assertEquals("reality", profile.security)
        assertEquals("test-key", profile.publicKey)
        assertEquals("ab", profile.shortId)
    }

    @Test
    fun rejectsUnsupportedOptionsRatherThanDowngrading() {
        for (extra in listOf("plugin: v2ray-plugin", "certificate: cert.pem", "dialer-proxy: upstream")) {
            assertThrows(IllegalArgumentException::class.java) {
                ClashYamlFmt.toLinks("proxies: [{type: ss, server: example.com, port: 443, password: secret, cipher: aes-128-gcm, $extra}]")
            }
        }
    }

    @Test
    fun rejectsUnsafeMalformedAndOversizedDocuments() {
        for (yaml in listOf("proxies: [", "proxies: []\nproxies: []", "proxies: !!java.net.URL ['https://example.com']", "proxies: []")) {
            assertThrows(Exception::class.java) { ClashYamlFmt.toLinks(yaml) }
        }
        assertThrows(IllegalArgumentException::class.java) { ClashYamlFmt.toLinks(" ".repeat(2_000_001)) }
    }

    @Test
    fun importsStrictJsonWhenProxiesIsNotTheFirstProperty() {
        val json = """
            {"mixed-port":7890,"mode":"rule","proxies":[{"name":"NL","type":"trojan","server":"example.com","port":443,"password":"secret","sni":"example.com"}]}
        """.trimIndent()
        assertTrue(ClashYamlFmt.isClashYaml(json))
        val link = ClashYamlFmt.toLinks(json).single()
        assertTrue(link.startsWith("trojan://"))
    }

    @Test
    fun importsBomPrefixedMinifiedJson() {
        val json = "\uFEFF{\"dns\":{\"enable\":true},\"proxies\":[{\"name\":\"US\",\"type\":\"vmess\",\"server\":\"example.com\",\"port\":443,\"uuid\":\"abc\",\"alterId\":0}]}"
        assertTrue(ClashYamlFmt.isClashYaml(json))
        assertTrue(ClashYamlFmt.toLinks(json).single().startsWith("vmess://"))
    }

    @Test
    fun leavesOrdinaryLinksAndJsonAlone() {
        assertFalse(ClashYamlFmt.isClashYaml("vless://abc@example.com:443?security=tls#proxies"))
        assertFalse(ClashYamlFmt.isClashYaml("{\"outbounds\": []}"))
        assertFalse(ClashYamlFmt.isClashYaml("{\"proxies\":null}"))
        assertTrue(ClashYamlFmt.isClashYaml("{proxies: []}"))
    }
}
