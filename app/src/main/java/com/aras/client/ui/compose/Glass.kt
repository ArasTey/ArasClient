package com.aras.client.ui.compose

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aras.client.R

/**
 * Tokens for the liquid-glass skin. Compose cannot sample what sits behind a
 * surface, so the look is rebuilt with a translucent fill and a hairline rim
 * over the ambient wallpaper.
 */
@Immutable
data class GlassPalette(
    val isDark: Boolean,
    val glassTop: Color,
    val glassBorder: Color,
    val railFill: Color,
    val railBorder: Color,
    val railSelectedTop: Color,
    val railSelectedText: Color,
    val railText: Color,
    val selectedTop: Color,
    val selectedBorder: Color,
    val chipFill: Color,
    val chipBorder: Color,
    val chipText: Color,
    val hairline: Color,
    val faint: Color,
    val accent: Color,
    val periwinkle: Color,
    val amber: Color,
    val lit: Color,
    val danger: Color,
    val pingGood: Color,
    val pingMid: Color,
    val pingBad: Color,
    val scrim: Color,
    val sheet: Color,
)

val DarkGlassPalette = GlassPalette(
    isDark = true,
    glassTop = Color(0x10FFFFFF),
    glassBorder = Color(0x25FFFFFF),
    railFill = Color(0x14CFE9F5),
    railBorder = Color(0x2E8FD2E8),
    railSelectedTop = Color(0xFF2F86D6),
    railSelectedText = Color(0xFFFFFFFF),
    railText = Color(0xFFA9BDC9),
    selectedTop = Color(0x333A96CD),
    selectedBorder = Color(0x7554C3F2),
    chipFill = Color(0x295A96EB),
    chipBorder = Color(0x1FFFFFFF),
    chipText = Color(0xFF9DBFF3),
    hairline = Color(0x13FFFFFF),
    faint = Color(0xFF718795),
    accent = Color(0xFF54C3F2),
    periwinkle = Color(0xFF8FA3F2),
    amber = Color(0xFFF3C969),
    lit = Color(0xFF30E08A),
    danger = Color(0xFFEF8F86),
    pingGood = Color(0xFF4ADE9A),
    pingMid = Color(0xFFF3C969),
    pingBad = Color(0xFFF08A82),
    scrim = Color(0xFF05080B),
    sheet = Color(0xF20E141A),
)

val LightGlassPalette = GlassPalette(
    isDark = false,
    glassTop = Color(0xD1FFFFFF),
    glassBorder = Color(0x3DB6A48D),
    railFill = Color(0x66E8F4FC),
    railBorder = Color(0x2E7FB6CE),
    railSelectedTop = Color(0xFF0284C7),
    railSelectedText = Color(0xFFFFFFFF),
    railText = Color(0xFF546977),
    selectedTop = Color(0xA6E4F6FF),
    selectedBorder = Color(0x8C2E9DD6),
    chipFill = Color(0x1F0284C7),
    chipBorder = Color(0x140284C7),
    chipText = Color(0xFF0B6E9E),
    hairline = Color(0x14000000),
    faint = Color(0xFF7B8B96),
    accent = Color(0xFF0284C7),
    periwinkle = Color(0xFF4A86E8),
    amber = Color(0xFFB07E18),
    lit = Color(0xFF169B5E),
    danger = Color(0xFFC05B50),
    pingGood = Color(0xFF138A52),
    pingMid = Color(0xFF9A6B0C),
    pingBad = Color(0xFFB03A30),
    scrim = Color(0xFFF3E9D8),
    sheet = Color(0xFDFBF6EE),
)

val LocalGlassPalette = compositionLocalOf { DarkGlassPalette }

fun Modifier.glassPanel(
    palette: GlassPalette,
    shape: Shape = RoundedCornerShape(16.dp),
    fill: Color = palette.glassTop,
    borderColor: Color = palette.glassBorder,
    borderWidth: Dp = 0.9.dp,
): Modifier = this
    .clip(shape)
    .background(fill, shape)
    .border(borderWidth, borderColor, shape)

/** Ambient backdrop: drawn in code — soft scene light, vignette and film grain. */
@Composable
fun GlassBackground(modifier: Modifier = Modifier) {
    val palette = LocalGlassPalette.current
    val context = LocalContext.current
    val grain = remember {
        BitmapFactory.decodeResource(context.resources, R.drawable.grain)
    }
    val grainDark = remember {
        BitmapFactory.decodeResource(context.resources, R.drawable.grain_dark)
    }
    Box(
        modifier
            .fillMaxSize()
            .drawBehind {
                if (palette.isDark) {
                    drawRect(
                        Brush.verticalGradient(
                            0f to Color(0xFF101619),
                            0.5f to Color(0xFF090D10),
                            1f to Color(0xFF050708)
                        )
                    )
                    // Cool daylight falling in from the top-left corner.
                    drawRect(
                        Brush.radialGradient(
                            listOf(Color(0x33BFE6F5), Color(0x0FBFE6F5), Color.Transparent),
                            center = Offset(size.width * 0.08f, -size.height * 0.05f),
                            radius = size.width * 1.0f
                        )
                    )
                    // A soft blue bounce across the upper right keeps the wall
                    // from reading as a flat black fill.
                    drawRect(
                        Brush.radialGradient(
                            listOf(Color(0x14CFE9F5), Color.Transparent),
                            center = Offset(size.width * 0.78f, size.height * 0.3f),
                            radius = size.width * 0.9f
                        )
                    )
                    drawRect(
                        Brush.radialGradient(
                            listOf(Color(0x1AFFFFFF), Color.Transparent),
                            center = Offset(size.width * 0.1f, size.height * 0.04f),
                            radius = size.width * 0.5f
                        )
                    )
                    // Warm floor bounce in the opposite corner.
                    drawRect(
                        Brush.radialGradient(
                            listOf(Color(0x33B07A3E), Color(0x0FB07A3E), Color.Transparent),
                            center = Offset(-size.width * 0.08f, size.height * 0.97f),
                            radius = size.width * 0.92f
                        )
                    )
                    drawRect(
                        Brush.radialGradient(
                            listOf(Color.Transparent, Color(0x99000000)),
                            center = Offset(size.width * 0.5f, size.height * 0.4f),
                            radius = size.width * 1.15f
                        )
                    )
                    drawGrain(grainDark, PorterDuff.Mode.SCREEN)
                } else {
                    drawRect(
                        Brush.verticalGradient(
                            0f to Color(0xFFFEFAF4),
                            0.45f to Color(0xFFFAF2E4),
                            1f to Color(0xFFF2E6D0)
                        )
                    )
                    // Daylight through a window: a soft diagonal band of white
                    // falling across the upper third, like light on a plaster wall.
                    drawRect(
                        Brush.linearGradient(
                            listOf(Color.Transparent, Color(0x33FFFFFF), Color(0x0FFFFFFF), Color.Transparent),
                            start = Offset(size.width * 0.08f, -size.height * 0.04f),
                            end = Offset(size.width * 0.72f, size.height * 0.5f)
                        )
                    )
                    // A whisper of cool sky in the top-right corner.
                    drawRect(
                        Brush.radialGradient(
                            listOf(Color(0x3DC2DCE9), Color(0x14C2DCE9), Color.Transparent),
                            center = Offset(size.width * 0.98f, -size.height * 0.06f),
                            radius = size.width * 0.85f
                        )
                    )
                    drawRect(
                        Brush.radialGradient(
                            listOf(Color(0x40FFFFFF), Color.Transparent),
                            center = Offset(size.width * 0.9f, size.height * 0.035f),
                            radius = size.width * 0.5f
                        )
                    )
                    // Warm lamp glow rising from the lower-left corner.
                    drawRect(
                        Brush.radialGradient(
                            listOf(Color(0x47F2C98C), Color(0x17F2C98C), Color.Transparent),
                            center = Offset(-size.width * 0.1f, size.height * 0.98f),
                            radius = size.width * 0.95f
                        )
                    )
                    drawRect(
                        Brush.radialGradient(
                            listOf(Color.Transparent, Color(0x146B5233)),
                            center = Offset(size.width * 0.5f, size.height * 0.62f),
                            radius = size.width * 1.5f
                        )
                    )
                    drawGrain(grain, PorterDuff.Mode.OVERLAY)
                }
            }
    )
}

private fun DrawScope.drawGrain(grain: Bitmap?, mode: PorterDuff.Mode) {
    if (grain == null) return
    drawIntoCanvas { canvas ->
        val paint = Paint().apply {
            shader = BitmapShader(grain, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            xfermode = PorterDuffXfermode(mode)
        }
        canvas.nativeCanvas.drawRect(0f, 0f, size.width, size.height, paint)
    }
}
