package com.aras.client.core

import com.aras.client.AppConfig
import com.aras.client.dto.entities.ProfileItem
import com.aras.client.enums.EConfigType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * A Mieru profile is carried by a SOCKS hop to the mieru client's loopback listener,
 * the same shape an Aether profile has: the tunnel and its credentials belong to that
 * process, so the app must not try to build one itself.
 */
class MieruOutboundTest {

    @Before
    fun setUp() {
        TestSettings.install()
    }

    @Test
    fun `a mieru profile dials the client on the default port`() {
        val outbound = CoreOutboundBuilder.convert(
            ProfileItem(
                configType = EConfigType.MIERU,
                server = "198.51.100.9",
                serverPort = "1080",
            )
        )

        assertEquals("socks", outbound?.protocol)
        assertEquals(AppConfig.LOOPBACK, outbound?.settings?.address)
        assertEquals(AppConfig.PORT_MIERU_SOCKS.toInt(), outbound?.settings?.port)
    }

    @Test
    fun `a mieru profile honours its own listen port`() {
        val outbound = CoreOutboundBuilder.convert(
            ProfileItem(configType = EConfigType.MIERU, mieruListenPort = "10825")
        )

        assertEquals(10825, outbound?.settings?.port)
    }

    @Test
    fun `a mieru profile has no key of its own to carry`() {
        // The outbound is a hop, not a tunnel: nothing from the profile's credential
        // belongs in the generated configuration.
        val outbound = CoreOutboundBuilder.convert(
            ProfileItem(
                configType = EConfigType.MIERU,
                server = "198.51.100.9",
                serverPort = "1080",
                username = "user1",
                password = "pass1",
            )
        )

        assertEquals(null, outbound?.settings?.user)
        assertEquals(null, outbound?.settings?.pass)
        assertEquals(null, outbound?.settings?.peers)
    }
}