package dev.cluvex.zedsecure.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cluvex.zedsecure.domain.model.ConnectButtonStyle
import dev.cluvex.zedsecure.ui.theme.ThemeProfile
import dev.cluvex.zedsecure.ui.theme.ThemeProfiles
import dev.cluvex.zedsecure.ui.platform.draggableHorizontalScroll

@Composable
fun ThemeProfileRow(
    label: String,
    subtitle: String,
    defaultLabel: String,
    selectedId: String?,
    onSelect: (ThemeProfile?) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth().draggableHorizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ProfileCard(
                name = defaultLabel,
                selected = selectedId == null,
                onClick = { onSelect(null) },
            ) { StockPreview() }
            ThemeProfiles.forEach { profile ->
                ProfileCard(
                    name = profile.name,
                    selected = selectedId == profile.id,
                    onClick = { onSelect(profile) },
                ) { ProfilePreview(profile) }
            }
        }
    }
}

@Composable
private fun ProfileCard(
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
    preview: @Composable () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(width = 92.dp, height = 148.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(
                    width = if (selected) 3.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(16.dp),
                )
                .clickable(onClick = onClick)
                .padding(8.dp),
        ) { preview() }
        Spacer(Modifier.height(6.dp))
        Text(
            name,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun MiniHome(
    hero: Color,
    downloadTile: Color,
    uploadTile: Color,
    download: Color,
    upload: Color,
    configCard: Color,
    configText: Color,
    connect: Color,
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(hero))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            MiniTile(Modifier.weight(1f), downloadTile, download)
            MiniTile(Modifier.weight(1f), uploadTile, upload)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(20.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(configCard),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .padding(start = 6.dp)
                    .size(width = 34.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(configText),
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(18.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(connect),
        )
    }
}

@Composable
private fun MiniTile(modifier: Modifier, surface: Color, accent: Color) {
    Box(
        modifier
            .height(30.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(surface),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(accent))
            Spacer(Modifier.height(3.dp))
            Box(
                Modifier
                    .size(width = 18.dp, height = 3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accent),
            )
        }
    }
}

@Composable
private fun ProfilePreview(p: ThemeProfile) = MiniHome(
    hero = Color(p.connect),
    downloadTile = Color(p.downloadTile),
    uploadTile = Color(p.uploadTile),
    download = Color(p.download),
    upload = Color(p.upload),
    configCard = Color(p.configCard),
    configText = Color(p.configCardText),
    connect = Color(p.connect),
)

@Composable
private fun StockPreview() {
    val cs = MaterialTheme.colorScheme
    MiniHome(
        hero = cs.primary,
        downloadTile = cs.surfaceContainerHighest,
        uploadTile = cs.surfaceContainerHighest,
        download = cs.primary,
        upload = cs.tertiary,
        configCard = cs.primaryContainer,
        configText = cs.onPrimaryContainer,
        connect = cs.primary,
    )
}

@Composable
fun ConnectStyleRow(
    label: String,
    subtitle: String,
    selected: ConnectButtonStyle,
    nameOf: @Composable (ConnectButtonStyle) -> String,
    onSelect: (ConnectButtonStyle) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth().draggableHorizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ConnectButtonStyle.entries.forEach { style ->
                val isSelected = style == selected
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(width = 88.dp, height = 66.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .border(
                                width = if (isSelected) 3.dp else 1.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(14.dp),
                            )
                            .clickable { onSelect(style) },
                        contentAlignment = Alignment.Center,
                    ) { StyleGlyph(style) }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        nameOf(style),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

@Composable
private fun StyleGlyph(style: ConnectButtonStyle) {
    val accent = MaterialTheme.colorScheme.primary
    val on = MaterialTheme.colorScheme.onPrimary
    when (style) {
        ConnectButtonStyle.Pill -> Box(
            Modifier.size(width = 58.dp, height = 22.dp)
                .clip(RoundedCornerShape(11.dp)).background(accent),
        )
        ConnectButtonStyle.Hero -> Box(
            Modifier.size(38.dp).clip(CircleShape).background(accent),
            contentAlignment = Alignment.Center,
        ) { Text("Z", color = on, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
        ConnectButtonStyle.Ring -> Box(
            Modifier.size(38.dp).clip(CircleShape).background(accent)
                .border(3.dp, on.copy(alpha = 0.5f), CircleShape),
        )
        ConnectButtonStyle.Bar -> Box(
            Modifier.size(width = 62.dp, height = 14.dp)
                .clip(RoundedCornerShape(4.dp)).background(accent),
        )
        ConnectButtonStyle.Icon -> Box(
            Modifier.size(30.dp).clip(RoundedCornerShape(10.dp)).background(accent),
        )
        ConnectButtonStyle.Switch -> Box(
            Modifier.size(width = 58.dp, height = 26.dp)
                .clip(RoundedCornerShape(13.dp)).background(accent),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                Modifier.padding(start = 4.dp).size(18.dp).clip(CircleShape)
                    .background(on.copy(alpha = 0.5f)),
            )
        }
    }
}
