package com.aras.client.core

import com.aras.client.dto.entities.ProfileItem
import com.aras.client.enums.EConfigType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which hops of a chain need an external process before the core can dial them.
 *
 * Aether and Mieru are not protocols the core speaks: each lowers to a SOCKS
 * outbound on loopback and a separate process serves that port. Nothing
 * connects unless that process is up, and when it is not the latency test
 * returns -1 — for every profile in a subscription at once, because an entry
 * proxy is the outermost hop shared by all of them.
 *
 * These pin the selection rule. The start path and the latency-test path both
 * route through it, so a chain that grows a new daemon-backed protocol has to be
 * added here or it silently dials a dead port.
 */
class HelperProcessSupportTest {

    private fun profile(type: EConfigType, remarks: String) =
        ProfileItem(configType = type, remarks = remarks, server = "1.2.3.4", serverPort = "443")

    @Test
    fun `a chain of core-native protocols needs no helper process`() {
        val chain = listOf(
            profile(EConfigType.VLESS, "exit"),
            profile(EConfigType.TROJAN, "middle"),
            profile(EConfigType.VMESS, "entry"),
        )

        assertTrue(HelperProcessSupport.helperProfilesOf(chain).isEmpty())
    }

    @Test
    fun `an aether entry proxy is found in the chain`() {
        val chain = listOf(
            profile(EConfigType.VLESS, "exit"),
            profile(EConfigType.AETHER, "entry"),
        )

        val helpers = HelperProcessSupport.helperProfilesOf(chain)

        assertEquals(listOf("entry"), helpers.map { it.remarks })
    }

    @Test
    fun `a mieru exit proxy is found in the chain`() {
        val chain = listOf(
            profile(EConfigType.MIERU, "exit"),
        )

        assertEquals(listOf("exit"), HelperProcessSupport.helperProfilesOf(chain).map { it.remarks })
    }

    @Test
    fun `a dns tunnel hop is found in the chain`() {
        // The tunnel is a transport: it lowers to a SOCKS hop like Aether and
        // Mieru, so its engine has to be up before the core dials it.
        val chain = listOf(
            profile(EConfigType.VLESS, "exit"),
            profile(EConfigType.DNSTUNNEL, "tunnel"),
        )

        assertEquals(listOf("tunnel"), HelperProcessSupport.helperProfilesOf(chain).map { it.remarks })
    }

    @Test
    fun `every daemon-backed hop is collected and chain order is kept`() {
        // Built innermost first, entry proxy last — the order CoreConfigManager
        // wires dialerProxy in, and the order it must be started in.
        val chain = listOf(
            profile(EConfigType.VLESS, "exit"),
            profile(EConfigType.AETHER, "hop-2"),
            profile(EConfigType.VMESS, "hop-1"),
            profile(EConfigType.MIERU, "entry"),
        )

        val helpers = HelperProcessSupport.helperProfilesOf(chain)

        assertEquals(listOf("hop-2", "entry"), helpers.map { it.remarks })
    }

    @Test
    fun `an unchained aether profile is still its own helper hop`() {
        // A standalone Aether profile resolves to a one-element chain. Dropping
        // it here is what stopped the core from ever being started at all.
        val chain = listOf(profile(EConfigType.AETHER, "warp"))

        assertEquals(listOf("warp"), HelperProcessSupport.helperProfilesOf(chain).map { it.remarks })
    }
}