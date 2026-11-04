package com.aras.client.ui.main

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import androidx.compose.material3.TextButton
import com.aras.client.ui.radar.RadarConfigActivity
import com.aras.client.ui.dns.DnsChangerActivity
import com.aras.client.ui.dns.DnsScannerActivity
import com.aras.client.enums.EConfigType
import com.aras.client.ui.server.ServerAetherActivity
import com.aras.client.ui.zx.ZxUiActivity
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.aras.client.R
import com.aras.client.ui.compose.AppTopBar

@Composable
fun MainTopBar(
    isLoading: Boolean,
    showSearch: Boolean,
    searchQuery: String,
    autoScrollEnabled: Boolean,
    onToggleAutoScroll: (Boolean) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSearchClose: () -> Unit,
    onSearchToggle: (Boolean) -> Unit,
    onMenuClick: () -> Unit,
    onAction: (MainAction) -> Unit,
    onMoreMenuAction: (MainMoreMenuAction) -> Unit
) {
    val context = LocalContext.current
    var showImportMenu by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    AnchoredDropdown(
        expanded = showImportMenu,
        onDismiss = { showImportMenu = false }
    ) {
        ImportMenuContent(
            onAction = { action ->
                showImportMenu = false
                onAction(action)
            },
            onOpenDnsChanger = {
                showImportMenu = false
                context.startActivity(Intent(context, DnsChangerActivity::class.java))
            },
            onOpenDnsScanner = {
                showImportMenu = false
                context.startActivity(Intent(context, DnsScannerActivity::class.java))
            },
            onOpenPsiphonCdn = {
                showImportMenu = false
                context.startActivity(Intent(context, ServerAetherActivity::class.java).apply {
                    putExtra("createConfigType", EConfigType.AETHER.value)
                    putExtra("presetPsiphonCdn", true)
                })
            }
        )
        TextButton(onClick = {
            showImportMenu = false
            context.startActivity(Intent(context, RadarConfigActivity::class.java))
        }) {
            Text("رادار کانفیگ")
        }
    }
    AnchoredDropdown(
        expanded = showMenu,
        onDismiss = { showMenu = false }
    ) {
        MoreMenuContent { action ->
            showMenu = false
            onMoreMenuAction(action)
        }
    }

    AppTopBar(
        title = stringResource(R.string.app_name),
        titleContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "RADAR ",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "VPN",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        onBackClick = {},
        isLoading = isLoading,
        isSearchActive = showSearch,
        searchQuery = searchQuery,
        onSearchQueryChange = onSearchQueryChange,
        onSearchClose = onSearchClose,
        searchPlaceholder = stringResource(R.string.menu_item_search),
        navigationIcon = {
            if (showSearch) {
                IconButton(onClick = onSearchClose) {
                    Icon(painterResource(R.drawable.ic_arrow_back_24dp), contentDescription = stringResource(R.string.acc_back))
                }
            } else {
                IconButton(onClick = onMenuClick) {
                    Icon(painterResource(R.drawable.ic_menu_24dp), contentDescription = stringResource(R.string.acc_open_menu))
                }
            }
        },
        actions = {
            if (!showSearch) {
                TextButton(onClick = { context.startActivity(Intent(context, ZxUiActivity::class.java)) }) {
                    Text("ZX UI")
                }
                IconButton(onClick = { onToggleAutoScroll(!autoScrollEnabled) }) {
                    Icon(
                        painterResource(R.drawable.ic_autoscroll_24dp),
                        contentDescription = stringResource(R.string.acc_auto_scroll),
                        tint = if (autoScrollEnabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { onSearchToggle(true) }) {
                    Icon(painterResource(R.drawable.ic_search_24dp), contentDescription = stringResource(R.string.acc_search))
                }
            }
            IconButton(onClick = { showImportMenu = true }) {
                Icon(painterResource(R.drawable.ic_add_24dp), contentDescription = stringResource(R.string.acc_add))
            }
            IconButton(onClick = { showMenu = true }) {
                Icon(painterResource(R.drawable.ic_more_vert_24dp), contentDescription = stringResource(R.string.acc_more))
            }
        }
    )
}
