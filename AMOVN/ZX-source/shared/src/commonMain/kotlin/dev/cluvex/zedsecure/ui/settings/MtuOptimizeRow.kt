package dev.cluvex.zedsecure.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.cluvex.zedsecure.data.net.MtuOptimizer
import dev.cluvex.zedsecure.data.net.MtuOverhead
import dev.cluvex.zedsecure.data.net.MtuOverheads
import dev.cluvex.zedsecure.data.net.MtuResult
import dev.cluvex.zedsecure.shared.resources.Res
import dev.cluvex.zedsecure.shared.resources.mtu_apply
import dev.cluvex.zedsecure.shared.resources.mtu_link_known
import dev.cluvex.zedsecure.shared.resources.mtu_measuring
import dev.cluvex.zedsecure.shared.resources.mtu_not_measurable
import dev.cluvex.zedsecure.shared.resources.mtu_optimize
import dev.cluvex.zedsecure.shared.resources.mtu_overhead
import dev.cluvex.zedsecure.shared.resources.mtu_probed_public
import dev.cluvex.zedsecure.shared.resources.mtu_probed_server
import dev.cluvex.zedsecure.shared.resources.mtu_unconfirmed
import dev.cluvex.zedsecure.shared.resources.mtu_optimize_note
import dev.cluvex.zedsecure.shared.resources.mtu_result
import dev.cluvex.zedsecure.shared.resources.mtu_vpn_active
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

@Composable
fun MtuOptimizeRow(
    enabled: Boolean,

    serverHost: String? = null,

    overhead: MtuOverhead = MtuOverheads.worstCase(),
    onApply: (Int) -> Unit,
) {
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<MtuResult?>(null) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            stringResource(Res.string.mtu_optimize_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(
                enabled = enabled && !busy,
                onClick = {
                    busy = true
                    result = null

                    scope.launch {
                        val r = withContext(Dispatchers.Default) {
                            MtuOptimizer.optimize(serverHost, overhead)
                        }
                        result = r
                        busy = false
                    }
                },
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(Res.string.mtu_measuring))
                } else {
                    Text(stringResource(Res.string.mtu_optimize))
                }
            }
            val measured = result as? MtuResult.Measured
            if (measured != null) {
                Button(onClick = { onApply(measured.recommended) }) {
                    Text(stringResource(Res.string.mtu_apply, measured.recommended))
                }
            }
        }

        when (val r = result) {
            null -> Unit
            is MtuResult.VpnActive -> Status(stringResource(Res.string.mtu_vpn_active), error = true)
            is MtuResult.NotMeasurable -> {
                Status(stringResource(Res.string.mtu_not_measurable), error = true)
                r.linkMtu?.let { Status(stringResource(Res.string.mtu_link_known, it)) }
            }
            is MtuResult.Measured -> {
                Status(stringResource(Res.string.mtu_result, r.pathMtu, r.recommended))

                Status(
                    if (r.toServer) {
                        stringResource(Res.string.mtu_probed_server, r.host)
                    } else {
                        stringResource(Res.string.mtu_probed_public, r.host)
                    },
                    error = !r.toServer,
                )
                Status(stringResource(Res.string.mtu_overhead, r.overhead.explain()))
                if (!r.confirmed) Status(stringResource(Res.string.mtu_unconfirmed), error = true)
                r.linkMtu?.let { Status(stringResource(Res.string.mtu_link_known, it)) }
            }
        }
    }
}

@Composable
private fun Status(text: String, error: Boolean = false) {
    Spacer(Modifier.height(8.dp))
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    )
}
