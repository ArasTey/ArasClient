package dev.cluvex.zedsecure.ui.map

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cluvex.zedsecure.data.map.WorldMap
import dev.cluvex.zedsecure.ui.components.FlagBadge
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class MapPoint(val x: Float, val y: Float, val label: String, val code: String = "")

data class MapLabel(

    val x: Float,
    val y: Float,
    val text: String,

    val span: Float,
)

private data class View(val scale: Float, val offset: Offset)

private val MARKER: Dp = 26.dp

private const val MIN_CAPTION_PX = 10f

@Composable
fun WorldMapCanvas(
    countries: List<WorldMap.Country>,
    origin: MapPoint?,
    exit: MapPoint?,

    accent: Color,

    originColor: Color,

    landColor: Color,
    reduceMotion: Boolean,

    labels: List<MapLabel> = emptyList(),
    modifier: Modifier = Modifier,
) {
    val land = remember(countries) {
        Path().apply { countries.forEach { c -> c.rings.forEach { appendRing(it) } } }
    }
    val originPath = remember(countries, origin) { origin?.let { pathAround(countries, it) } }
    val exitPath = remember(countries, exit) { exit?.let { pathAround(countries, it) } }

    val linked = origin != null && exit != null
    val reveal by animateFloatAsState(
        targetValue = if (linked) 1f else 0f,
        animationSpec = if (reduceMotion) tween(0) else tween(1100, easing = LinearEasing),
        label = "route-reveal",
    )
    val pulse = if (reduceMotion) 0f else {
        val t = rememberInfiniteTransition(label = "map-pulse")
        t.animateFloat(
            0f, 1f,
            infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart),
            label = "travel",
        ).value
    }
    val breathe = if (reduceMotion) 0f else {
        val t = rememberInfiniteTransition(label = "map-breathe")
        t.animateFloat(
            0f, 1f,
            infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Reverse),
            label = "halo",
        ).value
    }

    var canvas by remember { mutableStateOf(Size.Zero) }
    var view by remember { mutableStateOf<View?>(null) }

    LaunchedEffect(canvas, origin, exit) {
        if (canvas.width > 0f && canvas.height > 0f) {
            view = framing(canvas, listOfNotNull(origin, exit))
        }
    }

    val density = LocalDensity.current
    val markerPx = with(density) { MARKER.toPx() }

    val measurer = rememberTextMeasurer()
    val captionStyle = MaterialTheme.typography.labelSmall.copy(
        fontSize = 14.sp,
        letterSpacing = TextUnit(0.18f, TextUnitType.Em),
    )
    val captions = remember(labels, captionStyle, measurer) {
        labels.map { it to measurer.measure(it.text, captionStyle) }
    }

    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { canvas = Size(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(origin, exit) {
                detectTapGestures(
                    onDoubleTap = { view = framing(canvas, listOfNotNull(origin, exit)) },
                )
            }
            .pointerInput(origin, exit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val v = view ?: return@detectTransformGestures
                    val floor = wholeWorldScale(canvas)
                    val next = (v.scale * zoom).coerceIn(floor, floor * 24f)

                    val applied = if (v.scale == 0f) 1f else next / v.scale

                    val anchored = centroid - (centroid - v.offset) * applied + pan
                    view = View(next, clampOffset(anchored, next, canvas))
                }
            },
    ) {
        val v = view
        Canvas(Modifier.fillMaxSize()) {
            drawRect(
                Brush.verticalGradient(
                    listOf(
                        accent.copy(alpha = 0.05f),
                        Color.Transparent,
                        originColor.copy(alpha = 0.04f),
                    ),
                ),
            )
            if (v == null) return@Canvas

            val px = { dp: Float -> dp / v.scale }

            withTransform({
                translate(v.offset.x, v.offset.y)
                scale(v.scale, v.scale, pivot = Offset.Zero)
            }) {
                graticule(landColor.copy(alpha = 0.06f), px(1f))

                drawPath(land, landColor.copy(alpha = 0.10f))
                drawPath(land, landColor.copy(alpha = 0.26f), style = Stroke(px(1.1f)))

                originPath?.let {
                    drawPath(it, originColor.copy(alpha = 0.34f))
                    drawPath(it, originColor.copy(alpha = 0.85f), style = Stroke(px(1.7f)))
                }
                exitPath?.let {
                    drawPath(it, accent.copy(alpha = 0.42f))
                    drawPath(it, accent, style = Stroke(px(2f)))
                }

                if (origin != null && exit != null && reveal > 0.01f) {
                    route(origin, exit, reveal, pulse, accent, px)
                }

                captions.forEach { (label, layout) -> caption(label, layout, v.scale, landColor) }

                origin?.let { halo(it, originColor, breathe, px) }
                exit?.let { halo(it, accent, breathe, px) }
            }
        }

        if (v != null) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Box(Modifier.fillMaxSize()) {
                    origin?.let { FlagMarker(it, v, canvas, originColor, markerPx, MARKER) }
                    exit?.let { FlagMarker(it, v, canvas, accent, markerPx, MARKER) }
                }
            }
        }
    }
}

@Composable
private fun FlagMarker(
    point: MapPoint,
    view: View,
    canvas: Size,
    ring: Color,
    markerPx: Float,
    size: Dp,
) {
    val x = view.offset.x + point.x * view.scale
    val y = view.offset.y + point.y * view.scale
    val half = markerPx / 2f
    val margin = markerPx
    if (x < -margin || y < -margin || x > canvas.width + margin || y > canvas.height + margin) return
    if (point.code.length != 2) return

    Box(
        Modifier
            .absoluteOffset { IntOffset((x - half).roundToInt(), (y - half).roundToInt()) }
            .size(size)
            .clip(CircleShape)
            .background(ring.copy(alpha = 0.30f))
            .border(2.dp, ring, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        FlagBadge(point.code, size = size, modifier = Modifier.clip(CircleShape))
    }
}

private fun DrawScope.caption(
    label: MapLabel,
    layout: TextLayoutResult,
    scale: Float,
    color: Color,
) {
    val w = layout.size.width.toFloat()
    val h = layout.size.height.toFloat()
    if (w <= 0f || h <= 0f) return

    val k = label.span / w
    if (h * k * scale < MIN_CAPTION_PX) return

    withTransform({
        translate(label.x, label.y)
        scale(k, k, pivot = Offset.Zero)
    }) {
        drawText(
            textLayoutResult = layout,
            color = color.copy(alpha = 0.42f),
            topLeft = Offset(-w / 2f, -h / 2f),
        )
    }
}

private fun DrawScope.graticule(color: Color, width: Float) {
    val w = WorldMap.WIDTH
    val h = WorldMap.HEIGHT
    var lon = -180f
    while (lon <= 180f) {
        val x = (lon + 180f) / 360f * w
        drawLine(color, Offset(x, 0f), Offset(x, h), strokeWidth = width)
        lon += 30f
    }
    var lat = -90f
    while (lat <= 90f) {
        val y = (90f - lat) / 180f * h
        drawLine(color, Offset(0f, y), Offset(w, y), strokeWidth = width)
        lat += 30f
    }
}

private fun DrawScope.route(
    origin: MapPoint,
    exit: MapPoint,
    reveal: Float,
    pulse: Float,
    accent: Color,
    px: (Float) -> Float,
) {
    val dx = exit.x - origin.x
    val dy = exit.y - origin.y
    val len = hypot(dx, dy)
    if (len < 1f) return
    val bow = len * 0.22f
    val control = Offset(
        (origin.x + exit.x) / 2f - dy / len * bow,
        (origin.y + exit.y) / 2f + dx / len * bow,
    )
    val full = Path().apply {
        moveTo(origin.x, origin.y)
        quadraticTo(control.x, control.y, exit.x, exit.y)
    }

    val measure = PathMeasure().apply { setPath(full, false) }
    val drawnTo = measure.length * reveal
    val shown = Path()
    measure.getSegment(0f, drawnTo, shown, true)

    drawPath(shown, accent.copy(alpha = 0.12f), style = Stroke(px(9f)))
    drawPath(shown, accent.copy(alpha = 0.45f), style = Stroke(px(3.4f)))
    drawPath(shown, accent.copy(alpha = 0.95f), style = Stroke(px(1.4f)))

    val tip = measure.getPosition(drawnTo)
    val tangent = measure.getTangent(drawnTo)
    val tl = hypot(tangent.x, tangent.y)
    if (tl > 0.0001f) {
        val ux = tangent.x / tl
        val uy = tangent.y / tl
        val head = px(15f)
        val wing = px(7.5f)
        val baseX = tip.x - ux * head
        val baseY = tip.y - uy * head
        val arrow = Path().apply {
            moveTo(tip.x, tip.y)
            lineTo(baseX - uy * wing, baseY + ux * wing)
            lineTo(baseX + uy * wing, baseY - ux * wing)
            close()
        }
        drawPath(arrow, accent.copy(alpha = 0.25f), style = Stroke(px(6f)))
        drawPath(arrow, accent)
    }

    if (reveal > 0.98f && pulse > 0f) {
        val at = measure.getPosition(measure.length * pulse)
        drawCircle(accent.copy(alpha = 0.22f), px(9f), at)
        drawCircle(accent, px(3.2f), at)
    }
}

private fun DrawScope.halo(p: MapPoint, color: Color, breathe: Float, px: (Float) -> Float) {
    val at = Offset(p.x, p.y)
    drawCircle(color.copy(alpha = 0.10f + 0.10f * breathe), px(26f + 8f * breathe), at)
    drawCircle(color.copy(alpha = 0.22f), px(16f), at)
}

private fun Path.appendRing(ring: FloatArray) {
    if (ring.size < 6) return
    moveTo(ring[0], ring[1])
    var i = 2
    while (i < ring.size) {
        lineTo(ring[i], ring[i + 1])
        i += 2
    }
    close()
}

private fun pathAround(countries: List<WorldMap.Country>, p: MapPoint): Path? {
    val c = countries.firstOrNull { abs(it.anchorX - p.x) < 0.5f && abs(it.anchorY - p.y) < 0.5f }
        ?: return null
    return Path().apply { c.rings.forEach { appendRing(it) } }
}

private fun wholeWorldScale(size: Size): Float =
    if (size.width <= 0f || size.height <= 0f) 1f
    else min(size.width / WorldMap.WIDTH, size.height / WorldMap.HEIGHT)

private fun clampOffset(offset: Offset, scale: Float, size: Size): Offset {
    if (size.width <= 0f || size.height <= 0f) return offset
    val mw = WorldMap.WIDTH * scale
    val mh = WorldMap.HEIGHT * scale
    val x = if (mw <= size.width) (size.width - mw) / 2f
    else offset.x.coerceIn(size.width - mw, 0f)
    val y = if (mh <= size.height) (size.height - mh) / 2f
    else offset.y.coerceIn(size.height - mh, 0f)
    return Offset(x, y)
}

private fun framing(size: Size, focus: List<MapPoint>): View {
    val w = WorldMap.WIDTH
    val h = WorldMap.HEIGHT
    val whole = wholeWorldScale(size)

    if (focus.isEmpty()) {
        return View(whole, Offset((size.width - w * whole) / 2f, (size.height - h * whole) / 2f))
    }

    val cx = focus.map { it.x }.average().toFloat()
    val cy = focus.map { it.y }.average().toFloat()

    val scale = if (focus.size < 2) {
        whole * 2.6f
    } else {
        val spanX = (focus.maxOf { it.x } - focus.minOf { it.x }) * 1.9f + w * 0.06f
        val spanY = (focus.maxOf { it.y } - focus.minOf { it.y }) * 2.4f + h * 0.06f
        min(size.width / max(spanX, 1f), size.height / max(spanY, 1f))
            .coerceIn(whole * 0.95f, whole * 4.5f)
    }
    val centred = Offset(size.width / 2f - cx * scale, size.height / 2f - cy * scale)
    return View(scale, clampOffset(centred, scale, size))
}
