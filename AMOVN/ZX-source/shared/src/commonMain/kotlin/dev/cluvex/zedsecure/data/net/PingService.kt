package dev.cluvex.zedsecure.data.net

import dev.cluvex.zedsecure.core.CoreProbe
import dev.cluvex.zedsecure.core.LogBus
import dev.cluvex.zedsecure.core.VpnManager
import dev.cluvex.zedsecure.domain.config.CustomConfig
import dev.cluvex.zedsecure.domain.config.VpnProfile
import dev.cluvex.zedsecure.platform.currentTimeMillis
import dev.cluvex.zedsecure.platform.httpTimedTransfer
import dev.cluvex.zedsecure.platform.tcpConnectMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

object PingService {
    private const val TCP_TIMEOUT_MS = 2_500

    private const val REAL_PING_CONCURRENCY = 16

    private const val GEO_CONCURRENCY = 8

    const val FAILED_PING = -1

    private const val PRECHECK_TIMEOUT_MS = 2_500

    private const val PROBE_TIMEOUT_MS = 8_000L

    private const val QUICK_FAILURE_MS = 3_000L

    private const val REQUEST_PROBE_TIMEOUT_MS = 8_000

    private const val CUSTOM_PROBE_CONCURRENCY = 8

    private val geoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun tcpPing(host: String, port: Int, timeoutMs: Int = TCP_TIMEOUT_MS): Long =
        withContext(Dispatchers.IO) { tcpConnectMillis(host, port, timeoutMs) }

    suspend fun realDelay(
        profile: VpnProfile,
        url: String = NetworkInfoRepository.DELAY_TEST_URL,
        chainConfig: (VpnProfile) -> String? = { null },
    ): Long = withContext(Dispatchers.IO) { measureProfile(profile, url, chainConfig) }

    suspend fun activeConnectionHealth(
        url: String = NetworkInfoRepository.DELAY_TEST_URL,
    ): CoreProbe.DelayOutcome = withContext(Dispatchers.IO) {
        val first = probeActiveConnection(url)
        if (first.ok) return@withContext first
        if (url != NetworkInfoRepository.DELAY_TEST_URL2) {
            val second = probeActiveConnection(NetworkInfoRepository.DELAY_TEST_URL2)
            if (second.ok) return@withContext second
            return@withContext CoreProbe.DelayOutcome(-1L, second.error ?: first.error)
        }
        first
    }

    private suspend fun probeActiveConnection(url: String): CoreProbe.DelayOutcome {
        if (runsXrayCore(VpnManager.activeKind.value)) {
            return runCatching { CoreProbe.measureDelayDetailed(url) }
                .getOrElse { CoreProbe.DelayOutcome(-1L, it.message) }
        }
        return runCatching {
            val timing = httpTimedTransfer(
                url,
                socksPort = VpnManager.activeSocksPort,
                upload = false,
                uploadBytes = 0,
                connectTimeoutMs = REQUEST_PROBE_TIMEOUT_MS,
                readTimeoutMs = REQUEST_PROBE_TIMEOUT_MS,
            )
            CoreProbe.DelayOutcome((timing.ttfbNanos / 1_000_000).coerceAtLeast(1), null)
        }.getOrElse { CoreProbe.DelayOutcome(-1L, it.message ?: it.toString()) }
    }

    fun runsXrayCore(kind: String?): Boolean =
        kind == null || kind == VpnManager.KIND_XRAY || kind == VpnManager.KIND_SNISPOOF ||
            kind == VpnManager.KIND_CROSS_CHAIN

    suspend fun realDelayOfActiveConnection(
        url: String = NetworkInfoRepository.DELAY_TEST_URL,
    ): Long = withContext(Dispatchers.IO) {
        val viaCore = runCatching { CoreProbe.measureDelay(url) }.getOrDefault(-1L)
        if (viaCore > 0) return@withContext viaCore

        if (url != NetworkInfoRepository.DELAY_TEST_URL2) {
            val viaSecond =
                runCatching { CoreProbe.measureDelay(NetworkInfoRepository.DELAY_TEST_URL2) }
                    .getOrDefault(-1L)
            if (viaSecond > 0) return@withContext viaSecond
        }

        if (VpnManager.activeSocksPort != null) return@withContext -1L
        val host = url.substringAfter("://", url).substringBefore('/').substringBefore(':')
        val port = if (url.startsWith("http://", true)) 80 else 443
        if (host.isBlank()) -1L else runCatching { tcpConnectMillis(host, port, TCP_TIMEOUT_MS) }
            .getOrDefault(-1L)
    }

    suspend fun measureAll(
        profiles: List<VpnProfile>,
        useRealDelay: Boolean,

        concurrency: Int = REAL_PING_CONCURRENCY,
        delayUrl: String = NetworkInfoRepository.DELAY_TEST_URL,

        chainConfig: (VpnProfile) -> String? = { null },
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        onResult: (id: String, ms: Int?, countryCode: String?) -> Unit,

    ) = withContext(Dispatchers.IO) {
        val realPermits = concurrency.takeIf { it in 1..128 } ?: REAL_PING_CONCURRENCY
        val url = delayUrl.ifBlank { NetworkInfoRepository.DELAY_TEST_URL }
        val permits = Semaphore(if (useRealDelay) realPermits else realPermits * 2)

        val customPermits = Semaphore(minOf(realPermits, CUSTOM_PROBE_CONCURRENCY))
        val geoPermits = Semaphore(GEO_CONCURRENCY)

        val targets = profiles.filterNot { (it.isManagedTunnel && !it.isSingBoxConfig) || it.isDnsBasedTunnel }
        val total = targets.size
        var done = 0
        val doneLock = Semaphore(1)
        onProgress(0, total)
        targets.map { profile ->
            launch(Dispatchers.IO) {
                val heavy = useRealDelay || profile.isProxyChain
                val pool = if (heavy && profile.isCustom) customPermits else permits
                val ms = pool.withPermit {
                    if (heavy) measureProfile(profile, url, chainConfig)
                    else tcpPing(profile.address, profile.port)
                }

                val latency = if (ms > 0) ms.toInt() else FAILED_PING

                onResult(profile.id, latency, profile.countryCode)
                doneLock.withPermit { onProgress(++done, total) }

                if (latency > 0 && profile.countryCode == null) {
                    geoScope.launch {
                        val country = geoPermits.withPermit { GeoLookup.countryOf(profile.address) }
                        if (country != null) onResult(profile.id, latency, country)
                    }
                }
            }
        }.joinAll()
    }

    private suspend fun measureProfile(
        profile: VpnProfile,
        url: String,
        chainConfig: (VpnProfile) -> String? = { null },
    ): Long {
        val config = if (profile.isProxyChain) {
            chainConfig(profile) ?: return -1L
        } else {
            if (shouldPrecheck(profile)) {
                if (tcpConnectMillis(profile.address, profile.port, PRECHECK_TIMEOUT_MS) < 0) return -1L
            }

            runCatching { profile.toXrayConfigJson(forSpeedtest = true) }.getOrElse { return -1L }
        }
        val started = currentTimeMillis()
        val first = probeWithTimeout(profile, config, url)
        if (first >= 0 || currentTimeMillis() - started > QUICK_FAILURE_MS) return first
        return probeWithTimeout(profile, config, url)
    }

    fun shouldPrecheck(profile: VpnProfile): Boolean {
        if (profile.address.isBlank() || profile.address == "-") return false
        if (profile.port !in 1..65535) return false
        if (profile.isCustom) {
            val raw = profile.rawPayload() ?: return false
            return CustomConfig.tcpProbeable(raw)
        }

        (profile.source as? dev.cluvex.zedsecure.domain.config.ProfileSource.SingBox)?.let { src ->
            val servers = dev.cluvex.zedsecure.domain.config.SingBoxJson.servers(src.json)
            val server = servers.firstOrNull() ?: return false
            return !server.udp && !src.json.contains("\"detour\"")
        }
        if (profile.isSingBoxConfig) return false
        return profile.protocol !in UDP_OUTBOUND_PROTOCOLS
    }

    private val UDP_OUTBOUND_PROTOCOLS = setOf("WIREGUARD", "AMNEZIAWG", "HYSTERIA")

    private suspend fun probeWithTimeout(profile: VpnProfile, config: String, url: String): Long =
        withContext(Dispatchers.IO) {
            val job = async(Dispatchers.IO) {
                runCatching { CoreProbe.measureOutboundDelay(config, url) }
                    .getOrElse {
                        LogBus.append("W/PingService real delay failed for ${profile.name}: ${it.message}")
                        -1L
                    }
            }
            withTimeoutOrNull(PROBE_TIMEOUT_MS) { job.await() } ?: run {
                job.cancel()
                LogBus.append("W/PingService real delay timed out for ${profile.name}")
                -1L
            }
        }
}
