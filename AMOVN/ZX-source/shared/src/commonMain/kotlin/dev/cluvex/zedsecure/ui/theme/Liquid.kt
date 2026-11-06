package dev.cluvex.zedsecure.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * "Liquid" design language: iOS 26 style translucent glass layered over a soft animated
 * aurora, with One UI 9 style large rounded containers.
 */

/** One UI flavoured corner radii: bigger and rounder than the stock Material set. */
val LiquidShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(22.dp),
    large = RoundedCornerShape(30.dp),
    largeIncreased = RoundedCornerShape(36.dp),
    extraLarge = RoundedCornerShape(42.dp),
    extraLargeIncreased = RoundedCornerShape(48.dp),
    extraExtraLarge = RoundedCornerShape(56.dp),
)

private val isDarkSurface: Boolean
    @Composable get() = MaterialTheme.colorScheme.surface.luminance() < 0.5f

/** Translucent glass fill. [strength] scales how opaque the pane is (1 = normal card). */
@Composable
fun liquidFill(strength: Float = 1f): Color {
    val dark = isDarkSurface
    val base = if (dark) 0.10f else 0.58f
    return Color.White.copy(alpha = (base * strength).coerceIn(0f, 1f))
}

/** Glass tinted with an accent colour (used for the "live" hero and active elements). */
@Composable
fun liquidTint(tint: Color, strength: Float = 1f): Color {
    val dark = isDarkSurface
    val base = if (dark) 0.34f else 0.62f
    return tint.copy(alpha = (base * strength).coerceIn(0f, 1f))
}

/** Thin bright edge that fades around the pane, the "light catching the glass" look. */
@Composable
fun liquidBorder(width: androidx.compose.ui.unit.Dp = 1.dp): BorderStroke {
    val dark = isDarkSurface
    val hi = Color.White.copy(alpha = if (dark) 0.42f else 0.95f)
    val lo = Color.White.copy(alpha = if (dark) 0.05f else 0.25f)
    return BorderStroke(
        width,
        Brush.linearGradient(
            colors = listOf(hi, lo, hi.copy(alpha = hi.alpha * 0.45f)),
            start = Offset.Zero,
            end = Offset.Infinite,
        ),
    )
}

/**
 * Slowly drifting colour blobs behind everything. [clock] is in seconds; when it is not
 * running the blobs simply sit still, so reduce-motion and battery saver cost nothing.
 */
@Composable
fun LiquidBackdrop(clock: State<Float>, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val dark = isDarkSurface
    val a = cs.primary
    val b = cs.tertiary
    val c = cs.secondary
    val alpha = if (dark) 0.42f else 0.30f
    Box(modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val t = clock.value
            val w = size.width
            val h = size.height
            val r = maxOf(w, h) * 0.62f
            fun blob(color: Color, cx: Float, cy: Float, radius: Float) {
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(color.copy(alpha = alpha), color.copy(alpha = alpha * 0.35f), Color.Transparent),
                        center = Offset(cx, cy),
                        radius = radius,
                    ),
                    radius = radius,
                    center = Offset(cx, cy),
                )
            }
            blob(a, w * (0.15f + 0.10f * sin(t * 0.21f)), h * (0.18f + 0.06f * cos(t * 0.17f)), r)
            blob(b, w * (0.90f + 0.08f * cos(t * 0.19f)), h * (0.48f + 0.08f * sin(t * 0.23f)), r * 0.9f)
            blob(c, w * (0.40f + 0.12f * sin(t * 0.15f + 2f)), h * (0.98f + 0.04f * cos(t * 0.2f)), r * 0.85f)
        }
    }
}

/** A clock that never ticks: the backdrop sits still on screens that don't animate it. */
val LiquidStaticClock: State<Float> = object : State<Float> {
    override val value: Float = 0f
}
