package dev.cluvex.zedsecure.ui.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

object ZedGradients {
    val connected: Brush
        get() = Brush.linearGradient(
            colors = listOf(ZedViolet, ZedHotPink, ZedLime),
            start = Offset(0f, 0f),
            end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
        )

    private val sessionPalettes: List<List<Color>> = listOf(
        listOf(ZedViolet, ZedHotPink, ZedLime),
        listOf(ZedDeepViolet, ZedViolet, ZedCyan),
        listOf(ZedHotPink, ZedViolet, ZedCyan),
        listOf(ZedViolet, ZedCyan, ZedLime),
        listOf(ZedDeepViolet, ZedHotPink, ZedViolet),
    )

    fun forSession(sessionId: Int): Brush = Brush.linearGradient(
        colors = paletteFor(sessionId),
        start = Offset(0f, 0f),
        end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
    )

    private fun paletteFor(sessionId: Int): List<Color> =
        sessionPalettes[((sessionId % sessionPalettes.size) + sessionPalettes.size) % sessionPalettes.size]

    fun connectedStops(sessionId: Int): List<Color> =
        listOf(ZedDeepViolet) + paletteFor(sessionId)

    val connectingStops: List<Color> = listOf(ZedDeepViolet, ZedViolet, ZedHotPink)

    val idle: Brush
        get() = Brush.linearGradient(
            colors = listOf(ZedDeepViolet, ZedViolet, ZedHotPink.copy(alpha = 0.7f)),
            start = Offset(0f, 0f),
            end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
        )

    val connecting: Brush
        get() = Brush.linearGradient(
            colors = listOf(ZedViolet, ZedCyan, ZedLime),
            start = Offset(0f, 0f),
            end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
        )
}
