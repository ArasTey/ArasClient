package com.aras.client.ui.main

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aras.client.dto.GroupMapItem
import com.aras.client.dto.entities.ServersCache
import com.aras.client.ui.compose.LocalGlassPalette
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

private val RailShape = RoundedCornerShape(999.dp)
private val TabBarPadding = 3.dp
private val TabSpacing = 2.dp
private val TabHeight = 34.dp

/**
 * Segmented glass rail: one frosted capsule holding pills, the selected one lit blue.
 *
 * Like the upstream PattNG tab bar, when every pill fits an equal share of the rail they share
 * it (so the capsule fills edge-to-edge with no trailing gap); once they no longer fit they keep
 * their natural width and the rail scrolls, keeping the selected pill centered. The highlight is
 * a single thumb that glides to the selected pill's measured bounds.
 */
@Composable
fun GroupTabBar(
    groups: List<GroupMapItem>,
    selectedTabIndex: Int,
    mainViewModel: MainViewModel,
    onTabClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = LocalGlassPalette.current
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    // Captured before the rail is forced to LTR below, so the pills themselves can
    // still lay out in the app's real direction.
    val appLayoutDirection = LocalLayoutDirection.current

    // index -> (left, width) in px, measured relative to the rail's own
    // coordinate space. The highlight follows the selected entry, so pills can
    // size to content or share the width without breaking the glide.
    val itemBounds = remember { mutableStateMapOf<Int, Pair<Float, Float>>() }

    // The selected pill's on-screen left/width measured against the rail itself, so
    // horizontal scroll and RTL/LTR are already resolved by the layout system. The
    // thumb is positioned from this directly — the old "absolute-left minus scroll,
    // then negate for RTL" math mirrored the highlight onto the wrong tab in Persian.
    var railLeftPx by remember { mutableStateOf(0f) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
    ) {
        val railWidthPx = with(density) { maxWidth.toPx() }
        val barPaddingPx = with(density) { TabBarPadding.roundToPx() }
        val availablePx = constraints.maxWidth - 2 * barPaddingPx

        // Keep the selected pill centered as the selection changes. The pill's on-screen
        // centre is measured against the rail, so this is direction-agnostic; only the
        // scroll *response* flips: raising scrollState.value moves content left in LTR but
        // right in RTL, so the delta must be negated for LTR to push the pill toward centre.
        LaunchedEffect(selectedTabIndex, groups.size, itemBounds.keys.size) {
            val b = itemBounds[selectedTabIndex] ?: return@LaunchedEffect
            val pillCenter = b.first + b.second / 2f
            val shift = railWidthPx / 2f - pillCenter
            val delta = if (appLayoutDirection == LayoutDirection.Rtl) shift else -shift
            val target = (scrollState.value + delta).roundToInt()
                .coerceIn(0, scrollState.maxValue.coerceAtLeast(0))
            scrollState.animateScrollTo(target)
        }

        // Force the rail to LTR so the thumb's TopStart anchor is always the rail's
        // left edge, regardless of the app direction; the pill Row is switched back to
        // the app direction so tabs still read right-to-left in Persian. The thumb is
        // then placed by a plain translationX equal to the selected pill's on-screen
        // left measured against the rail — direction-correct because it is the real
        // screen position, not an absolute-left minus scroll guess.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .clip(RailShape)
                    .background(palette.railFill)
                    .onGloballyPositioned { railLeftPx = it.boundsInWindow().left }
            ) {
                val padPx = with(density) { TabBarPadding.toPx() }
                val selected = itemBounds[selectedTabIndex]
                val glide = tween<Float>(
                    durationMillis = 260,
                    easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
                )
                val thumbLeft by animateFloatAsState(
                    targetValue = selected?.first ?: 0f,
                    animationSpec = glide,
                    label = "railHighlightLeft"
                )
                val thumbWidth by animateFloatAsState(
                    targetValue = selected?.second ?: 0f,
                    animationSpec = glide,
                    label = "railHighlightWidth"
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .graphicsLayer {
                            translationX = thumbLeft
                            translationY = padPx
                        }
                        .width(with(density) { thumbWidth.toDp() })
                        .height(TabHeight)
                        .clip(RailShape)
                        .background(palette.railSelectedTop)
                )
                CompositionLocalProvider(LocalLayoutDirection provides appLayoutDirection) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(TabBarPadding)
                            .horizontalScroll(scrollState)
                    ) {
                        EqualOrNaturalTabsLayout(availablePx = availablePx) {
                            groups.forEachIndexed { index, group ->
                                GroupTabItem(
                                    group = group,
                                    selected = index == selectedTabIndex,
                                    railLeftPx = railLeftPx,
                                    serverFlowProvider = { mainViewModel.serversForGroup(group.id) },
                                    onBounds = { left, width -> itemBounds[index] = left to width },
                                    onClick = { onTabClick(index) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Lays the pills out in a row. If every pill fits an equal share of [availablePx] they all get
 * that share (so the rail fills, with no trailing gap); otherwise, if they still fit together,
 * the spare space is spread evenly; otherwise they keep their natural width (the rail scrolls).
 */
@Composable
private fun EqualOrNaturalTabsLayout(
    availablePx: Int,
    content: @Composable () -> Unit
) {
    Layout(content = content) { measurables, _ ->
        if (measurables.isEmpty()) return@Layout layout(0, 0) {}

        val spacing = TabSpacing.roundToPx()
        val tabHeight = TabHeight.roundToPx()
        val count = measurables.size
        val gaps = spacing * (count - 1)
        val natural = measurables.map { it.maxIntrinsicWidth(tabHeight) }
        val equalShare = (availablePx - gaps) / count

        val widths = when {
            availablePx <= 0 -> natural
            natural.all { it <= equalShare } -> List(count) { equalShare }
            natural.sum() + gaps <= availablePx -> {
                val extra = (availablePx - gaps - natural.sum()) / count
                natural.map { it + extra }
            }
            else -> natural
        }

        val placeables = measurables.mapIndexed { i, measurable ->
            measurable.measure(Constraints.fixed(widths[i], tabHeight))
        }
        layout(widths.sum() + gaps, tabHeight) {
            var x = 0
            placeables.forEach { placeable ->
                placeable.placeRelative(x, 0)
                x += placeable.width + spacing
            }
        }
    }
}

@Composable
private fun GroupTabItem(
    group: GroupMapItem,
    selected: Boolean,
    railLeftPx: Float,
    serverFlowProvider: () -> StateFlow<List<ServersCache>>,
    onBounds: (left: Float, width: Float) -> Unit,
    onClick: () -> Unit
) {
    val palette = LocalGlassPalette.current
    val serverFlow = remember(group.id) { serverFlowProvider() }
    val servers by serverFlow.collectAsStateWithLifecycle()
    val labelColor by animateColorAsState(
        targetValue = if (selected) palette.railSelectedText else palette.railText,
        animationSpec = tween(durationMillis = 160, easing = FastOutSlowInEasing),
        label = "tabLabelColor"
    )
    val countColor = labelColor.copy(alpha = 0.55f)

    Box(
        modifier = Modifier
            .height(TabHeight)
            // The parent layout fixes the width (equal share or natural); the pill
            // only reports where it landed so the highlight can follow it.
            .onGloballyPositioned { coords ->
                val r = coords.boundsInWindow()
                onBounds(r.left - railLeftPx, r.width)
            }
            .clickable(
                // No ripple/hover state layer: the sliding highlight is the
                // only feedback, and the pressed box outline looked wrong here.
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        // Name and count are separate texts: folded into one string the tail —
        // the count — was the first thing ellipsis threw away on a long name.
        // The name takes the space left after the count, so it ellipsizes only
        // when the pill is too narrow, never pushing the count out.
        Row(
            modifier = Modifier.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = group.remarks,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelLarge,
                fontSize = 10.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                letterSpacing = 0.sp,
                color = labelColor,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (group.id.isNotEmpty()) {
                Text(
                    text = "  (${servers.size})",
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                    style = MaterialTheme.typography.labelLarge,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.sp,
                    color = countColor
                )
            }
        }
    }
}
