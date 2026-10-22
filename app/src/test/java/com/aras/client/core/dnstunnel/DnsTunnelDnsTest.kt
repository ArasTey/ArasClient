package com.aras.client.core.dnstunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The address the core is given for the DNS a tunnel engine serves.
 */
class DnsTunnelDnsTest {

    @Test
    fun `the address carries a scheme`() {
        // The core reads a bare "127.0.0.1:10822" as a URL and refuses to
        // start on it, so the plain-DNS-over-UDP form is written in full.
        val address = DnsTunnelDns.serverAddress(10822)

        assertEquals("udp://127.0.0.1:10822", address)
        assertTrue(address.startsWith("udp://"))
    }
}
