@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.cluvex.zedsecure.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import dev.cluvex.zedsecure.domain.model.ColorContrast
import dev.cluvex.zedsecure.domain.model.ColorStyle
import dev.cluvex.zedsecure.shared.resources.Res
import dev.cluvex.zedsecure.shared.resources.*
import dev.cluvex.zedsecure.ui.components.MorphingBlob
import dev.cluvex.zedsecure.ui.platform.draggableHorizontalScroll
import dev.cluvex.zedsecure.ui.theme.AccentPresets
import dev.cluvex.zedsecure.ui.theme.readableOn
import dev.cluvex.zedsecure.ui.theme.zedColorScheme
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

val ColorStyle.label: StringResource
    get() = when (this) {
        ColorStyle.ZedSecure -> Res.string.color_style_zedsecure
        ColorStyle.TonalSpot -> Res.string.color_style_tonal
        ColorStyle.Vibrant -> Res.string.color_style_vibrant
        ColorStyle.Expressive -> Res.string.color_style_expressive
        ColorStyle.Rainbow -> Res.string.color_style_rainbow
        ColorStyle.FruitSalad -> Res.string.color_style_fruit_salad
        ColorStyle.Fidelity -> Res.string.color_style_fidelity
        ColorStyle.Neutral -> Res.string.color_style_neutral
        ColorStyle.Monochrome -> Res.string.color_style_monochrome
    }

@Composable
fun ColorStylePicker(
    selected: ColorStyle,
    accent: Int,
    contrast: ColorContrast,
    onSelect: (ColorStyle) -> Unit,
) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    SettingsSegment {
        Column(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 16.dp)) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(
                    stringResource(Res.string.color_style_title),
                    style = MaterialTheme.typography.bodyLargeEmphasized,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(Res.string.color_style_sub),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .draggableHorizontalScroll(rememberScrollState())
                    .selectableGroup()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ColorStyle.entries.forEach { style ->
                    val scheme = remember(style, accent, dark, contrast) { zedColorScheme(dark, accent, style, contrast) }
                    StyleCard(stringResource(style.label), scheme, style == selected) { onSelect(style) }
                }
            }
        }
    }
}

@Composable
private fun StyleCard(name: String, scheme: ColorScheme, selected: Boolean, onClick: () -> Unit) {
    val spec = MaterialTheme.motionScheme.fastSpatialSpec<androidx.compose.ui.unit.Dp>()
    val corner by animateDpAsState(if (selected) 30.dp else 18.dp, spec, label = "style-corner")
    val tilt by animateFloatAsState(if (selected) -14f else 0f, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "style-tilt")
    val shape = RoundedCornerShape(corner)
    val ring = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(92.dp)) {
        Box(
            Modifier
                .size(width = 92.dp, height = 108.dp)
                .clip(shape)
                .background(scheme.surfaceContainer)
                .border(if (selected) 3.dp else 1.dp, ring, shape)
                .selectable(
                    selected = selected,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(),
                    role = Role.RadioButton,
                    onClick = onClick,
                ),
        ) {
            Box(
                Modifier
                    .offset(x = 10.dp, y = 12.dp)
                    .size(56.dp)
                    .graphicsLayer { rotationZ = tilt }
                    .clip(MaterialShapes.Cookie9Sided.toShape())
                    .background(scheme.primary),
            )
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-8).dp, y = 40.dp)
                    .size(34.dp)
                    .graphicsLayer { rotationZ = -tilt * 2f }
                    .clip(MaterialShapes.Clover4Leaf.toShape())
                    .background(scheme.tertiary),
            )
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .offset(x = 10.dp, y = (-12).dp)
                    .size(width = 50.dp, height = 18.dp)
                    .clip(CircleShape)
                    .background(scheme.secondaryContainer),
            )
            androidx.compose.animation.AnimatedVisibility(
                visible = selected,
                enter = scaleIn(MaterialTheme.motionScheme.fastSpatialSpec()),
                exit = scaleOut(MaterialTheme.motionScheme.fastSpatialSpec()),
                modifier = Modifier.align(Alignment.BottomEnd).offset(x = (-8).dp, y = (-8).dp),
            ) {
                Box(
                    Modifier.size(24.dp).clip(CircleShape).background(scheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(Res.drawable.ic_check),
                        contentDescription = null,
                        tint = scheme.onPrimary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            name,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

private val AccentShapes: List<RoundedPolygon> = listOf(
    MaterialShapes.Cookie9Sided,
    MaterialShapes.Clover4Leaf,
    MaterialShapes.Sunny,
    MaterialShapes.Flower,
    MaterialShapes.Cookie6Sided,
    MaterialShapes.SoftBurst,
    MaterialShapes.Puffy,
    MaterialShapes.Cookie4Sided,
    MaterialShapes.Gem,
    MaterialShapes.PuffyDiamond,
)

@Composable
fun AccentPicker(selected: Int, onSelect: (Int) -> Unit) {
    SettingsSegment {
        Column(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 16.dp)) {
            Text(
                stringResource(Res.string.title_pref_accent),
                style = MaterialTheme.typography.bodyLargeEmphasized,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .draggableHorizontalScroll(rememberScrollState())
                    .selectableGroup()
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                AccentPresets.forEachIndexed { i, preset ->
                    AccentSwatch(
                        name = preset.name,
                        color = preset.light,
                        shape = AccentShapes[i % AccentShapes.size],
                        selected = i == selected,
                    ) { onSelect(i) }
                }
            }
        }
    }
}

@Composable
private fun AccentSwatch(name: String, color: Color, shape: RoundedPolygon, selected: Boolean, onClick: () -> Unit) {
    val morph by animateFloatAsState(if (selected) 1f else 0f, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "accent-morph")
    val spin by animateFloatAsState(if (selected) 0f else -45f, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "accent-spin")
    val scale by animateFloatAsState(if (selected) 1f else 0.82f, MaterialTheme.motionScheme.fastSpatialSpec(), label = "accent-scale")
    Box(
        Modifier
            .size(50.dp)
            .selectable(
                selected = selected,
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = false, radius = 26.dp),
                role = Role.RadioButton,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        MorphingBlob(
            progress = morph,
            brush = SolidColor(color),
            modifier = Modifier
                .size(48.dp)
                .graphicsLayer {
                    rotationZ = spin
                    scaleX = scale
                    scaleY = scale
                },
            startShape = MaterialShapes.Circle,
            endShape = shape,
        )
        AnimatedVisibility(
            visible = selected,
            enter = scaleIn(MaterialTheme.motionScheme.fastSpatialSpec()),
            exit = scaleOut(MaterialTheme.motionScheme.fastSpatialSpec()),
        ) {
            Icon(
                painterResource(Res.drawable.ic_check),
                contentDescription = name,
                tint = color.readableOn(),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
