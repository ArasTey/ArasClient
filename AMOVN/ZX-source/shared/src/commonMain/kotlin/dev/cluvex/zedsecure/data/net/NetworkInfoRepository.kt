package dev.cluvex.zedsecure.data.net

import dev.cluvex.zedsecure.core.VpnManager
import dev.cluvex.zedsecure.domain.model.ConnectionState
import dev.cluvex.zedsecure.platform.currentTimeMillis
import dev.cluvex.zedsecure.platform.httpGetViaSocks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class NetworkInfo(
    val ipv4: String? = null,
    val ipv6: String? = null,
    val isp: String? = null,
    val city: String? = null,
    val country: String? = null,
    val countryCode: String? = null,
    val pingMs: Int? = null,
    val loading: Boolean = false,
    val pingLoading: Boolean = false,
    val placeLoading: Boolean = false,
    val error: String? = null,

    val fault: dev.cluvex.zedsecure.core.ConnectionFault? = null,

    val viaTunnel: Boolean = false,

    val underVpn: Boolean = false,
)

class NetworkInfoRepository(

    private val get: suspend (url: String) -> String = { url -> tunnelGet(url) },
) {
    suspend fun fetchInfo(preferredUrl: String? = null, force: Boolean = false): NetworkInfo = coroutineScope {
        if (!force) cached()?.let { return@coroutineScope it }

        val tunnelActive = VpnManager.activeSocksPort != null ||
            VpnManager.status.value.state == ConnectionState.Connected
        val exitAtStart = currentExit()

        val vpnAtStart = anyVpnNow()
        val v6 = async(Dispatchers.IO) { lookupV6() }

        val urls = buildList {
            preferredUrl?.takeIf { it.isNotBlank() }?.let { add(it) }
            addAll(GEO_ENDPOINTS)
        }

        val base = preferredLocation(urls)
            ?: return@coroutineScope NetworkInfo(
                ipv6 = v6.await(), error = "unreachable", viaTunnel = tunnelActive,
                underVpn = vpnAtStart || anyVpnNow(),
            )

        val v4 = base.ipv4 ?: lookupV4()
        val vpnAtEnd = anyVpnNow()
        base.copy(
            ipv4 = v4,
            ipv6 = base.ipv6 ?: v6.await(),
            viaTunnel = tunnelActive,
            underVpn = vpnAtStart || vpnAtEnd,
        ).also {
            if (vpnAtStart == vpnAtEnd && exitAtStart == currentExit()) store(it, exitAtStart)
        }
    }

    private suspend fun preferredLocation(urls: List<String>): NetworkInfo? = coroutineScope {
        val results = kotlinx.coroutines.channels.Channel<Pair<Int, NetworkInfo?>>(urls.size)
        val jobs = urls.mapIndexed { index, url ->
            launch(Dispatchers.IO) {
                results.send(index to runCatching { GeoPayload.parse(get(url)) }.getOrNull())
            }
        }
        try {
            val answers = HashMap<Int, NetworkInfo?>()
            suspend fun receiveOne() {
                val (index, info) = results.receive()
                answers[index] = info
            }
            while (answers.size < urls.size && answers.values.none { GeoPreference.hasLocation(it) }) receiveOne()
            var chosen = GeoPreference.choose(answers)
            if (chosen == null && answers.values.any { GeoPreference.hasLocation(it) }) {
                kotlinx.coroutines.withTimeoutOrNull(PRIORITY_WAIT_MS) {
                    while (chosen == null && answers.size < urls.size) {
                        receiveOne()
                        chosen = GeoPreference.choose(answers)
                    }
                }
                chosen = chosen ?: GeoPreference.choose(answers, waitedEnough = true)
            }
            var best = chosen?.let { answers[it] } ?: return@coroutineScope null
            answers.values.filterNotNull().forEach { best = GeoPayload.complete(best, it) }
            kotlinx.coroutines.withTimeoutOrNull(COMPLETE_GRACE_MS) {
                while (!GeoPayload.isComplete(best) && answers.size < urls.size) {
                    val (index, info) = results.receive()
                    answers[index] = info
                    if (info != null) best = GeoPayload.complete(best, info)
                }
            }
            best
        } finally {
            jobs.forEach { it.cancel() }
        }
    }

    suspend fun fetch(): NetworkInfo = coroutineScope {
        val ping = async { realDelay() }
        val info = fetchInfo()
        info.copy(pingMs = ping.await())
    }

    suspend fun realDelay(url: String? = null): Int? =
        PingService.realDelayOfActiveConnection(url?.takeIf { it.isNotBlank() } ?: DELAY_TEST_URL)
            .takeIf { it > 0 }
            ?.toInt()

    suspend fun health(url: String? = null): dev.cluvex.zedsecure.core.CoreProbe.DelayOutcome =
        PingService.activeConnectionHealth(url?.takeIf { it.isNotBlank() } ?: DELAY_TEST_URL)

    private suspend fun lookupV6(): String? = runCatching {
        get("https://api64.ipify.org").trim().takeIf { isIpv6(it) }
    }.getOrNull()

    private suspend fun lookupV4(): String? = runCatching {
        get("https://api.ipify.org").trim().takeIf { it.isNotBlank() && !isIpv6(it) }
    }.getOrNull()

    private fun anyVpnNow(): Boolean =
        VpnManager.activeSocksPort != null ||
            VpnManager.status.value.state == ConnectionState.Connected ||
            VpnManager.deviceVpnActive()

    private fun currentExit(): String =
        "${VpnManager.status.value.sessionId}/${dev.cluvex.zedsecure.core.AutoSelect.exitGeneration()}"

    private fun cached(): NetworkInfo? {
        val hit = lastInfo ?: return null
        if (lastPort != VpnManager.activeSocksPort) return null

        if (lastExit != currentExit()) return null

        if (lastVpn != anyVpnNow()) return null
        if (currentTimeMillis() - lastAt > CACHE_TTL_MS) return null
        return hit
    }

    private fun store(info: NetworkInfo, exit: String) {
        lastInfo = info
        lastPort = VpnManager.activeSocksPort
        lastExit = exit
        lastVpn = info.underVpn
        lastAt = currentTimeMillis()
    }

    companion object {
        private var lastInfo: NetworkInfo? = null
        private var lastPort: Int? = null
        private var lastExit: String? = null
        private var lastVpn: Boolean = false
        private var lastAt: Long = 0L

        private const val CACHE_TTL_MS = 45_000L
        private const val GEO_TIMEOUT_MS = 5_000

        private suspend fun tunnelGet(url: String): String = httpGetViaSocks(
            url,
            socksPort = VpnManager.activeSocksPort,
            userAgent = "ZedSecure",
            connectTimeoutMs = GEO_TIMEOUT_MS,
            readTimeoutMs = GEO_TIMEOUT_MS,
            closeConnection = true,
        )

        private const val COMPLETE_GRACE_MS = 1_200L

        private const val PRIORITY_WAIT_MS = 1_500L

        private fun isIpv6(value: String): Boolean = value.contains(':')

        const val IPWHOIS_URL = "https://ipwho.is/"

        const val IP_API_URL = "https://api.ip.sb/geoip"
        const val IPAPI_CO_URL = "https://ipapi.co/json/"
        const val IPINFO_URL = "https://ipinfo.io/json"
        const val DELAY_TEST_URL = "https://www.gstatic.com/generate_204"

        const val DELAY_TEST_URL2 = "https://www.google.com/generate_204"

        val GEO_ENDPOINTS: List<String> = listOf(IPWHOIS_URL, IP_API_URL, IPAPI_CO_URL, IPINFO_URL)
    }
}

object GeoPreference {
    fun hasLocation(info: NetworkInfo?): Boolean =
        info != null && (!info.countryCode.isNullOrBlank() || !info.country.isNullOrBlank())

    fun choose(answers: Map<Int, NetworkInfo?>, waitedEnough: Boolean = false): Int? {
        val best = answers.filterValues { hasLocation(it) }.keys.minOrNull() ?: return null
        return best.takeIf { waitedEnough || (0 until best).all { it in answers } }
    }
}
