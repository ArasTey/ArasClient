package com.aras.client.ui.rdr

import android.content.Intent
import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.aras.client.R
import com.aras.client.ui.main.ImportMenuContent
import com.aras.client.ui.main.MainAction
import com.aras.client.ui.main.MainMoreMenuAction
import com.aras.client.ui.main.MoreMenuContent
import com.aras.client.ui.radar.RadarConfigActivity

/** پاپ‌آپ زیر دکمه، هم‌تراز با لبه‌ی چپِ لنگر (کپسول سمت چپ نوار بالا). */
private object BelowAnchorStart : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = anchorBounds.left.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val y = (anchorBounds.bottom - 6).coerceAtMost((windowSize.height - popupContentSize.height).coerceAtLeast(0))
        return IntOffset(x, y)
    }
}

/** منوی بازشونده‌ی شیشه‌ای که محتوای آن همان آیتم‌های واقعی برنامه است. */
@Composable
fun RdrGlassPopup(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val appDir = if (LocalContext.current.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
        LayoutDirection.Rtl
    } else {
        LayoutDirection.Ltr
    }
    val visible = remember { MutableTransitionState(false).apply { targetState = true } }
    Popup(
        popupPositionProvider = BelowAnchorStart,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides appDir) {
            AnimatedVisibility(
                visibleState = visible,
                enter = fadeIn(tween(140)) + scaleIn(
                    initialScale = 0.86f,
                    transformOrigin = TransformOrigin(0f, 0f),
                    animationSpec = spring(dampingRatio = 0.72f, stiffness = 520f),
                ),
            ) {
                Column(
                    Modifier
                        .padding(10.dp)
                        .width(272.dp)
                        .heightIn(max = 520.dp)
                        .glass(RoundedCornerShape(24.dp), strong = true, shadow = 18.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 6.dp),
                    content = content,
                )
            }
        }
    }
}

/**
 * کپسول شیشه‌ایِ ابزار (همگام‌سازی / جستجو / + / ⋮).
 * منوهای «+» و «⋮» دقیقاً همان منوهای واقعی برنامه‌اند (ImportMenuContent و MoreMenuContent).
 */
@Composable
fun RdrToolbarCapsule(
    onSearch: () -> Unit,
    onImportAction: (MainAction) -> Unit,
    onMoreAction: (MainMoreMenuAction) -> Unit,
    modifier: Modifier = Modifier,
    onSync: (() -> Unit)? = null,
) {
    val c = LocalRdrColors.current
    val context = LocalContext.current
    var showAdd by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    Box(modifier) {
        GlassCapsule {
            if (onSync != null) {
                PlainIcon(Icons.Rounded.Sync, stringResource(R.string.rdr_update_subs), onSync, tint = c.accent, size = 36.dp)
            }
            PlainIcon(Icons.Rounded.Search, stringResource(R.string.rdr_search_hint), onSearch, size = 36.dp)
            PlainIcon(Icons.Rounded.Add, stringResource(R.string.rdr_add), { showAdd = true }, size = 36.dp)
            PlainIcon(Icons.Rounded.MoreVert, stringResource(R.string.rdr_more), { showMore = true }, size = 36.dp)
        }
        if (showAdd) {
            RdrGlassPopup(onDismiss = { showAdd = false }) {
                ImportMenuContent(
                    onAction = { action ->
                        showAdd = false
                        onImportAction(action)
                    },
                    onOpenDnsChanger = {
                        showAdd = false
                        context.startActivity(Intent(context, com.aras.client.ui.dns.DnsChangerActivity::class.java))
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.rdr_config_radar)) },
                    onClick = {
                        showAdd = false
                        context.startActivity(Intent(context, RadarConfigActivity::class.java))
                    },
                )
            }
        }
        if (showMore) {
            RdrGlassPopup(onDismiss = { showMore = false }) {
                MoreMenuContent { action ->
                    showMore = false
                    onMoreAction(action)
                }
            }
        }
    }
}
