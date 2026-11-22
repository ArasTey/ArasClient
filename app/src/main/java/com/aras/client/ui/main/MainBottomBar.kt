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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aras.client.R
import com.aras.client.ui.compose.CardCornerRadius
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
    isConnecting: Boolean = false,
    showSmartConnect: Boolean = true
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
    // the one-line layout of the name, address and traffic figures. FittingBarText
    // below owns the per-string shrink, so nothing here keeps a size state.

    // The connect card must read as just another config card, so it takes the exact
    // same fill token they do (surfaceContainerLow) rather than a hardcoded colour —
    // that keeps it identical in light, dark AND dynamic (Material You) themes. The
    // region beside/below it stays transparent so the Scaffold background shows through,
    // and the list is clipped above the bar (see MainScreen) so nothing scrolls into it.
    val panelFill = MaterialTheme.colorScheme.surfaceContainerLow

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .glassPanel(
                    palette = palette,
                    shape = RoundedCornerShape(CardCornerRadius),
                    fill = panelFill,
                    borderColor = Color.Transparent,
                    borderWidth = 0.dp
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
                    FittingBarText(
                        text = displayText,
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxFontSize = 12f,
                        minFontSize = 8f,
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
                                FittingBarText(
                                    text = subtitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxFontSize = 10.5f,
                                    minFontSize = 7.5f,
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
                                FittingBarText(
                                    text = compactAddress(subtitleIp),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        textDirection = TextDirection.Ltr
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxFontSize = 10.5f,
                                    minFontSize = 7.5f
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))

                if (showSmartConnect) {
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
                }

                Box {
                    NeonConnectButton(
                        isRunning = isRunning,
                        isConnecting = isConnecting,
                        onClick = { onAction(MainAction.ToggleService) },
                    )
                }
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
private fun SessionStat(iconRes: Int, value: String, tint: Color) {    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(13.dp)
        )
        Spacer(Modifier.width(4.dp))
        FittingBarText(
            text = value,
            style = TextStyle(
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            maxFontSize = 11f,
            minFontSize = 8f
        )
    }
}

/**
 * A line of the connect panel that never loses its tail. Cutting a status message,
 * a long config name or an address with an ellipsis reads as a bug, so this shrinks
 * its own face until the line fits and only takes a second line once the smallest
 * allowed size is reached. The bar is measured the same way in the single-column and
 * the two-column server layouts, so one rule covers both.
 */
@Composable
private fun FittingBarText(
    text: String,
    style: TextStyle,
    color: Color,
    maxFontSize: Float,
    minFontSize: Float,
    modifier: Modifier = Modifier,
) {
    var fontSize by remember(text) { mutableFloatStateOf(maxFontSize) }
    var wrapped by remember(text) { mutableStateOf(false) }
    Text(
        text = text,
        style = style.copy(fontSize = fontSize.sp, color = color),
        maxLines = if (wrapped) 2 else 1,
        softWrap = wrapped,
        overflow = TextOverflow.Clip,
        onTextLayout = { layout ->
            if (layout.didOverflowWidth) {
                when {
                    fontSize > minFontSize ->
                        fontSize = (fontSize - 0.5f).coerceAtLeast(minFontSize)
                    !wrapped -> wrapped = true
                }
            }
        },
        modifier = modifier
    )
}
