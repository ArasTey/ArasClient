package com.aras.client.radar.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RadarConfigProbeCacheTest {
    @Test
    fun successfulProbeIsCachedForThreeMinutes() {
        RadarConfigProbeCache.clear()
        val candidate = candidate()
        val check = RadarConfigCheck(candidate, RadarProbeStatus.TCP_REACHABLE, tcpLatencyMs = 42, detail = "ok")
        RadarConfigProbeCache.put(check, nowMs = 1_000)

        assertEquals(42L, RadarConfigProbeCache.get(candidate, nowMs = 1_000 + 179_000)?.tcpLatencyMs)
        assertNull(RadarConfigProbeCache.get(candidate, nowMs = 1_000 + 181_000))
    }

    @Test
    fun failedProbeHasShorterCacheLifetime() {
        RadarConfigProbeCache.clear()
        val candidate = candidate()
        val check = RadarConfigCheck(candidate, RadarProbeStatus.TCP_FAILED, detail = "failed")
        RadarConfigProbeCache.put(check, nowMs = 5_000)

        assertEquals(RadarProbeStatus.TCP_FAILED, RadarConfigProbeCache.get(candidate, nowMs = 5_000 + 44_000)?.status)
        assertNull(RadarConfigProbeCache.get(candidate, nowMs = 5_000 + 46_000))
    }

    private fun candidate() = RadarConfigCandidate(
        uri = "vless://id@example.com:443",
        protocol = "vless",
        host = "example.com",
        port = 443,
        source = "test",
        identity = "vless://id@example.com:443"
    )
}
