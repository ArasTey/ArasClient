package dev.cluvex.zedsecure.data.net

import dev.cluvex.zedsecure.platform.HttpTiming
import dev.cluvex.zedsecure.platform.httpStreamTransfer
import dev.cluvex.zedsecure.platform.httpTimedTransfer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.math.max
import kotlin.math.min
import kotlin.time.TimeSource

enum class SpeedTestPhase { Idle, Ping, Download, Upload, Done, Stopped, Error }

data class SpeedSample(val mbps: Double)

data class SpeedTestState(
    val phase: SpeedTestPhase = SpeedTestPhase.Idle,
    val pingMs: Double? = null,
    val jitterMs: Double? = null,
    val loadedPingMs: Double? = null,
    val downloadMbps: Double? = null,
    val uploadMbps: Double? = null,
    val liveMbps: Double = 0.0,
    val progress: Float = 0f,
    val downSamples: List<SpeedSample> = emptyList(),
    val upSamples: List<SpeedSample> = emptyList(),
    val bytesUsed: Long = 0L,
    val error: String? = null,
) {
    val bufferbloatMs: Double?
        get() {
            val idle = pingMs ?: return null
            val loaded = loadedPingMs ?: return null
            return max(0.0, loaded - idle)
        }

    val running: Boolean
        get() = phase == SpeedTestPhase.Ping ||
            phase == SpeedTestPhase.Download ||
            phase == SpeedTestPhase.Upload

    val hasResults: Boolean
        get() = pingMs != null || downloadMbps != null || uploadMbps != null
}

data class Measurement(
    val upload: Boolean,
    val bytes: Long,
    val count: Int,

    val bypassFinishRule: Boolean = false,
)

class SpeedTestEngine(private val socksPort: Int?) {
    private val _state = MutableStateFlow(SpeedTestState())
    val state: StateFlow<SpeedTestState> = _state.asStateFlow()

    @Volatile
    private var stopped = false

    companion object {
        const val DOWN_URL = "https://speed.cloudflare.com/__down?bytes="
        const val UP_URL = "https://speed.cloudflare.com/__up"

        const val PING_COUNT = 20

        const val FINISH_REQUEST_MS = 1_000.0

        const val MIN_REQUEST_MS = 10.0

        const val BANDWIDTH_PERCENTILE = 0.9
        const val LATENCY_PERCENTILE = 0.5

        const val LOADED_PROBE_MS = 400L
        const val LOADED_MAX_POINTS = 20

        const val LOADED_MIN_REQUEST_MS = 250.0

        const val TOTAL_BUDGET_BYTES = 150L * 1000 * 1000

        const val CONNECT_TIMEOUT = 10_000
        const val READ_TIMEOUT = 20_000

        private const val PING_READ_TIMEOUT = 5_000

        private const val SAMPLE_MS = 250L

        val SCHEDULE: List<Measurement> = listOf(
            Measurement(upload = false, bytes = 100_000, count = 1, bypassFinishRule = true),
            Measurement(upload = false, bytes = 100_000, count = 9),
            Measurement(upload = false, bytes = 1_000_000, count = 8),
            Measurement(upload = true, bytes = 100_000, count = 8),
            Measurement(upload = true, bytes = 1_000_000, count = 6),
            Measurement(upload = false, bytes = 10_000_000, count = 6),
            Measurement(upload = true, bytes = 10_000_000, count = 4),
            Measurement(upload = false, bytes = 25_000_000, count = 4),
            Measurement(upload = true, bytes = 25_000_000, count = 4),
        )
    }

    fun cancel() {
        stopped = true
    }

    private val downSamples = ArrayList<BandwidthSample>()
    private val upSamples = ArrayList<BandwidthSample>()
    private val idlePings = ArrayList<Double>()
    private val loadedPings = ArrayList<Double>()
    private var spentBytes = 0L

    private var downFinished = false
    private var upFinished = false

    suspend fun run() {
        stopped = false
        downSamples.clear(); upSamples.clear()
        idlePings.clear(); loadedPings.clear()
        spentBytes = 0L
        downFinished = false; upFinished = false

        try {
            _state.value = SpeedTestState(phase = SpeedTestPhase.Ping)
            measureIdleLatency()
            if (stopped) return finishStopped()
            if (idlePings.isEmpty()) throw IllegalStateException("No latency response")

            runSchedule()
            if (stopped) return finishStopped()

            _state.value = _state.value.copy(
                phase = SpeedTestPhase.Done,
                liveMbps = 0.0,
                progress = 1f,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            _state.value = _state.value.copy(
                phase = SpeedTestPhase.Error,
                error = e.message ?: "Speed test failed",
            )
        } finally {
            if (_state.value.running) {
                _state.value = _state.value.copy(
                    phase = if (stopped) SpeedTestPhase.Stopped else SpeedTestPhase.Error,
                    liveMbps = 0.0,
                    progress = 1f,
                )
            }
        }
    }

    private fun finishStopped() {
        _state.value = _state.value.copy(phase = SpeedTestPhase.Stopped, liveMbps = 0.0, progress = 1f)
    }

    private suspend fun measureIdleLatency() {
        repeat(PING_COUNT) { i ->
            if (stopped) return
            val ms = probeLatency() ?: return@repeat
            idlePings.add(ms)
            _state.value = _state.value.copy(
                liveMbps = ms,
                pingMs = SpeedTestMath.percentile(idlePings, LATENCY_PERCENTILE),
                jitterMs = SpeedTestMath.jitter(idlePings),
                progress = (i + 1) / PING_COUNT.toFloat(),
            )
        }
    }

    private suspend fun probeLatency(): Double? {
        val t = runCatching {
            httpTimedTransfer(DOWN_URL + "0", socksPort, false, 0, CONNECT_TIMEOUT, PING_READ_TIMEOUT)
        }.getOrNull() ?: return null
        return max(0.0, t.ttfbNanos / 1_000_000.0 - t.serverMillis)
    }

    private suspend fun runSchedule() {
        val probeScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val probeJob = probeScope.launch { loadedLatencyLoop() }
        try {
            for (m in SCHEDULE) {
                if (stopped) return
                if (finishedFor(m.upload)) continue
                if (spentBytes + m.bytes > TOTAL_BUDGET_BYTES) {
                    downFinished = true; upFinished = true
                    return
                }
                runRound(m)
            }
        } finally {
            probeJob.cancel()
            probeScope.cancel()
        }
    }

    private fun finishedFor(upload: Boolean) = if (upload) upFinished else downFinished

    private suspend fun runRound(m: Measurement) {
        _state.value = _state.value.copy(
            phase = if (m.upload) SpeedTestPhase.Upload else SpeedTestPhase.Download,
            liveMbps = 0.0,
        )
        var minDuration = Double.MAX_VALUE

        for (i in 0 until m.count) {
            if (stopped) return
            if (spentBytes + m.bytes > TOTAL_BUDGET_BYTES) {
                downFinished = true; upFinished = true
                return
            }
            val sample = runRequest(m) ?: continue
            minDuration = min(minDuration, sample.durationMs)
            publish(m.upload, sample, roundProgress = (i + 1) / m.count.toFloat())
        }

        if (!m.bypassFinishRule && minDuration != Double.MAX_VALUE && minDuration > FINISH_REQUEST_MS) {
            if (m.upload) upFinished = true else downFinished = true
        }
    }

    private suspend fun runRequest(m: Measurement): BandwidthSample? {
        val url = if (m.upload) UP_URL else DOWN_URL + m.bytes
        var moved = 0L
        var tick = TimeSource.Monotonic.markNow()
        val timing: HttpTiming = runCatching {
            httpStreamTransfer(
                url = url,
                socksPort = socksPort,
                upload = m.upload,
                uploadBytes = m.bytes,
                connectTimeoutMs = CONNECT_TIMEOUT,
                readTimeoutMs = READ_TIMEOUT,
                onChunk = { delta ->
                    moved += delta

                    if (tick.elapsedNow().inWholeMilliseconds >= SAMPLE_MS) {
                        tick = TimeSource.Monotonic.markNow()
                        _state.value = _state.value.copy(bytesUsed = spentBytes + moved)
                    }
                    !stopped
                },
            )
        }.getOrNull() ?: run {
            spentBytes += moved
            _state.value = _state.value.copy(bytesUsed = spentBytes)
            return null
        }

        spentBytes += max(moved, timing.bytes)
        return toSample(m.upload, m.bytes, timing)
    }

    private fun toSample(upload: Boolean, requested: Long, t: HttpTiming): BandwidthSample? {
        val ttfbMs = t.ttfbNanos / 1_000_000.0
        val wallMs = t.wallNanos / 1_000_000.0
        val ping = max(0.0, ttfbMs - t.serverMillis)
        val payloadMs = max(0.0, wallMs - ttfbMs)
        val durationMs = if (upload) ttfbMs else ping + payloadMs
        if (durationMs <= 0.0) return null

        val payloadBytes = if (upload) requested.toDouble() * 1.005 else {
            (if (t.bytes > 0) t.bytes else requested).toDouble()
        }
        val bps = payloadBytes * 8.0 / (durationMs / 1000.0)
        return BandwidthSample(bytes = requested, durationMs = durationMs, bps = bps, ping = ping)
    }

    private fun publish(upload: Boolean, sample: BandwidthSample, roundProgress: Float) {
        val list = if (upload) upSamples else downSamples
        list.add(sample)

        val mbps = sample.bps / 1_000_000.0
        val agg = SpeedTestMath.bandwidthBps(list, BANDWIDTH_PERCENTILE, MIN_REQUEST_MS)
            ?.let { it / 1_000_000.0 }
        val graph = list.map { SpeedSample(it.bps / 1_000_000.0) }

        _state.value = _state.value.copy(
            liveMbps = mbps,
            progress = roundProgress,
            bytesUsed = spentBytes,
            downSamples = if (!upload) graph else _state.value.downSamples,
            upSamples = if (upload) graph else _state.value.upSamples,
            downloadMbps = if (!upload) agg ?: _state.value.downloadMbps else _state.value.downloadMbps,
            uploadMbps = if (upload) agg ?: _state.value.uploadMbps else _state.value.uploadMbps,
        )
    }

    private suspend fun CoroutineScope.loadedLatencyLoop() {
        while (isActive && !stopped) {
            delay(LOADED_PROBE_MS)
            if (stopped) return

            if (!_state.value.running || _state.value.phase == SpeedTestPhase.Ping) continue
            val ms = probeLatency() ?: continue
            loadedPings.add(ms)
            while (loadedPings.size > LOADED_MAX_POINTS) loadedPings.removeAt(0)
            _state.value = _state.value.copy(
                loadedPingMs = SpeedTestMath.percentile(loadedPings, LATENCY_PERCENTILE),
            )
        }
    }
}

val speedTestBudgetMb: Int
    get() = (SpeedTestEngine.TOTAL_BUDGET_BYTES / 1_000_000).toInt()
