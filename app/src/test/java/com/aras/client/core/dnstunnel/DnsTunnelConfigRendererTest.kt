package com.aras.client.core.dnstunnel

import com.aras.client.dto.entities.ProfileItem
import com.aras.client.enums.EConfigType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine configuration the DNS tunnel is started with: the keys the engines
 * read, the ones only the CottenDns fork knows, and the resolver list.
 */
class DnsTunnelConfigRendererTest {

    private fun profile() = ProfileItem(
        configType = EConfigType.DNSTUNNEL,
        dnsTunnelDomain = "v.example.com",
        dnsTunnelKey = "s3cret",
        dnsTunnelMethod = 3,
        dnsTunnelResolvers = "1.1.1.1\n8.8.8.8",
    )

    @Test
    fun `the configuration carries the tunnel zone, the key and the method`() {
        val toml = DnsTunnelConfigRenderer.renderToml(
            profile(),
            DnsTunnelEngine.STORM,
            socksPort = 10821,
            dnsPort = null,
        )

        assertTrue(toml.contains("""DOMAINS = ["v.example.com"]"""))
        assertTrue(toml.contains("""ENCRYPTION_KEY = "s3cret""""))
        assertTrue(toml.contains("DATA_ENCRYPTION_METHOD = 3"))
        assertTrue(toml.contains("""PROTOCOL_TYPE = "SOCKS5""""))
        assertTrue(toml.contains("""LISTEN_IP = "127.0.0.1""""))
        assertTrue(toml.contains("LISTEN_PORT = 10821"))
    }

    @Test
    fun `the local DNS is off unless a port is given`() {
        val without = DnsTunnelConfigRenderer.renderToml(profile(), DnsTunnelEngine.STORM, 10821, null)
        assertTrue(without.contains("LOCAL_DNS_ENABLED = false"))

        val with = DnsTunnelConfigRenderer.renderToml(profile(), DnsTunnelEngine.STORM, 10821, 10822)
        assertTrue(with.contains("LOCAL_DNS_ENABLED = true"))
        assertTrue(with.contains("LOCAL_DNS_PORT = 10822"))
    }

    @Test
    fun `the StormDNS engine is given no key it would refuse`() {
        val toml = DnsTunnelConfigRenderer.renderToml(profile(), DnsTunnelEngine.STORM, 10821, null)

        // The CottenDns fork added the resolver transport; the StormDNS engine
        // refuses a configuration carrying a key it does not know.
        assertFalse(toml.contains("RESOLVER_TRANSPORT"))
    }

    @Test
    fun `the CottenDns engine is given the keys it added`() {
        val toml = DnsTunnelConfigRenderer.renderToml(profile(), DnsTunnelEngine.COTTEN, 10821, null)

        assertTrue(toml.contains("""RESOLVER_TRANSPORT = "auto""""))
    }

    @Test
    fun `a quote in the key does not break the configuration`() {
        val toml = DnsTunnelConfigRenderer.renderToml(
            profile().apply { dnsTunnelKey = """a"b\c""" },
            DnsTunnelEngine.STORM,
            socksPort = 10821,
            dnsPort = null,
        )

        assertTrue(toml.contains("""ENCRYPTION_KEY = "a\"b\\c""""))
    }

    @Test
    fun `the resolver list keeps one resolver per line, without comments or repeats`() {
        val resolvers = DnsTunnelConfigRenderer.renderResolvers(
            profile().apply {
                dnsTunnelResolvers = """
                    # a comment
                    1.1.1.1
                    8.8.8.8

                    1.1.1.1
                    9.9.9.9:5353
                """.trimIndent()
            }
        )

        assertEquals("1.1.1.1\n8.8.8.8\n9.9.9.9:5353", resolvers)
    }

    @Test
    fun `extra engine settings are written as they were given`() {
        val toml = DnsTunnelConfigRenderer.renderToml(
            profile().apply { dnsTunnelExtra = "UPLOAD_COMPRESSION_TYPE = 2" },
            DnsTunnelEngine.STORM,
            socksPort = 10821,
            dnsPort = null,
        )

        assertTrue(toml.contains("UPLOAD_COMPRESSION_TYPE = 2"))
    }

    @Test
    fun `the CottenDns preset is written when one is chosen`() {
        val withPreset = DnsTunnelConfigRenderer.renderToml(
            profile().apply { dnsTunnelPreset = "speed" },
            DnsTunnelEngine.COTTEN,
            socksPort = 10821,
            dnsPort = null,
        )
        assertTrue(withPreset.contains("""CONFIG_PRESET = "speed""""))

        val withoutPreset = DnsTunnelConfigRenderer.renderToml(
            profile(),
            DnsTunnelEngine.COTTEN,
            socksPort = 10821,
            dnsPort = null,
        )
        assertFalse(withoutPreset.contains("CONFIG_PRESET"))
    }

    @Test
    fun `the Soar configuration is JSON, with the zone, the key and the listeners`() {
        val json = DnsTunnelConfigRenderer.renderConfig(
            profile().apply {
                dnsTunnelEngine = "soar"
                dnsTunnelKey = "AAAAC3NzaC1lZDI1NTE5AAAAIB2Sample"
                dnsTunnelCountry = "ir"
            },
            DnsTunnelEngine.SOAR,
            socksPort = 10821,
            dnsPort = 10822,
        )

        assertTrue(json.contains(""""zone": "v.example.com""""))
        assertTrue(json.contains(""""server_public_key": "AAAAC3NzaC1lZDI1NTE5AAAAIB2Sample""""))
        assertTrue(json.contains(""""listen": "127.0.0.1:10821""""))
        assertTrue(json.contains(""""dns_listen": "127.0.0.1:10822""""))
        assertTrue(json.contains(""""resolvers": ["1.1.1.1", "8.8.8.8"]"""))
        assertTrue(json.contains(""""country": "IR""""))
    }

    @Test
    fun `a public key is 32 bytes, as base64 or as hex`() {
        val base64 = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
        val hex = ByteArray(32) { it.toByte() }.joinToString("") { "%02x".format(it) }

        assertTrue(DnsTunnelConfigRenderer.isValidPublicKey(base64))
        assertTrue(DnsTunnelConfigRenderer.isValidPublicKey(hex))
        assertFalse(DnsTunnelConfigRenderer.isValidPublicKey("short"))
        assertFalse(DnsTunnelConfigRenderer.isValidPublicKey(""))
        assertFalse(DnsTunnelConfigRenderer.isValidPublicKey(null))
    }

    @Test
    fun `a resolver is an address, an address with a port, or an address with a prefix`() {        assertTrue(DnsTunnelConfigRenderer.isValidResolver("1.1.1.1"))
        assertTrue(DnsTunnelConfigRenderer.isValidResolver("1.1.1.1:5353"))
        assertTrue(DnsTunnelConfigRenderer.isValidResolver("192.168.1.0/30"))
        assertTrue(DnsTunnelConfigRenderer.isValidResolver("[2001:4860:4860::8888]:53"))
        assertFalse(DnsTunnelConfigRenderer.isValidResolver(""))
        assertFalse(DnsTunnelConfigRenderer.isValidResolver("not a resolver"))
    }
}
