package com.aras.client.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.first
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aras.client.AppConfig
import com.aras.client.R
import com.aras.client.dto.LocateTarget
import com.aras.client.dto.entities.ProfileItem
import com.aras.client.dto.entities.ServersCache
import com.aras.client.extension.isComplexType
import com.aras.client.extension.nullIfBlank
import com.aras.client.handler.AngConfigManager
import com.aras.client.handler.ArasExportImportManager
import com.aras.client.handler.MmkvManager
import com.aras.client.ui.compose.ReorderableGridItem
import com.aras.client.ui.compose.ReorderableListItem
import com.aras.client.ui.compose.colorConfigType
import com.aras.client.ui.compose.colorPing
import com.aras.client.ui.compose.colorPingRed
import com.aras.client.ui.compose.verticalScrollbar
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlin.math.abs

@Composable
fun GroupPagerPage(
    groupId: String,
    mainViewModel: MainViewModel,
    selectedGuid: String?,
    locateTarget: LocateTarget?,
    doubleColumnDisplay: Boolean,
    confirmRemove: Boolean,
    searchQuery: String,
    scrollToTopTick: Int,
    lazyListStates: MutableMap<String, LazyListState>,
    lazyGridStates: MutableMap<String, LazyGridState>,
    onSelectServer: (String) -> Unit,
    onEditServer: (String, ProfileItem) -> Unit,
    onShareServer: (String, ProfileItem) -> Unit,
    onMoreServer: (String, ProfileItem) -> Unit,
    onRemoveServer: (String) -> Unit,
    onTestServer: (String) -> Unit,
    contentPadding: PaddingValues
) {
    val serverFlow = remember(groupId) {
        mainViewModel.serversForGroup(groupId)
    }
    val servers by serverFlow.collectAsStateWithLifecycle()
    val canReorder = groupId.isNotEmpty() && searchQuery.isEmpty()
    Column {
        SubscriptionInfoBar(groupId = groupId)
        ServerListPage(
        servers = servers,
        selectedGuid = selectedGuid,
        locateTarget = locateTarget?.takeIf { it.groupId == groupId },
        canReorder = canReorder,
        doubleColumnDisplay = doubleColumnDisplay,
        subscriptionId = groupId,
        confirmRemove = confirmRemove,
        groupId = groupId,
        scrollToTopTick = scrollToTopTick,
        lazyListStates = lazyListStates,
        lazyGridStates = lazyGridStates,
        onSelectServer = onSelectServer,
        onEditServer = onEditServer,
        onShareServer = onShareServer,
        onMoreServer = onMoreServer,
        onRemoveServer = onRemoveServer,
        onTestServer = onTestServer,
        onLocateHandled = { mainViewModel.onAction(MainAction.LocateHandled) },
        onMoveServer = { fromIndex, toIndex -> mainViewModel.moveServer(groupId, fromIndex, toIndex) },
        contentPadding = contentPadding
        )
    }
}

@Composable
private fun ServerListPage(
    servers: List<ServersCache>,
    selectedGuid: String?,
    locateTarget: LocateTarget?,
    canReorder: Boolean,
    doubleColumnDisplay: Boolean,
    subscriptionId: String,
    confirmRemove: Boolean,
    groupId: String,
    scrollToTopTick: Int,
    lazyListStates: MutableMap<String, LazyListState>,
    lazyGridStates: MutableMap<String, LazyGridState>,
    onSelectServer: (String) -> Unit,
    onEditServer: (String, ProfileItem) -> Unit,
    onShareServer: (String, ProfileItem) -> Unit,
    onMoreServer: (String, ProfileItem) -> Unit,
    onRemoveServer: (String) -> Unit,
    onTestServer: (String) -> Unit,
    onLocateHandled: () -> Unit,
    onMoveServer: (Int, Int) -> Unit,
    contentPadding: PaddingValues
) {
    if (doubleColumnDisplay) {
        val gridState = remember(groupId) {
            lazyGridStates.getOrPut(groupId) { LazyGridState() }
        }
        val reorderableGridState = if (canReorder) {
            rememberReorderableLazyGridState(gridState) { from, to ->
                onMoveServer(from.index, to.index)
            }
        } else null

        LocateTargetEffect(locateTarget, servers, gridState, onLocateHandled)
        ScrollToTopEffect(scrollToTopTick, gridState)

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            state = gridState,
            modifier = Modifier
                .fillMaxSize()
                .verticalScrollbar(gridState),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = contentPadding
        ) {
            itemsIndexed(items = servers, key = { _, item -> item.guid }, contentType = { _, _ -> "server" }) { _, serverCache ->
                val content: @Composable () -> Unit = {
                    ServerItemColumn(
                        serverCache = serverCache,
                        selectedGuid = selectedGuid,
                        subscriptionId = subscriptionId,
                        doubleColumnDisplay = true,
                        onSelectServer = onSelectServer,
                        onEditServer = onEditServer,
                        onShareServer = onShareServer,
                        onMoreServer = onMoreServer,
                        onRemoveServer = onRemoveServer,
                        onTestServer = onTestServer
                    )
                }
                if (canReorder && reorderableGridState != null) {
                    ReorderableItem(
                        reorderableGridState,
                        key = serverCache.guid
                    ) { isDragging ->
                        ReorderableGridItem(
                            scope = this,
                            isDragging = isDragging
                        ) { content() }
                    }
                } else {
                    content()
                }
            }
        }
    } else {
        val listState = remember(groupId) {
            lazyListStates.getOrPut(groupId) { LazyListState() }
        }
        val reorderableState = if (canReorder) {
            rememberReorderableLazyListState(listState) { from, to ->
                onMoveServer(from.index, to.index)
            }
        } else null

        LocateTargetEffect(locateTarget, servers, listState, onLocateHandled)
        ScrollToTopEffect(scrollToTopTick, listState)

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .verticalScrollbar(listState),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = contentPadding
        ) {
            itemsIndexed(items = servers, key = { _, item -> item.guid }, contentType = { _, _ -> "server" }) { _, serverCache ->
                if (canReorder && reorderableState != null) {
                    ReorderableItem(
                        reorderableState,
                        key = serverCache.guid
                    ) { isDragging ->
                        ReorderableListItem(
                            scope = this,
                            isDragging = isDragging
                        ) {
                            ServerItemRow(
                                serverCache = serverCache,
                                selectedGuid = selectedGuid,
                                subscriptionId = subscriptionId,
                                onSelectServer = onSelectServer,
                                onEditServer = onEditServer,
                                onShareServer = onShareServer,
                                onMoreServer = onMoreServer,
                                onRemoveServer = onRemoveServer,
                                onTestServer = onTestServer
                            )
                        }
                    }
                } else {
                    ServerItemRow(
                        serverCache = serverCache,
                        selectedGuid = selectedGuid,
                        subscriptionId = subscriptionId,
                        onSelectServer = onSelectServer,
                        onEditServer = onEditServer,
                        onShareServer = onShareServer,
                        onMoreServer = onMoreServer,
                        onRemoveServer = onRemoveServer,
                        onTestServer = onTestServer
                    )
                }
            }
        }
    }
}

@Composable
private fun ScrollToTopEffect(scrollToTopTick: Int, state: LazyListState) {
    LaunchedEffect(scrollToTopTick) {
        // Wait for the user to release the list; never fight an active scroll.
        state.waitForUserScrollToEnd()
        if (scrollToTopTick > 0 && state.firstVisibleItemIndex > 0) {
            state.animateScrollToItem(0)
        }
    }
}

@Composable
private fun ScrollToTopEffect(scrollToTopTick: Int, state: LazyGridState) {
    LaunchedEffect(scrollToTopTick) {
        state.waitForUserScrollToEnd()
        if (scrollToTopTick > 0 && state.firstVisibleItemIndex > 0) {
            state.animateScrollToItem(0)
        }
    }
}

private suspend fun LazyListState.waitForUserScrollToEnd() {
    snapshotFlow { isScrollInProgress }.first { !it }
}

private suspend fun LazyGridState.waitForUserScrollToEnd() {
    snapshotFlow { isScrollInProgress }.first { !it }
}

@Composable
private fun LocateTargetEffect(
    target: LocateTarget?,
    servers: List<ServersCache>,
    state: LazyListState,
    onHandled: () -> Unit,
) {
    if (target == null) return
    LaunchedEffect(target, servers) {
        val index = servers.indexOfFirst { it.guid == target.serverGuid }
        if (index < 0) return@LaunchedEffect
        state.scrollToItem(index, -state.layoutInfo.viewportSize.height / 3)
        onHandled()
    }
}

@Composable
private fun LocateTargetEffect(
    target: LocateTarget?,
    servers: List<ServersCache>,
    state: LazyGridState,
    onHandled: () -> Unit,
) {
    if (target == null) return
    LaunchedEffect(target, servers) {
        val index = servers.indexOfFirst { it.guid == target.serverGuid }
        if (index < 0) return@LaunchedEffect
        state.scrollToItem(index, -state.layoutInfo.viewportSize.height / 3)
        onHandled()
    }
}

@Composable
private fun ServerItemRow(
    serverCache: ServersCache,
    selectedGuid: String?,
    subscriptionId: String,
    onSelectServer: (String) -> Unit,
    onEditServer: (String, ProfileItem) -> Unit,
    onShareServer: (String, ProfileItem) -> Unit,
    onMoreServer: (String, ProfileItem) -> Unit,
    onRemoveServer: (String) -> Unit,
    onTestServer: (String) -> Unit
) {
    val profile = serverCache.profile
    val subRemarks = if (subscriptionId.isEmpty()) {
        MmkvManager.decodeSubscription(profile.subscriptionId)?.remarks?.firstOrNull()
            ?.toString() ?: ""
    } else ""
    val isProtected = ArasExportImportManager.isProtected(serverCache.guid)
    val protectedDescription = stringResource(R.string.protected_config_hidden)

    ServerListItem(
        remarks = profile.remarks,
        statistics = if (ArasExportImportManager.isProtected(serverCache.guid))
            protectedDescription
        else
            profile.description.nullIfBlank()
                ?: AngConfigManager.generateDescription(profile),
        typeDescription = getProtocolDescription(profile),
        configTypeName = profile.configType.name,
        testDelayMillis = serverCache.testDelayMillis,
        isSelected = serverCache.guid == selectedGuid,
        subscriptionRemarks = subRemarks,
        doubleColumnDisplay = false,
        onClick = { onSelectServer(serverCache.guid) },
        onShare = { onShareServer(serverCache.guid, profile) },
        onEdit = { onEditServer(serverCache.guid, profile) },
        onRemove = { onRemoveServer(serverCache.guid) },
        onMore = { onMoreServer(serverCache.guid, profile) },
        onTest = { onTestServer(serverCache.guid) },
        isProtected = ArasExportImportManager.isProtected(serverCache.guid)
    )
}

@Composable
private fun ServerItemColumn(
    serverCache: ServersCache,
    selectedGuid: String?,
    subscriptionId: String,
    doubleColumnDisplay: Boolean,
    onSelectServer: (String) -> Unit,
    onEditServer: (String, ProfileItem) -> Unit,
    onShareServer: (String, ProfileItem) -> Unit,
    onMoreServer: (String, ProfileItem) -> Unit,
    onRemoveServer: (String) -> Unit,
    onTestServer: (String) -> Unit
) {
    val profile = serverCache.profile
    val subRemarks = if (subscriptionId.isEmpty()) {
        MmkvManager.decodeSubscription(profile.subscriptionId)?.remarks?.firstOrNull()?.toString() ?: ""
    } else ""
    ServerListItem(
        remarks = profile.remarks,
        statistics = profile.description.nullIfBlank() ?: AngConfigManager.generateDescription(profile),
        typeDescription = getProtocolDescription(profile),
        configTypeName = profile.configType.name,
        testDelayMillis = serverCache.testDelayMillis,
        isSelected = serverCache.guid == selectedGuid,
        subscriptionRemarks = subRemarks,
        doubleColumnDisplay = doubleColumnDisplay,
        onClick = { onSelectServer(serverCache.guid) },
        onEdit = { onEditServer(serverCache.guid, profile) },
        onShare = { onShareServer(serverCache.guid, profile) },
        onRemove = { onRemoveServer(serverCache.guid) },
        onMore = { onMoreServer(serverCache.guid, profile) },
        onTest = { onTestServer(serverCache.guid) }
    )
}

@Composable
fun ServerListItem(
    remarks: String,
    statistics: String,
    typeDescription: String,
    configTypeName: String,
    testDelayMillis: Long,
    isSelected: Boolean,
    subscriptionRemarks: String,
    doubleColumnDisplay: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onRemove: () -> Unit,
    onMore: () -> Unit,
    onTest: () -> Unit,
    modifier: Modifier = Modifier,
    dragModifier: Modifier = Modifier,
    isProtected: Boolean = false
) {
    val showDelayChips = remember {
        MmkvManager.decodeSettingsBool(AppConfig.PREF_SHOW_DELAY_CHIPS, true)
    }
    val testResult = if (testDelayMillis == 0L || !showDelayChips) {
        ""
    } else {
        stringResource(R.string.server_test_delay_value, testDelayMillis)
    }
    val selectedStateDescription = if (isSelected) {
        stringResource(R.string.acc_selected_server)
    } else {
        null
    }
    val cardShape = RoundedCornerShape(16.dp)
    val cardColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceContainerLow
    val pingColor = if (testDelayMillis < 0L) colorPingRed else colorPing

    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                if (selectedStateDescription != null) {
                    stateDescription = selectedStateDescription
                }
            }
            .clip(cardShape)
            .background(cardColor)
            .then(
                if (isSelected) Modifier.border(
                    1.5.dp,
                    MaterialTheme.colorScheme.primary,
                    cardShape
                ) else Modifier
            )
            .clickable(onClick = onClick)
            .then(dragModifier)
            .padding(start = 10.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {

        // Title, address, chips
        Column(
            Modifier
                .weight(1f)
        ) {
            Text(
                remarks,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    lineBreak = LineBreak.Paragraph
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (subscriptionRemarks.isNotBlank()) {
                    Text(
                        "[$subscriptionRemarks] ",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1
                    )
                }
                Text(
                    statistics,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(5.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    typeDescription,
                    style = MaterialTheme.typography.labelSmall,
                    color = colorConfigType,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(colorConfigType.copy(alpha = 0.13f))
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                )
                if (testResult.isNotBlank()) {
                    Text(
                        testResult,
                        style = MaterialTheme.typography.labelSmall,
                        color = pingColor,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(pingColor.copy(alpha = 0.13f))
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    )
                }
            }
        }

        Spacer(Modifier.size(4.dp))

        // Actions
        Column(horizontalAlignment = Alignment.End) {
            Row {
                if (doubleColumnDisplay) {
                    CircularActionIcon(R.drawable.ic_more_vert_24dp, stringResource(R.string.acc_more), onMore)
                } else {
                    if (!isProtected) {
                        CircularActionIcon(R.drawable.ic_share_24dp, stringResource(R.string.title_configuration_share), onShare)
                        CircularActionIcon(R.drawable.ic_edit_24dp, stringResource(R.string.acc_edit), onEdit)
                    }
                    CircularActionIcon(R.drawable.ic_delete_24dp, stringResource(R.string.acc_delete), onRemove)
                }
            }
            // Single-server test button under the delete icon
            CircularActionIcon(
                R.drawable.ic_speedtest_24dp,
                stringResource(R.string.title_test_single),
                onTest,
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun CircularActionIcon(
    drawableRes: Int,
    contentDesc: String,
    onClick: () -> Unit,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
    ) {
        Icon(
            painterResource(drawableRes),
            contentDescription = contentDesc,
            modifier = Modifier.size(18.dp),
            tint = tint
        )
    }
}

private fun getProtocolDescription(profile: ProfileItem): String {
    if (profile.configType.isComplexType()) return profile.configType.name
    val parts = mutableListOf(profile.configType.name)
    profile.network?.let { net ->
        if (net.isNotBlank() && !net.equals("tcp", ignoreCase = true)) parts.add(net)
    }
    profile.security?.let { sec ->
        if (sec.isNotBlank()) {
            if (profile.insecure == true && sec.equals("tls", ignoreCase = true)) {
                parts.add("$sec insecure")
            } else {
                parts.add(sec)
            }
        }
    }
    return parts.joinToString(" / ")
}

internal suspend fun PagerState.navigateToPageOptimized(
    targetPage: Int,
    animateAdjacentPage: Boolean = true
) {
    if (pageCount <= 0) return
    val target = targetPage.coerceIn(0, pageCount - 1)
    val current = settledPage.coerceIn(0, pageCount - 1)
    if (target == current) return

    if (abs(target - current) == 1 && animateAdjacentPage) {
        animateScrollToPage(target)
    } else {
        scrollToPage(target)
    }
}
