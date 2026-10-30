package com.aras.client.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.aras.client.util.ConnectionStatsManager
import kotlinx.coroutines.delay

// ---------------------------------------------------------------- formatting

fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "-"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    return if (unit == 0) "${value.toLong()} ${units[unit]}"
    else String.format("%.1f %s", value, units[unit])
}

fun formatDuration(millis: Long): String {
    if (millis < 0) return "-"
    val totalSec = millis / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return when {
        h > 0 -> String.format("%d:%02d:%02d", h, m, s)
        else -> String.format("%02d:%02d", m, s)
    }
}

private fun formatDurationLong(millis: Long): String {
    if (millis < 0) return "-"
    val totalSec = millis / 1000
    val d = totalSec / 86400
    val h = (totalSec % 86400) / 3600
    val m = (totalSec % 3600) / 60
    return when {
        d > 0 -> "${d}d ${h}h ${m}m"
        h > 0 -> "${h}h ${m}m"
        else -> "${m}m"
    }
}

// ----------------------------------------------------------- settings detail

/** Full statistics breakdown shown inside Settings. */
@Composable
fun ConnectionStatsSection(modifier: Modifier = Modifier) {
    // Lifetime session seconds are persisted at disconnect; live-updating
    // while connected would reset on process death, so keep it read-only.
    var totalDown by remember { mutableLongStateOf(0L) }
    var totalUp by remember { mutableLongStateOf(0L) }
    var sessionMs by remember { mutableLongStateOf(0L) }
    var connectCount by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        while (true) {
            val snap = ConnectionStatsManager.snapshot()
            val prefs = com.aras.client.handler.MmkvManager
            totalDown = prefs.decodeSettingsLong("stats_total_down", 0) + snap.sessionDown
            totalUp = prefs.decodeSettingsLong("stats_total_up", 0) + snap.sessionUp
            sessionMs = prefs.decodeSettingsLong("stats_session_ms", 0) + snap.sessionMillis
            connectCount = prefs.decodeSettingsLong("stats_connect_count", 0)
            delay(2000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        StatRow(stringResource(com.aras.client.R.string.stats_connections), connectCount.toString())
        StatRow(
            stringResource(com.aras.client.R.string.stats_total_time),
            formatDurationLong(sessionMs)
        )
        StatRow(
            stringResource(com.aras.client.R.string.stats_total_down),
            formatBytes(totalDown),
            valueColor = MaterialTheme.colorScheme.primary
        )
        StatRow(
            stringResource(com.aras.client.R.string.stats_total_up),
            formatBytes(totalUp),
            valueColor = MaterialTheme.colorScheme.tertiary
        )
        StatRow(
            stringResource(com.aras.client.R.string.stats_total_all),
            formatBytes(totalDown + totalUp),
            valueColor = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun StatRow(label: String, value: String, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            color = valueColor,
            fontFamily = FontFamily.Monospace
        )
    }
}
