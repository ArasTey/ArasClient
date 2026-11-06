@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.cluvex.zedsecure.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.cluvex.zedsecure.shared.resources.Res
import dev.cluvex.zedsecure.shared.resources.*
import dev.cluvex.zedsecure.ui.theme.argbLongToColor
import dev.cluvex.zedsecure.ui.theme.hsvColor
import dev.cluvex.zedsecure.ui.theme.parseHexColor
import dev.cluvex.zedsecure.ui.theme.readableOn
import dev.cluvex.zedsecure.ui.theme.toArgbLong
import dev.cluvex.zedsecure.ui.theme.toHex
import dev.cluvex.zedsecure.ui.theme.toHsv
import org.jetbrains.compose.resources.stringResource

@Composable
fun ColorPickerDialog(
    title: String,
    initial: Color,
    preview: @Composable (Color) -> Unit,
    onPick: (Color) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val initHsv = remember { initial.toHsv() }
    var hue by remember { mutableFloatStateOf(initHsv.h) }
    var sat by remember { mutableFloatStateOf(initHsv.s) }
    var value by remember { mutableFloatStateOf(initHsv.v) }
    var hexText by remember { mutableStateOf(initial.toHex()) }
    val color = hsvColor(hue, sat, value)

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onPick(color) }) { Text(stringResource(Res.string.action_save)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onReset) { Text(stringResource(Res.string.personalize_reset)) }
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
            }
        },
        title = { Text(title) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                preview(color)
                Spacer(Modifier.height(16.dp))

                SaturationValueSquare(
                    hue = hue,
                    sat = sat,
                    value = value,
                    onChange = { s, v -> sat = s; value = v; hexText = hsvColor(hue, s, v).toHex() },
                    modifier = Modifier.fillMaxWidth().aspectRatio(1.6f),
                )
                Spacer(Modifier.height(14.dp))

                HueSlider(
                    hue = hue,
                    onChange = { h -> hue = h; hexText = hsvColor(h, sat, value).toHex() },
                    modifier = Modifier.fillMaxWidth().height(26.dp),
                )
                Spacer(Modifier.height(14.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(color)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                    )
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(
                        value = hexText,
                        onValueChange = { raw ->
                            hexText = raw
                            parseHexColor(raw)?.let { c ->
                                val h = c.toHsv()
                                hue = h.h; sat = h.s; value = h.v
                            }
                        },
                        singleLine = true,
                        label = { Text(stringResource(Res.string.personalize_hex)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
    )
}

@Composable
fun PersonalizeColorRow(
    title: String,
    subtitle: String,
    stored: Long?,
    default: Color,
    preview: @Composable (Color) -> Unit,
    onChange: (Long?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val current = stored?.argbLongToColor() ?: default
    SettingsRow(
        title = title,
        description = if (stored == null) stringResource(Res.string.personalize_default) else current.toHex(),
        trailing = {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(current)
                    .border(
                        width = if (stored == null) 1.dp else 2.dp,
                        color = if (stored == null) MaterialTheme.colorScheme.outlineVariant
                        else MaterialTheme.colorScheme.onSurface,
                        shape = CircleShape,
                    ),
            )
        },
        onClick = { open = true },
    )
    if (open) {
        ColorPickerDialog(
            title = title,
            initial = current,
            preview = preview,
            onPick = { open = false; onChange(it.toArgbLong()) },
            onReset = { open = false; onChange(null) },
            onDismiss = { open = false },
        )
    }
}

@Composable
fun TrafficTilePreview(
    icon: DrawableResource,
    label: String,
    accent: Color,
    container: Color? = null,
) {
    val surface = container ?: MaterialTheme.colorScheme.surfaceContainerHigh
    val onSurface = container?.readableOn() ?: MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        color = surface,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(26.dp).clip(CircleShape).background(accent.copy(alpha = 0.20f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(icon), null, tint = accent, modifier = Modifier.size(15.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text(label.uppercase(), style = MaterialTheme.typography.labelMedium, color = onSurface)
            Spacer(Modifier.weight(1f))
            Text("12.4", style = MaterialTheme.typography.titleMedium, color = accent)
            Spacer(Modifier.width(4.dp))
            Text("MB/s", style = MaterialTheme.typography.labelSmall, color = onSurface)
        }
    }
}

@Composable
fun ServerCardPreview(container: Color, active: Boolean) {
    val onContainer = if (container.toHsv().v > 0.6f) Color(0xFF10131A) else Color.White
    Surface(
        color = container,
        contentColor = onContainer,
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (active) stringResource(Res.string.config_active) else "Server",
                    style = MaterialTheme.typography.titleMedium,
                    color = onContainer,
                )
                Text(
                    "VLESS  •  1.2.3.4:443",
                    style = MaterialTheme.typography.bodySmall,
                    color = onContainer.copy(alpha = 0.72f),
                )
            }
            Text("42 ms", style = MaterialTheme.typography.labelSmall, color = onContainer.copy(alpha = 0.72f))
        }
    }
}

@Composable
fun TapHintPreview(hint: Color) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                stringResource(Res.string.home_select_config),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                stringResource(Res.string.home_tap_to_change),
                style = MaterialTheme.typography.bodySmall,
                color = hint,
            )
        }
    }
}

@Composable
fun ConnectButtonPreview(container: Color) {
    val onContainer = if (container.toHsv().v > 0.6f) Color(0xFF10131A) else Color.White
    Surface(
        color = container,
        contentColor = onContainer,
        shape = RoundedCornerShape(26.dp),
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.action_connect), style = MaterialTheme.typography.titleMedium, color = onContainer)
        }
    }
}

@Composable
private fun SaturationValueSquare(
    hue: Float,
    sat: Float,
    value: Float,
    onChange: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var w by remember { mutableFloatStateOf(0f) }
    var h by remember { mutableFloatStateOf(0f) }
    Box(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .onSizeChanged { w = it.width.toFloat(); h = it.height.toFloat() }
            .pointerInput(Unit) {
                detectTapGestures { pos ->
                    if (w > 0f && h > 0f) {
                        onChange((pos.x / w).coerceIn(0f, 1f), 1f - (pos.y / h).coerceIn(0f, 1f))
                    }
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    if (w > 0f && h > 0f) {
                        onChange(
                            (change.position.x / w).coerceIn(0f, 1f),
                            1f - (change.position.y / h).coerceIn(0f, 1f),
                        )
                    }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cw = size.width
            val ch = size.height
            drawRect(brush = Brush.horizontalGradient(listOf(Color.White, hsvColor(hue, 1f, 1f))))
            drawRect(brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
            val cx = sat * cw
            val cy = (1f - value) * ch
            drawCircle(Color.White, radius = 11f, center = Offset(cx, cy), style = Stroke(width = 4f))
            drawCircle(
                Color.Black.copy(alpha = 0.6f),
                radius = 13f,
                center = Offset(cx, cy),
                style = Stroke(width = 1.5f),
            )
        }
    }
}

@Composable
private fun HueSlider(
    hue: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var width by remember { mutableFloatStateOf(0f) }
    val rainbow = remember { (0..360 step 60).map { hsvColor(it.toFloat(), 1f, 1f) } }
    Box(
        modifier
            .clip(CircleShape)
            .onSizeChanged { width = it.width.toFloat() }
            .pointerInput(Unit) {
                detectTapGestures { if (width > 0f) onChange((it.x / width).coerceIn(0f, 1f) * 360f) }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    if (width > 0f) onChange((change.position.x / width).coerceIn(0f, 1f) * 360f)
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(brush = Brush.horizontalGradient(rainbow))
            val x = ((hue / 360f) * size.width).coerceIn(6f, size.width - 6f)
            drawCircle(
                Color.White,
                radius = size.height / 2f - 2f,
                center = Offset(x, size.height / 2f),
                style = Stroke(width = 4f),
            )
        }
    }
}
