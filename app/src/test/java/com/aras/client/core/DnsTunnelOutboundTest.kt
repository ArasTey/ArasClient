package com.aras.client.core

import com.aras.client.AppConfig
import com.aras.client.dto.entities.ProfileItem
import com.aras.client.enums.EConfigType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * A DNS-tunnel profile is carried by a SOCKS hop to the engine's loopback
 * listener, the same shape an Aether or a Mieru profile has: the tunnel and its
 * key belong to that process, so the app must not try to build one itself.
 */
class DnsTunnelOutboundTest {

    @Before
    fun setUp() {
        TestSettings.install()
    }

    @Test
    fun `a dns tunnel profile dials the engine on the default port`() {
        val outbound = CoreOutboundBuilder.convert(
            ProfileItem(
                configType = EConfigType.DNSTUNNEL,
                dnsTunnelDomain = "v.example.com",
                dnsTunnelKey = "s3cret",
            )
        )

        assertEquals("socks", outbound?.protocol)
        assertEquals(AppConfig.LOOPBACK, outbound?.settings?.address)
        assertEquals(AppConfig.PORT_DNSTUNNEL_SOCKS.toInt(), outbound?.settings?.port)
    }

    @Test
    fun `a dns tunnel profile honours its own port`() {
        val outbound = CoreOutboundBuilder.convert(
            ProfileItem(configType = EConfigType.DNSTUNNEL, dnsTunnelPort = "10825")
        )

        assertEquals(10825, outbound?.settings?.port)
    }

    @Test
    fun `a dns tunnel profile carries no credential of its own into the configuration`() {
        val outbound = CoreOutboundBuilder.convert(
            ProfileItem(
                configType = EConfigType.DNSTUNNEL,
                dnsTunnelDomain = "v.example.com",
                dnsTunnelKey = "s3cret",
            )
        )

        // The hop is a SOCKS, not a tunnel: the key and the zone belong to the
        // engine's own configuration, not to the generated one.
        assertNull(outbound?.settings?.user)
        assertNull(outbound?.settings?.pass)
        assertNull(outbound?.settings?.peers)
    }
}
