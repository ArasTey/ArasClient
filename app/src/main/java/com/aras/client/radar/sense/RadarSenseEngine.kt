package com.aras.client.radar.sense

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Radar Sense — modern multi-vector network atmosphere probe.
 *
 * Inspired by practical ideas from open DPI/network diagnostic tools
 * (multi-host HTTPS, DNS, TCP, UDP/QUIC reachability) but kept lightweight
 * and product-oriented for RADAR VPN.
 *
 * Runs fully on-device. Does not require root. Does not modify VPN state.
 */
object RadarSenseEngine {

    private data class Target(
        val id: String,
        val label: String,
        val category: String,
        val host: String,
        val httpsUrl: String? = null,
        val tcpPort: Int? = 443,
        val udpPort: Int? = null
    )

    private val targets = listOf(
        Target("dns_cf", "Cloudflare DNS", "dns", "1.1.1.1", tcpPort = 443),
        Target("dns_gg", "Google DNS", "dns", "8.8.8.8", tcpPort = 443),
        Target(
            "cf_trace", "Cloudflare Trace", "global",
            "www.cloudflare.com",
            httpsUrl = "https://www.cloudflare.com/cdn-cgi/trace"
        ),
        Target(
            "gstatic", "Google Connectivity", "global",
            "connectivitycheck.gstatic.com",
            httpsUrl = "https://connectivitycheck.gstatic.com/generate_204"
        ),
        Target(
            "wikipedia", "Wikipedia", "global",
            "www.wikipedia.org",
            httpsUrl = "https://www.wikipedia.org/"
        ),
        Target(
            "github_assets", "GitHub Assets", "cdn",
            "github.githubassets.com",
            httpsUrl = "https://github.githubassets.com/favicons/favicon.svg"
        ),
        Target("udp443_cf", "UDP/443 Cloudflare", "quic", "1.1.1.1", tcpPort = null, udpPort = 443),
        Target("udp443_gg", "UDP/443 Google", "quic", "8.8.8.8", tcpPort = null, udpPort = 443)
    )

    suspend fun scan(): RadarSenseReport = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        val probes = coroutineScope {
            targets.map { t -> async { probeTarget(t) } }.awaitAll()
        }
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        classify(probes, elapsed)
    }

    private fun probeTarget(t: Target): SenseProbe {
        val dnsStart = System.nanoTime()
        try {
            InetAddress.getAllByName(t.host)
        } catch (e: Exception) {
            return SenseProbe(
                t.id, t.label, t.category, false, null,
                "DNS failed: " + (e.message ?: e.javaClass.simpleName)
            )
        }
        val dnsMs = (System.nanoTime() - dnsStart) / 1_000_000L

        var tcpMs: Long? = null
        if (t.tcpPort != null) {
            tcpMs = tcpConnectMs(t.host, t.tcpPort)
            if (tcpMs == null && t.httpsUrl == null && t.udpPort == null) {
                return SenseProbe(t.id, t.label, t.category, false, dnsMs, "TCP ${t.tcpPort} unreachable")
            }
        }

        var httpsMs: Long? = null
        if (t.httpsUrl != null) {
            httpsMs = httpsMs(t.httpsUrl)
            if (httpsMs == null) {
                return SenseProbe(
                    t.id, t.label, t.category, false,
                    tcpMs ?: dnsMs,
                    "HTTPS/TLS failed (DNS ok" + (tcpMs?.let { ", TCP ${it}ms" } ?: "") + ")"
                )
            }
            return SenseProbe(
                t.id, t.label, t.category, true, httpsMs,
                "HTTPS ok · ${httpsMs}ms" + (tcpMs?.let { " · TCP ${it}ms" } ?: "")
            )
        }

        if (t.udpPort != null) {
            val udpOk = udpProbe(t.host, t.udpPort)
            return SenseProbe(
                t.id, t.label, t.category, udpOk, dnsMs,
                if (udpOk) "UDP ${t.udpPort} appears open/responsive" else "UDP ${t.udpPort} no response (may be filtered)"
            )
        }

        return if (tcpMs != null) {
            SenseProbe(t.id, t.label, t.category, true, tcpMs, "TCP ${t.tcpPort} ok · ${tcpMs}ms")
        } else {
            SenseProbe(t.id, t.label, t.category, false, dnsMs, "No successful transport probe")
        }
    }

    private fun tcpConnectMs(host: String, port: Int, timeoutMs: Int = 2500): Long? {
        return try {
            val start = System.nanoTime()
            Socket().use { s -> s.connect(InetSocketAddress(host, port), timeoutMs) }
            (System.nanoTime() - start) / 1_000_000L
        } catch (_: Exception) {
            null
        }
    }

    private fun httpsMs(url: String, timeoutMs: Int = 5000): Long? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                requestMethod = "GET"
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "RADAR-Sense/1.0")
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Cache-Control", "no-cache")
            }
            val start = System.nanoTime()
            val code = conn.responseCode
            val ms = (System.nanoTime() - start) / 1_000_000L
            if (code in 200..399 || code == 204) ms else null
        } catch (_: Exception) {
            null
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun udpProbe(host: String, port: Int, timeoutMs: Int = 1500): Boolean {
        return try {
            DatagramSocket().use { ds ->
                ds.soTimeout = timeoutMs
                val addr = InetAddress.getByName(host)
                val payload = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x52, 0x44, 0x52)
                ds.send(DatagramPacket(payload, payload.size, addr, port))
                val buf = ByteArray(64)
                try {
                    ds.receive(DatagramPacket(buf, buf.size))
                    true
                } catch (_: Exception) {
                    false
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun classify(probes: List<SenseProbe>, elapsedMs: Long): RadarSenseReport {
        val global = probes.filter { it.category == "global" || it.category == "cdn" }
        val dns = probes.filter { it.category == "dns" }
        val quic = probes.filter { it.category == "quic" }

        val globalOk = global.count { it.ok }
        val dnsOk = dns.count { it.ok }
        val quicOk = quic.count { it.ok }
        val totalOk = probes.count { it.ok }

        val avgLatency = probes.mapNotNull { it.latencyMs }.filter { it > 0 }.average().let {
            if (it.isNaN()) 9999.0 else it
        }

        val atmosphere = when {
            totalOk == 0 -> NetworkAtmosphere.OFFLINE
            globalOk == 0 && dnsOk > 0 -> NetworkAtmosphere.HEAVY_FILTER
            globalOk < global.size && globalOk > 0 -> NetworkAtmosphere.TURBULENT
            globalOk == global.size && avgLatency < 800 -> NetworkAtmosphere.CLEAR
            globalOk == global.size -> NetworkAtmosphere.TURBULENT
            else -> NetworkAtmosphere.UNKNOWN
        }

        var score = 0
        score += (globalOk * 25).coerceAtMost(50)
        score += (dnsOk * 10).coerceAtMost(20)
        score += (quicOk * 8).coerceAtMost(16)
        if (avgLatency < 200) score += 14
        else if (avgLatency < 500) score += 8
        else if (avgLatency < 1000) score += 3
        score = score.coerceIn(0, 100)

        val hint = when (atmosphere) {
            NetworkAtmosphere.CLEAR -> ProtocolHint(
                primary = "Any modern stack",
                alternatives = listOf("VLESS+Reality", "Hysteria2", "TUIC"),
                reason = "Network looks open; prefer low-latency nodes."
            )
            NetworkAtmosphere.TURBULENT -> ProtocolHint(
                primary = "VLESS + Reality",
                alternatives = listOf("Hysteria2", "TUIC", "Trojan+TLS"),
                reason = "Partial filtering/congestion — camouflaged TLS or UDP-based protocols usually fare better."
            )
            NetworkAtmosphere.HEAVY_FILTER -> ProtocolHint(
                primary = "Hysteria2 / TUIC / Reality",
                alternatives = listOf("VLESS+XHTTP", "AmneziaWG"),
                reason = "Global HTTPS weak while base IP works — strong anti-DPI stacks recommended."
            )
            NetworkAtmosphere.OFFLINE -> ProtocolHint(
                primary = "Fix base connectivity first",
                alternatives = listOf("Check SIM/Wi-Fi", "Disable private DNS temporarily"),
                reason = "Almost no probe succeeded; tunnel cannot help until the link is up."
            )
            NetworkAtmosphere.UNKNOWN -> ProtocolHint(
                primary = "VLESS + Reality",
                alternatives = listOf("Hysteria2"),
                reason = "Inconclusive; start with modern camouflaged protocols."
            )
        }

        val summaryFa = when (atmosphere) {
            NetworkAtmosphere.CLEAR ->
                "جوّ شبکه نسبتاً باز است (امتیاز $score). می‌توانی روی سرعت و پینگ تمرکز کنی."
            NetworkAtmosphere.TURBULENT ->
                "جوّ شبکه ناپایدار/نیمه‌محدود است (امتیاز $score). Reality یا Hysteria2 معمولاً پایدارترند."
            NetworkAtmosphere.HEAVY_FILTER ->
                "نشانهٔ فیلتر سنگین روی مسیرهای جهانی دیده شد (امتیاز $score). سراغ استک ضد DPI برو."
            NetworkAtmosphere.OFFLINE ->
                "تقریباً هیچ مقصدی پاسخ نداد (امتیاز $score). اول اینترنت پایه را بررسی کن."
            NetworkAtmosphere.UNKNOWN ->
                "نتیجه مبهم بود (امتیاز $score). یک‌بار دیگر با VPN خاموش/روشن مقایسه کن."
        }

        val fp = fingerprint(probes, atmosphere)

        return RadarSenseReport(
            atmosphere = atmosphere,
            score = score,
            fingerprint = fp,
            probes = probes,
            hint = hint,
            summaryFa = summaryFa,
            elapsedMs = elapsedMs
        )
    }

    private fun fingerprint(probes: List<SenseProbe>, atmosphere: NetworkAtmosphere): String {
        val raw = buildString {
            append(atmosphere.name)
            probes.sortedBy { it.id }.forEach { p ->
                append('|')
                append(p.id)
                append('=')
                append(if (p.ok) '1' else '0')
                append('@')
                append((p.latencyMs ?: -1).let { if (it < 0) -1 else it / 50 })
            }
        }
        val dig = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return dig.take(6).joinToString("") { b -> "%02x".format(Locale.US, b) }
    }
}
