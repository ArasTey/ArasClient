package com.aras.client.ui.compose

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import kotlinx.coroutines.launch

/**
 * The connect button.
 *
 * Everything that glows is drawn *inside* the circle: the rotating sweep is inset to
 * the rim and clipped by the shape, and the breathing light is a radial gradient that
 * is transparent in the middle. Nothing spills past the edge, so the button keeps a
 * clean outline against whatever sits behind it.
 *
 * The two continuous animations run only while the tunnel is up — an idle button that
 * animates forever costs battery for nothing.
 */
@Composable
fun NeonConnectButton(
    isRunning: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val lit = Color(0xFF30E08A)
    val idleFill = MaterialTheme.colorScheme.surfaceVariant
    val idleEdge = MaterialTheme.colorScheme.outlineVariant

    // The entry spring: a small overshoot on the way to connected.
    val pop = remember { Animatable(1f) }
    LaunchedEffect(isRunning) {
        if (!isRunning) return@LaunchedEffect
        pop.snapTo(0.88f)
        pop.animateTo(1f, tween(420))
    }
    val popScale = pop.value

    val sweepDegrees = remember { Animatable(0f) }
    val glow = remember { Animatable(0f) }
    LaunchedEffect(isRunning) {
        if (!isRunning) {
            sweepDegrees.animateTo(0f, tween(260))
            glow.animateTo(0f, tween(260))
            return@LaunchedEffect
        }
        // A long, slow rotation and a slow breath: present, not busy.
        launch {
            sweepDegrees.animateTo(
                targetValue = sweepDegrees.value + 360f,
                animationSpec = infiniteRepeatable(
                    tween(2800, easing = LinearEasing),
                    RepeatMode.Restart,
                ),
            )
        }
        launch {
            glow.animateTo(
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    tween(2800, easing = EaseInOut),
                    RepeatMode.Reverse,
                ),
            )
        }
    }

    Box(
        modifier = modifier
            .size(58.dp)
            .scale(popScale)
            .clip(CircleShape)
            .background(
                if (isRunning) {
                    Brush.radialGradient(
                        colors = listOf(Color(0xFF0F2A1D), Color(0xFF07170F)),
                        center = androidx.compose.ui.geometry.Offset(120f, 100f),
                        radius = 620f,
                    )
                } else {
                    Brush.verticalGradient(listOf(idleFill, idleFill))
                }
            )
            .border(
                width = if (isRunning) 2.dp else 1.5.dp,
                color = if (isRunning) lit else idleEdge,
                shape = CircleShape,
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (isRunning) {
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
                            0.93f to lit.copy(alpha = 0.34f),
                            1f to lit.copy(alpha = 0.34f),
                        )
                    )
                )
            }
            // The breathing light, brightest at the rim and clear in the middle.
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.Transparent,
                            lit.copy(alpha = 0.16f + 0.32f * glow.value),
                        ),
                        center = center,
                        radius = size.minDimension / 2f,
                    )
                )
            }
        }

        androidx.compose.material3.Icon(
            painter = painterResource(
                if (isRunning) R.drawable.ic_stop_24dp else R.drawable.ic_play_24dp
            ),
            contentDescription = stringResource(
                if (isRunning) R.string.acc_stop else R.string.acc_start
            ),
            tint = if (isRunning) lit else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(25.dp),
        )
    }
}