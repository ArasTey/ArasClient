package com.aras.client.fmt

import com.aras.client.handler.AngConfigManager
import com.aras.client.fmt.ShadowsocksFmt
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
    fun convertsShadowsocksV2rayPlugin() {
        val json = """
            {"proxies":[{"name":"SS WS","type":"ss","server":"example.com","port":443,"cipher":"aes-128-gcm","password":"secret","plugin":"v2ray-plugin","client-fingerprint":"chrome","plugin-opts":{"mode":"websocket","host":"cdn.example.com","path":"/proxy","tls":true}}]}
        """.trimIndent()
        val generatedLink = ClashYamlFmt.toLinks(json).single()
        assertTrue(generatedLink.contains("plugin=v2ray-plugin%3Bmode%3Dwebsocket"))
        val profile = ShadowsocksFmt.parse(
            "ss://aes-128-gcm:secret@example.com:443" +
                    "?plugin=v2ray-plugin%3Bmode%3Dwebsocket%3Bhost%3Dcdn.example.com" +
                    "%3Bpath%3D%2Fproxy%3Btls%3Bfp%3Dchrome#SS"
        )!!
        assertEquals("shadowsocks", profile.configType.name.lowercase())
        assertEquals("ws", profile.network)
        assertEquals("tls", profile.security)
        assertEquals("/proxy", profile.path)
        assertEquals("cdn.example.com", profile.host)
        assertEquals("chrome", profile.fingerPrint)
    }

    @Test
    fun rejectsUnsupportedOptionsRatherThanDowngrading() {
        for (extra in listOf("plugin: obfs-local", "certificate: cert.pem", "packet-addr: true")) {
            assertThrows(IllegalArgumentException::class.java) {
                ClashYamlFmt.toLinks("proxies: [{type: ss, server: example.com, port: 443, password: secret, cipher: aes-128-gcm, $extra}]")
            }
        }
    }

    @Test
    fun importsHysteria2WithObfsBandwidthAndPortHopping() {
        val yaml = """
            proxies:
              - name: HY
                type: hysteria2
                server: hy.example.com
                port: 443
                password: pw
                obfs: salamander
                obfs-password: op
                up: 100 Mbps
                down: 50
                ports: 30000-31000
                hop-interval: 30
                sni: hy.example.com
                skip-cert-verify: true
        """.trimIndent()
        val profile = Hysteria2Fmt.parse(ClashYamlFmt.toLinks(yaml).single())!!
        assertEquals("hy.example.com", profile.server)
        assertEquals("op", profile.obfsPassword)
        assertEquals("100", profile.bandwidthUp)
        assertEquals("50", profile.bandwidthDown)
        assertEquals("30000-31000", profile.portHopping)
        assertEquals("30", profile.portHoppingInterval)
        assertEquals(true, profile.insecure)
    }

    @Test
    fun rejectsUnknownHysteria2ObfsRatherThanDialingWrong() {
        assertThrows(IllegalArgumentException::class.java) {
            ClashYamlFmt.toLinks("proxies: [{type: hysteria2, server: h.example.com, port: 443, password: pw, obfs: weird}]")
        }
    }

    @Test
    fun importsAnytlsEvenWithoutTransportOptions() {
        val yaml = "proxies: [{name: AT, type: anytls, server: at.example.com, port: 443, password: pw, sni: at.example.com, skip-cert-verify: true, client-fingerprint: chrome}]"
        val profile = AnytlsFmt.parse(ClashYamlFmt.toLinks(yaml).single())!!
        assertEquals("at.example.com", profile.server)
        assertEquals("pw", profile.password)
        assertEquals("at.example.com", profile.sni)
        assertEquals(true, profile.insecure)
        assertEquals("chrome", profile.fingerPrint)
    }

    @Test
    fun tuningKeysAndWsHeaderMapsDoNotRejectAProxy() {
        val yaml = """
            proxies:
              - name: WS
                type: vless
                server: example.com
                port: 443
                uuid: abc
                tls: true
                network: ws
                ws-opts:
                  path: /p
                  headers:
                    Host: cdn.example.com
                    User-Agent: whatever
                udp: true
                tfo: false
                smux: {enabled: true}
                ip-version: ipv4
                dialer-proxy: other
        """.trimIndent()
        val profile = AngConfigManager.parseAnyLink(ClashYamlFmt.toLinks(yaml).single())!!
        assertEquals("cdn.example.com", profile.host)
        assertEquals("/p", profile.path)
    }

    @Test
    fun importsH2AndHttpUpgradeTransports() {
        val links = ClashYamlFmt.toLinks("""
            proxies:
              - {name: H2, type: vless, server: example.com, port: 443, uuid: abc, tls: true, network: h2, h2-opts: {host: [h2.example.com], path: /h2}}
              - {name: HU, type: vless, server: example.com, port: 443, uuid: abc, tls: true, network: ws, ws-opts: {path: /up, headers: {Host: up.example.com}, v2ray-http-upgrade: true}}
        """.trimIndent())
        val h2 = AngConfigManager.parseAnyLink(links[0])!!
        assertEquals("http", h2.network)
        assertEquals("h2.example.com", h2.host)
        assertEquals("/h2", h2.path)
        val httpUpgrade = AngConfigManager.parseAnyLink(links[1])!!
        assertEquals("httpupgrade", httpUpgrade.network)
        assertEquals("up.example.com", httpUpgrade.host)
    }

    @Test
    fun importsShadowsocksObfsHttpPlugin() {
        val yaml = "proxies: [{name: SSO, type: ss, server: example.com, port: 443, cipher: aes-128-gcm, password: secret, plugin: obfs, plugin-opts: {mode: http, host: bing.com}}]"
        val link = ClashYamlFmt.toLinks(yaml).single()
        // The converter emits the SIP002 link; the plugin is URI-encoded inside it.
        assertTrue(URLDecoder.decode(link, "UTF-8").contains("plugin=obfs-local;obfs=http;obfs-host=bing.com"))
        // The app's SS parser turns that plugin string back into TCP+HTTP obfs.
        val profile = ShadowsocksFmt.parse(
            "ss://aes-128-gcm:secret@example.com:443" +
                    "?plugin=obfs-local%3Bobfs%3Dhttp%3Bobfs-host%3Dbing.com#SSO"
        )!!
        assertEquals("tcp", profile.network)
        assertEquals("http", profile.headerType)
        assertEquals("bing.com", profile.host)
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
