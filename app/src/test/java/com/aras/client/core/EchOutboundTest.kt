package com.aras.client.core

import android.util.Log
import com.aras.client.AppConfig
import com.aras.client.dto.XrayConfig
import com.aras.client.dto.entities.ProfileItem
import com.aras.client.enums.EConfigType
import com.aras.client.util.JsonUtil
import com.google.gson.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.MockedStatic
import org.mockito.Mockito.mockStatic

/**
 * The ECH outbound: the raw-JSON outbound a TLS profile sends its echConfigList query through.
 * These pin the checks, the tag linking and the final configuration shape, so the JSON handed
 * to the core keeps matching what PattNG (P67) generates.
 */
class EchOutboundTest {

    private lateinit var mockLog: MockedStatic<Log>

    @Before
    fun setUp() {
        mockLog = mockStatic(Log::class.java)
        TestSettings.install()
    }

    @After
    fun tearDown() {
        mockLog.close()
    }

    // The PattNG recipe: a DoH-able resolver queried through a masked, certificate-trusting outbound.
    private val recipeOutbound =
        """{"tag":"tls-repack-dns","protocol":"freedom","settings":{"redirect":"1.1.1.1:443"},""" +
            """"streamSettings":{"security":"tls","tlsSettings":{"serverName":"www.microsoft.com",""" +
            """"verifyPeerCertByName":"fromMitm,www.microsoft.com","alpn":["fromMitm"],"fingerprint":"chrome"}}}"""

    private fun profile(type: EConfigType) = ProfileItem(
        configType = type,
        remarks = "test",
        server = "1.2.3.4",
        serverPort = "443",
    )

    private fun tlsProfile(type: EConfigType) = profile(type).apply {
        security = AppConfig.TLS
        echConfigList = "h2c://1.1.1.1/dns-query"
        echOutbound = recipeOutbound
    }

    private fun config(vararg outbounds: XrayConfig.OutboundBean) = XrayConfig(
        log = XrayConfig.LogBean(),
        inbounds = ArrayList(),
        outbounds = ArrayList(outbounds.toList()),
        routing = XrayConfig.RoutingBean(
            domainStrategy = "AsIs",
            rules = ArrayList(),
        ),
    )

    private fun tlsOutbound(
        tag: String = "proxy",
        echOutbound: String? = null,
    ) = XrayConfig.OutboundBean(
        tag = tag,
        protocol = "vless",
        settings = XrayConfig.OutboundBean.OutSettingsBean(id = "ray-uuid"),
        streamSettings = XrayConfig.OutboundBean.StreamSettingsBean(
            security = AppConfig.TLS,
            tlsSettings = XrayConfig.OutboundBean.StreamSettingsBean.TlsSettingsBean(
                serverName = "example.com",
                echConfigList = "h2c://1.1.1.1/dns-query",
                echOutbound = echOutbound,
            ),
        ),
        mux = null,
    )

    @Test
    fun `applies to the TLS protocols and Hysteria2, and to nothing else`() {
        assertNull(EchOutbound.validate(tlsProfile(EConfigType.VLESS)))
        assertNull(EchOutbound.validate(tlsProfile(EConfigType.VMESS)))
        assertNull(EchOutbound.validate(tlsProfile(EConfigType.SHADOWSOCKS)))
        assertNull(EchOutbound.validate(tlsProfile(EConfigType.TROJAN)))
        assertNull(EchOutbound.validate(tlsProfile(EConfigType.HYSTERIA2)))

        val hy2 = tlsProfile(EConfigType.HYSTERIA2).apply { security = null }
        assertNull(EchOutbound.validate(hy2))

        // Kept, but neither checked nor used
        assertNull(EchOutbound.validate(tlsProfile(EConfigType.AETHER)))
        assertNull(EchOutbound.validate(tlsProfile(EConfigType.PROXYCHAIN)))
    }

    @Test
    fun `an echOutbound without echConfigList is an error`() {
        val profile = tlsProfile(EConfigType.VLESS).apply { echConfigList = null }
        assertEquals(EchOutbound.Error.NEEDS_ECH_CONFIG_LIST, EchOutbound.validate(profile))
    }

    @Test
    fun `an echOutbound that is not a JSON object is an error`() {
        val profile = tlsProfile(EConfigType.VLESS).apply { echOutbound = "not json" }
        assertEquals(EchOutbound.Error.INVALID_JSON, EchOutbound.validate(profile))
    }

    @Test
    fun `reserved tags are an error, a blank one too`() {
        for (echOutbound in listOf(
            """{"tag":"direct","protocol":"freedom"}""",
            """{"tag":"block","protocol":"freedom"}""",
            """{"tag":"proxy","protocol":"freedom"}""",
            """{"tag":"proxy-panel","protocol":"freedom"}""",
            """{"tag":"exit-node","protocol":"freedom"}""",
            """{"protocol":"freedom"}""",
            """{"tag":"","protocol":"freedom"}""",
        )) {
            val profile = tlsProfile(EConfigType.VLESS).apply { this.echOutbound = echOutbound }
            assertEquals(echOutbound, EchOutbound.Error.INVALID_TAG, EchOutbound.validate(profile))
        }
    }

    @Test
    fun `convert carries the echOutbound into the TLS settings of a TLS outbound`() {
        val outbound = CoreOutboundBuilder.convert(tlsProfile(EConfigType.VLESS))

        assertTrue(outbound != null)
        assertEquals(recipeOutbound, outbound!!.streamSettings?.tlsSettings?.echOutbound)
        assertEquals("h2c://1.1.1.1/dns-query", outbound.streamSettings?.tlsSettings?.echConfigList)
    }

    @Test
    fun `a REALITY outbound uses realitySettings alone`() {
        val profile = tlsProfile(EConfigType.VLESS).apply { security = AppConfig.REALITY }
        val outbound = CoreOutboundBuilder.convert(profile)

        assertTrue(outbound != null)
        assertNull(outbound!!.streamSettings?.tlsSettings)
        // The ECH outbound is a TLS-only setting, as in PattNG: on REALITY it is kept but unused
        assertNull(outbound.streamSettings?.realitySettings?.echOutbound)
    }

    @Test
    fun `serialize appends the ECH outbound after every other outbound and points the sockopt at it`() {
        val xrayConfig = config(
            tlsOutbound(echOutbound = recipeOutbound),
            tlsOutbound(tag = AppConfig.TAG_DIRECT, echOutbound = recipeOutbound),
        )

        val result = EchOutbound.serialize(xrayConfig)
        assertTrue("serialize failed: $result", result is EchOutbound.Result.Done)

        val json = JsonUtil.parseString((result as EchOutbound.Result.Done).content)!!
        val outbounds = json.getAsJsonArray("outbounds")
        assertEquals(3, outbounds.size())

        val appended = outbounds[2].asJsonObject
        assertEquals("tls-repack-dns", appended.get("tag").asString)
        val appendedTls = appended.getAsJsonObject("streamSettings").getAsJsonObject("tlsSettings")
        assertEquals("fromMitm,www.microsoft.com", appendedTls.get("verifyPeerCertByName").asString)
        assertEquals("www.microsoft.com", appendedTls.get("serverName").asString)
        assertEquals("chrome", appendedTls.get("fingerprint").asString)

        assertEquals("tls-repack-dns", xrayConfig.outbounds[0].streamSettings?.tlsSettings?.echSockopt?.dialerProxy)
        assertEquals("tls-repack-dns", xrayConfig.outbounds[1].streamSettings?.tlsSettings?.echSockopt?.dialerProxy)

        // the as-written ECH outbound never lands in the exported TLS settings itself
        for (i in 0..1) {
            val tlsSettings = outbounds[i].asJsonObject
                .getAsJsonObject("streamSettings").getAsJsonObject("tlsSettings")
            assertFalse(tlsSettings.has("echOutbound"))
        }
    }

    @Test
    fun `outbounds sharing the same echOutbound get the same tag, appended once`() {
        val xrayConfig = config(tlsOutbound(echOutbound = recipeOutbound), tlsOutbound(echOutbound = recipeOutbound))
        val result = EchOutbound.serialize(xrayConfig)
        assertTrue(result is EchOutbound.Result.Done)
        val json = JsonUtil.parseString((result as EchOutbound.Result.Done).content)!!

        assertEquals(3, json.getAsJsonArray("outbounds").size())
        assertEquals(
            "tls-repack-dns",
            xrayConfig.outbounds[0].streamSettings?.tlsSettings?.echSockopt?.dialerProxy,
        )
        assertEquals(
            "tls-repack-dns",
            xrayConfig.outbounds[1].streamSettings?.tlsSettings?.echSockopt?.dialerProxy,
        )
    }

    @Test
    fun `a written tag an earlier ECH outbound has is renumbered`() {
        val retagged = recipeOutbound.replace("tls-repack-dns", "query-dns")
        val second = retagged.replace("1.1.1.1:443", "9.9.9.9:443")
        val xrayConfig = config(tlsOutbound(echOutbound = retagged), tlsOutbound(echOutbound = second))

        val result = EchOutbound.serialize(xrayConfig)
        assertTrue(result is EchOutbound.Result.Done)
        val json = JsonUtil.parseString((result as EchOutbound.Result.Done).content)!!
        val tags = json.getAsJsonArray("outbounds").map { it.asJsonObject.get("tag").asString }
        assertEquals(listOf("proxy", "proxy", "query-dns", "query-dns-2"), tags)
    }

    @Test
    fun `a tag an existing outbound already uses is a conflict`() {
        val conflicting = recipeOutbound.replace("tls-repack-dns", "relay")
        val xrayConfig = config(tlsOutbound(tag = "relay", echOutbound = conflicting))

        val result = EchOutbound.serialize(xrayConfig)
        assertTrue(result is EchOutbound.Result.TagConflict)
        assertEquals("relay", (result as EchOutbound.Result.TagConflict).tag)
    }

    @Test
    fun `an ECH outbound whose text fails validation fails the configuration`() {
        val xrayConfig = config(tlsOutbound(echOutbound = "not json"))
        assertEquals(EchOutbound.Result.Invalid(EchOutbound.Error.INVALID_JSON), EchOutbound.serialize(xrayConfig))
    }

    @Test
    fun `serializing a configuration without an echOutbound changes nothing`() {
        val xrayConfig = config(tlsOutbound())
        val result = EchOutbound.serialize(xrayConfig)
        assertTrue(result is EchOutbound.Result.Done)
        val json = JsonUtil.parseString((result as EchOutbound.Result.Done).content)!!
        assertEquals(1, json.getAsJsonArray("outbounds").size())
        val tlsSettings = json.getAsJsonArray("outbounds")[0].asJsonObject
            .getAsJsonObject("streamSettings").getAsJsonObject("tlsSettings")
        assertTrue(tlsSettings is JsonObject)
        assertFalse(tlsSettings.has("echOutbound"))
        assertFalse(tlsSettings.has("echSockopt"))
    }
}
