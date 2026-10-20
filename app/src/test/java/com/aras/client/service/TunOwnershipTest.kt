package com.aras.client.service

import android.util.Log
import com.aras.client.core.CoreOutboundBuilder
import com.aras.client.core.TestSettings
import com.aras.client.dto.entities.ProfileItem
import com.aras.client.enums.EConfigType
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.MockedStatic
import org.mockito.Mockito.mockStatic

/**
 * The TUN has exactly one reader.
 *
 * There is one TUN descriptor and one thing that reads it: hev-socks5-tunnel,
 * which forwards every packet to the local SOCKS the core serves. A protocol
 * that took the descriptor for itself — the standalone AmneziaWG tunnel did —
 * gives the TUN a second reader: tun2socks drains the packets into a SOCKS
 * proxy that nothing serves in that mode, the tunnel comes up, and it carries
 * nothing. That is why AmneziaWG appeared to start and then do nothing.
 *
 * So every protocol the app offers is dialled by the core, WireGuard and
 * AmneziaWG included, and the app builds an outbound for each of them.
 */
class TunOwnershipTest {

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

    @Test
    fun `every protocol the app offers is dialled by the core`() {
        val dialled = listOf(
            EConfigType.VMESS, EConfigType.VLESS, EConfigType.TROJAN,
            EConfigType.SHADOWSOCKS, EConfigType.SOCKS, EConfigType.HTTP,
            EConfigType.WIREGUARD, EConfigType.AMNEZIAWG,
            EConfigType.HYSTERIA2,
            EConfigType.ANYTLS, EConfigType.MASQUE,
            EConfigType.AETHER, EConfigType.MIERU,
        )

        dialled.forEach { type ->
            val outbound = CoreOutboundBuilder.convert(profile(type))
            assertNotNull("$type must be dialled by the core, not by the app", outbound)
        }
    }

    @Test
    fun `the profiles that are resolved elsewhere build no outbound of their own`() {
        // Policies and proxy chains are resolved by the config manager; the builder
        // must decline them rather than emit a half-populated outbound.
        listOf(EConfigType.CUSTOM, EConfigType.POLICYGROUP, EConfigType.PROXYCHAIN).forEach { type ->
            assertNull(
                "$type must not build an outbound",
                CoreOutboundBuilder.convert(profile(type)),
            )
        }
    }

    private fun profile(type: EConfigType) = ProfileItem(
        configType = type,
        remarks = "test",
        server = "1.2.3.4",
        serverPort = "443",
        publicKey = "pubkey",
        secretKey = "secretkey",
        localAddress = "10.0.0.2/32",
        password = "pw",
    )
}
