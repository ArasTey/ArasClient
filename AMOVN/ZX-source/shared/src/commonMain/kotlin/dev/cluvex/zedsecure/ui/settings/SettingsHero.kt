@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.cluvex.zedsecure.ui.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import dev.cluvex.zedsecure.shared.resources.Res
import dev.cluvex.zedsecure.shared.resources.*
import dev.cluvex.zedsecure.ui.components.MorphingBlob
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource

private val HeroShapes = listOf(
    MaterialShapes.Cookie12Sided,
    MaterialShapes.Clover8Leaf,
    MaterialShapes.SoftBurst,
    MaterialShapes.Flower,
    MaterialShapes.Sunny,
    MaterialShapes.Cookie9Sided,
    MaterialShapes.Puffy,
)

@Composable
fun SettingsHero(
    title: String,
    tagline: String,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Box(modifier.fillMaxWidth()) {
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .layout { measurable, _ ->
                    val side = 230.dp.roundToPx()
                    val placeable = measurable.measure(Constraints.fixed(side, side))
                    layout(0, 0) { placeable.placeRelative(70.dp.roundToPx() - side, (-36).dp.roundToPx()) }
                }
                .graphicsLayer {
                    rotationZ = 16f
                    alpha = 0.08f
                }
                .clip(MaterialShapes.Cookie9Sided.toShape())
                .background(cs.primary),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 16.dp, top = 22.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.displayMediumEmphasized,
                    color = cs.onSurface,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    tagline,
                    style = MaterialTheme.typography.titleMedium,
                    color = cs.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            HeroMark(reduceMotion)
        }
    }
}

@Composable
private fun HeroMark(reduceMotion: Boolean) {
    val cs = MaterialTheme.colorScheme
    var index by rememberSaveable { mutableIntStateOf(0) }
    var progress by remember { mutableFloatStateOf(0f) }
    var morphing by remember { mutableStateOf<Job?>(null) }
    val spin = remember { Animatable(if (reduceMotion) 0f else -56f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val brush = remember(cs.primary, cs.tertiary) { Brush.linearGradient(listOf(cs.primary, cs.tertiary)) }

    LaunchedEffect(Unit) {
        if (!reduceMotion) spin.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessLow))
    }

    Box(
        Modifier
            .size(112.dp)
            .clearAndSetSemantics { }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Image,
            ) {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                if (reduceMotion) {
                    index = (index + 1) % HeroShapes.size
                    return@clickable
                }
                if (morphing?.isActive != true) {
                    morphing = scope.launch {
                        animate(0f, 1f, animationSpec = spring(dampingRatio = 0.7f, stiffness = 420f)) { value, _ ->
                            progress = value
                        }
                        Snapshot.withMutableSnapshot {
                            index = (index + 1) % HeroShapes.size
                            progress = 0f
                        }
                    }
                }
                scope.launch { spin.animateTo(spin.value + 72f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessLow)) }
            },
        contentAlignment = Alignment.Center,
    ) {
        MorphingBlob(
            progress = progress,
            brush = brush,
            modifier = Modifier
                .size(112.dp)
                .graphicsLayer { rotationZ = spin.value },
            startShape = HeroShapes[index],
            endShape = HeroShapes[(index + 1) % HeroShapes.size],
        )
        Icon(
            painterResource(Res.drawable.ic_zed_glyph),
            contentDescription = null,
            tint = cs.onPrimary,
            modifier = Modifier.size(58.dp),
        )
    }
}
