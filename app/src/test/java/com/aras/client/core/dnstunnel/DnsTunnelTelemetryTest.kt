package com.aras.client.core.dnstunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the engine prints about its session: the progress of the handshake, the
 * resolvers it found, and the traffic it carries.
 */
class DnsTunnelTelemetryTest {

    @Test
    fun `the progress line is read`() {
        DnsTunnelProcessManager.applyTelemetry("WD_PROGRESS phase=session percent=90")

        val state = DnsTunnelProcessManager.currentState()
        assertEquals("session", state.phase)
        assertEquals(90, state.percent)
    }

    @Test
    fun `the resolver line is read`() {
        DnsTunnelProcessManager.applyTelemetry(
            "WD_RESOLVERS active=1.1.1.1,8.8.8.8 standby=9.9.9.9 valid=2"
        )

        val state = DnsTunnelProcessManager.currentState()
        assertEquals(listOf("1.1.1.1", "8.8.8.8"), state.activeResolvers)
        assertEquals(listOf("9.9.9.9"), state.standbyResolvers)
        assertEquals(2, state.validResolvers)
    }

    @Test
    fun `an empty resolver list is not a resolver`() {
        DnsTunnelProcessManager.applyTelemetry("WD_RESOLVERS active=- standby=- valid=0")

        val state = DnsTunnelProcessManager.currentState()
        assertEquals(emptyList<String>(), state.activeResolvers)
        assertEquals(0, state.validResolvers)
    }

    @Test
    fun `the traffic line is read`() {
        DnsTunnelProcessManager.applyTelemetry("12.3 MB/s (Total: 45.6 MB) | 1.2 MB/s (Total: 9.9 MB)")

        val state = DnsTunnelProcessManager.currentState()
        assertEquals("12.3 MB/s", state.uploadSpeed)
        assertEquals("1.2 MB/s", state.downloadSpeed)
    }

    @Test
    fun `a line that says nothing about the session is left alone`() {
        val before = DnsTunnelProcessManager.currentState()

        DnsTunnelProcessManager.applyTelemetry("starting the client")

        assertEquals(before, DnsTunnelProcessManager.currentState())
    }

    @Test
    fun `the engine's own complaint is kept for the user`() {
        DnsTunnelProcessManager.handleOutput(
            "[StormDNS Client] [WARN] ❌ Rejected (1/1): v.example.com via 1.1.1.1:53 | reason=UPLOAD_MTU"
        )

        val error = DnsTunnelProcessManager.lastError()
        assertTrue(error != null && error.contains("v.example.com"))
    }
}
