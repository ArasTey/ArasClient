package com.aras.client.ui.zx.mtu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aras.client.AppConfig
import com.aras.client.handler.MmkvManager
import com.aras.client.core.CoreServiceManager
import com.aras.client.ui.base.BaseComponentActivity
import kotlinx.coroutines.launch

/** ZX "Optimize MTU" row as a standalone screen; applies the result to the VPN MTU setting. */
class MtuOptimizeActivity : BaseComponentActivity() {
    @Composable
    override fun ScreenContent() {
        var busy by remember { mutableStateOf(false) }
        var result by remember { mutableStateOf<MtuResult?>(null) }
        var applied by remember { mutableStateOf<Int?>(null) }
        val scope = rememberCoroutineScope()
        Column(
            Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("MTU Optimizer", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Measures the real path MTU with don't-fragment pings and subtracts protocol overhead. Disconnect the VPN first.",
                style = MaterialTheme.typography.bodySmall,
            )
            FilledTonalButton(
                enabled = !busy,
                onClick = {
                    busy = true; result = null; applied = null
                    scope.launch {
                        result = MtuOptimizer.optimize(vpnActive = CoreServiceManager.isRunning())
                        busy = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                    Text("Measuring…")
                } else Text("Optimize MTU")
            }
            when (val r = result) {
                null -> Unit
                MtuResult.VpnActive -> Text("Disconnect the VPN before measuring.", color = MaterialTheme.colorScheme.error)
                MtuResult.NotMeasurable -> Text(
                    "Path MTU could not be measured (ICMP blocked or ping unavailable).",
                    color = MaterialTheme.colorScheme.error,
                )
                is MtuResult.Measured -> {
                    Text("Path MTU ${r.pathMtu} → recommended ${r.recommended}")
                    Text(
                        if (r.toServer) "Probed server ${r.host}" else "Probed public host ${r.host}, not your server",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text("Overhead: ${r.overhead.explain()}", style = MaterialTheme.typography.bodySmall)
                    if (!r.confirmed) Text("Result not fully confirmed.", color = MaterialTheme.colorScheme.error)
                    Button(
                        onClick = {
                            MmkvManager.encodeSettings(AppConfig.PREF_VPN_MTU, r.recommended.toString())
                            applied = r.recommended
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Apply MTU ${r.recommended}") }
                    applied?.let { Text("VPN MTU set to $it. Reconnect to use it.") }
                }
            }
        }
    }
}
