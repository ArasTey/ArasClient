package com.aras.client.ui.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aras.client.AppConfig
import com.aras.client.R
import com.aras.client.dto.entities.SubscriptionItem
import com.aras.client.handler.MmkvManager
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Compact v2Box-style bar above the config list showing subscription
 * traffic (used / total) and remaining time, plus an optional announcement
 * banner. Both are gated by subscription settings toggles and only render
 * when the subscription actually provides the data.
 */
@Composable
fun SubscriptionInfoBar(groupId: String, modifier: Modifier = Modifier) {
    if (groupId.isEmpty()) return
    val showInfo = MmkvManager.decodeSettingsBool(AppConfig.PREF_SHOW_SUB_INFO, true)
    val showAnnouncement =
        MmkvManager.decodeSettingsBool(AppConfig.PREF_SHOW_SUB_ANNOUNCEMENT, true)
    if (!showInfo && !showAnnouncement) return

    var sub by remember(groupId) { mutableStateOf<SubscriptionItem?>(null) }
    LaunchedEffect(groupId) {
        sub = withContext(Dispatchers.IO) {
            MmkvManager.decodeSubscription(groupId)
        }
    }
    val current = sub ?: return

    val hasInfo = showInfo && (current.trafficTotal > 0 || current.expireAt > 0)
    val announcement = current.announcement?.takeIf {
        showAnnouncement && it.isNotBlank()
    }
    if (!hasInfo && announcement == null) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (hasInfo) {
            TrafficExpiryBar(current)
        }
        AnimatedVisibility(visible = announcement != null) {
            announcement?.let { text ->
                AnnouncementBanner(text, current.creatorMessage)
            }
        }
    }
}

@Composable
private fun TrafficExpiryBar(sub: SubscriptionItem) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_subscriptions_24dp),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = trafficSummary(sub),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (sub.expireAt > 0) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = expirySummary(sub.expireAt),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isExpiringSoon(sub.expireAt))
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (sub.trafficTotal > 0 && sub.trafficUsed >= 0) {
            Spacer(Modifier.height(6.dp))
            val ratio = (sub.trafficUsed.toDouble() / sub.trafficTotal)
                .coerceIn(0.0, 1.0).toFloat()
            LinearProgressIndicator(
                progress = { ratio },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
                color = if (ratio > 0.9f)
                    MaterialTheme.colorScheme.error
                else
                    MaterialTheme.colorScheme.secondary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
            )
        }
    }
}

@Composable
private fun AnnouncementBanner(text: String, creatorMessage: String?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_promotion_24dp),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
        creatorMessage?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(2.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ------------------------------------------------------------------ helpers

private fun trafficSummary(sub: SubscriptionItem): String {
    if (sub.trafficTotal <= 0) return ""
    val used = sub.trafficUsed.coerceAtLeast(0)
    return "${formatBytes(used)} / ${formatBytes(sub.trafficTotal)}"
}

private fun expirySummary(expireAt: Long): String {
    val now = System.currentTimeMillis()
    val days = TimeUnit.MILLISECONDS.toDays(expireAt - now)
    return when {
        expireAt <= now -> "-"
        days >= 1 -> "$days d"
        else -> "${TimeUnit.MILLISECONDS.toHours(expireAt - now)} h"
    }
}

private fun isExpiringSoon(expireAt: Long): Boolean {
    val remaining = expireAt - System.currentTimeMillis()
    return remaining in 1..TimeUnit.DAYS.toMillis(3)
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "-"
    val units = listOf("B", "KB", "MB", "GB", "TB", "PB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    return if (unit == 0) "${value.toLong()} ${units[unit]}"
    else String.format("%.1f %s", value, units[unit])
}
