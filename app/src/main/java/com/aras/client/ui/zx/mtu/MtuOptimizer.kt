package com.aras.client.ui.zx.mtu

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Port of ZX MtuOptimizer: binary-searches the path MTU with don't-fragment pings. */
enum class MtuVerdict { Fits, TooBig, NoReply, Unavailable }

data class MtuOverhead(val total: Int, val parts: List<Pair<String, Int>>) {
    fun explain(): String = parts.joinToString(" + ") { "${it.first} ${it.second}" } + " = $total bytes"

    companion object {
        /** Worst case among supported transports (IPv4 + TCP w/ options + TLS + WebSocket + VMess chunk). */
        fun worstCase() = MtuOverhead(
            20 + 32 + 21 + 8 + 18,
            listOf("IPv4" to 20, "TCP" to 32, "TLS" to 21, "WebSocket" to 8, "VMess" to 18),
        )
    }
}

sealed interface MtuResult {
    data class Measured(
        val pathMtu: Int,
        val recommended: Int,
        val host: String,
        val overhead: MtuOverhead,
        val confirmed: Boolean,
        val toServer: Boolean,
    ) : MtuResult

    data object VpnActive : MtuResult
    data object NotMeasurable : MtuResult
}

object MtuOptimizer {
    private const val IP_ICMP_OVERHEAD = 28
    const val MIN_MTU = 1280
    const val MAX_MTU = 1500
    private val FALLBACK_TARGETS = listOf("1.1.1.1", "8.8.8.8", "9.9.9.9", "217.218.155.155")
    private const val RETRIES = 2

    suspend fun optimize(
        vpnActive: Boolean,
        serverHost: String? = null,
        overhead: MtuOverhead = MtuOverhead.worstCase(),
        probe: (String, Int) -> MtuVerdict = ::pingProbe,
    ): MtuResult = withContext(Dispatchers.IO) {
        if (vpnActive) return@withContext MtuResult.VpnActive
        fun fits(host: String, payload: Int): Boolean {
            repeat(RETRIES + 1) {
                when (runCatching { probe(host, payload) }.getOrDefault(MtuVerdict.Unavailable)) {
                    MtuVerdict.Fits -> return true
                    MtuVerdict.TooBig, MtuVerdict.Unavailable -> return false
                    MtuVerdict.NoReply -> Unit
                }
            }
            return false
        }
        val candidates = listOfNotNull(serverHost?.takeIf { it.isNotBlank() }) + FALLBACK_TARGETS
        val host = candidates.firstOrNull { fits(it, 64) } ?: return@withContext MtuResult.NotMeasurable
        val toServer = serverHost != null && host == serverHost
        var lo = 64
        var hi = MAX_MTU - IP_ICMP_OVERHEAD
        while (lo < hi) {
            val mid = lo + (hi - lo + 1) / 2
            if (fits(host, mid)) lo = mid else hi = mid - 1
        }
        val confirmed = fits(host, lo) && (lo >= MAX_MTU - IP_ICMP_OVERHEAD || !fits(host, lo + 1))
        val path = lo + IP_ICMP_OVERHEAD
        MtuResult.Measured(path, recommend(path, overhead.total), host, overhead, confirmed, toServer)
    }

    fun recommend(pathMtu: Int, overheadBytes: Int): Int =
        (pathMtu - overheadBytes).coerceIn(MIN_MTU, MAX_MTU)

    /** Uses the system ping binary with the don't-fragment flag; Unavailable if it cannot run. */
    fun pingProbe(host: String, payload: Int): MtuVerdict {
        val process = ProcessBuilder("ping", "-c", "1", "-W", "2", "-M", "do", "-s", payload.toString(), host)
            .redirectErrorStream(true).start()
        val finished = process.waitFor(4, TimeUnit.SECONDS)
        if (!finished) { process.destroyForcibly(); return MtuVerdict.NoReply }
        val out = process.inputStream.bufferedReader().readText().lowercase()
        return when {
            "frag needed" in out || "message too long" in out || "too long" in out -> MtuVerdict.TooBig
            "invalid option" in out || "usage" in out || "not found" in out -> MtuVerdict.Unavailable
            process.exitValue() == 0 -> MtuVerdict.Fits
            else -> MtuVerdict.NoReply
        }
    }
}
