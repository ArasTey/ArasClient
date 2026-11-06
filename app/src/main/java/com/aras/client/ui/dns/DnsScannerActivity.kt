package com.aras.client.ui.dns

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.aras.client.ui.base.BaseComponentActivity

class DnsScannerActivity : BaseComponentActivity() {
    @Composable
    override fun ScreenContent() {
        val scanState by DnsScanCoordinator.state.collectAsState()
        DnsScannerScreen(
            scanState = scanState,
            onBack = { finish() },
            onScan = { resolvers -> DnsScanCoordinator.start(resolvers) },
            onCancel = { DnsScanCoordinator.cancel() },
            onRestoreHistory = { entry -> DnsScanCoordinator.restoreHistory(entry) }
        )
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DnsScannerScreen(
    scanState: DnsScannerUiState,
    onBack: () -> Unit,
    onScan: (List<DnsProfile>) -> Unit,
    onCancel: () -> Unit,
    onRestoreHistory: (DnsScanHistoryEntry) -> Unit,
) {
    val builtIn = remember {
        (DnsChangerStore.presets + DnsChangerStore.customProfiles()).distinctBy { it.primary }
    }
    var useRanges by remember { mutableStateOf(false) }
    var rangeText by remember { mutableStateOf("") }
    var healthyOnly by remember { mutableStateOf(false) }
    var sortByScore by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    val parsed = remember(rangeText) { DnsScanInput.parse(rangeText) }
    val resolvers = if (useRanges) DnsScanInput.toProfiles(parsed.ips) else builtIn
    val clipboard = LocalClipboardManager.current
    val results = scanState.results
        .filter { !healthyOnly || it.isHealthy }
        .let { list ->
            if (sortByScore) list.sortedByDescending { it.passedCount }
            else list.sortedBy { it.averageLatencyMs ?: Long.MAX_VALUE }
        }
    val scanning = scanState.isScanning
    val progress = scanState.progress
    var selectionMessage by remember { mutableStateOf<String?>(null) }
    val message = selectionMessage ?: scanState.message

    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("DNS SCANNER & RESOLVER", fontWeight = FontWeight.Black)
                        Text("Real DNS probes • compatibility • latency", style = MaterialTheme.typography.labelSmall)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(insets),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = !useRanges, onClick = { useRanges = false }, label = { Text("Built-in list") })
                            FilterChip(selected = useRanges, onClick = { useRanges = true }, label = { Text("IP ranges / custom") })
                        }
                        if (useRanges) {
                            OutlinedTextField(
                                value = rangeText,
                                onValueChange = { rangeText = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("8.8.8.0/24 • 1.1.1.1-1.1.1.10 • 9.9.9.9 • 1.2.3.4:53") },
                                minLines = 3,
                            )
                            if (parsed.invalid.isNotEmpty()) {
                                Text("Ignored: ${parsed.invalid.take(5).joinToString()}", style = MaterialTheme.typography.labelSmall)
                            }
                            if (parsed.truncated) {
                                Text("Limited to ${DnsScanInput.MAX_TARGETS} addresses", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Text("${resolvers.size} resolvers ready", fontWeight = FontWeight.Bold)
                        Text("A, AAAA, NS, TXT, EDNS and randomized NXDOMAIN checks. Also validates independent Google DoH and Cloudflare DoT reachability. UDP replies retry over TCP when truncated.")
                        Button(
                            enabled = scanning || resolvers.isNotEmpty(),
                            onClick = {
                                if (scanning) onCancel() else {
                                    selectionMessage = null
                                    onScan(resolvers)
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (scanning) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text(if (scanning) "CANCEL SCAN" else "SCAN ALL RESOLVERS")
                        }
                        if (scanning) {
                            Text(
                                "Scanned ${progress.completed} / ${progress.total}" +
                                    progress.resolverName.takeIf { it.isNotBlank() }?.let { " • $it" }.orEmpty(),
                                style = MaterialTheme.typography.bodySmall
                            )
                            androidx.compose.material3.LinearProgressIndicator(
                                progress = { if (progress.total == 0) 0f else progress.completed.toFloat() / progress.total },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = showHistory, onClick = { showHistory = !showHistory }, label = { Text("History (" + scanState.history.size + ")") })
                }
            }
            if (showHistory) {
                items(scanState.history, key = { "history-" + it.timestamp }) { entry ->
                    Card {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(entry.timestamp)), fontWeight = FontWeight.Bold)
                            val healthyCount = entry.results.count { it.isHealthy }
                            Text(entry.results.size.toString() + " resolvers • " + healthyCount + " healthy")
                            OutlinedButton(onClick = { onRestoreHistory(entry); showHistory = false }, modifier = Modifier.fillMaxWidth()) { Text("CONTINUE / RESTORE THIS SCAN") }
                        }
                    }
                }
            }
            if (scanState.results.isNotEmpty()) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(selected = healthyOnly, onClick = { healthyOnly = !healthyOnly }, label = { Text("Healthy only") })
                        FilterChip(selected = sortByScore, onClick = { sortByScore = !sortByScore }, label = { Text("Sort: score") })
                        OutlinedButton(onClick = {
                            val csv = buildString {
                                appendLine("resolver,ip,healthy,passed,total,check,transport,latency_ms,detail")
                                results.forEach { result ->
                                    result.checks.forEach { check ->
                                        val cells = listOf(result.resolver.name, result.resolver.primary, result.isHealthy.toString(), result.passedCount.toString(), result.checks.size.toString(), check.name, check.transport, check.latencyMs?.toString().orEmpty(), check.detail)
                                        appendLine(cells.joinToString(",") { "\"" + it.replace("\"", "\"\"") + "\"" })
                                    }
                                }
                            }
                            clipboard.setText(AnnotatedString(csv))
                            selectionMessage = "CSV results copied"
                        }) { Text("Copy CSV") }
                    }
                }
            }
            if (results.isEmpty() && !scanning) {
                item { Text("Run a scan to compare resolver health and protocol compatibility.") }
            }
            items(results, key = { it.resolver.primary }) { result ->
                Card {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (result.isHealthy) Icons.Rounded.CheckCircle else Icons.Rounded.Dns,
                                contentDescription = null,
                                tint = if (result.isHealthy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.size(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(result.resolver.name, fontWeight = FontWeight.Bold)
                                Text(result.resolver.primary, style = MaterialTheme.typography.labelSmall)
                            }
                            Text("${result.passedCount}/${result.checks.size}", fontWeight = FontWeight.Bold)
                        }
                        Text(
                            "Average: ${result.averageLatencyMs?.let { "$it ms" } ?: "—"} • " +
                                if (result.isHealthy) "Healthy" else "Limited / failed",
                            style = MaterialTheme.typography.bodySmall
                        )
                        result.checks.forEach { check ->
                            Text(
                                "${if (check.passed) "✓" else "✕"} ${check.name}: " +
                                    "${check.latencyMs?.let { "$it ms" } ?: check.detail} • ${check.transport}",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        Button(
                            enabled = result.isHealthy && !scanning,
                            onClick = {
                                DnsChangerStore.activate(result.resolver)
                                selectionMessage = "${result.resolver.name} selected. Open DNS Changer to connect."
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("USE THIS RESOLVER") }
                    }
                }
            }
        }
    }
}
