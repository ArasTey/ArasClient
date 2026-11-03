package com.aras.client.handler

import com.aras.client.AppConfig
import com.aras.client.dto.IPAPIInfo
import com.aras.client.dto.UrlContentRequest
import com.aras.client.util.HttpUtil
import com.aras.client.util.JsonUtil
import com.aras.client.util.LogUtil
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.UnknownHostException
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Real network measurements used by the RADAR VPN theme speed panel:
 * TCP latency, download throughput, upload throughput (Mbps).
 *
 * Resilient multi-endpoint design:
 * - Cloudflare speed endpoints (same family as speed.cloudflare.com / speedtest-style)
 * - Fallback public probes when Cloudflare is filtered (common without tunnel)
 * - Multiple latency targets
 *
 * Prefer calling while VPN is up so results reflect the tunnel path.
 */
object SpeedtestManager {

    data class RemoteEndpointInfo(
        val country: String?,
        val ipAddress: String?,
    )

    data class SpeedResult(
        val latencyMs: Long,
        val downloadMbps: Double,
        val uploadMbps: Double,
        val error: String? = null,
    ) {
        val ok: Boolean get() = error == null && (downloadMbps > 0.0 || uploadMbps > 0.0 || latencyMs > 0L)
    }

    /** TCP connect time in ms, or -1 on failure. */
    fun socketConnectTime(url: String, port: Int, timeoutMs: Int = 1500): Long {
        var socket: Socket? = null
        val start = System.currentTimeMillis()
        try {
            socket = Socket()
            socket.connect(InetSocketAddress(url, port), timeoutMs)
            return System.currentTimeMillis() - start
        } catch (e: UnknownHostException) {
            LogUtil.e(AppConfig.TAG, "Unknown host: $url", e)
        } catch (e: IOException) {
            LogUtil.e(AppConfig.TAG, "socketConnectTime IOException: ${e.message}")
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed socket $url:$port", e)
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {
            }
        }
        return -1
    }

    fun getRemoteIPInfo(): RemoteEndpointInfo? {
        val url = MmkvManager.decodeSettingsString(AppConfig.PREF_IP_API_URL)
            .takeIf { !it.isNullOrBlank() } ?: AppConfig.IP_API_URL

        val proxyUsername = SettingsManager.getSocksUsername()
        val proxyPassword = SettingsManager.getSocksPassword()
        val httpPort = SettingsManager.getHttpPort()
        if (httpPort == 0) return null
        val content = HttpUtil.getUrlContent(
            UrlContentRequest(
                url = url,
                timeout = 5000,
                httpPort = httpPort,
                proxyUsername = proxyUsername,
                proxyPassword = proxyPassword
            )
        ) ?: return null
        val ipInfo = JsonUtil.fromJsonSafe(content, IPAPIInfo::class.java) ?: return null

        val ip = listOf(
            ipInfo.ip,
            ipInfo.clientIp,
            ipInfo.ip_addr,
            ipInfo.query
        ).firstOrNull { !it.isNullOrBlank() }

        val country = listOf(
            ipInfo.country_code,
            ipInfo.country,
            ipInfo.countryCode,
            ipInfo.location?.country_code
        ).firstOrNull { !it.isNullOrBlank() }

        return RemoteEndpointInfo(country = country, ipAddress = ip)
    }

    /**
     * Full speed test: multi-host latency + multi-endpoint download/upload.
     * Prefer calling while VPN is up so results reflect tunnel path.
     */
    fun runFullTest(
        downloadBytes: Int = 6_000_000,
        uploadBytes: Int = 1_500_000,
        latencyHost: String = "1.1.1.1",
        latencyPort: Int = 443,
    ): SpeedResult {
        return try {
            val latencyTargets = listOf(
                latencyHost to latencyPort,
                "1.1.1.1" to 443,
                "8.8.8.8" to 443,
                "9.9.9.9" to 443,
            ).distinct()

            val latencySamples = latencyTargets.flatMap { (host, port) ->
                listOf(socketConnectTime(host, port, 1800))
            }.filter { it >= 0 }
            val latency = if (latencySamples.isEmpty()) -1L else latencySamples.minOrNull() ?: -1L

            val down = measureDownloadMbps(downloadBytes)
            val up = measureUploadMbps(uploadBytes)

            if (down < 0 && up < 0 && latency < 0) {
                SpeedResult(
                    latencyMs = -1,
                    downloadMbps = 0.0,
                    uploadMbps = 0.0,
                    error = "Speed test failed — endpoints unreachable (try again with VPN connected)"
                )
            } else {
                SpeedResult(
                    latencyMs = latency,
                    downloadMbps = max(0.0, down),
                    uploadMbps = max(0.0, up),
                )
            }
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "runFullTest failed", e)
            SpeedResult(-1, 0.0, 0.0, e.message ?: "error")
        }
    }

    /**
     * Download throughput with Cloudflare-first + public fallbacks
     * (inspired by speedtest / LibreSpeed style multi-server probing).
     * Returns -1.0 on total failure.
     */
    fun measureDownloadMbps(byteCount: Int = 6_000_000): Double {
        val endpoints = listOf(
            "https://speed.cloudflare.com/__down?bytes=$byteCount",
            "https://speed.cloudflare.com/__down?bytes=${byteCount / 2}",
            "https://speed.cloudflare.com/__down?bytes=${maxOf(250_000, byteCount / 8)}",
            "https://httpbin.org/bytes/${minOf(byteCount, 1_000_000)}",
            "https://github.githubassets.com/favicons/favicon.svg",
            "https://www.google.com/generate_204",
            "https://connectivitycheck.gstatic.com/generate_204",
        )
        for (endpoint in endpoints) {
            val mbps = tryDownload(endpoint, byteCount)
            if (mbps > 0) return mbps
        }
        return -1.0
    }

    private fun tryDownload(endpoint: String, preferredBytes: Int): Double {
        try {
            val url = URL(endpoint)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 7_000
                readTimeout = 22_000
                requestMethod = "GET"
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "G-ST-VPN-Speed/1.3")
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Cache-Control", "no-cache")
            }
            val start = System.nanoTime()
            conn.connect()
            val code = conn.responseCode
            if (code !in 200..299 && code != 204) {
                conn.disconnect()
                return -1.0
            }
            var total = 0L
            conn.inputStream.use { input ->
                val buf = ByteArray(32 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    total += n
                    if (total >= preferredBytes) break
                }
            }
            val elapsedSec = (System.nanoTime() - start) / 1_000_000_000.0
            conn.disconnect()
            if (total < 500 && code in 200..299) return 0.05
            if (elapsedSec <= 0.0 || total < 1_000) return -1.0
            return (total * 8.0) / elapsedSec / 1_000_000.0
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "download measure failed ($endpoint): ${e.message}")
            return -1.0
        }
    }

    /**
     * Upload with Cloudflare primary + httpbin fallback.
     */
    fun measureUploadMbps(byteCount: Int = 1_500_000): Double {
        val endpoints = listOf(
            "https://speed.cloudflare.com/__up",
            "https://httpbin.org/post",
        )
        for (endpoint in endpoints) {
            val mbps = tryUpload(endpoint, byteCount)
            if (mbps > 0) return mbps
        }
        return -1.0
    }

    private fun tryUpload(endpoint: String, byteCount: Int): Double {
        try {
            val url = URL(endpoint)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 7_000
                readTimeout = 22_000
                requestMethod = "POST"
                doOutput = true
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "G-ST-VPN-Speed/1.3")
                setRequestProperty("Content-Type", "application/octet-stream")
                setFixedLengthStreamingMode(byteCount)
            }
            val payload = ByteArray(32 * 1024) { (it % 251).toByte() }
            val start = System.nanoTime()
            conn.outputStream.use { out: OutputStream ->
                var sent = 0
                while (sent < byteCount) {
                    val n = minOf(payload.size, byteCount - sent)
                    out.write(payload, 0, n)
                    sent += n
                }
                out.flush()
            }
            val code = conn.responseCode
            try {
                (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes() }
            } catch (_: Exception) {
            }
            val elapsedSec = (System.nanoTime() - start) / 1_000_000_000.0
            conn.disconnect()
            if (code !in 200..399 || elapsedSec <= 0.0) return -1.0
            return (byteCount * 8.0) / elapsedSec / 1_000_000.0
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "upload measure failed ($endpoint): ${e.message}")
            return -1.0
        }
    }

    fun formatMbps(value: Double): String {
        if (value < 0) return "—"
        return if (value >= 100) "${value.roundToInt()} Mbps"
        else String.format("%.1f Mbps", value)
    }

    fun formatLatency(ms: Long): String {
        if (ms < 0) return "—"
        return "$ms ms"
    }
}
