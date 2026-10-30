package com.aras.client.ui.compose

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aras.client.R

/**
 * The connect button.
 *
 * Everything that glows is drawn *inside* the circle: the rotating sweep is inset to
 * the rim and clipped by the shape. Nothing spills past the edge, so the button keeps a
 * clean outline against whatever sits behind it.
 *
 * Three states: idle, connecting (amber, sweep runs faster) and connected (green).
 * The sweep runs only while something is happening — an idle button that animates
 * forever costs battery for nothing.
 */
@Composable
fun NeonConnectButton(
    isRunning: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isConnecting: Boolean = false,
) {
    val palette = LocalGlassPalette.current
    val lit = Color(0xFF30E08A)
    val pending = palette.amber
    val active = isRunning || isConnecting
    val activeColor = if (isRunning) lit else pending
    val idleFill = MaterialTheme.colorScheme.surfaceVariant
    val idleEdge = MaterialTheme.colorScheme.outlineVariant
    val activeFill = when {
        isRunning -> Color(0xFF0C2117)
        palette.isDark -> Color(0xFF2A2210)
        else -> Color(0xFFFFF4DC)
    }

    // The entry spring: a small overshoot on the way to an active state.
    val pop = remember { Animatable(1f) }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        pop.snapTo(0.88f)
        pop.animateTo(1f, tween(420))
    }
    val popScale = pop.value

    val sweepDegrees = remember { Animatable(0f) }
    LaunchedEffect(active, isConnecting) {
        if (!active) {
            sweepDegrees.animateTo(0f, tween(260))
            return@LaunchedEffect
        }
        // A long, slow rotation when connected: present, not busy. While connecting
        // it turns faster, because here it *is* the progress cue.
        sweepDegrees.animateTo(
            targetValue = sweepDegrees.value + 360f,
            animationSpec = infiniteRepeatable(
                tween(if (isConnecting) 1400 else 2800, easing = LinearEasing),
                RepeatMode.Restart,
            ),
        )
    }

    Box(
        modifier = modifier
            .size(58.dp)
            .scale(popScale)
            .clip(CircleShape)
            .background(if (active) activeFill else idleFill)
            .border(
                width = if (active) 2.dp else 1.5.dp,
                color = if (active) activeColor else idleEdge,
                shape = CircleShape,
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (active) {
            // The sweep, clipped to the circle by the parent's shape. One bright arc
            // trailing around, not a gradient across the whole circle: a symmetric
            // sweepGradient would read as two lobes spinning, which is busier than
            // what was chosen.
            Canvas(
                Modifier
                    .fillMaxSize()
                    .rotate(sweepDegrees.value)
            ) {
                drawCircle(
                    brush = Brush.sweepGradient(
                        colorStops = arrayOf(
                            0f to Color.Transparent,
                            0.84f to Color.Transparent,
                            0.93f to activeColor.copy(alpha = if (palette.isDark) 0.34f else 0.42f),
                            1f to activeColor.copy(alpha = if (palette.isDark) 0.34f else 0.42f),
                        )
                    )
                )
            }
        }

        androidx.compose.material3.Icon(
            painter = painterResource(
                if (active) R.drawable.ic_stop_24dp else R.drawable.ic_play_24dp
            ),
            contentDescription = stringResource(
                if (active) R.string.acc_stop else R.string.acc_start
            ),
            tint = if (active) activeColor else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(25.dp),
        )
    }
}
