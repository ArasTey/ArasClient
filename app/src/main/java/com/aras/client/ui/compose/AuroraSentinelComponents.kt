package com.aras.client.ui.compose

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Aurora Sentinel reusable visual components.
 *
 * These components deliberately receive state and labels from the caller;
 * they never claim that a tunnel is healthy based on animation alone.
 */
enum class AuroraLinkState {
    OFF, CONNECTING, CONNECTED, DANGER
}

data class AuroraRadarContact(
    val id: String,
    val pingMs: Int?,
    val profileResponding: Boolean,
    val tested: Boolean = true
)

@Composable
private fun AuroraLinkState.accent(): Color = when (this) {
    AuroraLinkState.OFF -> MaterialTheme.colorScheme.outline
    AuroraLinkState.CONNECTING -> MaterialTheme.colorScheme.secondary
    AuroraLinkState.CONNECTED -> MaterialTheme.colorScheme.primary
    AuroraLinkState.DANGER -> MaterialTheme.colorScheme.error
}

@Composable
fun AuroraGlassCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .background(
                brush = Brush.linearGradient(
                    listOf(
                        scheme.surfaceContainerHigh.copy(alpha = 0.96f),
                        scheme.surface.copy(alpha = 0.92f),
                        scheme.surfaceContainerLow.copy(alpha = 0.96f),
                    )
                ),
                shape = RoundedCornerShape(24.dp)
            )
            .border(
                width = 1.dp,
                color = scheme.outline.copy(alpha = 0.48f),
                shape = RoundedCornerShape(24.dp)
            )
            .padding(17.dp),
        content = content
    )
}

@Composable
fun AuroraStatusPill(
    label: String,
    state: AuroraLinkState,
    modifier: Modifier = Modifier
) {
    val accent by animateColorAsState(
        targetValue = state.accent(),
        animationSpec = tween(300),
        label = "aurora-status-color"
    )

    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(accent.copy(alpha = 0.09f))
            .border(1.dp, accent.copy(alpha = 0.42f), CircleShape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(7.dp).background(accent, CircleShape))
        Spacer(Modifier.size(8.dp))
        Text(
            text = label,
            color = accent,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.8.sp
        )
    }
}

@Composable
fun AuroraSentinelRadar(
    state: AuroraLinkState,
    contacts: List<AuroraRadarContact>,
    centerLabel: String,
    centerSubtitle: String,
    modifier: Modifier = Modifier,
    selectedContactId: String? = null
) {
    val accent = state.accent()
    val surface = MaterialTheme.colorScheme.surface
    val background = MaterialTheme.colorScheme.background
    val transition = rememberInfiniteTransition(label = "aurora-radar")
    val sweepAngle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(3200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "aurora-radar-sweep"
    )
    val pulse by transition.animateFloat(
        initialValue = 0.82f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100),
            repeatMode = RepeatMode.Reverse
        ),
        label = "aurora-radar-pulse"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val radius = size.minDimension * 0.405f
            val center = androidx.compose.ui.geometry.Offset(cx, cy)

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        accent.copy(alpha = 0.10f),
                        surface,
                        background
                    ),
                    center = center,
                    radius = radius
                ),
                radius = radius,
                center = center
            )

            for (i in 1..5) {
                drawCircle(
                    color = accent.copy(alpha = if (state == AuroraLinkState.OFF) 0.12f else 0.23f),
                    radius = radius * i / 5f,
                    center = center,
                    style = Stroke(width = if (i == 5) 1.5f else 0.8f)
                )
            }

            for (degree in 0 until 360 step 15) {
                val rad = Math.toRadians(degree.toDouble())
                val inner = radius * 0.94f
                val outer = radius * if (degree % 45 == 0) 1.08f else 1.02f
                drawLine(
                    color = accent.copy(alpha = if (degree % 45 == 0) 0.48f else 0.22f),
                    start = androidx.compose.ui.geometry.Offset(
                        cx + cos(rad).toFloat() * inner,
                        cy + sin(rad).toFloat() * inner
                    ),
                    end = androidx.compose.ui.geometry.Offset(
                        cx + cos(rad).toFloat() * outer,
                        cy + sin(rad).toFloat() * outer
                    ),
                    strokeWidth = if (degree % 45 == 0) 1.5f else 0.8f
                )
            }

            drawLine(
                accent.copy(alpha = 0.20f),
                androidx.compose.ui.geometry.Offset(cx - radius, cy),
                androidx.compose.ui.geometry.Offset(cx + radius, cy),
                strokeWidth = 0.8f
            )
            drawLine(
                accent.copy(alpha = 0.20f),
                androidx.compose.ui.geometry.Offset(cx, cy - radius),
                androidx.compose.ui.geometry.Offset(cx, cy + radius),
                strokeWidth = 0.8f
            )

            if (state != AuroraLinkState.OFF) {
                val rad = Math.toRadians(sweepAngle.toDouble())
                val end = androidx.compose.ui.geometry.Offset(
                    cx + cos(rad).toFloat() * radius,
                    cy + sin(rad).toFloat() * radius
                )
                val tailRad = rad - 0.28
                val trail = Path().apply {
                    moveTo(cx, cy)
                    lineTo(end.x, end.y)
                    lineTo(
                        cx + cos(tailRad).toFloat() * radius,
                        cy + sin(tailRad).toFloat() * radius
                    )
                    close()
                }
                drawPath(trail, accent.copy(alpha = 0.11f))
                drawLine(accent.copy(alpha = 0.9f), center, end, strokeWidth = 1.7f)
            }

            if (state != AuroraLinkState.OFF) {
                contacts.forEach { contact ->
                    val ping = contact.pingMs
                    val pointColor = when {
                        !contact.tested -> Color(0xFFFFC56E)
                        !contact.profileResponding -> MaterialTheme.colorScheme.error
                        ping == null || ping < 0 -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.primary
                    }
                    val radial = if (ping == null || ping < 0) {
                        0.91f
                    } else {
                        0.10f + (ping.coerceIn(0, 250) / 250f) * 0.80f
                    }
                    val angle = Math.toRadians(
                        ((contact.id.hashCode().toLong() and 0x7FFFFFFF) % 360).toDouble()
                    )
                    val point = androidx.compose.ui.geometry.Offset(
                        cx + cos(angle).toFloat() * radius * radial,
                        cy + sin(angle).toFloat() * radius * radial
                    )

                    drawCircle(pointColor.copy(alpha = 0.18f), 10f * pulse, point)
                    drawCircle(pointColor, if (contact.id == selectedContactId) 5f else 3.5f, point)
                    if (contact.id == selectedContactId) {
                        drawCircle(pointColor, 9f, point, style = Stroke(1.4f))
                    }
                }
            }

            drawCircle(accent, 3f, center)
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = centerLabel,
                color = if (state == AuroraLinkState.OFF) MaterialTheme.colorScheme.error else accent,
                fontSize = 14.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.3.sp
            )
            Spacer(Modifier.height(5.dp))
            Text(
                text = centerSubtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 8.sp,
                letterSpacing = 0.7.sp
            )
        }
    }
}

@Composable
fun AuroraTelemetryMetric(
    title: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
    accent: Color = Color(0xFF55FFC0)
) {
    AuroraGlassCard(modifier = modifier) {
        Text(
            text = title.uppercase(),
            color = Color(0xFF91A39A),
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.sp
        )
        Spacer(Modifier.height(10.dp))
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                text = value,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = unit,
                color = accent,
                fontSize = 10.sp,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
    }
}
