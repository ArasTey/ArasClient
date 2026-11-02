package com.aras.client.radar.config

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/** TCP pre-check only. It never labels a TCP handshake as a verified VPN tunnel. */
object RadarConfigProbe {
    fun check(candidate: RadarConfigCandidate): RadarConfigCheck {
        val dnsStart = System.nanoTime()
        val addresses = try {
            InetAddress.getAllByName(candidate.host)
        } catch (_: Exception) {
            return RadarConfigCheck(candidate, RadarProbeStatus.DNS_FAILED, detail = "DNS lookup failed")
        }
        val dnsMs = elapsedMs(dnsStart)
        if (addresses.isEmpty()) return RadarConfigCheck(candidate, RadarProbeStatus.DNS_FAILED, dnsLatencyMs = dnsMs, detail = "No DNS address")
        if (addresses.any(::isNonPublicAddress)) {
            return RadarConfigCheck(candidate, RadarProbeStatus.PRIVATE_ADDRESS, dnsLatencyMs = dnsMs, detail = "Private or reserved address; probe skipped")
        }

        var lastError: Exception? = null
        var successfulAddress: InetAddress? = null
        val samples = mutableListOf<Long>()
        for (address in addresses) {
            repeat(TCP_ATTEMPTS) {
                val tcpStart = System.nanoTime()
                try {
                    Socket().use { it.connect(InetSocketAddress(address, candidate.port), TCP_TIMEOUT_MS) }
                    samples += elapsedMs(tcpStart)
                    successfulAddress = address
                } catch (error: Exception) {
                    lastError = error
                }
            }
            if (samples.isNotEmpty()) break
        }
        if (samples.isNotEmpty()) {
            val sorted = samples.sorted()
            val median = sorted[sorted.size / 2]
            return RadarConfigCheck(
                candidate = candidate, status = RadarProbeStatus.TCP_REACHABLE,
                dnsLatencyMs = dnsMs, tcpLatencyMs = median,
                detail = "TCP reachable (" + samples.size + "/" + TCP_ATTEMPTS + " samples); VPN handshake not verified",
                resolvedIp = successfulAddress?.hostAddress
            )
        }
        val detail = when (lastError) {
            is java.net.SocketTimeoutException -> "TCP connection timed out"
            is java.net.ConnectException -> "TCP connection refused or unreachable"
            else -> "TCP connection failed"
        }
        return RadarConfigCheck(candidate, RadarProbeStatus.TCP_FAILED, dnsMs, detail = detail)
    }

    private fun isNonPublicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) return true
        val bytes = address.address
        if (bytes.size == 4) {
            val a = bytes[0].toInt() and 0xff
            val b = bytes[1].toInt() and 0xff
            if (a == 100 && b in 64..127) return true
            if (a == 192 && b == 0) return true
            if (a == 198 && b in 18..19) return true
            if (a == 203 && b == 0 && (bytes[2].toInt() and 0xff) == 113) return true
        }
        return false
    }

    private fun elapsedMs(startNanos: Long): Long = ((System.nanoTime() - startNanos) / 1_000_000L).coerceAtLeast(0L)
    private const val TCP_TIMEOUT_MS = 1_800
    private const val TCP_ATTEMPTS = 2
}
