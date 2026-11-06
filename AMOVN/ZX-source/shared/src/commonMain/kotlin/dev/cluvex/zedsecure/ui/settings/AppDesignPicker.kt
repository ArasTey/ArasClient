@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.cluvex.zedsecure.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.cluvex.zedsecure.domain.model.AppDesign
import dev.cluvex.zedsecure.shared.resources.Res
import dev.cluvex.zedsecure.shared.resources.*
import dev.cluvex.zedsecure.ui.theme.ZedGradients
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.sqrt

@Composable
fun AppDesignPicker(selected: AppDesign, onSelect: (AppDesign) -> Unit) {
    SettingsSegment {
        Column(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 16.dp)) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(
                    stringResource(Res.string.home_design_title),
                    style = MaterialTheme.typography.bodyLargeEmphasized,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(Res.string.home_design_sub),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth().selectableGroup().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DesignCard(
                    name = stringResource(Res.string.home_design_liquid),
                    selected = selected == AppDesign.Liquid,
                    modifier = Modifier.weight(1f),
                    onClick = { onSelect(AppDesign.Liquid) },
                ) { LiquidPreview() }
                DesignCard(
                    name = stringResource(Res.string.home_design_portal),
                    selected = selected == AppDesign.Portal,
                    modifier = Modifier.weight(1f),
                    onClick = { onSelect(AppDesign.Portal) },
                ) { PortalPreview() }
                DesignCard(
                    name = stringResource(Res.string.home_design_old),
                    selected = selected == AppDesign.Old,
                    modifier = Modifier.weight(1f),
                    onClick = { onSelect(AppDesign.Old) },
                ) { OldPreview() }
            }
        }
    }
}

@Composable
private fun LiquidPreview() {
    val cs = MaterialTheme.colorScheme
    val glass = Color.White.copy(alpha = 0.22f)
    val edge = Brush.linearGradient(listOf(Color.White.copy(alpha = 0.7f), Color.White.copy(alpha = 0.08f)))
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                Brush.radialGradient(listOf(cs.primary.copy(alpha = 0.55f), Color.Transparent), Offset(size.width * 0.15f, size.height * 0.1f), size.width),
                size.width,
                Offset(size.width * 0.15f, size.height * 0.1f),
            )
            drawCircle(
                Brush.radialGradient(listOf(cs.tertiary.copy(alpha = 0.5f), Color.Transparent), Offset(size.width * 0.95f, size.height * 0.75f), size.width),
                size.width,
                Offset(size.width * 0.95f, size.height * 0.75f),
            )
        }
        Column(
            Modifier.fillMaxSize().padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(glass)
                    .border(1.dp, edge, RoundedCornerShape(20.dp)),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.85f), cs.primary, cs.tertiary), Offset(14f, 12f), 90f)),
                )
                Spacer(Modifier.height(7.dp))
                Box(Modifier.size(width = 36.dp, height = 5.dp).clip(CircleShape).background(cs.onSurface.copy(alpha = 0.6f)))
            }
            Box(Modifier.fillMaxWidth().height(16.dp).clip(RoundedCornerShape(10.dp)).background(glass).border(1.dp, edge, RoundedCornerShape(10.dp)))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.weight(1f).height(14.dp).clip(RoundedCornerShape(9.dp)).background(glass).border(1.dp, edge, RoundedCornerShape(9.dp)))
                Box(Modifier.weight(1f).height(14.dp).clip(RoundedCornerShape(9.dp)).background(glass).border(1.dp, edge, RoundedCornerShape(9.dp)))
            }
        }
    }
}

@Composable
private fun DesignCard(
    name: String,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    preview: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val corner by animateDpAsState(if (selected) 32.dp else 20.dp, MaterialTheme.motionScheme.fastSpatialSpec(), label = "design-corner")
    val shape = RoundedCornerShape(corner)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(150.dp)
                .clip(shape)
                .background(cs.surface)
                .border(if (selected) 3.dp else 1.dp, if (selected) cs.primary else cs.outlineVariant, shape)
                .selectable(
                    selected = selected,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(),
                    role = Role.RadioButton,
                    onClick = onClick,
                ),
        ) {
            preview()
            androidx.compose.animation.AnimatedVisibility(
                visible = selected,
                enter = scaleIn(MaterialTheme.motionScheme.fastSpatialSpec()),
                exit = scaleOut(MaterialTheme.motionScheme.fastSpatialSpec()),
                modifier = Modifier.align(Alignment.TopEnd).offset(x = (-10).dp, y = 10.dp),
            ) {
                Box(
                    Modifier.size(26.dp).clip(CircleShape).background(cs.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(Res.drawable.ic_check),
                        contentDescription = null,
                        tint = cs.onPrimary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            name,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) cs.primary else cs.onSurfaceVariant,
        )
    }
}

@Composable
private fun PortalPreview() {
    val cs = MaterialTheme.colorScheme
    val dot = cs.onSurface
    val glowA = cs.primary
    val glowB = cs.tertiary
    val ring = cs.primary
    val ticket = cs.primaryContainer
    val meter = cs.surfaceContainerHigh
    Canvas(Modifier.fillMaxSize()) {
        val c = Offset(size.width / 2f, size.height * 0.40f)
        val r = size.width * 0.17f
        val far = sqrt(c.x * c.x + c.y * c.y)
        val step = 9.dp.toPx()
        var y = step / 2f
        var row = 0
        while (y < size.height * 0.74f) {
            var x = if (row % 2 == 0) step / 2f else step
            while (x < size.width) {
                val dx = x - c.x
                val dy = y - c.y
                val d = sqrt(dx * dx + dy * dy)
                if (d > r * 1.25f) {
                    val q = d / r
                    val nd = d + r * 0.78f / (1f + q * q * q)
                    val fade = (1f - nd / far).coerceIn(0f, 1f)
                    drawCircle(dot.copy(alpha = 0.32f * fade * fade + 0.03f), 1.1.dp.toPx(), Offset(c.x + dx / d * nd, c.y + dy / d * nd))
                }
                x += step
            }
            y += step
            row++
        }
        drawCircle(
            Brush.radialGradient(listOf(glowA.copy(alpha = 0.45f), glowB.copy(alpha = 0.15f), Color.Transparent), c, r * 2.6f),
            r * 2.6f,
            c,
        )
        drawCircle(ring.copy(alpha = 0.35f), r * 1.35f, c, style = Stroke(1.dp.toPx()))
        drawCircle(Brush.radialGradient(listOf(cs.primaryContainer, glowA, glowB), c, r), r, c)
        drawCircle(glowB, 2.6.dp.toPx(), Offset(c.x + r * 1.35f * 0.7f, c.y - r * 1.35f * 0.71f))
        val pad = 10.dp.toPx()
        val meterTop = size.height * 0.76f
        val half = (size.width - pad * 2 - 3.dp.toPx()) / 2f
        drawRoundRect(meter, Offset(pad, meterTop), Size(half, 14.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(7.dp.toPx()))
        drawRoundRect(meter, Offset(pad + half + 3.dp.toPx(), meterTop), Size(half, 14.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(7.dp.toPx()))
        drawRoundRect(ticket, Offset(pad, meterTop + 18.dp.toPx()), Size(size.width - pad * 2, 18.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(9.dp.toPx()))
    }
}

@Composable
private fun OldPreview() {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .size(62.dp)
                .clip(MaterialShapes.Cookie9Sided.toShape())
                .background(ZedGradients.idle),
            contentAlignment = Alignment.Center,
        ) {
            Text("Z", color = Color.White, style = MaterialTheme.typography.titleLargeEmphasized)
        }
        Spacer(Modifier.height(2.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Box(Modifier.weight(1f).height(26.dp).clip(RoundedCornerShape(7.dp)).background(cs.surfaceContainerHighest))
            Box(Modifier.weight(1f).height(26.dp).clip(RoundedCornerShape(7.dp)).background(cs.surfaceContainerHighest))
        }
        Box(Modifier.fillMaxWidth().height(18.dp).clip(RoundedCornerShape(7.dp)).background(cs.primaryContainer))
        Box(Modifier.fillMaxWidth().height(18.dp).clip(RoundedCornerShape(9.dp)).background(cs.primary))
    }
}
