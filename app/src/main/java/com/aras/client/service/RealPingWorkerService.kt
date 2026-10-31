package com.aras.client.service

import android.content.Context
import com.aras.client.AppConfig
import com.aras.client.core.CoreConfigContextBuilder
import com.aras.client.core.CoreConfigManager
import com.aras.client.core.CoreNativeManager
import com.aras.client.core.HelperProcessSupport
import com.aras.client.dto.RealPingEvent
import com.aras.client.enums.EConfigType
import com.aras.client.extension.isComplexType
import com.aras.client.extension.isNotNullEmpty
import com.aras.client.handler.MmkvManager
import com.aras.client.handler.SettingsManager
import com.aras.client.handler.SpeedtestManager
import com.aras.client.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

internal object RealPingExecutionLimiter {
    // Custom profiles start complete Xray configs, exit-node profiles bind one fixed
    // loopback port, and Aether, Mieru, Tor and Psiphon share the daemon's single
    // helper slot. Two probes of those kinds running at once take each other's core
    // and both measure -1, so they share one lane. Ordinary configs stay parallel.
    private val exclusiveMutex = Mutex()

    suspend fun <T> run(exclusive: Boolean, block: suspend () -> T): T =
        if (exclusive) exclusiveMutex.withLock { block() } else block()
}

/**
 * Worker that runs a batch of real-ping tests independently.
 * Each batch owns its own CoroutineScope/dispatcher and can be cancelled separately.
 */
class RealPingWorkerService(
    private val context: Context,
    private val guids: List<String>,
    private val onlyTcp: Boolean = false,
    private val onEvent: (RealPingEvent) -> Unit = {}
) {
    private val job = SupervisorJob()
    private val concurrency = SettingsManager.getRealPingConcurrency()
    private val dispatcher = Executors.newFixedThreadPool(if (onlyTcp) concurrency * 2 else concurrency).asCoroutineDispatcher()
    private val scope = CoroutineScope(job + dispatcher + CoroutineName("RealPingBatchWorker"))

    private val runningCount = AtomicInteger(0)
    private val totalCount = AtomicInteger(0)

    fun start() {
        val jobs = guids.map { guid ->
            totalCount.incrementAndGet()
            scope.launch {
                runningCount.incrementAndGet()
                try {
                    val result = if (onlyTcp) {
                        // A TCP handshake is the cheapest probe, but the protocols it
                        // cannot speak (Aether, Mieru, Tor, Psiphon, WireGuard, h3-only
                        // and the rest) answered -1 on every round while they connected
                        // fine. They get the real probe instead of a permanent -1; the
                        // configs TCP can reach keep the cheap path.
                        startTcping(guid) ?: startRealPing(guid)
                    } else {
                        startRealPing(guid)
                    }
                    if (scope.isActive) {
                        onEvent(
                            RealPingEvent.Result(
                                guid = guid,
                                delayMillis = result.delayMillis,
                                country = result.country,
                                ipAddress = result.ipAddress,
                            )
                        )
                    }
                } catch (_: Throwable) {
                    // ignore
                } finally {
                    val count = totalCount.decrementAndGet()
                    val left = runningCount.decrementAndGet()
                    if (scope.isActive) {
                        onEvent(RealPingEvent.Progress("$left / $count"))
                    }
                }
            }
        }

        scope.launch {
            try {
                joinAll(*jobs.toTypedArray())
                if (isActive) {
                    onEvent(RealPingEvent.Finish("0"))
                }
            } catch (_: CancellationException) {
                // If cancelled, don't send finish event to avoid confusion
            } finally {
                close()
            }
        }
    }

    fun cancel() {
        job.cancel()
    }

    private fun close() {
        try {
            dispatcher.close()
        } catch (_: Throwable) {
            // ignore
        }
    }

    /** A delay measurement plus whatever egress info the dial path carried back. */
    internal data class PingResult(
        val delayMillis: Long,
        val country: String? = null,
        val ipAddress: String? = null,
    )

    private suspend fun startRealPing(guid: String): PingResult {
        val retFailure = PingResult(-1L)

        val config = MmkvManager.decodeServerConfig(guid) ?: return retFailure
        val chain = CoreConfigContextBuilder.resolveChainProfiles(context, guid)

        val configResult = CoreConfigManager.getXrayConfig4Speedtest(context, guid)
        if (!configResult.status) {
            return retFailure
        }
        // Any Aether, Mieru, Tor or Psiphon hop in the chain needs the daemon's single
        // helper slot, which only one profile can hold at a time.
        val helperChain = HelperProcessSupport.helperProfilesOf(chain).isNotEmpty()
        // An exit-node profile serves its egress on a fixed loopback port that
        // every concurrent Xray instance would also bind, so only one such test
        // may run at a time — together with the helper-backed and custom probes.
        val exclusive = helperChain || config.aetherExitNode.isNotNullEmpty() ||
            config.configType == EConfigType.CUSTOM

        return RealPingExecutionLimiter.run(exclusive) {
            // Any Aether or Mieru hop in the resolved chain lowers to a SOCKS hop on
            // loopback, so its daemon has to be listening or every measurement through
            // it is -1. The refcounts here mean a batch of profiles sharing one entry
            // proxy starts that daemon once, not once per profile.
            val helpers = HelperProcessSupport.acquire(context, chain)
            if (!helpers.ok) {
                LogUtil.w(AppConfig.TAG, "RealPing: ${helpers.error}")
                HelperProcessSupport.release(helpers)
                return@run retFailure
            }
            try {
                val first = measureOnce(configResult.content)
                if (first.delayMillis >= 0 || !helperChain) return@run first
                // A helper that has just come up can lose the first probe while its
                // listener settles; a second try is what turns a cold -1 into a delay.
                delay(400L)
                val second = measureOnce(configResult.content)
                if (second.delayMillis >= 0) second else first
            } finally {
                HelperProcessSupport.release(helpers)
            }
        }
    }

    private fun measureOnce(content: String): PingResult {
        val info = CoreNativeManager.measureOutboundDelayWithInfo(
            content,
            SettingsManager.getDelayTestUrl(),
            MmkvManager.decodeSettingsString(AppConfig.PREF_IP_API_URL)
                .takeIf { !it.isNullOrBlank() }
                ?: AppConfig.IP_API_URL,
        ) ?: return PingResult(-1L)
        return PingResult(info.getDelay(), info.getCountry(), info.getIP())
    }

    /**
     * The handshake delay, or null when TCP cannot reach this kind of profile at
     * all — a complex config, a single-protocol tunnel without a TCP path, a
     * carrier-only tunnel, or one that only speaks over HTTP/3. Null sends the
     * caller to the real probe rather than reporting -1 for a profile that works.
     */
    private fun startTcping(guid: String): PingResult? {
        val config = MmkvManager.decodeServerConfig(guid) ?: return PingResult(-1L)
        if (config.configType.isComplexType()
            || config.configType == EConfigType.HYSTERIA2
            || config.configType == EConfigType.WIREGUARD
            || config.configType == EConfigType.AMNEZIAWG
            || config.configType == EConfigType.ANYTLS
            || config.configType == EConfigType.MASQUE
            || config.configType == EConfigType.AETHER
            || config.configType == EConfigType.MIERU
            || config.configType.isCarrierOnly
            || config.alpn?.split(',')?.all { it.trim().startsWith("h3") } == true
        ) {
            return null
        }

        val url = config.server.orEmpty()
        val port = config.serverPort?.toIntOrNull() ?: return null
        return PingResult(SpeedtestManager.socketConnectTime(url, port, 1000))
    }
}
