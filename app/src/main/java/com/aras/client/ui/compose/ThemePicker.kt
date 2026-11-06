package com.aras.client.ui.compose

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aras.client.R

private data class ThemeChoice(
    val value: String,
    val title: String,
    val description: String,
    val background: Color,
    val surface: Color,
    val accent: Color,
    val foreground: Color
)

@Composable
fun ThemePicker(
    selectedValue: String,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val patternIntensity by ThemeManager.patternIntensity.collectAsState()
    val reducedMotion by ThemeManager.reducedMotionEnabled.collectAsState()
    val persianDigits by ThemeManager.persianDigitsEnabled.collectAsState()

    val choices = listOf(
        ThemeChoice(
            value = "0",
            title = stringResource(R.string.theme_system_title),
            description = stringResource(R.string.theme_system_summary),
            background = if (isSystemInDarkTheme()) Color(0xFF222226) else Color(0xFFFAF6F0),
            surface = if (isSystemInDarkTheme()) Color(0xFF39393F) else Color(0xFFEBE3D8),
            accent = if (isSystemInDarkTheme()) Color(0xFF4FB3F0) else Color(0xFF0284C7),
            foreground = if (isSystemInDarkTheme()) Color(0xFFE8E2DA) else Color(0xFF222226)
        ),
        ThemeChoice(
            value = "3",
            title = stringResource(R.string.theme_cultural_night_title),
            description = stringResource(R.string.theme_cultural_night_summary),
            background = Color(0xFF0B1420),
            surface = Color(0xFF16222F),
            accent = Color(0xFF4FB3B0),
            foreground = Color(0xFFF2E8D5)
        ),
        ThemeChoice(
            value = "4",
            title = stringResource(R.string.theme_ancient_paper_title),
            description = stringResource(R.string.theme_ancient_paper_summary),
            background = Color(0xFFF4ECD8),
            surface = Color(0xFFFFF8E9),
            accent = Color(0xFF2A7F7A),
            foreground = Color(0xFF1E3650)
        )
    )
    val uiTheme by ThemeManager.uiTheme.collectAsState()
    val uiChoices = listOf(
        ThemeChoice("default", stringResource(R.string.theme_ui_default_title), stringResource(R.string.theme_ui_default_summary), Color(0xFFFAF6F0), Color(0xFFEFE6DA), Color(0xFF0284C7), Color(0xFF222226)),
        ThemeChoice("radar", stringResource(R.string.theme_ui_radar_title), stringResource(R.string.theme_ui_radar_summary), Color(0xFF020A06), Color(0xFF06150C), Color(0xFF39FF88), Color(0xFFB8FFD2)),
        ThemeChoice("survival", stringResource(R.string.theme_ui_survival_title), stringResource(R.string.theme_ui_survival_summary), Color(0xFF090E0B), Color(0xFF141B15), Color(0xFF9BCB72), Color(0xFFD8D0B8)),
        ThemeChoice("aurora", stringResource(R.string.theme_ui_aurora_title), stringResource(R.string.theme_ui_aurora_summary), Color(0xFF070A0B), Color(0xFF0C1212), Color(0xFF54FFC0), Color(0xFFF1F8F4)),
        ThemeChoice("rdrn", "RADAR VPN", "Neon radar · six tabs · speed test and animated navigation", Color(0xFF040B07), Color(0xFF0A2216), Color(0xFF22E07A), Color(0xFFE9F3EC)),
        ThemeChoice("rdrx", "RADAR Glass", "Liquid glass · radar connect button · translucent surfaces", Color(0xFF05070D), Color(0xFF111A2B), Color(0xFF30D158), Color(0xFFF5F6FA)),
        ThemeChoice("zx", "ZX Liquid", "Violet glass · cyan highlights · liquid gradients and soft glow", Color(0xFF090817), Color(0xFF1B1730), Color(0xFF8B7CFF), Color(0xFFF4F1FF)),
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = stringResource(R.string.title_pref_ui_mode_night),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            choices.forEach { choice ->
                val selected = if (choice.value == "0") {
                    selectedValue !in setOf("3", "4")
                } else selectedValue == choice.value
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .selectable(
                            selected = selected,
                            role = Role.RadioButton,
                            onClick = { onSelected(choice.value) }
                        ),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(
                        width = if (selected) 2.dp else 1.dp,
                        color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(choice.background)
                                .padding(7.dp)
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxWidth(0.52f)
                                        .height(5.dp)
                                        .clip(CircleShape)
                                        .background(choice.accent)
                                )
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(13.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(choice.surface)
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Box(
                                        Modifier
                                            .size(7.dp)
                                            .clip(CircleShape)
                                            .background(choice.accent)
                                    )
                                    Box(
                                        Modifier
                                            .weight(1f)
                                            .height(4.dp)
                                            .align(Alignment.CenterVertically)
                                            .clip(CircleShape)
                                            .background(choice.foreground.copy(alpha = 0.45f))
                                    )
                                }
                            }
                            if (selected) {
                                Text(
                                    text = "✓",
                                    color = choice.accent,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.align(Alignment.BottomEnd)
                                )
                            }
                        }
                        Text(
                            text = choice.title,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                        )
                        Text(
                            text = choice.description,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            minLines = 2
                        )
                    }
                }
            }
        }
        Text(
            text = stringResource(R.string.theme_ui_style_title),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp)
        )
        uiChoices.chunked(2).forEach { rowChoices ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowChoices.forEach { choice ->
                    val selected = uiTheme == choice.value
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .selectable(
                                selected = selected,
                                role = Role.RadioButton,
                                onClick = { ThemeManager.setUiTheme(choice.value) }
                            ),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(
                            width = if (selected) 2.dp else 1.dp,
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(if (choice.value == "rdrx" || choice.value == "zx") 96.dp else 42.dp)
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(
                                        if (choice.value == "rdrx" || choice.value == "zx") androidx.compose.ui.graphics.Brush.verticalGradient(
                                            listOf(Color(0xFF0B1B3A), Color(0xFF2A1250))
                                        ) else androidx.compose.ui.graphics.SolidColor(choice.background)
                                    )
                                    .padding(7.dp)
                            ) {
                                if (choice.value == "rdrx") {
                                    // پیش‌نمایش زنده‌ی کوچک: رادار سبز + مستطیل شیشه‌ای + کپسول
                                    androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
                                        val r = size.height * 0.30f
                                        val ctr = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height * 0.40f)
                                        drawCircle(Color(0xFF30D158).copy(alpha = 0.18f), r * 1.35f, ctr)
                                        drawCircle(Color(0xFF30D158), r, ctr, style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
                                        drawCircle(Color(0xFF30D158).copy(alpha = 0.5f), r * 0.6f, ctr, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5f))
                                        drawCircle(Color(0xFF30D158), 3.5f, androidx.compose.ui.geometry.Offset(ctr.x + r * 0.35f, ctr.y - r * 0.25f))
                                        drawLine(Color(0xFF30D158), ctr, androidx.compose.ui.geometry.Offset(ctr.x + r * 0.8f, ctr.y - r * 0.55f), 2f)
                                        drawRoundRect(
                                            Color.White.copy(alpha = 0.16f),
                                            androidx.compose.ui.geometry.Offset(size.width * 0.12f, size.height * 0.76f),
                                            androidx.compose.ui.geometry.Size(size.width * 0.76f, size.height * 0.15f),
                                            androidx.compose.ui.geometry.CornerRadius(14f, 14f),
                                        )
                                    }
                                }
                                if (choice.value != "rdrx" && choice.value != "zx") Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Box(
                                        Modifier.fillMaxWidth(0.55f).height(4.dp)
                                            .clip(CircleShape).background(choice.accent)
                                    )
                                    Box(
                                        Modifier.fillMaxWidth().height(10.dp)
                                            .clip(RoundedCornerShape(4.dp)).background(choice.surface)
                                    )
                                }
                                if (selected) {
                                    Text(
                                        text = "✓",
                                        color = choice.accent,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.align(Alignment.BottomEnd)
                                    )
                                }
                            }
                            Text(
                                text = choice.title,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                            )
                            Text(
                                text = choice.description,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                minLines = 2
                            )
                        }
                    }
                }
                if (rowChoices.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        if (selectedValue == "3") {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.theme_pattern_intensity),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "$patternIntensity%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Slider(
                value = patternIntensity.toFloat(),
                onValueChange = { ThemeManager.setPatternIntensity(it.toInt()) },
                valueRange = 0f..100f,
                steps = 99
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.theme_reduced_motion),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.theme_reduced_motion_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = reducedMotion,
                    onCheckedChange = { ThemeManager.setReducedMotionEnabled(it) }
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.theme_persian_digits),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.theme_persian_digits_summary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = persianDigits,
                    onCheckedChange = { ThemeManager.setPersianDigitsEnabled(it) }
                )
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}
