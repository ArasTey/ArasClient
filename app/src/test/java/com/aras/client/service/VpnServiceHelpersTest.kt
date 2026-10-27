package com.aras.client.service

import com.aras.client.AppConfig
import com.aras.client.handler.SettingsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.IllegalArgumentException

/**
 * The builder's DNS servers and MTU, and the IPv6 fallback at a failed establish: what a
 * setting the builder refuses, an address family the interface lacks, or a kernel that
 * refuses the interface's IPv6 do to a start — and none of it ends the start.
 */
class VpnServiceHelpersTest {

    // --------------------------------------------------------------- the MTU

    @Test
    fun `the VPN MTU survives the settings dialog's spaces and line breaks`() {
        assertEquals(1500, SettingsManager.vpnMtu("1500"))
        assertEquals(1500, SettingsManager.vpnMtu("1500\n"))
        assertEquals(1500, SettingsManager.vpnMtu(" 1500 "))
        assertEquals(1280, SettingsManager.vpnMtu("1280"))
    }

    @Test
    fun `the VPN MTU falls back to the default where the builder would refuse the setting`() {
        // setMtu refuses 0 and less with an exception, and the VPN would not start.
        assertEquals(AppConfig.VPN_MTU, SettingsManager.vpnMtu("0"))
        assertEquals(AppConfig.VPN_MTU, SettingsManager.vpnMtu("-1"))
        assertEquals(AppConfig.VPN_MTU, SettingsManager.vpnMtu("0\n"))
        // And a setting that cannot be read is no MTU at all.
        assertEquals(AppConfig.VPN_MTU, SettingsManager.vpnMtu(null))
        assertEquals(AppConfig.VPN_MTU, SettingsManager.vpnMtu(""))
        assertEquals(AppConfig.VPN_MTU, SettingsManager.vpnMtu("big"))
    }

    // --------------------------------------------------------------- the DNS servers

    private var refusals = emptyList<String>()

    /**
     * VpnService.Builder.addDnsServer as Android stands in for it: a loopback or wildcard
     * (0.0.0.0, ::) address, or one it cannot read, it refuses with an IllegalArgumentException.
     */
    private val realBuilder: (String) -> Unit = { server ->
        val refused = server == "0.0.0.0" || server == "::" || !server.contains(':') && server.count { it == '.' } != 3
        if (refused) throw IllegalArgumentException("bad dns server $server") else Unit
    }

    @Test
    fun `the VPN DNS servers keep their family and their refusals off the interface`() {
        val ipv6 = addVpnDnsServers(
            listOf("1.1.1.1", "2606:4700:4700::1111", "dns.example", "0.0.0.0"),
            ipv6 = true,
            realBuilder,
        ) { _, _ -> }
        // A name is no server at all; a wildcard is skipped, not a failure.
        assertEquals(listOf("1.1.1.1", "2606:4700:4700::1111"), ipv6)

        refusals = emptyList()
        val refused = addVpnDnsServers(
            listOf("1.1.1.1", "2606:4700:4700::1111"),
            ipv6 = true,
            { if (it == "2606:4700:4700::1111") throw IllegalArgumentException(it) else Unit },
        ) { server, _ -> refusals += server }
        assertEquals(listOf("1.1.1.1"), refused)
        assertEquals(listOf("2606:4700:4700::1111"), refusals)
    }

    @Test
    fun `an interface without IPv6 takes no IPv6 DNS server, and none stays none under it`() {
        // A DNS server allows its address family on the VPN, so one of IPv6 would leave
        // the VPN's IPv6 unblocked, and it would go around the VPN.
        val added = addVpnDnsServers(
            listOf("1.1.1.1", "2606:4700:4700::1111", "::1"),
            ipv6 = false,
            { it },
        ) { _, _ -> }
        assertEquals(listOf("1.1.1.1"), added)

        // Without IPv6, none given means the default: the default network's servers would
        // be used otherwise, and the family would slip in through them as well.
        val withDefault = addVpnDnsServers(
            listOf("dns.example", ""),
            ipv6 = false,
            { it },
        ) { _, _ -> }
        assertEquals(listOf(AppConfig.DNS_VPN), withDefault)

        // With IPv6, nothing given at all stays none: the extra address would allow a
        // family the setting took away. Addresses given that are no server at all fall
        // back to the default meanwhile.
        assertTrue(addVpnDnsServers(emptyList(), ipv6 = true, { it }) { _, _ -> }.isEmpty())
        assertEquals(
            listOf(AppConfig.DNS_VPN),
            addVpnDnsServers(listOf("dns.example"), ipv6 = true, { it }) { _, _ -> },
        )
    }

    // --------------------------------------------------------------- the IPv6 fallback

    @Test
    fun `a kernel that refuses the IPv6 interface is met with one without it`() {
        assertEquals("v4", establishFallingBackToIpv4(
            ipv6 = false,
            { "v4" },
            { error("no second try was needed") },
        ) { error("no refusal was seen") })
        assertEquals("v4", establishFallingBackToIpv4(
            ipv6 = true,
            { "v4" },
            { error("no second try was needed") },
        ) { error("no refusal was seen") })
        var refused = false
        assertEquals(
            "without",
            establishFallingBackToIpv4(
                ipv6 = true,
                { throw IllegalStateException("refused") },
                { "without" },
            ) { refused = true },
        )
        assertTrue(refused)
    }
}
