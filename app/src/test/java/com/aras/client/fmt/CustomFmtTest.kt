package com.aras.client.fmt

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomFmtTest {

    @Test
    fun `reads the minimum core version of a single config`() {
        val raw = """
            {
              "remarks": "Serverless-v56-oob",
              "version": { "min": "26.11.1" },
              "outbounds": [{ "protocol": "direct" }]
            }
        """.trimIndent()
        assertEquals("26.11.1", CustomFmt.minCoreVersionRequired(raw))
    }

    @Test
    fun `takes the highest demand of a subscription array`() {
        val raw = """
            [
              { "remarks": "fragA", "version": { "min": "26.11.1" } },
              { "remarks": "older", "version": { "min": "26.9.27" } },
              { "remarks": "no-mark" }
            ]
        """.trimIndent()
        assertEquals("26.11.1", CustomFmt.minCoreVersionRequired(raw))
    }

    @Test
    fun `texts that demand nothing stay null`() {
        assertNull(CustomFmt.minCoreVersionRequired(null))
        assertNull(CustomFmt.minCoreVersionRequired(""))
        assertNull(CustomFmt.minCoreVersionRequired("{ \"remarks\": \"plain\" }"))
        assertNull(CustomFmt.minCoreVersionRequired("{ \"version\": { \"min\": \"\" } }"))
        assertNull(CustomFmt.minCoreVersionRequired("vless://not-json"))
    }

    @Test
    fun `compares versions numerically part by part`() {
        assertTrue(CustomFmt.isCoreOlderThan("26.10.22", "26.11.1"))
        assertTrue(CustomFmt.isCoreOlderThan("26.9.27", "26.10.1"))
        assertTrue(CustomFmt.isCoreOlderThan("26", "26.0.1"))
        assertFalse(CustomFmt.isCoreOlderThan("26.11.1", "26.11.1"))
        assertFalse(CustomFmt.isCoreOlderThan("26.12", "26.11.1"))
        assertFalse(CustomFmt.isCoreOlderThan("27", "26.11.1"))
    }

    @Test
    fun `suffixes on the core version are ignored`() {
        assertFalse(CustomFmt.isCoreOlderThan("26.11.1-PattNG", "26.11.1"))
        assertFalse(CustomFmt.isCoreOlderThan("Xray 26.11.1 (Patt)", "26.11.1"))
        // An unreadable core version never warns.
        assertFalse(CustomFmt.isCoreOlderThan("Unknown", "26.11.1"))
    }

    @Test
    fun `maxVersion keeps the greater demand`() {
        assertEquals("26.11.1", CustomFmt.maxVersion("26.10.22", "26.11.1"))
        assertEquals("26.11.1", CustomFmt.maxVersion("26.11.1", "26.9.27"))
        assertEquals("26.11.1", CustomFmt.maxVersion(null, "26.11.1"))
        assertEquals("26.10.22", CustomFmt.maxVersion("26.10.22", null))
        assertNull(CustomFmt.maxVersion(null, null))
    }

    @Test
    fun `probe config puts the masked outbound of the 443 rule first`() {
        val rewritten = CustomFmt.rewriteForOutboundTest(serverlessRaw("tcp-oob")) ?: fail("rewritten")
        val outbounds = JsonObjectProbe.outboundTags(rewritten)
        assertEquals(listOf("tcp-oob", "block", "tcp-direct", "tcp-fragment-tls"), outbounds)
    }

    @Test
    fun `domain and inbound keyed rules are not used for the probe`() {
        val rewritten = CustomFmt.rewriteForOutboundTest(serverlessRaw("tcp-fragment-tls")) ?: fail("rewritten")
        assertEquals(
            listOf("tcp-fragment-tls", "block", "tcp-direct", "tcp-oob"),
            JsonObjectProbe.outboundTags(rewritten)
        )
    }

    @Test
    fun `configs that already start with a usable outbound are left alone`() {
        val raw = """
            {
              "outbounds": [
                { "tag": "proxy", "protocol": "vless" },
                { "tag": "block", "protocol": "block" }
              ],
              "routing": { "rules": [
                { "port": "443", "network": "tcp", "outboundTag": "block" }
              ] }
            }
        """.trimIndent()
        assertNull(CustomFmt.rewriteForOutboundTest(raw))
    }

    @Test
    fun `without a matching rule the first usable outbound is used`() {
        val raw = """
            {
              "outbounds": [
                { "tag": "block", "protocol": "block" },
                { "tag": "dns-out", "protocol": "dns" },
                { "tag": "proxy", "protocol": "trojan" }
              ]
            }
        """.trimIndent()
        val rewritten = CustomFmt.rewriteForOutboundTest(raw) ?: fail("rewritten")
        assertEquals(listOf("proxy", "block", "dns-out"), JsonObjectProbe.outboundTags(rewritten))
    }

    @Test
    fun `nothing usable or nothing parseable returns null`() {
        assertNull(CustomFmt.rewriteForOutboundTest(null))
        assertNull(CustomFmt.rewriteForOutboundTest(""))
        assertNull(CustomFmt.rewriteForOutboundTest("vless://not-json"))
        assertNull(CustomFmt.rewriteForOutboundTest("""[{"outbounds":[]}]"""))
        assertNull(
            CustomFmt.rewriteForOutboundTest(
                """{ "outbounds": [ { "tag": "block", "protocol": "block" } ] }"""
            )
        )
    }

    /** The Serverless shape: block first, then rules keyed on domains, inbounds and IPs. */
    private fun serverlessRaw(maskedTag: String) = """
        {
          "remarks": "Serverless-v56",
          "inbounds": [{ "port": 10808, "protocol": "mixed" }],
          "outbounds": [
            { "tag": "block", "protocol": "block" },
            { "tag": "tcp-direct", "protocol": "direct" },
            { "tag": "tcp-oob", "protocol": "direct" },
            { "tag": "tcp-fragment-tls", "protocol": "direct" }
          ],
          "routing": { "rules": [
            { "outboundTag": "block", "domain": ["geosite:category-ads-all"] },
            { "outboundTag": "tcp-direct", "inboundTag": ["domestic-dns"], "network": "tcp" },
            { "outboundTag": "tcp-direct", "network": "tcp", "domain": ["domain:ir"] },
            { "outboundTag": "block", "ip": ["10.10.34.0/24"] },
            { "outboundTag": "tcp-direct", "network": "tcp", "ip": ["geoip:private"] },
            { "outboundTag": "block", "network": "udp", "port": "443", "ip": ["0.0.0.0/0", "::/0"] },
            { "outboundTag": "$maskedTag", "network": "tcp", "protocol": ["tls"], "ip": ["0.0.0.0/0", "::/0"] },
            { "outboundTag": "$maskedTag", "network": "tcp", "port": "443", "ip": ["0.0.0.0/0", "::/0"] },
            { "outboundTag": "tcp-direct", "network": "tcp", "ip": ["0.0.0.0/0", "::/0"] },
            { "outboundTag": "block", "port": "0-65535" }
          ] }
        }
    """.trimIndent()

    private fun fail(message: String): Nothing = throw AssertionError(message)

    private object JsonObjectProbe {
        fun outboundTags(raw: String): List<String> =
            JsonParser.parseString(raw).asJsonObject.getAsJsonArray("outbounds")
                .map { it.asJsonObject.get("tag").asString }
    }
}
