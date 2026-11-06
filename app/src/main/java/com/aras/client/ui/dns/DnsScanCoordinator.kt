package com.aras.client.ui.dns

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class DnsScannerUiState(
    val isScanning: Boolean = false,
    val progress: DnsScanProgress = DnsScanProgress(0, 0, ""),
    val results: List<DnsResolverScanResult> = emptyList(),
    val history: List<DnsScanHistoryEntry> = DnsChangerStore.loadScanHistory(),
    val message: String? = null,
)

 /**
  * Process-scoped scan coordinator. A screen recreation or navigation away does
  * not cancel an in-flight scan; the scanner screen can re-observe its progress.
  * The scan remains user-triggered and is not promoted to a foreground service.
  */
object DnsScanCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(DnsScannerUiState())
    val state: StateFlow<DnsScannerUiState> = mutableState.asStateFlow()
    @Volatile private var scanJob: Job? = null

    @Synchronized
    fun start(resolvers: List<DnsProfile>, timeoutMs: Int = 1800, concurrency: Int = 3, mode: DnsScanMode = DnsScanMode.ADVANCED) {
        if (scanJob?.isActive == true) return
        val unique = resolvers.distinctBy { it.primary }
        mutableState.value = DnsScannerUiState(
            isScanning = true,
            progress = DnsScanProgress(0, unique.size, ""),
        )
        scanJob = scope.launch {
            try {
                val scanned = DnsScannerEngine.scan(unique, timeoutMs = timeoutMs, concurrency = concurrency, mode = mode, onProgress = { progress ->
                    mutableState.update { current ->
                        if (current.isScanning) current.copy(progress = progress) else current
                    }
                })
                val sorted = scanned.sortedWith(
                    compareByDescending<DnsResolverScanResult> { it.isHealthy }
                        .thenBy { it.averageLatencyMs ?: Long.MAX_VALUE }
                )
                DnsChangerStore.saveScanHistory(sorted)
                val history = DnsChangerStore.loadScanHistory()
                mutableState.update {
                    it.copy(
                        isScanning = false,
                        results = sorted,
                        history = history,
                        message = "Scan completed: ${sorted.count { result -> result.isHealthy }} healthy of ${sorted.size}",
                    )
                }
            } catch (cancelled: CancellationException) {
                mutableState.update { it.copy(isScanning = false, message = "Scan cancelled") }
                throw cancelled
            } catch (error: Exception) {
                mutableState.update {
                    it.copy(isScanning = false, message = error.message ?: "Scan failed")
                }
            } finally {
                scanJob = null
            }
        }
    }

    fun cancel() {
        scanJob?.cancel()
    }

    fun restoreHistory(entry: DnsScanHistoryEntry) {
        if (scanJob?.isActive != true) {
            mutableState.update {
                it.copy(
                    isScanning = false,
                    results = entry.results,
                    history = DnsChangerStore.loadScanHistory(),
                    message = "Restored previous scan: " + java.text.DateFormat.getDateTimeInstance().format(java.util.Date(entry.timestamp)),
                )
            }
        }
    }

    fun clearResults() {
        if (scanJob?.isActive != true) mutableState.value = DnsScannerUiState()
    }
}
