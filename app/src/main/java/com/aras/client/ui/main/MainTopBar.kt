package com.aras.client.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aras.client.R
import com.aras.client.ui.compose.LocalGlassPalette

/**
 * Compact glyph-only top bar, matching the approved mockup: the menu sits alone
 * against the screen edge, the wordmark travels next to the action cluster, and
 * the actions run up to the opposite edge. No material button chrome — glyphs
 * only, with a small dot under the active one.
 */
@Composable
fun MainTopBar(
    isLoading: Boolean,
    showSearch: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onSearchClose: () -> Unit,
    onSearchToggle: (Boolean) -> Unit,
    onMenuClick: () -> Unit,
    onAction: (MainAction) -> Unit,
    onMoreMenuAction: (MainMoreMenuAction) -> Unit
) {
    var showImportMenu by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var importAnchor by remember { mutableStateOf<DropdownAnchor?>(null) }
    var moreAnchor by remember { mutableStateOf<DropdownAnchor?>(null) }
    val density = LocalDensity.current
    val view = LocalView.current

    fun anchorOf(coords: LayoutCoordinates): DropdownAnchor {
        val bounds = coords.boundsInWindow()
        return with(density) {
            DropdownAnchor(
                top = bounds.top.toDp(),
                width = bounds.width.toDp(),
                height = bounds.height.toDp(),
                centerFromEnd = (view.width - bounds.center.x).toDp(),
            )
        }
    }

    AnchoredDropdown(
        expanded = showImportMenu,
        anchor = importAnchor,
        onDismiss = { showImportMenu = false }
    ) {
        ImportMenuContent(
            onAction = { action ->
                showImportMenu = false
                onAction(action)
            }
        )
    }
    AnchoredDropdown(
        expanded = showMenu,
        anchor = moreAnchor,
        onDismiss = { showMenu = false }
    ) {
        MoreMenuContent { action ->
            showMenu = false
            onMoreMenuAction(action)
        }
    }

    val palette = LocalGlassPalette.current
    val ink = MaterialTheme.colorScheme.onSurface

    Column(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
    ) {
        if (showSearch) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .padding(start = 10.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TopBarGlyph(
                    iconRes = R.drawable.ic_arrow_back_24dp,
                    contentDescription = stringResource(R.string.acc_back),
                    tint = ink.copy(alpha = 0.92f),
                    glyphSize = 21.dp,
                    onClick = onSearchClose
                )
                Spacer(Modifier.width(4.dp))
                TopBarSearchField(
                    query = searchQuery,
                    onQueryChange = onSearchQueryChange,
                    placeholder = stringResource(R.string.menu_item_search),
                    modifier = Modifier.weight(1f)
                )
            }
        } else {
            // The chrome reads left-to-right even in an RTL UI: this is a tool
            // strip, not prose — menu, wordmark, then the action cluster.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .padding(start = 10.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TopBarGlyph(
                        iconRes = R.drawable.ic_menu_24dp,
                        contentDescription = stringResource(R.string.acc_open_menu),
                        tint = ink.copy(alpha = 0.92f),
                        glyphSize = 20.dp,
                        onClick = onMenuClick
                    )
                    Spacer(Modifier.width(6.dp))
                    Wordmark(palette.accent, ink)
                    Spacer(Modifier.weight(1f))
                    TopBarGlyph(
                        iconRes = R.drawable.ic_search_24dp,
                        contentDescription = stringResource(R.string.acc_search),
                        tint = ink.copy(alpha = 0.72f),
                        glyphSize = 18.dp,
                        onClick = { onSearchToggle(true) }
                    )
                    TopBarGlyph(
                        iconRes = R.drawable.ic_add_24dp,
                        contentDescription = stringResource(R.string.acc_add),
                        tint = ink.copy(alpha = 0.72f),
                        glyphSize = 18.dp,
                        modifier = Modifier.onGloballyPositioned { importAnchor = anchorOf(it) },
                        onClick = { showImportMenu = true }
                    )
                    TopBarGlyph(
                        iconRes = R.drawable.ic_more_vert_24dp,
                        contentDescription = stringResource(R.string.acc_more),
                        tint = ink.copy(alpha = 0.72f),
                        glyphSize = 18.dp,
                        modifier = Modifier.onGloballyPositioned { moreAnchor = anchorOf(it) },
                        onClick = { showMenu = true }
                    )
                }
            }
        }
        if (isLoading) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = palette.accent,
                trackColor = Color.Transparent
            )
        }
    }
}

@Composable
private fun Wordmark(accent: Color, ink: Color) {
    // The brand reads the same in every language, so this Row stays LTR.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Aras",
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.4).sp,
                color = ink
            )
            Text(
                "Client",
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.4).sp,
                color = accent
            )
        }
    }
}

@Composable
private fun TopBarGlyph(
    iconRes: Int,
    contentDescription: String,
    tint: Color,
    glyphSize: Dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(glyphSize)
        )
    }
}

@Composable
private fun TopBarSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    val palette = LocalGlassPalette.current
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val shape = RoundedCornerShape(999.dp)
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 14.sp
        ),
        cursorBrush = SolidColor(palette.accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = modifier
            .height(38.dp)
            .clip(shape)
            .background(palette.glassTop)
            .border(0.8.dp, palette.glassBorder, shape)
            .focusRequester(focusRequester)
            .padding(horizontal = 14.dp),
        decorationBox = { innerTextField ->
            Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.CenterStart
            ) {
                if (query.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = 13.sp,
                        color = palette.faint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                innerTextField()
            }
        }
    )
}
