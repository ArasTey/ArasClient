package com.aras.client.fmt

import com.aras.client.dto.entities.ProfileItem
import com.aras.client.enums.EConfigType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The share link of a DNS-tunnel profile: what a user copies out of the app and
 * hands to someone else has to come back as the same profile.
 */
class DnsTunnelFmtTest {

    @Test
    fun `a link round trip keeps the tunnel parameters`() {
        val config = ProfileItem(
            configType = EConfigType.DNSTUNNEL,
            remarks = "tunnel",
            dnsTunnelEngine = "cotten",
            dnsTunnelDomain = "v.example.com",
            dnsTunnelKey = "s3cret",
            dnsTunnelMethod = 3,
            dnsTunnelResolvers = "1.1.1.1\n8.8.8.8",
            dnsTunnelPort = "10825",
            dnsTunnelDnsPort = "10826",
            dnsTunnelTarget = "My server",
        )

        val link = "dnstunnel://" + DnsTunnelFmt.toUri(config)
        val parsed = DnsTunnelFmt.parse(link)

        assertNotNull(parsed)
        assertEquals("tunnel", parsed?.remarks)
        assertEquals("v.example.com", parsed?.dnsTunnelDomain)
        assertEquals("cotten", parsed?.dnsTunnelEngine)
        assertEquals("s3cret", parsed?.dnsTunnelKey)
        assertEquals(3, parsed?.dnsTunnelMethod)
        assertEquals("1.1.1.1\n8.8.8.8", parsed?.dnsTunnelResolvers)
        assertEquals("10825", parsed?.dnsTunnelPort)
        assertEquals("10826", parsed?.dnsTunnelDnsPort)
        assertEquals("My server", parsed?.dnsTunnelTarget)
    }

    @Test
    fun `a key holding an equal sign survives the link`() {
        val config = ProfileItem(
            configType = EConfigType.DNSTUNNEL,
            dnsTunnelDomain = "v.example.com",
            dnsTunnelKey = "abc==",
        )

        val link = "dnstunnel://" + DnsTunnelFmt.toUri(config)
        val parsed = DnsTunnelFmt.parse(link)

        assertEquals("abc==", parsed?.dnsTunnelKey)
    }

    @Test
    fun `a link without a scheme is still read`() {
        val parsed = DnsTunnelFmt.parse(
            "dnstunnel://v.example.com?engine=storm&key=k#name"
        )

        assertEquals("v.example.com", parsed?.dnsTunnelDomain)
        assertEquals("storm", parsed?.dnsTunnelEngine)
        assertEquals("name", parsed?.remarks)
    }

    @Test
    fun `a port that is not a port is dropped`() {
        val parsed = DnsTunnelFmt.parse(
            "dnstunnel://v.example.com?port=99999&dns=0"
        )

        assertEquals(null, parsed?.dnsTunnelPort)
        assertEquals(null, parsed?.dnsTunnelDnsPort)
    }

    @Test
    fun `a link names the engine it was written for`() {
        val link = "dnstunnel://" + DnsTunnelFmt.toUri(
            ProfileItem(
                configType = EConfigType.DNSTUNNEL,
                dnsTunnelDomain = "v.example.com",
                dnsTunnelEngine = "cotten",
            )
        )

        assertTrue(link.contains("engine=cotten"))
    }
}
