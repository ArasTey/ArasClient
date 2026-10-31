package com.aras.client.ui.main

import android.os.SystemClock
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aras.client.R
import com.aras.client.ui.compose.GlassPalette
import com.aras.client.ui.compose.LocalGlassPalette
import com.aras.client.ui.compose.NeonConnectButton
import com.aras.client.ui.compose.formatBytes
import com.aras.client.ui.compose.formatDuration
import com.aras.client.ui.compose.glassPanel
import com.aras.client.util.ConnectionStatsManager
import kotlinx.coroutines.delay

/**
 * Floating connect panel: session time / throughput and connection status share
 * one glass card, so the traffic strip and the status message never stack into
 * two separate floating boxes.
 */
@Composable
fun MainBottomBar(
    displayText: String,
    isRunning: Boolean,
    onAction: (MainAction) -> Unit,
    subtitleOverride: String? = null,
    subtitleIp: String? = null,
    isConnecting: Boolean = false
) {
    val palette = LocalGlassPalette.current
    val dotColor = when {
        isRunning -> palette.pingGood
        isConnecting -> palette.amber
        else -> MaterialTheme.colorScheme.outline
    }
    // A still dot on an idle-looking card was the whole complaint: a tunnel that takes
    // a minute to come up read as a hung app. The pulse says work is still happening.
    val pulse = rememberInfiniteTransition(label = "connectPulse")
    val connectingAlpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            tween(850, easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        ),
        label = "connectPulseAlpha"
    )
    // The bar carries its own type scale: a larger system font must not break
    // the one-line layout of the name, address and traffic figures.
    var titleSize by remember(displayText) { mutableStateOf(12.sp) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .glassPanel(
                    palette = palette,
                    shape = RoundedCornerShape(28.dp),
                    fill = if (palette.isDark) Color(0xFA171F27) else Color(0xFDFEFFFF),
                    borderColor = if (palette.isDark) Color(0x42FFFFFF) else Color(0xF2FFFFFF)
                )
                .clickable(onClick = { onAction(MainAction.TestCurrentServer) })
        ) {
            if (isRunning) {
                SessionStatsRow(palette)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .height(0.7.dp)
                        .background(palette.hairline)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(palette.hairline),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(11.dp)
                            .clip(CircleShape)
                            .background(
                                dotColor.copy(alpha = if (isConnecting) connectingAlpha else 1f)
                            )
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = displayText,
                        style = MaterialTheme.typography.labelLarge,
                        fontSize = titleSize,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { layout ->
                            if (layout.didOverflowWidth && titleSize.value > 9f) {
                                titleSize = (titleSize.value - 0.5f).sp
                            }
                        },
                        modifier = Modifier.semantics { contentDescription = displayText }
                    )
                    // Null requests the disconnected hint; empty explicitly hides the subtitle.
                    val subtitle = subtitleOverride ?: if (isRunning) ""
                        else stringResource(R.string.bar_tap_to_connect)
                    if (subtitle.isNotEmpty() || !subtitleIp.isNullOrEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (subtitle.isNotEmpty()) {
                                // The country takes the squeeze; the address is short
                                // and must stay whole.
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 10.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                            }
                            if (!subtitleIp.isNullOrEmpty()) {
                                if (subtitle.isNotEmpty()) {
                                    Text(
                                        text = " · ",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 10.5.sp,
                                        color = MaterialTheme.colorScheme.outline,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                                // Language-neutral, so it is pinned LTR: inside the RTL
                                // bar the address would otherwise read reversed.
                                Text(
                                    text = compactAddress(subtitleIp),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        textDirection = TextDirection.Ltr
                                    ),
                                    fontSize = 10.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))

                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(palette.chipFill)
                        .border(0.8.dp, palette.accent.copy(alpha = 0.20f), CircleShape)
                        .clickable(onClick = { onAction(MainAction.SmartConnect) }),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_smart_connect_24dp),
                        contentDescription = stringResource(R.string.title_smart_connect),
                        tint = palette.accent,
                        modifier = Modifier.size(21.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))

                NeonConnectButton(
                    isRunning = isRunning,
                    isConnecting = isConnecting,
                    onClick = { onAction(MainAction.ToggleService) },
                )
            }
        }
    }
}

/** Time and live rates for the running session, folded into the connect panel. */
@Composable
private fun SessionStatsRow(palette: GlassPalette) {
    var snapshot by remember { mutableStateOf(ConnectionStatsManager.snapshot()) }
    var downRate by remember { mutableFloatStateOf(0f) }
    var upRate by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        var lastDown = snapshot.sessionDown
        var lastUp = snapshot.sessionUp
        var lastAt = SystemClock.elapsedRealtime()
        while (true) {
            delay(1000)
            val next = ConnectionStatsManager.snapshot()
            val now = SystemClock.elapsedRealtime()
            val seconds = ((now - lastAt) / 1000f).coerceAtLeast(0.5f)
            val instantDown = (next.sessionDown - lastDown).coerceAtLeast(0L) / seconds
            val instantUp = (next.sessionUp - lastUp).coerceAtLeast(0L) / seconds
            // The manager samples every 2 s while this row polls every 1 s, so ease
            // the per-second delta instead of flashing 0 B/s on the in-between ticks.
            downRate = if (downRate == 0f) instantDown else downRate * 0.55f + instantDown * 0.45f
            upRate = if (upRate == 0f) instantUp else upRate * 0.55f + instantUp * 0.45f
            lastDown = next.sessionDown
            lastUp = next.sessionUp
            lastAt = now
            snapshot = next
        }
    }

    // Traffic numbers are language-neutral; pin the strip to LTR so "351 B/s"
    // and its arrow never get bidi-reordered inside the RTL UI.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SessionStat(
                iconRes = R.drawable.ic_timer_24dp,
                value = formatDuration(snapshot.sessionMillis),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SessionStat(
                iconRes = R.drawable.ic_arrow_down_24dp,
                value = formatBytes(downRate.toLong()) + "/s",
                tint = palette.accent
            )
            SessionStat(
                iconRes = R.drawable.ic_arrow_up_24dp,
                value = formatBytes(upRate.toLong()) + "/s",
                tint = palette.periwinkle
            )
        }
    }
}

@Composable
private fun SessionStat(iconRes: Int, value: String, tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(13.dp)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = value,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false
        )
    }
}
