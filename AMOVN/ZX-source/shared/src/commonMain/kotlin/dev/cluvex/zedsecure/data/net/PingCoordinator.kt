package dev.cluvex.zedsecure.data.net

import dev.cluvex.zedsecure.domain.config.VpnProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object PingCoordinator {
    data class Progress(val done: Int, val total: Int, val real: Boolean)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _progress = MutableStateFlow<Progress?>(null)

    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    private var job: Job? = null

    private var generation = 0

    fun start(
        profiles: List<VpnProfile>,
        useRealDelay: Boolean,
        concurrency: Int,
        delayUrl: String,
        chainConfig: (VpnProfile) -> String?,
        clearPings: (List<String>) -> Unit,
        onResult: (id: String, ms: Int?, countryCode: String?) -> Unit,
        flush: () -> Unit,

        onFinished: () -> Unit = {},
    ) {
        job?.cancel()
        val mine = ++generation
        _progress.value = Progress(0, profiles.size, useRealDelay)
        job = scope.launch {
            try {
                clearPings(profiles.map { it.id })
                PingService.measureAll(
                    profiles = profiles,
                    useRealDelay = useRealDelay,
                    concurrency = concurrency,
                    delayUrl = delayUrl,
                    chainConfig = chainConfig,
                    onProgress = { done, total ->
                        if (mine == generation) _progress.value = Progress(done, total, useRealDelay)
                    },
                    onResult = onResult,
                )
            } finally {
                flush()
                if (mine == generation) {
                    _progress.value = null
                    onFinished()
                }
            }
        }
    }

    fun cancel() {
        generation++
        job?.cancel()
        job = null
        _progress.value = null
    }
}
