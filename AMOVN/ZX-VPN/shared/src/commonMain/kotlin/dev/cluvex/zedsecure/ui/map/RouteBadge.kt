package dev.cluvex.zedsecure.ui.map

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.cluvex.zedsecure.ui.components.FlagBadge

@Composable
fun RouteBadge(
    originCode: String?,
    originLabel: String,
    destinationCode: String?,
    destinationLabel: String,
    accent: Color,
    originColor: Color,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,

    linked: Boolean = true,
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        tonalElevation = 4.dp,
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
        ) {
            Endpoint(originCode, originLabel, originColor, hollow = true)
            if (linked) {
                Connector(accent, reduceMotion)
                Endpoint(destinationCode, destinationLabel, accent, hollow = false)
            }
        }
    }
}

@Composable
private fun Endpoint(code: String?, label: String, tint: Color, hollow: Boolean, size: Dp = 34.dp) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            shape = CircleShape,
            color = Color.Transparent,
            border = BorderStroke(if (hollow) 1.5.dp else 2.dp, tint),
            modifier = Modifier.size(size),
        ) {
            Box(contentAlignment = Alignment.Center) {
                FlagBadge(code, size = size, modifier = Modifier.clip(CircleShape))
            }
        }
        if (label.isNotBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun Connector(accent: Color, reduceMotion: Boolean) {
    val travel = if (reduceMotion) 0f else {
        val t = rememberInfiniteTransition(label = "route-travel")
        t.animateFloat(
            0f, 1f,
            infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart),
            label = "route-dot",
        ).value
    }

    val ltr = LocalLayoutDirection.current == LayoutDirection.Ltr
    Canvas(Modifier.width(38.dp).height(34.dp)) {
        val y = size.height / 2f
        val from = if (ltr) 0f else size.width
        val to = if (ltr) size.width else 0f
        val dotR = 1.6.dp.toPx()

        var t = 0f
        while (t <= 1f) {
            val x = from + (to - from) * t
            drawCircle(accent.copy(alpha = 0.30f), dotR, Offset(x, y))
            t += 0.18f
        }

        val hx = to - (if (ltr) 1f else -1f) * 3.dp.toPx()
        drawCircle(accent, 3.2.dp.toPx(), Offset(hx, y))

        if (!reduceMotion) {
            val x = from + (to - from) * travel
            drawCircle(accent.copy(alpha = 0.25f), 6.dp.toPx(), Offset(x, y))
            drawCircle(accent, 2.6.dp.toPx(), Offset(x, y))
        }
    }
}
