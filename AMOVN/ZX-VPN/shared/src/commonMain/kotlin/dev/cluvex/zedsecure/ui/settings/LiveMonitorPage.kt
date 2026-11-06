package dev.cluvex.zedsecure.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.cluvex.zedsecure.shared.resources.Res
import dev.cluvex.zedsecure.shared.resources.*
import dev.cluvex.zedsecure.ui.platform.LiveStats
import dev.cluvex.zedsecure.ui.platform.LocalPlatform
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.math.max
import kotlin.math.roundToInt

private const val HISTORY = 120

@Composable
fun LiveMonitorPage(contentPadding: PaddingValues, modifier: Modifier) {
    val platform = LocalPlatform.current
    val computer = platform.isComputer
    var stats by remember { mutableStateOf(LiveStats()) }
    val cpuHistory = remember { mutableStateListOf<Float>() }
    val memHistory = remember { mutableStateListOf<Float>() }
    val tempHistory = remember { mutableStateListOf<Float>() }

    var crypto by remember { mutableStateOf("") }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        crypto = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            platform.cryptoAcceleration()
        }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        suspend fun sample(): LiveStats =
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { platform.liveStats() }
        sample()
        while (true) {
            delay(1_000)
            val s = sample()
            stats = s
            deviceShare(s)?.let { cpuHistory.push(it) }
            s.memoryBytes?.let { memHistory.push(it / 1_048_576f) }
            s.tempC?.let { tempHistory.push(it) }
        }
    }

    val wide = Modifier.fillMaxWidth().padding(horizontal = 16.dp)

    SettingsPageScaffold(
        title = stringResource(Res.string.monitor_title),
        subtitle = stringResource(if (computer) Res.string.monitor_subtitle_computer else Res.string.monitor_subtitle),
        art = SettingsArts.Monitor,
        contentPadding = contentPadding,
        modifier = modifier,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(androidx.compose.foundation.layout.IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HeatCard(stats, tempHistory, computer, Modifier.weight(1f).fillMaxHeight())
            BatteryCard(stats, computer, Modifier.weight(1f).fillMaxHeight())
        }
        Spacer(Modifier.height(12.dp))
        UsageCard(stats, cpuHistory, computer, wide)
        Spacer(Modifier.height(12.dp))
        MemoryCard(stats, memHistory, wide)
        Spacer(Modifier.height(12.dp))
        CoreCard(stats, crypto, wide)
    }
}

private fun androidx.compose.runtime.snapshots.SnapshotStateList<Float>.push(v: Float) {
    add(v)
    while (size > HISTORY) removeAt(0)
}

@Composable
private fun UsageCard(stats: LiveStats, history: List<Float>, computer: Boolean, modifier: Modifier) {
    val percent = deviceShare(stats)
    val shown by animateFloatAsState(percent ?: 0f, tween(800), label = "cpu-value")
    val tone = MaterialTheme.colorScheme.secondary

    MonitorCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(Res.string.monitor_cpu),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    if (percent == null) "—" else formatOneDecimal(shown) + "%",
                    style = MaterialTheme.typography.headlineSmallEmphasized,
                    fontWeight = FontWeight.SemiBold,
                    color = tone,
                )
                Text(
                    stringResource(if (computer) Res.string.monitor_cpu_of_computer else Res.string.monitor_cpu_of_phone),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Sparkline(
                values = history,
                colour = tone,

                minimumTop = 25f,
                fixedTop = true,
                modifier = Modifier.weight(1.3f).height(52.dp),
            )
        }
    }
}

private fun deviceShare(stats: LiveStats): Float? {
    val ofOneCore = stats.cpuPercent ?: return null
    return (ofOneCore / stats.coreCount.coerceAtLeast(1)).coerceIn(0f, 100f)
}

@Composable
private fun HeatCard(stats: LiveStats, history: List<Float>, computer: Boolean, modifier: Modifier) {
    val headroom = stats.thermalHeadroom
    val temp = stats.tempC
    val tone = when {
        headroom != null -> when {
            headroom < 0.5f -> MaterialTheme.colorScheme.primary
            headroom < 0.85f -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.error
        }
        temp == null -> MaterialTheme.colorScheme.onSurfaceVariant
        temp < 38f -> MaterialTheme.colorScheme.primary
        temp < 43f -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }
    val headroomShown by animateFloatAsState((headroom ?: 0f) * 100f, tween(800), label = "headroom")
    val tempShown by animateFloatAsState(temp ?: 0f, tween(800), label = "temp-value")

    MonitorCard(modifier) {
        Text(
            stringResource(Res.string.monitor_heat),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        if (headroom != null) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    heatWord(headroom),
                    style = MaterialTheme.typography.headlineMediumEmphasized,
                    fontWeight = FontWeight.Bold,
                    color = tone,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    formatOneDecimal(headroomShown) + "%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            Text(
                stringResource(if (computer) Res.string.monitor_heat_headroom_computer else Res.string.monitor_heat_headroom),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            ScaleBar(value = headroom * 100f, from = 0f, to = 100f, colour = tone)
        } else {
            Text(
                stringResource(if (computer) Res.string.monitor_heat_unavailable_computer else Res.string.monitor_heat_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        thermalLabel(stats.thermalStatus)?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = tone)
        }

        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(if (computer) Res.string.monitor_cpu_temp else Res.string.monitor_battery_temp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (temp == null) "—" else formatOneDecimal(tempShown) + "°",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Sparkline(
                values = history,
                colour = tone,
                minimumTop = 45f,
                baseline = 20f,
                modifier = Modifier.weight(1.2f).height(40.dp),
            )
        }
    }
}

@Composable
private fun heatWord(headroom: Float): String = when {
    headroom < 0.6f -> stringResource(Res.string.monitor_heat_normal)
    headroom < 0.85f -> stringResource(Res.string.monitor_heat_warm)
    else -> stringResource(Res.string.monitor_heat_hot)
}

@Composable
private fun BatteryCard(stats: LiveStats, computer: Boolean, modifier: Modifier) {
    val percent = stats.batteryPercent
    val fraction by animateFloatAsState((percent ?: 0) / 100f, tween(800), label = "battery-ring")
    val tone = when {
        stats.charging -> MaterialTheme.colorScheme.primary
        (percent ?: 100) <= 20 -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.secondary
    }
    MonitorCard(modifier) {
        Text(
            stringResource(Res.string.monitor_battery),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(Modifier.size(104.dp), contentAlignment = Alignment.Center) {
                val track = MaterialTheme.colorScheme.surfaceContainerHighest
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 11.dp.toPx()
                    val inset = stroke / 2
                    drawArc(
                        color = track,
                        startAngle = 130f,
                        sweepAngle = 280f,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                    drawArc(
                        color = tone,
                        startAngle = 130f,
                        sweepAngle = 280f * fraction.coerceIn(0f, 1f),
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (percent == null) "—" else "$percent",
                        style = MaterialTheme.typography.headlineMediumEmphasized,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        val draw = stats.currentMilliAmps
        Text(
            when {
                computer && percent == null -> stringResource(Res.string.monitor_no_battery)
                stats.charging && draw != null -> stringResource(Res.string.monitor_charging_at, draw.absoluteValue())
                stats.charging -> stringResource(Res.string.monitor_charging)
                draw != null -> stringResource(
                    if (computer) Res.string.monitor_draw_computer else Res.string.monitor_draw,
                    draw.absoluteValue(),
                )
                else -> stringResource(Res.string.monitor_on_battery)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MemoryCard(stats: LiveStats, history: List<Float>, modifier: Modifier) {
    val mb = (stats.memoryBytes ?: 0L) / 1_048_576f
    val shown by animateFloatAsState(mb, tween(800), label = "mem-value")
    val tone = MaterialTheme.colorScheme.secondary
    MonitorCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(Res.string.monitor_memory),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    if (stats.memoryBytes == null) "—" else "${shown.roundToInt()} MB",
                    style = MaterialTheme.typography.headlineSmallEmphasized,
                    fontWeight = FontWeight.Bold,
                    color = tone,
                )
            }
            Sparkline(
                values = history,
                colour = tone,
                minimumTop = 200f,
                modifier = Modifier.weight(1.4f).height(52.dp),
            )
        }
    }
}

@Composable
private fun CoreCard(stats: LiveStats, crypto: String, modifier: Modifier) {
    MonitorCard(modifier) {
        Text(
            stringResource(Res.string.monitor_core),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        stats.processUptimeMs?.let {
            LabelledValue(stringResource(Res.string.monitor_uptime), formatDuration(it))
            Spacer(Modifier.height(6.dp))
        }
        val accelerated = crypto.contains("repaired=[") && !crypto.contains("repaired=[]") ||
            crypto.contains("already=[") && !crypto.contains("already=[]")
        LabelledValue(
            stringResource(Res.string.monitor_crypto),
            when {
                crypto.isBlank() -> "—"
                accelerated -> stringResource(Res.string.monitor_crypto_hw)
                else -> stringResource(Res.string.monitor_crypto_sw)
            },
            valueColour = if (accelerated) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.error,
        )
        if (crypto.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                crypto,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MonitorCard(modifier: Modifier = Modifier, content: @Composable ColumnScopeAlias.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier,
    ) {
        Column(Modifier.padding(18.dp), content = content)
    }
}

@Composable
private fun LabelledValue(label: String, value: String, valueColour: Color = MaterialTheme.colorScheme.onSurface) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = valueColour)
    }
}

@Composable
private fun Sparkline(
    values: List<Float>,
    colour: Color,
    minimumTop: Float,
    baseline: Float = 0f,

    fixedTop: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    Canvas(modifier) {
        val top = if (fixedTop) minimumTop else max(minimumTop, values.maxOrNull() ?: 0f)

        for (f in listOf(0.33f, 0.66f)) {
            drawLine(grid, Offset(0f, size.height * f), Offset(size.width, size.height * f), strokeWidth = 1f)
        }
        if (values.size < 2) return@Canvas

        val step = size.width / (values.size - 1).toFloat()
        fun yOf(v: Float): Float {
            val t = ((v - baseline) / (top - baseline)).coerceIn(0f, 1f)
            return size.height - t * size.height
        }
        val line = Path().apply {
            moveTo(0f, yOf(values.first()))
            values.forEachIndexed { i, v -> if (i > 0) lineTo(i * step, yOf(v)) }
        }
        val area = Path().apply {
            addPath(line)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(
            area,
            Brush.verticalGradient(listOf(colour.copy(alpha = 0.32f), colour.copy(alpha = 0.02f))),
        )
        drawPath(line, colour, style = Stroke(width = 2.5f * density, cap = StrokeCap.Round))
        drawCircle(colour, radius = 3f * density, center = Offset(size.width, yOf(values.last())))
    }
}

@Composable
private fun ScaleBar(value: Float?, from: Float, to: Float, colour: Color) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val position by animateFloatAsState(
        ((value ?: from) - from) / (to - from),
        tween(800),
        label = "scale",
    )
    Box(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape).background(track)) {
        Canvas(Modifier.fillMaxSize()) {
            if (value == null) return@Canvas
            val x = size.width * position.coerceIn(0f, 1f)
            drawRoundRect(
                brush = Brush.horizontalGradient(listOf(colour.copy(alpha = 0.35f), colour)),
                size = androidx.compose.ui.geometry.Size(max(x, 6f), size.height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2, size.height / 2),
            )
        }
    }
}

private fun Int.absoluteValue(): Int = if (this < 0) -this else this

private fun formatOneDecimal(v: Float): String {
    val scaled = (v * 10).roundToInt()
    return "${scaled / 10}.${scaled % 10}"
}

private fun formatDuration(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "${h}h ${m}m" else if (m > 0) "${m}m ${s}s" else "${s}s"
}

@Composable
private fun thermalLabel(status: Int?): String? = when (status) {
    null, 0 -> null
    1 -> stringResource(Res.string.monitor_thermal_light)
    2 -> stringResource(Res.string.monitor_thermal_moderate)
    else -> stringResource(Res.string.monitor_thermal_severe)
}
