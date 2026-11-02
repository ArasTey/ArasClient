package com.aras.client.service

import com.aras.client.enums.EConfigType
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every supported profile uses one TUN ownership model: tun2socks is the
 * only TUN reader and the bundled core dials the selected outbound.
 */
class TunOwnershipTest {

    @Test
    fun `AmneziaWG is dialled by the core and still uses tun2socks`() {
        assertTrue(shouldRunTun2Socks(EConfigType.AMNEZIAWG))
    }

    @Test
    fun `plain wireguard still uses tun2socks`() {
        // Both protocols use the core's WireGuard outbound.
        assertTrue(shouldRunTun2Socks(EConfigType.WIREGUARD))
    }

    @Test
    fun `every core-dialed protocol still uses tun2socks`() {
        val coreDialed = listOf(
            EConfigType.VMESS, EConfigType.VLESS, EConfigType.TROJAN,
            EConfigType.SHADOWSOCKS, EConfigType.SOCKS, EConfigType.HTTP,
            EConfigType.HYSTERIA, EConfigType.HYSTERIA2,
            EConfigType.ANYTLS, EConfigType.MASQUE,
            EConfigType.AETHER, EConfigType.MIERU,
            EConfigType.CUSTOM,
        )

        coreDialed.forEach { assertTrue("tun2socks must run for $it", shouldRunTun2Socks(it)) }
    }

    @Test
    fun `an unresolved profile falls back to tun2socks`() {
        assertTrue(shouldRunTun2Socks(null))
    }
}