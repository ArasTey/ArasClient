package com.aras.client.ui.main

import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.aras.client.dto.entities.ProfileItem
import com.aras.client.R
import com.aras.client.ui.compose.LocalAppSnackbar
import com.aras.client.ui.compose.QRCodeDialog
import com.aras.client.ui.compose.ToastType
import com.aras.client.ui.subscription.SubEditActivity
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
    val isConnecting = uiState.isConnecting
    // The name is only needed while connecting; read it once per selection, not per
    // recomposition tick of the stats row.
    val connectingServerName = remember(uiState.selectedGuid, isConnecting) {
        if (isConnecting) mainViewModel.connectedServerTitle() else ""
    }
    val connectingTitle = stringResource(R.string.connection_connecting)
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val egressCountryName = remember(uiState.egressCountry, configuration.locales) {
        com.aras.client.util.CountryResolver.displayName(
            uiState.egressCountry,
            configuration.locales[0]
        ).orEmpty()
    }
    val barText = mainBarText(
        uiState,
        mainViewModel.formatStatus(uiState.status),
        mainViewModel.connectedServerTitle(),
        egressCountryName
    )
    LifecycleResumeEffect(mainViewModel) {
        mainViewModel.setMainScreenActive(true)
        onPauseOrDispose { mainViewModel.setMainScreenActive(false) }
    }
    val selectedGuid = uiState.selectedGuid
    val doubleColumnDisplay = uiState.doubleColumnDisplay
    val confirmRemove = uiState.confirmRemove
    val shareQRCodeBitmap = uiState.shareQRCodeBitmap
    val scrollToTopTick = uiState.scrollToTopTick

    var showMenu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showDelAllConfirm by remember { mutableStateOf(false) }
    var showDelDuplicateConfirm by remember { mutableStateOf(false) }
    var showDelInvalidConfirm by remember { mutableStateOf(false) }
    var showRemoveConfirm by remember { mutableStateOf<String?>(null) }

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

    // Free configs support a manual delete; offer an immediate Undo and a
    // persistent restore entry in the ⋮ menu.
    val snackbar = LocalAppSnackbar.current
    val deletedMsg = stringResource(R.string.toast_free_config_deleted)
    val undoLabel = stringResource(R.string.action_undo)
    LaunchedEffect(snackbar) {
        mainViewModel.lastRemovedFree.collect {
            snackbar.show(deletedMsg, ToastType.NORMAL, true, undoLabel) {
                scope.launch { mainViewModel.undoRemoveFree() }
            }
        }
    }

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

    Scaffold(
            contentWindowInsets = ScaffoldDefaults.contentWindowInsets,
            topBar = {
                MainTopBar(
                    isLoading = isLoading,
                    showSearch = showSearch,
                    searchQuery = searchQuery,
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
                    onMoreMenuAction = { action ->
                        when (action) {
                            MainMoreMenuAction.RestartService -> onAction(MainAction.RestartService)
                            MainMoreMenuAction.DeleteAll -> showDelAllConfirm = true
                            MainMoreMenuAction.DeleteDuplicate -> showDelDuplicateConfirm = true
                            MainMoreMenuAction.DeleteInvalid -> showDelInvalidConfirm = true
                            MainMoreMenuAction.ExportAll -> onAction(MainAction.ExportAll)
                            MainMoreMenuAction.ExportSubTxt -> onAction(MainAction.ExportGroupTxt)
                            MainMoreMenuAction.LocateSelected -> onAction(MainAction.LocateSelectedServer)
                            MainMoreMenuAction.SortByTestResults -> onAction(MainAction.SortByTestResults)
                            MainMoreMenuAction.TestAll -> onAction(MainAction.TestAllServers)
                            MainMoreMenuAction.TestAllRealPing -> onAction(MainAction.TestRealAllServers)
                            MainMoreMenuAction.UpdateSubscriptions -> onAction(MainAction.UpdateSubscriptions)
                            MainMoreMenuAction.RestoreFreeConfigs -> {
                                scope.launch { mainViewModel.restoreAllFreeConfigs() }
                            }
                        }
                    }
                )
            },
            bottomBar = {
                // The connect panel keeps a fixed type scale: a big system font
                // would otherwise wrap the config name and squeeze the stats.
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density = density.density, fontScale = 1f)
                ) {
                    MainBottomBar(
                        displayText = if (isConnecting) connectingTitle else barText.title,
                        subtitleOverride = when {
                            isConnecting -> connectingServerName
                            uiState.status == MainStatus.Disconnected -> null
                            else -> barText.subtitle
                        },
                        subtitleIp = if (isConnecting) null else barText.subtitleIp,
                        isRunning = isRunning,
                        isConnecting = isConnecting,
                        onAction = onAction
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
                    updateAnnouncement?.let { announcement ->
                        UpdateAnnouncementBanner(
                            result = announcement,
                            onUpdateClick = {
                                onNavigate(MainDestination.CheckUpdate)
                            },
                            onDismiss = { mainViewModel.dismissUpdateAnnouncement() }
                        )
                    }
                    if (groups.size > 1) {
                        GroupTabBar(
                            groups = groups,
                            selectedTabIndex = pagerState.currentPage.coerceIn(0, groups.lastIndex),
                            mainViewModel = mainViewModel,
                            onTabClick = { targetIndex ->
                                scope.launch {
                                    pagerState.navigateToPageOptimized(targetIndex)
                                }
                            }
                        )
                    }

                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
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
                            onSelectServer = { guid -> onAction(MainAction.SelectServer(guid)) },
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
                                bottom = 120.dp
                            )
                        )
                    }
                }
            }
        }
}
