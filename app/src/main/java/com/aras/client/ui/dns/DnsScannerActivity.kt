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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.aras.client.ui.base.BaseComponentActivity
import kotlinx.coroutines.launch

class DnsScannerActivity : BaseComponentActivity() {
    @Composable
    override fun ScreenContent() {
        DnsScannerScreen(
            onBack = { finish() },
            onScan = { resolvers, onStart, onComplete ->
                lifecycleScope.launch {
                    onStart()
                    val result = runCatching { DnsScannerEngine.scan(resolvers) }
                    onComplete(result.getOrNull(), result.exceptionOrNull()?.message)
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DnsScannerScreen(
    onBack: () -> Unit,
    onScan: (List<DnsProfile>, () -> Unit, (List<DnsResolverScanResult>?, String?) -> Unit) -> Unit,
) {
    val resolvers = remember {
        (DnsChangerStore.presets + DnsChangerStore.customProfiles()).distinctBy { it.primary }
    }
    val results = remember { mutableStateListOf<DnsResolverScanResult>() }
    var scanning by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

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
                        Text("${resolvers.size} resolvers ready", fontWeight = FontWeight.Bold)
                        Text("A, AAAA, NS, TXT, EDNS and randomized NXDOMAIN checks. UDP is used first; truncated replies retry over TCP.")
                        Button(
                            enabled = !scanning,
                            onClick = {
                                results.clear()
                                message = null
                                onScan(resolvers, { scanning = true }) { scanned, error ->
                                    scanning = false
                                    if (scanned != null) {
                                        results.addAll(scanned.sortedWith(
                                            compareByDescending<DnsResolverScanResult> { it.isHealthy }
                                                .thenBy { it.averageLatencyMs ?: Long.MAX_VALUE }
                                        ))
                                        message = "Scan completed: ${scanned.count { it.isHealthy }} healthy of ${scanned.size}"
                                    } else message = error ?: "Scan failed"
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (scanning) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text(if (scanning) "SCANNING…" else "SCAN ALL RESOLVERS")
                        }
                        message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
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
                                message = "${result.resolver.name} selected. Open DNS Changer to connect."
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("USE THIS RESOLVER") }
                    }
                }
            }
        }
    }
}
