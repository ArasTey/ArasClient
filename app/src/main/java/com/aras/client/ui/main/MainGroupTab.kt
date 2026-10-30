package com.aras.client.ui.main

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
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

/**
 * Segmented glass rail: one frosted capsule holding equal-width pills, the
 * selected one lit blue. Long group lists scroll horizontally.
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

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
    ) {
        val railWidth = maxWidth
        val itemWidth = if (groups.size <= 4) {
            (railWidth - 6.dp - 2.dp * (groups.size - 1).coerceAtLeast(0)) /
                groups.size.coerceAtLeast(1)
        } else {
            104.dp
        }

        LaunchedEffect(selectedTabIndex, groups.size, railWidth) {
            if (groups.size <= 4) return@LaunchedEffect
            val itemPx = with(density) { (itemWidth + 2.dp).toPx() }
            val halfPx = with(density) { (railWidth - itemWidth).toPx() / 2f }
            scrollState.animateScrollTo(
                (selectedTabIndex * itemPx - halfPx).roundToInt().coerceAtLeast(0)
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(RailShape)
                .background(palette.railFill)
        ) {
            // A single highlight glides between segments; the thumb is the only
            // thing that moves, like a segmented-control highlight. It rides a
            // graphics layer so travel is pure draw work (no relayout per frame).
            val layoutDirection = LocalLayoutDirection.current
            val highlightOffset by animateDpAsState(
                targetValue = 3.dp + (itemWidth + 2.dp) * selectedTabIndex.coerceAtLeast(0),
                animationSpec = tween(
                    durationMillis = 260,
                    easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
                ),
                label = "railHighlight"
            )
            Box(
                modifier = Modifier
                    .graphicsLayer {
                        val travel = highlightOffset.roundToPx() - scrollState.value
                        translationX = if (layoutDirection == LayoutDirection.Rtl) {
                            -travel.toFloat()
                        } else {
                            travel.toFloat()
                        }
                        translationY = 3.dp.toPx()
                    }
                    .width(itemWidth)
                    .height(34.dp)
                    .clip(RailShape)
                    .background(palette.railSelectedTop)
            )
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(3.dp)
                    .horizontalScroll(scrollState),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                groups.forEachIndexed { index, group ->
                    GroupTabItem(
                        group = group,
                        selected = index == selectedTabIndex,
                        itemWidth = itemWidth,
                        serverFlowProvider = { mainViewModel.serversForGroup(group.id) },
                        onClick = { onTabClick(index) }
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupTabItem(
    group: GroupMapItem,
    selected: Boolean,
    itemWidth: androidx.compose.ui.unit.Dp,
    serverFlowProvider: () -> StateFlow<List<ServersCache>>,
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
    val label = remember(group.remarks, group.id, servers.size, labelColor) {
        buildAnnotatedString {
            append(group.remarks)
            if (group.id.isNotEmpty()) {
                withStyle(
                    SpanStyle(
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.sp,
                        color = labelColor.copy(alpha = 0.55f)
                    )
                ) {
                    append("  (")
                    append(servers.size.toString())
                    append(")")
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .width(itemWidth)
            .height(34.dp)
            .clickable(
                // No ripple/hover state layer: the sliding highlight is the
                // only feedback, and the pressed box outline looked wrong here.
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelLarge,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
            letterSpacing = 0.sp,
            color = labelColor,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
    }
}
