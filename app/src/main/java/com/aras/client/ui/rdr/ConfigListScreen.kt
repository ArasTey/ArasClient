package com.aras.client.ui.rdr

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.RssFeed
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aras.client.R

/**
 * صفحه‌ی لیست کانفیگ‌ها (بعد از لمس مستطیل بزرگ صفحه‌ی اصلی).
 * مثل بقیه‌ی فیلترشکن‌ها: گروه/اشتراک‌ها، جستجو، مرتب‌سازی، تست پینگ، انتخاب چندتایی با لمس طولانی،
 * عملیات روی کارت (تست/ویرایش/اشتراک/حذف) و دکمه‌ی افزودن با منوی QR / کلیپ‌بورد / فایل / اشتراک / دستی.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConfigListScreen(
    state: ConfigListState,
    actions: ConfigListActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    closeOnSelect: Boolean = true,
) {
    val c = LocalRdrColors.current
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var groupId by rememberSaveable { mutableStateOf("") }
    var sortIndex by rememberSaveable { mutableStateOf(0) }
    var sortOpen by rememberSaveable { mutableStateOf(false) }
    var addOpen by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf(emptySet<String>()) }
    val selecting = picked.isNotEmpty()
    val sort = ConfigSort.values()[sortIndex]

    LaunchedEffect(state.servers) {
        val ids = state.servers.map { it.id }.toSet()
        picked = picked.filter { it in ids }.toSet()
    }

    BackHandler(enabled = addOpen) { addOpen = false }
    BackHandler(enabled = selecting && !addOpen) { picked = emptySet() }

    val shown = remember(state.servers, query, groupId, sort) {
        val filtered = state.servers.filter { s ->
            (groupId.isEmpty() || s.groupId == groupId) &&
                (query.isBlank() ||
                    s.name.contains(query, ignoreCase = true) ||
                    s.host.contains(query, ignoreCase = true) ||
                    s.protocol.contains(query, ignoreCase = true))
        }
        when (sort) {
            ConfigSort.DEFAULT -> filtered
            ConfigSort.PING -> filtered.sortedBy { s -> s.pingMs?.takeIf { it > 0 } ?: Int.MAX_VALUE }
            ConfigSort.NAME -> filtered.sortedBy { it.name.lowercase() }
        }
    }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // ── سرصفحه ──
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(RdrDims.topBarHeight)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (selecting) {
                    GlassIconButton(Icons.Rounded.Close, stringResource(R.string.rdr_back), { picked = emptySet() })
                    Text(
                        stringResource(R.string.rdr_selected_count, picked.size),
                        color = c.text,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        modifier = Modifier.weight(1f),
                    )
                    GlassIconButton(
                        Icons.Rounded.SelectAll,
                        stringResource(R.string.rdr_select_all),
                        { picked = shown.map { it.id }.toSet() },
                    )
                } else {
                    GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.rdr_back), onBack)
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.rdr_configs),
                            color = c.text,
                            fontWeight = FontWeight.Bold,
                            fontSize = 22.sp,
                        )
                        Text(
                            "${shown.size}/${state.servers.size}",
                            color = c.textSecondary,
                            fontSize = 12.sp,
                        )
                    }
                    GlassCapsule {
                        PlainIcon(Icons.Rounded.Search, stringResource(R.string.rdr_search_hint), {
                            searching = !searching
                            if (!searching) query = ""
                        })
                        PlainIcon(Icons.Rounded.Sort, stringResource(R.string.rdr_sort), { sortOpen = !sortOpen })
                        PlainIcon(Icons.Rounded.Speed, stringResource(R.string.rdr_test_all), actions.onTestAll, tint = c.accent)
                    }
                }
            }

            // ── جستجو ──
            AnimatedVisibility(searching && !selecting, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                GlassSearchField(
                    value = query,
                    onValueChange = { query = it },
                    hint = stringResource(R.string.rdr_search_hint),
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .focusRequester(focus),
                )
            }

            // ── مرتب‌سازی ──
            AnimatedVisibility(sortOpen && !selecting, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    IosSegmented(
                        options = listOf(
                            stringResource(R.string.rdr_sort_default),
                            stringResource(R.string.rdr_sort_ping),
                            stringResource(R.string.rdr_sort_name),
                        ),
                        selected = sortIndex,
                        onSelect = { sortIndex = it },
                    )
                }
            }

            // ── گروه‌ها / اشتراک‌ها ──
            if (state.groups.isNotEmpty() && !selecting) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item(key = "all") {
                        GroupChip(stringResource(R.string.rdr_all), state.servers.size, groupId.isEmpty()) { groupId = "" }
                    }
                    items(state.groups, key = { it.id }) { g ->
                        GroupChip(g.title, g.count, groupId == g.id) { groupId = g.id }
                    }
                }
            }

            // ── نوار عملیات چندتایی (همان آیکون‌های نوار قبلی) ──
            AnimatedVisibility(selecting, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Row(
                    Modifier
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .fillMaxWidth()
                        .glass(RoundedCornerShape(24.dp), shadow = 8.dp)
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlainIcon(Icons.Rounded.Delete, stringResource(R.string.rdr_delete), {
                        actions.onDelete(picked.toList()); picked = emptySet()
                    }, tint = c.red, size = 44.dp)
                    PlainIcon(Icons.Rounded.ContentCopy, stringResource(R.string.rdr_duplicate), {
                        actions.onDuplicate(picked.toList()); picked = emptySet()
                    }, size = 44.dp)
                    PlainIcon(Icons.Rounded.Speed, stringResource(R.string.rdr_test), {
                        actions.onTest(picked.toList())
                    }, size = 44.dp)
                    PlainIcon(Icons.Rounded.History, null, { actions.onClockAction(picked.toList()) }, size = 44.dp)
                    PlainIcon(Icons.Rounded.Edit, stringResource(R.string.rdr_edit), {
                        picked.firstOrNull()?.let(actions.onEdit); picked = emptySet()
                    }, enabled = picked.size == 1, size = 44.dp)
                }
            }

            // ── لیست ──
            if (shown.isEmpty()) {
                EmptyState(Modifier.weight(1f))
            } else {
                LazyColumn(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 150.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(shown, key = { it.id }) { s ->
                        ServerCard(
                            s = s,
                            selected = s.id == state.selectedServerId,
                            picked = s.id in picked,
                            selecting = selecting,
                            onClick = {
                                if (selecting) {
                                    picked = if (s.id in picked) picked - s.id else picked + s.id
                                } else {
                                    actions.onSelect(s.id)
                                    if (closeOnSelect) onBack()
                                }
                            },
                            onLongClick = { picked = picked + s.id },
                            onTest = { actions.onTest(listOf(s.id)) },
                            onEdit = { actions.onEdit(s.id) },
                            onShare = { actions.onShare(s.id) },
                            onDelete = { actions.onDelete(listOf(s.id)) },
                        )
                    }
                }
            }
        }

        // ── دکمه‌ی افزودن + منو ──
        if (addOpen) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = if (c.isDark) 0.45f else 0.22f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { addOpen = false },
            )
        }
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AnimatedVisibility(
                addOpen,
                enter = fadeIn() + scaleIn(initialScale = 0.7f, transformOrigin = TransformOrigin(0.5f, 1f)),
                exit = fadeOut() + scaleOut(targetScale = 0.7f, transformOrigin = TransformOrigin(0.5f, 1f)),
            ) {
                AddMenu(
                    onQr = { addOpen = false; actions.onAddQr() },
                    onClipboard = { addOpen = false; actions.onAddClipboard() },
                    onFile = { addOpen = false; actions.onAddFile() },
                    onSubscription = { addOpen = false; actions.onAddSubscription() },
                    onManual = { addOpen = false; actions.onAddManual() },
                )
            }
            Spacer(Modifier.height(12.dp))
            AddFab(open = addOpen) { addOpen = !addOpen }
        }
    }
}

// ───────────────────────── کارت سرور ─────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ServerCard(
    s: ServerUi,
    selected: Boolean,
    picked: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onTest: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    val c = LocalRdrColors.current
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.975f else 1f, spring(0.6f, 500f), label = "card")
    val shape = RoundedCornerShape(26.dp)
    val borderColor = when {
        picked -> c.accent
        selected -> c.accent.copy(alpha = 0.85f)
        else -> Color.Transparent
    }
    Column(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .glass(shape, strong = selected || picked, tint = if (selected || picked) c.accent else Color.Unspecified, shadow = 8.dp)
            .border(1.6.dp, borderColor, shape)
            .combinedClickable(interactionSource = src, indication = null, onClick = onClick, onLongClick = onLongClick)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FlagBadge(s.flag, 48.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    s.name,
                    color = c.text,
                    fontSize = 15.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    androidx.core.text.BidiFormatter.getInstance().unicodeWrap("${maskHost(s.host)}:${s.port}"),
                    color = c.textSecondary,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                )
                Spacer(Modifier.height(7.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProtocolChip(s.protocol)
                    Spacer(Modifier.width(8.dp))
                    PingBadge(s.pingMs, s.testing)
                }
            }
            Spacer(Modifier.width(8.dp))
            when {
                selecting -> SelectDot(picked)
                selected -> Icon(Icons.Rounded.CheckCircle, null, tint = c.accent, modifier = Modifier.size(26.dp))
            }
        }
        AnimatedVisibility(selected && !selecting, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Row(
                Modifier
                    .padding(top = 10.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                PlainIcon(Icons.Rounded.Speed, stringResource(R.string.rdr_test), onTest, tint = c.accent)
                PlainIcon(Icons.Rounded.Edit, stringResource(R.string.rdr_edit), onEdit)
                PlainIcon(Icons.Rounded.Share, stringResource(R.string.rdr_share), onShare)
                PlainIcon(Icons.Rounded.Delete, stringResource(R.string.rdr_delete), onDelete, tint = c.red)
            }
        }
    }
}

@Composable
private fun SelectDot(on: Boolean) {
    val c = LocalRdrColors.current
    Box(
        Modifier
            .size(24.dp)
            .background(if (on) c.accent else Color.Transparent, CircleShape)
            .border(1.5.dp, if (on) c.accent else c.textSecondary.copy(alpha = 0.6f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (on) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
    }
}

// ───────────────────────── اجزا ─────────────────────────

@Composable
private fun GroupChip(title: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    val c = LocalRdrColors.current
    Row(
        Modifier
            .bouncyClick(onClick = onClick)
            .then(
                if (selected) Modifier.background(c.accent, CircleShape)
                else Modifier.glass(CircleShape, shadow = 0.dp),
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            color = if (selected) Color.White else c.text,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "$count",
            color = if (selected) Color.White.copy(alpha = 0.8f) else c.textSecondary,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun GlassSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
) {
    val c = LocalRdrColors.current
    Row(
        modifier
            .fillMaxWidth()
            .glass(CircleShape, shadow = 0.dp)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = c.textSecondary, modifier = Modifier.size(20.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = c.text, fontSize = 16.sp, textAlign = TextAlign.Start),
            cursorBrush = SolidColor(c.accent),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) Text(hint, color = c.textSecondary, fontSize = 16.sp)
                    inner()
                }
            },
        )
        if (value.isNotEmpty()) {
            Icon(
                Icons.Rounded.Close,
                null,
                tint = c.textSecondary,
                modifier = Modifier
                    .size(20.dp)
                    .clickable { onValueChange("") },
            )
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    val c = LocalRdrColors.current
    Column(
        modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        RadarButton(RadarState.IDLE, onClick = {}, size = 150.dp)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.rdr_empty_title), color = c.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.rdr_empty_sub),
            color = c.textSecondary,
            fontSize = 13.5.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun AddFab(open: Boolean, onClick: () -> Unit) {
    val c = LocalRdrColors.current
    val rot by animateFloatAsState(if (open) 45f else 0f, spring(0.6f, 400f), label = "fab")
    Box(
        Modifier
            .bouncyClick(haptic = true, onClick = onClick)
            .size(62.dp)
            .glass(CircleShape, strong = true, tint = c.accent, shadow = 16.dp)
            .background(
                androidx.compose.ui.graphics.Brush.linearGradient(
                    listOf(c.accent.copy(alpha = 0.92f), c.accent.copy(alpha = 0.70f)),
                ),
                CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Rounded.Add,
            stringResource(R.string.rdr_add),
            tint = Color.White,
            modifier = Modifier
                .size(30.dp)
                .rotate(rot),
        )
    }
}

@Composable
private fun AddMenu(
    onQr: () -> Unit,
    onClipboard: () -> Unit,
    onFile: () -> Unit,
    onSubscription: () -> Unit,
    onManual: () -> Unit,
) {
    val c = LocalRdrColors.current
    Column(
        Modifier
            .padding(horizontal = 32.dp)
            .fillMaxWidth()
            .glass(RoundedCornerShape(28.dp), strong = true, shadow = 24.dp)
            .padding(vertical = 8.dp),
    ) {
        AddRow(Icons.Rounded.QrCodeScanner, c.accent, stringResource(R.string.rdr_add_qr), onQr)
        AddRow(Icons.Rounded.ContentPaste, c.green, stringResource(R.string.rdr_add_clipboard), onClipboard)
        AddRow(Icons.Rounded.FileOpen, c.amber, stringResource(R.string.rdr_add_file), onFile)
        AddRow(Icons.Rounded.RssFeed, Color(0xFFBF5AF2), stringResource(R.string.rdr_add_sub), onSubscription)
        AddRow(Icons.Rounded.Edit, c.gray, stringResource(R.string.rdr_add_manual), onManual)
    }
}

@Composable
private fun AddRow(icon: ImageVector, tint: Color, title: String, onClick: () -> Unit) {
    val c = LocalRdrColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(34.dp)
                .background(tint, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Text(title, color = c.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}
