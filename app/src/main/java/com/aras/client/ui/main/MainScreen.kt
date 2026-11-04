package com.aras.client.ui.main

import androidx.compose.foundation.clickable

import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import com.aras.client.ui.subscription.SubEditActivity
import com.aras.client.ui.radar.DpiTestActivity
import com.aras.client.ui.dns.DnsChangerActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.Image
import com.aras.client.ui.compose.AuroraStatusPill
import com.aras.client.ui.compose.AuroraSentinelRadar
import com.aras.client.ui.compose.AuroraRadarContact
import com.aras.client.ui.compose.AuroraLinkState
import com.aras.client.ui.compose.AuroraGlassCard
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.activity.compose.BackHandler
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.aras.client.AppConfig
import com.aras.client.R
import com.aras.client.dto.entities.ProfileItem
import com.aras.client.handler.MmkvManager
import com.aras.client.ui.compose.CulturalRadar
import com.aras.client.ui.compose.LocalDarkTheme
import com.aras.client.ui.compose.ThemeManager
import com.aras.client.ui.compose.QRCodeDialog
import com.aras.client.ui.rdr.RdrListShell
import com.aras.client.ui.rdr.RdrListTopBar
import com.aras.client.ui.rdr.RdrToolbarCapsule
import com.aras.client.ui.rdr.RdrMainHost
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable
fun MainScreen(
    mainViewModel: MainViewModel,
    onAction: (MainAction) -> Unit,
    onNavigate: (MainDestination) -> Unit,
) {
    val context = LocalContext.current
    val uiState by mainViewModel.uiState.collectAsStateWithLifecycle()
    val updateAnnouncement by mainViewModel.updateAnnouncementFlow.collectAsStateWithLifecycle()
    val groups = uiState.groups
    val isLoading by mainViewModel.isLoading.collectAsStateWithLifecycle()
    val isRunning = uiState.isRunning
    val barText = mainBarText(
        uiState,
        mainViewModel.formatStatus(uiState.status),
        mainViewModel.connectedServerTitle()
    )
    LifecycleResumeEffect(mainViewModel) {
        mainViewModel.setMainScreenActive(true)
        onPauseOrDispose { mainViewModel.setMainScreenActive(false) }
    }
    val selectedGuid = uiState.selectedGuid
    // Ping-aware bottom bar: green only for a successful positive delay.
    val hasPing = if (isRunning && selectedGuid != null) {
        (MmkvManager.decodeServerAffiliationInfo(selectedGuid)?.testDelayMillis ?: -1L) > 0L
    } else false
    val doubleColumnDisplay = uiState.doubleColumnDisplay
    val confirmRemove = uiState.confirmRemove
    val shareQRCodeBitmap = uiState.shareQRCodeBitmap
    val scrollToTopTick = uiState.scrollToTopTick

    val isDarkTheme = LocalDarkTheme.current
    val themeMode by ThemeManager.themeMode.collectAsStateWithLifecycle()
    val uiTheme by ThemeManager.uiTheme.collectAsStateWithLifecycle()
    var showMenu by remember { mutableStateOf(false) }
    // RADAR Glass theme ("rdrx"): home = radar, tapping the config card opens the normal list.
    var rdrShowList by remember { mutableStateOf(false) }
    BackHandler(enabled = (uiTheme == "rdrx" || uiTheme == "rdrn") && rdrShowList) { rdrShowList = false }
    val scope = rememberCoroutineScope()
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showDelAllConfirm by remember { mutableStateOf(false) }
    var showDelDuplicateConfirm by remember { mutableStateOf(false) }
    var showDelInvalidConfirm by remember { mutableStateOf(false) }
    var showRemoveConfirm by remember { mutableStateOf<String?>(null) }
    var autoScrollEnabled by remember {
        mutableStateOf(MmkvManager.decodeSettingsBool(AppConfig.PREF_AUTO_SCROLL_TO_TOP, true))
    }

    var shareTarget by remember { mutableStateOf<Triple<String, ProfileItem, Boolean>?>(null) }
    val removeServer: (String) -> Unit = { guid ->
        if (confirmRemove) showRemoveConfirm = guid else onAction(MainAction.RemoveServer(guid))
    }

    val pagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { groups.size.coerceAtLeast(1) }
    )

    val lazyListStates = remember { mutableStateMapOf<String, LazyListState>() }
    val lazyGridStates = remember { mutableStateMapOf<String, LazyGridState>() }

    LaunchedEffect(groups) {
        val validGroupIds = groups.map { it.id }.toSet()
        lazyListStates.keys.retainAll(validGroupIds)
        lazyGridStates.keys.retainAll(validGroupIds)
    }

    LaunchedEffect(uiState.selectedGroupId, groups.size) {
        // Keyed WITHOUT the groups list itself: every subscription update
        // regenerates the list (new object identity), which used to re-run
        // this effect and stack scrollToPage calls on top of each other,
        // deadlocking the pager and ANRing the app (issue: traffic stuck).
        if (groups.isEmpty()) return@LaunchedEffect
        val selectedIndex = groups.indexOfFirst { it.id == uiState.selectedGroupId }
            .takeIf { it >= 0 } ?: 0
        if (pagerState.settledPage != selectedIndex) {
            runCatching { pagerState.scrollToPage(selectedIndex) }
        }
    }

    val latestGroups by rememberUpdatedState(groups)

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { page ->
                val currentGroups = latestGroups
                if (page in currentGroups.indices) {
                    onAction(MainAction.SelectGroup(currentGroups[page].id))
                }
            }
    }

    val handleMore: (MainMoreMenuAction) -> Unit = { action ->
        when (action) {
            MainMoreMenuAction.RestartService -> onAction(MainAction.RestartService)
            MainMoreMenuAction.DeleteAll -> showDelAllConfirm = true
            MainMoreMenuAction.DeleteDuplicate -> showDelDuplicateConfirm = true
            MainMoreMenuAction.DeleteInvalid -> showDelInvalidConfirm = true
            MainMoreMenuAction.ExportAll -> onAction(MainAction.ExportAll)
            MainMoreMenuAction.ExportSubTxt -> onAction(MainAction.ExportGroupTxt)
            MainMoreMenuAction.LocateSelected -> onAction(MainAction.LocateSelectedServer)
            MainMoreMenuAction.SortByTestResults -> onAction(MainAction.SortByTestResults)
            MainMoreMenuAction.PingSelectedSubscription -> onAction(MainAction.PingSelectedSubscription)
            MainMoreMenuAction.TestAll -> onAction(MainAction.TestAllServers)
            MainMoreMenuAction.TestAllRealPing -> onAction(MainAction.TestRealAllServers)
            MainMoreMenuAction.DpiDiagnostics -> context.startActivity(Intent(context, DpiTestActivity::class.java))
            MainMoreMenuAction.UpdateSubscriptions -> onAction(MainAction.UpdateSubscriptions)
        }
    }
    val rdrTheme = uiTheme == "rdrx"
    val closeSearch: () -> Unit = {
        searchQuery = ""
        onAction(MainAction.Search(""))
        showSearch = false
    }

    MainDialogs(
        showDelAllConfirm = showDelAllConfirm,
        onDismissDelAll = { showDelAllConfirm = false },
        onConfirmDelAll = { showDelAllConfirm = false; onAction(MainAction.RemoveAllServers) },
        showDelDuplicateConfirm = showDelDuplicateConfirm,
        onDismissDelDuplicate = { showDelDuplicateConfirm = false },
        onConfirmDelDuplicate = { showDelDuplicateConfirm = false; onAction(MainAction.RemoveDuplicateServers) },
        showDelInvalidConfirm = showDelInvalidConfirm,
        onDismissDelInvalid = { showDelInvalidConfirm = false },
        onConfirmDelInvalid = { showDelInvalidConfirm = false; onAction(MainAction.RemoveInvalidServers) },
        showRemoveConfirm = showRemoveConfirm,
        onDismissRemove = { showRemoveConfirm = null },
        onConfirmRemove = { guid -> showRemoveConfirm = null; onAction(MainAction.RemoveServer(guid)) }
    )

    if (shareTarget != null) {
        val (guid, profile, more) = shareTarget!!
        ShareMethodDialog(
            guid = guid,
            profile = profile,
            more = more,
            onDismiss = { shareTarget = null },
            onAction = onAction,
            onRemove = removeServer,
        )
    }
    if (shareQRCodeBitmap != null) {
        QRCodeDialog(bitmap = shareQRCodeBitmap, onDismiss = { onAction(MainAction.DismissQRCodeDialog) })
    }

    MainMenuSheet(
        show = showMenu,
        onDismiss = { showMenu = false },
        onNavigate = onNavigate,
    )

    if (uiTheme == "rdrn" && !rdrShowList) {
        // RADAR VPN look: six tabs + hamburger menu; the normal config manager opens through onOpenList.
        com.aras.client.ui.rdrn.RnMainHost(
            uiState = uiState,
            hasPing = hasPing,
            onAction = onAction,
            onNavigate = onNavigate,
            onOpenList = { rdrShowList = true },
        )
        return
    }

    if (uiTheme == "rdrx" && !rdrShowList) {
        RdrMainHost(
            uiState = uiState,
            hasPing = hasPing,
            isDark = isDarkTheme,
            onAction = onAction,
            onNavigate = onNavigate,
            onOpenList = { rdrShowList = true },
            onOpenSearch = { showSearch = true; rdrShowList = true },
            onMoreAction = handleMore,
        )
        return
    }

    val listContent: @Composable () -> Unit = {
    Scaffold(
            containerColor = if (rdrTheme) Color.Transparent else MaterialTheme.colorScheme.background,
            contentWindowInsets = ScaffoldDefaults.contentWindowInsets,
            topBar = {
                if (rdrTheme) {
                    RdrListTopBar(
                        isLoading = isLoading,
                        showSearch = showSearch,
                        searchQuery = searchQuery,
                        onSearchQueryChange = { query: String ->
                            searchQuery = query
                            onAction(MainAction.Search(query))
                        },
                        onSearchToggle = { show: Boolean -> if (show) showSearch = true else closeSearch() },
                        onBack = { rdrShowList = false },
                        toolbar = {
                            RdrToolbarCapsule(
                                onSearch = { showSearch = true },
                                onImportAction = onAction,
                                onMoreAction = handleMore,
                            )
                        },
                    )
                } else MainTopBar(
                    isLoading = isLoading,
                    showSearch = showSearch,
                    searchQuery = searchQuery,
                    autoScrollEnabled = autoScrollEnabled,
                    onToggleAutoScroll = { enabled ->
                        autoScrollEnabled = enabled
                        MmkvManager.encodeSettings(AppConfig.PREF_AUTO_SCROLL_TO_TOP, enabled)
                    },
                    onSearchQueryChange = { query: String ->
                        searchQuery = query
                        onAction(MainAction.Search(query))
                    },
                    onSearchClose = {
                        searchQuery = ""
                        onAction(MainAction.Search(""))
                        showSearch = false
                    },
                    onSearchToggle = { show: Boolean -> showSearch = show },
                    onMenuClick = { showMenu = true },
                    onAction = onAction,
                    onMoreMenuAction = handleMore
                )
            },
            bottomBar = {
                if (!rdrTheme) Column {
                    if (isRunning) {
                        com.aras.client.ui.compose.ConnectionMiniStats(
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                    MainBottomBar(
                        displayText = barText.title,
                        subtitleOverride = if (uiState.status == MainStatus.Disconnected) null else barText.subtitle,
                        isRunning = isRunning,
                        isDarkTheme = isDarkTheme,
                        onAction = onAction,
                        hasPing = hasPing
                    )
                }
            },
            floatingActionButton = {},
        ) { innerPadding ->
            val layoutDirection = LocalLayoutDirection.current

            if (groups.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = innerPadding.calculateTopPadding())
                ) {
                    // Keep the ordinary app layout as the default. Special dashboards
                    // appear only when the user explicitly selects that UI theme.
                    when (uiTheme) {
                        "aurora" -> AuroraSentinelHero(
                            isRunning = isRunning,
                            selectedGuid = selectedGuid,
                            selectedServerTitle = mainViewModel.connectedServerTitle(),
                        )
                        "radar" -> RadarDashboard(
                            isRunning = isRunning,
                            hasPing = hasPing,
                            selectedGuid = selectedGuid,
                            isDarkTheme = isDarkTheme,
                            serverTitle = mainViewModel.connectedServerTitle(),
                        )
                        "survival" -> SurvivalFieldPanel(
                            isConnected = isRunning && hasPing,
                            serverTitle = mainViewModel.connectedServerTitle(),
                        )
                    }
                    updateAnnouncement?.let { announcement ->
                        UpdateAnnouncementBanner(
                            result = announcement,
                            onUpdateClick = {
                                onNavigate(MainDestination.CheckUpdate)
                            },
                            onDismiss = { mainViewModel.dismissUpdateAnnouncement() }
                        )
                    }
                    if (themeMode == "3") {
                        CulturalRadar(
                            hasConfigs = groups.isNotEmpty(),
                            isRunning = isRunning,
                            onImportClipboard = { onAction(MainAction.ImportClipboard) },
                            onScanQr = { onAction(MainAction.ImportQRcode) }
                        )
                    }
                    androidx.compose.material3.Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .clickable {
                                context.startActivity(Intent(context, DnsChangerActivity::class.java))
                            },
                        colors = androidx.compose.material3.CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        ),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("DNS", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Black)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("DNS CHANGER", fontWeight = FontWeight.Bold)
                                Text("Custom DNS • latency test • profiles", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text("›", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    if (groups.size > 1) {
                        GroupTabBar(
                            groups = groups,
                            selectedTabIndex = pagerState.currentPage.coerceIn(0, groups.lastIndex),
                            mainViewModel = mainViewModel,
                            onTabClick = { targetIndex ->
                                scope.launch {
                                    pagerState.navigateToPageOptimized(
                                        targetPage = targetIndex,
                                        animateAdjacentPage = true
                                    )
                                }
                            }
                        )
                    }

                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.weight(1f),
                        userScrollEnabled = true,
                        beyondViewportPageCount = 1,
                        key = { page -> groups.getOrNull(page)?.id ?: "group-page-$page" }
                    ) { page ->
                        val group = groups.getOrNull(page) ?: return@HorizontalPager

                        GroupPagerPage(
                            groupId = group.id,
                            mainViewModel = mainViewModel,
                            selectedGuid = selectedGuid,
                            locateTarget = uiState.locateTarget,
                            doubleColumnDisplay = doubleColumnDisplay,
                            confirmRemove = confirmRemove,
                            searchQuery = searchQuery,
                            scrollToTopTick = scrollToTopTick,
                            lazyListStates = lazyListStates,
                            lazyGridStates = lazyGridStates,
                            onSelectServer = { guid ->
                                onAction(MainAction.SelectServer(guid))
                                if (uiTheme == "rdrx" || uiTheme == "rdrn") rdrShowList = false
                            },
                            onEditServer = { guid, profile -> onAction(MainAction.EditServer(guid, profile)) },
                            onShareServer = { guid, profile ->
                                shareTarget = Triple(guid, profile, false)
                            },
                            onMoreServer = { guid, profile ->
                                shareTarget = Triple(guid, profile, true)
                            },
                            onRemoveServer = removeServer,
                            onTestServer = { guid -> onAction(MainAction.SingleTestServer(guid)) },
                            onEditSubscription = if (group.id.isEmpty()) null
                            else { -> context.startActivity(Intent(context, SubEditActivity::class.java).putExtra("subId", group.id)) },
                            onUpdateSubscription = if (group.id.isEmpty()) null
                            else { -> mainViewModel.importConfigViaSub(group.id) },
                            onPingSubscription = if (group.id.isEmpty()) null
                            else { { onAction(MainAction.PingSelectedSubscription) } },
                            onRemoveSubscription = if (group.id.isEmpty() ||
                                group.id == com.aras.client.handler.FreeSubManager.FREE_SUB_ID
                            ) null else { -> mainViewModel.removeSubscription(group.id) },
                            onCopySubscriptionUrl = if (group.id.isEmpty()) null
                            else { -> mainViewModel.copySubscriptionUrl(group.id) },
                            isProtectedSubscription = group.id == com.aras.client.handler.FreeSubManager.FREE_SUB_ID,
                            contentPadding = PaddingValues(
                                start = 12.dp,
                                top = 4.dp,
                                end = 12.dp,
                                bottom = 88.dp
                            )
                        )
                    }
                }
            } else if (themeMode == "3" && !isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = androidx.compose.ui.Alignment.Center
                ) {
                    CulturalRadar(
                        hasConfigs = false,
                        isRunning = false,
                        onImportClipboard = { onAction(MainAction.ImportClipboard) },
                        onScanQr = { onAction(MainAction.ImportQRcode) },
                        modifier = Modifier.padding(bottom = 48.dp)
                    )
                }
            }
        }
    }
    if (rdrTheme) RdrListShell(isDarkTheme, listContent) else listContent()
}

@Composable
private fun AuroraSentinelHero(
    isRunning: Boolean,
    selectedGuid: String?,
    selectedServerTitle: String,
) {
    val pingMs = selectedGuid?.let { guid ->
        MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis
    }?.takeIf { it > 0L }?.toInt()

    // "Connected" is shown only when the existing app state says the VPN is running
    // and the selected profile has a successful positive ping. Running without a
    // successful test is shown as VERIFYING, not falsely labelled healthy.
    val state = when {
        !isRunning -> AuroraLinkState.OFF
        pingMs != null -> AuroraLinkState.CONNECTED
        else -> AuroraLinkState.CONNECTING
    }
    val statusLabel = when (state) {
        AuroraLinkState.OFF -> "NO SIGNAL"
        AuroraLinkState.CONNECTING -> "VERIFYING PROFILE"
        AuroraLinkState.CONNECTED -> "PROFILE RESPONDING"
        AuroraLinkState.DANGER -> "LINK FAILURE"
    }
    val statusSubtitle = when (state) {
        AuroraLinkState.OFF -> "VPN DISCONNECTED"
        AuroraLinkState.CONNECTING -> "WAITING FOR A VALID PING"
        AuroraLinkState.CONNECTED -> "PROFILE RESPONSE CONFIRMED"
        AuroraLinkState.DANGER -> "CONNECTION NEEDS ATTENTION"
    }

    AuroraGlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "A U R O R A   /   S E N T I N E L",
                    color = Color(0xFF91A39A),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.7.sp
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "TUNNEL RADAR",
                    color = Color(0xFFF1F8F4),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.5.sp
                )
            }
            AuroraStatusPill(label = statusLabel, state = state)
        }
        Spacer(Modifier.height(2.dp))
        AuroraSentinelRadar(
            state = state,
            contacts = if (selectedGuid != null && pingMs != null) {
                listOf(
                    AuroraRadarContact(
                        id = selectedGuid,
                        pingMs = pingMs,
                        profileResponding = true,
                        tested = true
                    )
                )
            } else {
                emptyList()
            },
            centerLabel = if (state == AuroraLinkState.OFF) "no signal" else {
                pingMs?.let { "$it MS" } ?: "SCANNING"
            },
            centerSubtitle = statusSubtitle,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
        )
        Spacer(Modifier.height(8.dp))
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "SELECTED PROFILE",
                    color = Color(0xFF91A39A),
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.8.sp
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = selectedServerTitle.ifBlank { "No profile selected" },
                    color = Color(0xFFE5F4EC),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
            Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                Text(
                    text = "PROFILE PING",
                    color = Color(0xFF91A39A),
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.8.sp
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = pingMs?.let { "$it ms" } ?: "--",
                    color = when {
                        !isRunning -> Color(0xFF77827F)
                        pingMs != null -> Color(0xFF55FFC0)
                        else -> Color(0xFFFFC56E)
                    },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}
