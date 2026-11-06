package dev.cluvex.zedsecure.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.roundToInt

data class Hsv(val h: Float, val s: Float, val v: Float)

fun Color.toHsv(): Hsv {
    val r = red; val g = green; val b = blue
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val d = max - min
    val h = when {
        d == 0f -> 0f
        max == r -> 60f * (((g - b) / d) % 6f)
        max == g -> 60f * (((b - r) / d) + 2f)
        else -> 60f * (((r - g) / d) + 4f)
    }.let { if (it < 0f) it + 360f else it }
    val s = if (max == 0f) 0f else d / max
    return Hsv(h, s, max)
}

fun hsvColor(h: Float, s: Float, v: Float): Color =
    Color.hsv(h.coerceIn(0f, 360f), s.coerceIn(0f, 1f), v.coerceIn(0f, 1f))

fun Color.toHex(): String {
    fun c(f: Float) = (f * 255f).roundToInt().coerceIn(0, 255).toString(16).padStart(2, '0')
    return "#${c(red)}${c(green)}${c(blue)}".uppercase()
}

fun parseHexColor(text: String): Color? {
    val h = text.trim().removePrefix("#")
    val full = when (h.length) {
        6 -> h
        3 -> h.map { "$it$it" }.joinToString("")
        else -> return null
    }
    val i = full.toLongOrNull(16) ?: return null
    val r = ((i shr 16) and 0xFF) / 255f
    val g = ((i shr 8) and 0xFF) / 255f
    val b = (i and 0xFF) / 255f
    return Color(r, g, b)
}

fun Color.toArgbLong(): Long {
    fun c(f: Float) = (f * 255f).roundToInt().coerceIn(0, 255).toLong()
    return (0xFFL shl 24) or (c(red) shl 16) or (c(green) shl 8) or c(blue)
}

fun Long.argbLongToColor(): Color {
    val r = ((this shr 16) and 0xFF) / 255f
    val g = ((this shr 8) and 0xFF) / 255f
    val b = (this and 0xFF) / 255f
    return Color(r, g, b)
}
