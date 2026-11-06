package dev.cluvex.zedsecure.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.cluvex.zedsecure.shared.resources.Res
import dev.cluvex.zedsecure.shared.resources.*
import dev.cluvex.zedsecure.core.tor.TorBridges
import dev.cluvex.zedsecure.domain.model.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

@Composable
fun TorBridgesScreen(
    s: AppSettings,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val transport = s.torBridgeTransport
    var bridges by remember(transport) { mutableStateOf<List<TorBridges.Bridge>>(emptyList()) }
    LaunchedEffect(transport) { bridges = TorBridges.load(transport) }
    val pings = remember(transport) { SnapshotStateMap<String, Long>() }
    var pingingAll by remember { mutableStateOf(false) }

    val enabled = remember(s.torEnabledBridges) {
        s.torEnabledBridges.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    fun toggle(line: String) {
        val next = enabled.toMutableSet()
        if (!next.remove(line)) next.add(line)
        onUpdate { it.copy(torEnabledBridges = next.joinToString("\n")) }
    }

    fun pingOne(b: TorBridges.Bridge) {
        pings[b.line] = -2L
        scope.launch(Dispatchers.Default) { pings[b.line] = TorBridges.ping(b, s.torSnowflakeRendezvous) }
    }

    Column(modifier.fillMaxSize().padding(contentPadding)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Spacer(Modifier.height(8.dp))
            SettingsHeader(
                title = stringResource(Res.string.tor_bridges_title),
                subtitle = transport,
                art = SettingsArts.Tor,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(Res.string.tor_bridges_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(
                    enabled = !pingingAll && bridges.isNotEmpty(),
                    onClick = {
                        pingingAll = true
                        scope.launch {
                            bridges.map { b ->
                                pings[b.line] = -2L
                                launch(Dispatchers.Default) {
                                    pings[b.line] = TorBridges.ping(b, s.torSnowflakeRendezvous)
                                }
                            }.joinAll()
                            pingingAll = false
                        }
                    },
                ) { Text(stringResource(Res.string.tor_bridges_ping_all)) }
            }
        }

        if (bridges.isEmpty()) {
            Text(
                stringResource(Res.string.tor_bridges_empty),
                Modifier.padding(24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                items(bridges, key = { it.line }) { b ->
                    BridgeRow(
                        bridge = b,
                        ping = pings[b.line],
                        checked = enabled.isEmpty() || b.line in enabled,
                        allUsedByDefault = enabled.isEmpty(),
                        onToggle = { toggle(b.line) },
                        onPing = { pingOne(b) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }
        }
    }
}

@Composable
private fun BridgeRow(
    bridge: TorBridges.Bridge,
    ping: Long?,
    checked: Boolean,
    allUsedByDefault: Boolean,
    onToggle: () -> Unit,
    onPing: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Column(Modifier.weight(1f)) {
            Text(
                bridge.address,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                bridge.line,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        when (ping) {
            -2L -> CircularProgressIndicator(Modifier.padding(4.dp), strokeWidth = 2.dp)
            null -> FilledTonalButton(onClick = onPing) { Text(stringResource(Res.string.tor_bridges_ping)) }
            -1L -> Text(
                stringResource(Res.string.tor_bridges_unreachable),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
            else -> Text(
                "$ping ms",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = when {
                    ping < 400 -> MaterialTheme.colorScheme.primary
                    ping < 1200 -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.error
                },
            )
        }
    }
}
