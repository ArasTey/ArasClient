package dev.cluvex.zedsecure.data.net

import dev.cluvex.zedsecure.core.LogBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class MtuVerdict {
    Fits,

    TooBig,

    NoReply,

    Unavailable,
}

object MtuProbe {
    @Volatile
    var linkMtu: () -> Int? = { null }

    @Volatile
    var vpnActive: () -> Boolean = { false }

    @Volatile
    var probe: (host: String, payload: Int, timeoutMs: Int) -> MtuVerdict =
        { _, _, _ -> MtuVerdict.Unavailable }
}

sealed interface MtuResult {
    data class Measured(
        val pathMtu: Int,
        val recommended: Int,
        val linkMtu: Int?,
        val host: String,
        val overhead: MtuOverhead,
        val confirmed: Boolean,

        val toServer: Boolean,
    ) : MtuResult

    data object VpnActive : MtuResult

    data class NotMeasurable(val linkMtu: Int?) : MtuResult
}

object MtuOptimizer {
    private const val IP_ICMP_OVERHEAD = 28

    const val MIN_MTU = 1280
    const val MAX_MTU = 1500

    private val FALLBACK_TARGETS = listOf("1.1.1.1", "8.8.8.8", "9.9.9.9", "217.218.155.155")

    private const val TIMEOUT_MS = 2_000

    private const val RETRIES = 2

    suspend fun optimize(
        serverHost: String? = null,
        overhead: MtuOverhead = MtuOverheads.worstCase(),
    ): MtuResult = withContext(Dispatchers.IO) {
        val link = runCatching { MtuProbe.linkMtu() }.getOrNull()
        if (runCatching { MtuProbe.vpnActive() }.getOrDefault(false)) return@withContext MtuResult.VpnActive

        val candidates = listOfNotNull(serverHost?.takeIf { it.isNotBlank() }) + FALLBACK_TARGETS
        val host = candidates.firstOrNull { fits(it, 64) }
            ?: return@withContext MtuResult.NotMeasurable(link)
        val toServer = serverHost != null && host == serverHost

        val ceiling = (link ?: MAX_MTU).coerceAtMost(MAX_MTU)
        var lo = 64
        var hi = ceiling - IP_ICMP_OVERHEAD
        if (hi <= lo) {
            val path = lo + IP_ICMP_OVERHEAD
            return@withContext measured(path, link, host, overhead, confirmed = false, toServer)
        }
        while (lo < hi) {
            val mid = lo + (hi - lo + 1) / 2
            if (fits(host, mid)) lo = mid else hi = mid - 1
        }

        val confirmed = fits(host, lo) && (lo >= ceiling - IP_ICMP_OVERHEAD || !fits(host, lo + 1))
        val path = lo + IP_ICMP_OVERHEAD
        LogBus.append(
            "I/MtuOptimizer host=$host server=$toServer link=${link ?: "?"} path=$path " +
                "overhead=${overhead.explain()} confirmed=$confirmed",
        )
        measured(path, link, host, overhead, confirmed, toServer)
    }

    private fun measured(
        path: Int,
        link: Int?,
        host: String,
        overhead: MtuOverhead,
        confirmed: Boolean,
        toServer: Boolean,
    ) = MtuResult.Measured(path, recommend(path, overhead.total), link, host, overhead, confirmed, toServer)

    private fun fits(host: String, payload: Int): Boolean {
        repeat(RETRIES + 1) {
            when (runCatching { MtuProbe.probe(host, payload, TIMEOUT_MS) }.getOrDefault(MtuVerdict.Unavailable)) {
                MtuVerdict.Fits -> return true
                MtuVerdict.TooBig -> return false
                MtuVerdict.NoReply -> Unit
                MtuVerdict.Unavailable -> return false
            }
        }
        return false
    }

    fun recommend(pathMtu: Int, overheadBytes: Int): Int =
        (pathMtu - overheadBytes).coerceIn(MIN_MTU, MAX_MTU)
}
