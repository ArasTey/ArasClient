package com.aras.client.ui.compose

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aras.client.R

/**
 * Theme chooser built around real captures of this app, so what you tap is what
 * the screen becomes. The hero shows the selected theme at the current column
 * layout; the four tiles below are the same captures shrunk down.
 */
@Composable
fun ThemePicker(
    selected: AppThemeMode,
    twoColumn: Boolean,
    labels: List<String>,
    modifier: Modifier = Modifier,
    onSelect: (AppThemeMode) -> Unit,
    onTwoColumnChange: (Boolean) -> Unit,
) {
    val palette = LocalGlassPalette.current
    val heroShape = RoundedCornerShape(24.dp)

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(heroShape)
                .background(if (palette.isDark) Color(0x0FFFFFFF) else Color(0x0A000000))
                .border(0.7.dp, palette.hairline, heroShape)
                .padding(vertical = 22.dp),
            contentAlignment = Alignment.Center
        ) {
            Crossfade(targetState = selected to twoColumn, animationSpec = tween(240)) { (mode, two) ->
                Box(
                    modifier = Modifier
                        .height(260.dp)
                        .width(260.dp * PHONE_SHAPE)
                        .clip(RoundedCornerShape(22.dp))
                        .border(1.6.dp, palette.accent.copy(alpha = 0.5f), RoundedCornerShape(22.dp))
                        .padding(3.dp)
                ) {
                    Image(
                        painter = painterResource(themeHeroRes(mode, two)),
                        contentDescription = labels.getOrNull(mode.ordinal),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(19.dp))
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Text(
            text = labels.getOrNull(selected.ordinal).orEmpty(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))

        // Column layout is part of the look, so it is chosen here and the hero
        // re-renders with the matching capture.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            LayoutChip(
                label = stringResource(R.string.theme_layout_one_column),
                selected = !twoColumn,
                modifier = Modifier.weight(1f),
                onClick = { onTwoColumnChange(false) }
            )
            LayoutChip(
                label = stringResource(R.string.theme_layout_two_column),
                selected = twoColumn,
                modifier = Modifier.weight(1f),
                onClick = { onTwoColumnChange(true) }
            )
        }

        Spacer(Modifier.height(18.dp))

        AppThemeMode.entries.chunked(2).forEach { rowModes ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                rowModes.forEach { mode ->
                    ThemeTile(
                        label = labels.getOrNull(mode.ordinal).orEmpty(),
                        thumbRes = themeThumbRes(mode, twoColumn),
                        selected = mode == selected,
                        modifier = Modifier.weight(1f),
                        onClick = { onSelect(mode) }
                    )
                }
                // Keep a lone tile at half width instead of stretching it.
                if (rowModes.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

private const val PHONE_SHAPE = 1080f / 2400f

@Composable
private fun LayoutChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val palette = LocalGlassPalette.current
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .background(
                if (selected) palette.chipFill else if (palette.isDark) Color(0x0FFFFFFF) else Color(0x0A000000)
            )
            .border(
                width = if (selected) 1.2.dp else 0.7.dp,
                color = if (selected) palette.accent.copy(alpha = 0.6f) else palette.hairline,
                shape = shape
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) palette.chipText else palette.faint,
            maxLines = 1
        )
    }
}

@Composable
private fun ThemeTile(
    label: String,
    thumbRes: Int,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val palette = LocalGlassPalette.current
    val shape = RoundedCornerShape(20.dp)
    val sel by animateFloatAsState(if (selected) 1f else 0f, tween(180), label = "themeTile")
    val fill = if (palette.isDark) Color(0x14FFFFFF) else Color(0xF2FFFFFF)

    Column(
        modifier = modifier
            .clip(shape)
            .background(fill)
            .border(
                width = if (selected) 1.4.dp else 0.7.dp,
                color = if (selected) palette.accent.copy(alpha = 0.55f + 0.45f * sel) else palette.hairline,
                shape = shape
            )
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            Image(
                painter = painterResource(thumbRes),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .height(108.dp)
                    .width(108.dp * PHONE_SHAPE)
                    .clip(RoundedCornerShape(12.dp))
                    .border(0.7.dp, palette.hairline, RoundedCornerShape(12.dp))
            )
            // Badge rides in on a fade+settle so the tile never re-lays out.
            Box(
                modifier = Modifier
                    .graphicsLayer {
                        alpha = sel
                        scaleX = 0.6f + 0.4f * sel
                        scaleY = 0.6f + 0.4f * sel
                    }
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(palette.accent),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_action_done),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(13.dp)
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onSurface else palette.faint,
            maxLines = 2,
            textAlign = TextAlign.Center
        )
    }
}

/** Hero capture for a theme, in the layout the app currently uses. */
private fun themeHeroRes(mode: AppThemeMode, twoColumn: Boolean): Int = when (mode) {
    AppThemeMode.DARK_DYNAMIC -> if (twoColumn) R.drawable.theme_prev_dark_dynamic_2 else R.drawable.theme_prev_dark_dynamic_1
    AppThemeMode.DARK_NORMAL -> if (twoColumn) R.drawable.theme_prev_dark_normal_2 else R.drawable.theme_prev_dark_normal_1
    AppThemeMode.LIGHT_DYNAMIC -> if (twoColumn) R.drawable.theme_prev_light_dynamic_2 else R.drawable.theme_prev_light_dynamic_1
    AppThemeMode.LIGHT_NORMAL -> if (twoColumn) R.drawable.theme_prev_light_normal_2 else R.drawable.theme_prev_light_normal_1
}

private fun themeThumbRes(mode: AppThemeMode, twoColumn: Boolean): Int = when (mode) {
    AppThemeMode.DARK_DYNAMIC -> if (twoColumn) R.drawable.theme_thumb_dark_dynamic_2 else R.drawable.theme_thumb_dark_dynamic_1
    AppThemeMode.DARK_NORMAL -> if (twoColumn) R.drawable.theme_thumb_dark_normal_2 else R.drawable.theme_thumb_dark_normal_1
    AppThemeMode.LIGHT_DYNAMIC -> if (twoColumn) R.drawable.theme_thumb_light_dynamic_2 else R.drawable.theme_thumb_light_dynamic_1
    AppThemeMode.LIGHT_NORMAL -> if (twoColumn) R.drawable.theme_thumb_light_normal_2 else R.drawable.theme_thumb_light_normal_1
}
