package dev.cluvex.zedsecure.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.cluvex.zedsecure.domain.model.ScreenTransition
import dev.cluvex.zedsecure.shared.resources.Res
import dev.cluvex.zedsecure.shared.resources.*
import dev.cluvex.zedsecure.ui.motion.layoutSign
import dev.cluvex.zedsecure.ui.motion.screenTransition
import dev.cluvex.zedsecure.ui.motion.transitionDecoration
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import dev.cluvex.zedsecure.ui.platform.mouseDragScroll
import androidx.compose.foundation.lazy.rememberLazyListState

@Composable
fun TransitionPicker(
    selected: ScreenTransition,
    reduceMotion: Boolean,
    onSelect: (ScreenTransition) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(
            stringResource(Res.string.transition_title),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            stringResource(Res.string.transition_sub),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        TransitionPreview(selected, reduceMotion)
        if (reduceMotion) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(Res.string.transition_reduced),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(12.dp))
        val row = rememberLazyListState()
        LazyRow(
            state = row,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.mouseDragScroll(row),
        ) {
            items(ScreenTransition.entries) { style ->
                val chosen = style == selected
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (chosen) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = if (chosen) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.selectable(selected = chosen, onClick = { onSelect(style) }),
                ) {
                    Text(
                        transitionName(style),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TransitionPreview(style: ScreenTransition, reduceMotion: Boolean) {
    var step by remember { mutableIntStateOf(0) }

    LaunchedEffect(style) {
        step = 0
        while (true) {
            delay(1_700)
            step++
        }
    }
    val sign = layoutSign()
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().height(132.dp),
    ) {
        Box(Modifier.padding(10.dp)) {
            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    screenTransition(
                        style = style,
                        forward = targetState > initialState,
                        direction = sign,
                        reduceMotion = reduceMotion,
                    ).using(SizeTransform(clip = false))
                },
                label = "transition-preview",
                modifier = Modifier.fillMaxSize(),
            ) { value ->
                val decoration = transitionDecoration(style, value >= step, sign, reduceMotion)
                ToyScreen(index = value, modifier = decoration)
            }
        }
    }
}

@Composable
private fun ToyScreen(index: Int, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val accent = if (index % 2 == 0) scheme.primary else scheme.tertiary
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = scheme.surfaceContainerHighest,
        modifier = modifier.fillMaxSize(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(22.dp).clip(CircleShape).background(accent))
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .height(10.dp)
                        .width(90.dp)
                        .clip(CircleShape)
                        .background(Brush.horizontalGradient(listOf(accent, accent.copy(alpha = 0.35f)))),
                )
            }
            repeat(3) { row ->
                Box(
                    Modifier
                        .fillMaxWidth(if (row == 1) 0.72f else 0.92f)
                        .height(12.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(scheme.surfaceContainer),
                )
            }
        }
    }
}

@Composable
fun transitionName(style: ScreenTransition): String = when (style) {
    ScreenTransition.Fade -> stringResource(Res.string.transition_fade)
    ScreenTransition.Slide -> stringResource(Res.string.transition_slide)
    ScreenTransition.Push -> stringResource(Res.string.transition_push)
    ScreenTransition.Depth -> stringResource(Res.string.transition_depth)
    ScreenTransition.Elastic -> stringResource(Res.string.transition_elastic)
    ScreenTransition.Curtain -> stringResource(Res.string.transition_curtain)
    ScreenTransition.Flip -> stringResource(Res.string.transition_flip)
}
