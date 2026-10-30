package com.aras.client.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.aras.client.ui.compose.LocalGlassPalette
import com.aras.client.ui.compose.glassPanel
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The top-bar glyph a menu hangs from, measured in window coordinates.
 */
@Immutable
data class DropdownAnchor(
    val top: Dp,
    val width: Dp,
    val height: Dp,
    val centerFromEnd: Dp,
)

private val PanelWidth = 292.dp
private val PanelEndInset = 8.dp

/**
 * Dropdown unrolled just below the top bar. The panel's right edge sits at the
 * anchor's right edge and its top edge starts level with the anchor, so the tab
 * on the panel is exactly the anchor's size and completely covers the button
 * that opened it. Both + and ⋮ therefore read as one control opening up.
 */
@Composable
fun AnchoredDropdown(
    expanded: Boolean,
    anchor: DropdownAnchor?,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!expanded || anchor == null) return
    val palette = LocalGlassPalette.current
    val contentDirection = LocalLayoutDirection.current
    val density = LocalDensity.current

    val shape = remember(anchor) {
        BubbleShape(
            tailCenterFromEnd = anchor.centerFromEnd - PanelEndInset,
            tailWidth = anchor.width,
            tailHeight = anchor.height,
        )
    }
    val positionProvider = remember(anchor, density) {
        DropdownPositionProvider(
            topPx = with(density) { anchor.top.toPx() }.roundToInt(),
            endInsetPx = with(density) { PanelEndInset.toPx() }.roundToInt(),
        )
    }

    // The top bar is pinned LTR, so its buttons sit on the physical right even
    // in an RTL UI; the popup has to anchor there too. Rows get their own
    // direction back below.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Popup(
            popupPositionProvider = positionProvider,
            onDismissRequest = onDismiss,
            properties = PopupProperties(focusable = true)
        ) {
            Box(modifier = Modifier.width(PanelWidth)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 560.dp + anchor.height)
                        .glassPanel(
                            palette = palette,
                            shape = shape,
                            fill = if (palette.isDark) Color(0xF5161D24) else Color(0xF7FFFFFF),
                            borderColor = if (palette.isDark) Color(0x52FFFFFF) else Color(0xE6FFFFFF),
                        )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = anchor.height + 7.dp, bottom = 7.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        CompositionLocalProvider(LocalLayoutDirection provides contentDirection) {
                            content()
                        }
                    }
                }
            }
        }
    }
}

private class DropdownPositionProvider(
    private val topPx: Int,
    private val endInsetPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset(
        x = (windowSize.width - endInsetPx - popupContentSize.width).coerceAtLeast(0),
        y = topPx,
    )
}

/**
 * Rounded glass panel whose top edge rises into a tab that matches the anchor
 * button, so the tab replaces the glyph while the menu is open. When the tab
 * reaches the side edge it merges into that edge instead of having a corner cut
 * across it.
 */
private class BubbleShape(
    private val tailCenterFromEnd: Dp,
    private val tailWidth: Dp,
    private val tailHeight: Dp,
    private val fillet: Dp = 5.dp,
    private val cornerRadius: Dp = 20.dp,
    private val tailCornerRadius: Dp = 8.dp,
) : Shape {

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val path = Path()
        if (size.width <= 0f || size.height <= 0f) return Outline.Generic(path)

        val w = size.width
        val h = size.height
        val tailH = with(density) { tailHeight.toPx() }.coerceAtMost(h * 0.35f)
        val r = with(density) { cornerRadius.toPx() }.coerceAtMost(min(w, h) * 0.5f)
        val f = with(density) { fillet.toPx() }
        // The corner radius has to leave a straight neck below it, otherwise it
        // folds back on the shoulder and the tab turns into a dome.
        val tr = with(density) { tailCornerRadius.toPx() }.coerceIn(0f, (tailH - f).coerceAtLeast(0f))
        val neckHalf = with(density) { (tailWidth / 2).toPx() }
        val centerX = w - with(density) { tailCenterFromEnd.toPx() }
        // The tab reaches the side edge when its shoulder would collide with the
        // rounded corner; in that case the tab side becomes the panel side.
        val flush = centerX + neckHalf + f >= w - r
        val neckRight = if (flush) w else centerX + neckHalf
        val neckLeft = neckRight - neckHalf * 2f

        val k = 0.5523f
        val rk = r * k
        val trk = tr * k
        val fk = f * k
        val top = tailH

        path.moveTo(0f, top + r)
        path.lineTo(0f, h - r)
        path.cubicTo(0f, h - r + rk, r - rk, h, r, h)
        path.lineTo(w - r, h)
        path.cubicTo(w - r + rk, h, w, h - r + rk, w, h - r)

        if (flush) {
            path.lineTo(w, tr)
            path.cubicTo(w, tr - trk, w - tr + trk, 0f, w - tr, 0f)
        } else {
            path.lineTo(w, top + r)
            path.cubicTo(w, top + r - rk, w - r + rk, top, w - r, top)
            path.lineTo(neckRight + f, top)
            path.cubicTo(
                neckRight + f - fk, top,
                neckRight, top - f + fk,
                neckRight, top - f
            )
            path.lineTo(neckRight, tr)
            path.cubicTo(neckRight, tr - trk, neckRight - tr + trk, 0f, neckRight - tr, 0f)
        }

        path.lineTo(neckLeft + tr, 0f)
        path.cubicTo(neckLeft + tr - trk, 0f, neckLeft, tr - trk, neckLeft, tr)
        path.lineTo(neckLeft, top - f)
        path.cubicTo(neckLeft, top - f + fk, neckLeft - f + fk, top, neckLeft - f, top)
        path.lineTo(r, top)
        path.cubicTo(r - rk, top, 0f, top + r - rk, 0f, top + r)

        path.close()
        return Outline.Generic(path)
    }
}
