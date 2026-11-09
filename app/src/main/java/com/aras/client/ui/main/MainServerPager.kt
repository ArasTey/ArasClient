package com.aras.client.ui.main

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.first
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aras.client.AppConfig
import com.aras.client.R
import com.aras.client.dto.LocateTarget
import com.aras.client.dto.entities.ProfileItem
import com.aras.client.dto.entities.ServersCache
import com.aras.client.enums.AetherProtocol
import com.aras.client.enums.AetherPsiphon
import com.aras.client.enums.AetherTor
import com.aras.client.enums.EConfigType
import com.aras.client.extension.isComplexType
import com.aras.client.extension.nullIfBlank
import com.aras.client.extension.withLatinDigits
import com.aras.client.handler.AngConfigManager
import com.aras.client.handler.FreeSubManager
import com.aras.client.handler.ArasExportImportManager
import com.aras.client.handler.MmkvManager
import com.aras.client.ui.compose.CardCornerRadius
import com.aras.client.ui.compose.FlagIcon
import com.aras.client.ui.compose.LocalGlassPalette
import com.aras.client.ui.compose.ReorderableGridItem
import com.aras.client.ui.compose.ReorderableListItem
import com.aras.client.ui.compose.glassSelectionRing
import com.aras.client.ui.compose.verticalScrollbar
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState
import sh.calvin.reorderable.rememberReorderableLazyListState

private fun cardRemarks(profile: ProfileItem): String {
    // Provider flag emojis are guesses too; the card only shows a country the
    // connection test actually measured, so the title never carries one either.
    return com.aras.client.util.CountryResolver.withoutFlags(profile.remarks)
        .ifBlank {
            profile.server?.takeIf { it.isNotBlank() }
                ?: profile.description?.takeIf { it.isNotBlank() }
                ?: "Server"
        }
}

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
    onEditSubscription: (() -> Unit)? = null,
    onUpdateSubscription: (() -> Unit)? = null,
    onPingSubscription: (() -> Unit)? = null,
    onSortSubscription: (() -> Unit)? = null,
    onRemoveSubscription: (() -> Unit)? = null,
    onCopySubscriptionUrl: (() -> Unit)? = null,
    isProtectedSubscription: Boolean = false,
    contentPadding: PaddingValues
) {
    val serverFlow = remember(groupId) {
        mainViewModel.serversForGroup(groupId)
    }
    val servers by serverFlow.collectAsStateWithLifecycle()
    val uiState by mainViewModel.uiState.collectAsStateWithLifecycle()
    val canReorder = groupId.isNotEmpty() &&
            !FreeSubManager.isFreeSubId(groupId) &&
            searchQuery.isEmpty()
    Column {
        SubscriptionInfoBar(
            groupId = groupId,
            onEditSubscription = onEditSubscription,
            onUpdateSubscription = onUpdateSubscription,
            onPingSubscription = onPingSubscription,
            onSortSubscription = onSortSubscription,
            onRemoveSubscription = onRemoveSubscription,
            onCopySubscriptionUrl = onCopySubscriptionUrl,
            isProtectedSubscription = isProtectedSubscription
        )
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
        testingGuids = uiState.testingGuids,
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
    testingGuids: Set<String>,
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
                        isTesting = serverCache.guid in testingGuids,
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
                    ) { _ ->
                        ReorderableGridItem(
                            scope = this
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
                    ) { _ ->
                        ReorderableListItem(
                            scope = this
                        ) {
                            ServerItemRow(
                                serverCache = serverCache,
                                selectedGuid = selectedGuid,
                                subscriptionId = subscriptionId,
                                isTesting = serverCache.guid in testingGuids,
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
                        isTesting = serverCache.guid in testingGuids,
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
    isTesting: Boolean,
    onSelectServer: (String) -> Unit,
    onEditServer: (String, ProfileItem) -> Unit,
    onShareServer: (String, ProfileItem) -> Unit,
    onMoreServer: (String, ProfileItem) -> Unit,
    onRemoveServer: (String) -> Unit,
    onTestServer: (String) -> Unit
) {
    val profile = serverCache.profile
    val subRemarks = remember(subscriptionId, profile.subscriptionId) {
        if (subscriptionId.isEmpty()) {
            MmkvManager.decodeSubscription(profile.subscriptionId)?.remarks?.firstOrNull()
                ?.toString() ?: ""
        } else ""
    }
    val isProtected = remember(serverCache.guid) {
        ArasExportImportManager.isProtected(serverCache.guid)
    }
    val protectedDescription = stringResource(R.string.protected_config_hidden)
    // The flag only appears once a real ping carried data and reported the
    // egress country; name- and GeoIP-derived guesses never reach the card.
    val country = remember(serverCache.testCountryCode) {
        com.aras.client.util.CountryResolver.isoForCountry(serverCache.testCountryCode)
    }
    val displayRemarks = remember(serverCache.guid, profile.remarks) {
        cardRemarks(profile)
    }
    CompactServerCard {
        ServerListItem(
            countryIso = country,
            remarks = displayRemarks,
            statistics = if (isProtected)
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
            isTesting = isTesting,
            onClick = { onSelectServer(serverCache.guid) },
            onShare = { onShareServer(serverCache.guid, profile) },
            onEdit = { onEditServer(serverCache.guid, profile) },
            onRemove = { onRemoveServer(serverCache.guid) },
            onMore = { onMoreServer(serverCache.guid, profile) },
            onTest = { onTestServer(serverCache.guid) },
            isProtected = isProtected
        )
    }
}

@Composable
private fun ServerItemColumn(
    serverCache: ServersCache,
    selectedGuid: String?,
    subscriptionId: String,
    doubleColumnDisplay: Boolean,
    isTesting: Boolean,
    onSelectServer: (String) -> Unit,
    onEditServer: (String, ProfileItem) -> Unit,
    onShareServer: (String, ProfileItem) -> Unit,
    onMoreServer: (String, ProfileItem) -> Unit,
    onRemoveServer: (String) -> Unit,
    onTestServer: (String) -> Unit
) {
    val profile = serverCache.profile
    val subRemarks = remember(subscriptionId, profile.subscriptionId) {
        if (subscriptionId.isEmpty()) {
            MmkvManager.decodeSubscription(profile.subscriptionId)?.remarks?.firstOrNull()
                ?.toString() ?: ""
        } else ""
    }
    val isProtected = remember(serverCache.guid) {
        ArasExportImportManager.isProtected(serverCache.guid)
    }
    // The flag only appears once a real ping carried data and reported the
    // egress country; name- and GeoIP-derived guesses never reach the card.
    val country = remember(serverCache.testCountryCode) {
        com.aras.client.util.CountryResolver.isoForCountry(serverCache.testCountryCode)
    }
    val displayRemarks = remember(serverCache.guid, profile.remarks) {
        cardRemarks(profile)
    }
    CompactServerCard {
        ServerListItem(
            countryIso = country,
            remarks = displayRemarks,
            statistics = if (isProtected)
                stringResource(R.string.protected_config_hidden)
            else
                profile.description.nullIfBlank() ?: AngConfigManager.generateDescription(profile),
            typeDescription = getProtocolDescription(profile),
            configTypeName = profile.configType.name,
            testDelayMillis = serverCache.testDelayMillis,
            isSelected = serverCache.guid == selectedGuid,
            subscriptionRemarks = subRemarks,
            doubleColumnDisplay = doubleColumnDisplay,
            isTesting = isTesting,
            onClick = { onSelectServer(serverCache.guid) },
            onEdit = { onEditServer(serverCache.guid, profile) },
            onShare = { onShareServer(serverCache.guid, profile) },
            onRemove = { onRemoveServer(serverCache.guid) },
            onMore = { onMoreServer(serverCache.guid, profile) },
            onTest = { onTestServer(serverCache.guid) },
            isProtected = isProtected
        )
    }
}

// Stock server card drawn at ~85% (size 2 -> 1.7) so it sits a bit lighter
// without diverging from the default layout.
@Composable
private fun CompactServerCard(content: @Composable () -> Unit) {
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(
            density = density.density * 0.85f,
            fontScale = density.fontScale
        ),
        content = content
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
    isProtected: Boolean = false,
    countryIso: String? = null,
    isTesting: Boolean = false
) {
    val showDelayChips = remember {
        MmkvManager.decodeSettingsBool(AppConfig.PREF_SHOW_DELAY_CHIPS, true)
    }
    val testResult = if (testDelayMillis == 0L || !showDelayChips) {
        ""
    } else {
        stringResource(R.string.server_test_delay_value, testDelayMillis).withLatinDigits()
    }
    val selectedStateDescription = if (isSelected) {
        stringResource(R.string.acc_selected_server)
    } else {
        null
    }
    val palette = LocalGlassPalette.current
    val cardShape = RoundedCornerShape(CardCornerRadius)
    val cardBrush = if (isSelected) {
        SolidColor(palette.selectedTop)
    } else {
        SolidColor(MaterialTheme.colorScheme.surfaceContainerLow)
    }
    val pingColor = if (testDelayMillis < 0L) palette.pingBad else palette.pingGood
    // `isTesting` is now this card's own round, so a neighbour that has never been
    // measured keeps its gauge instead of pulsing along.
    val pinging = isTesting && testDelayMillis == 0L
    // An aether card names its tunnel and its exit, not just the protocol, so
    // its chip takes the warm accent that marks the tunnels apart.
    val typeTint = if (configTypeName == EConfigType.AETHER.name) palette.amber else palette.accent

    Row(
        modifier = modifier
            .fillMaxWidth()
            // The ring is chained before the clip on purpose: inside the clipped
            // layer its outer half sits on the clip boundary, and the corner arc
            // is the only place that boundary is curved, so the ring reads as
            // thinner around the four corners than along the edges.
            .then(
                if (isSelected) {
                    Modifier.glassSelectionRing(palette)
                } else {
                    Modifier
                }
            )
            .semantics {
                if (selectedStateDescription != null) {
                    stateDescription = selectedStateDescription
                }
            }
            .clip(cardShape)
            .background(cardBrush)
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
            if (!doubleColumnDisplay) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        typeDescription,
                        style = MaterialTheme.typography.labelSmall,
                        color = typeTint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(typeTint.copy(alpha = 0.13f))
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
                    if (countryIso != null) {
                        FlagIcon(
                            iso = countryIso,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                }
            } else {
                Text(
                    typeDescription,
                    style = MaterialTheme.typography.labelSmall,
                    color = typeTint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .background(typeTint.copy(alpha = 0.13f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                )
                if (countryIso != null || testResult.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (countryIso != null) {
                            FlagIcon(iso = countryIso)
                        }
                        if (testResult.isNotBlank()) {
                            Text(
                                testResult,
                                style = MaterialTheme.typography.labelSmall,
                                color = pingColor,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f, fill = false)
                                    .widthIn(min = 56.dp)
                                    .background(pingColor.copy(alpha = 0.13f), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 7.dp, vertical = 2.dp)
                            )
                        }
                    }
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
            // Single-server test button under the delete icon; it pulses only while
            // this card's own measurement runs, so a press clearly did something.
            if (pinging) {
                IconButton(
                    onClick = onTest,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                ) {
                    PingingDots(
                        color = palette.amber,
                        contentDescription = stringResource(R.string.connection_test_testing)
                    )
                }
            } else {
                CircularActionIcon(
                    R.drawable.ic_speedtest_24dp,
                    stringResource(R.string.title_test_single),
                    onTest,
                    // The same warm accent the subscription bar's speed-test icon
                    // carries, so the two are recognised as one action.
                    tint = palette.amber
                )
            }
        }
    }
}

/** Three dots pulsing in turn: the "measuring this config's delay" sign. */
@Composable
private fun PingingDots(color: Color, contentDescription: String) {
    val transition = rememberInfiniteTransition(label = "pinging")
    val dots = List(3) { index ->
        transition.animateFloat(
            initialValue = 0.2f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 380, delayMillis = index * 190, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pingDot$index",
        )
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.5.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics { this.contentDescription = contentDescription },
    ) {
        dots.forEach { alpha ->
            Box(
                Modifier
                    .size(4.5.dp)
                    .graphicsLayer { this.alpha = alpha.value }
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}

@Composable
private fun CircularActionIcon(
    drawableRes: Int,
    contentDesc: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
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

@Composable
internal fun getProtocolDescription(profile: ProfileItem): String =
    protocolDescription(profile, stringResource(R.string.aether_carrier_short_only))

/** [getProtocolDescription] without the string lookup, so the labels can be checked without a composition. */
internal fun protocolDescription(profile: ProfileItem, onlyLabel: String): String {
    if (profile.configType == EConfigType.AETHER) {
        // An aether card names the whole path the traffic takes — the carrier around
        // the tunnel, the tunnel itself, the carrier inside it — since "AETHER" alone
        // names none of that, and the exit profile when one is set.
        val protocol = profile.aetherProtocol?.takeIf { it.isNotBlank() }
            ?.let { AetherProtocol.fromString(it).name }
        val tunnel = listOfNotNull(profile.configType.name, protocol).joinToString(" / ")
        val psiphon = AetherPsiphon.fromString(profile.aetherPsiphon)
        val tor = AetherTor.fromString(profile.aetherTor)
        val path = when {
            psiphon == AetherPsiphon.ONLY -> listOf("PSIPHON", onlyLabel)
            tor == AetherTor.ONLY -> listOf("TOR", onlyLabel)
            else -> buildList {
                if (tor == AetherTor.REVERSE) add("TOR")
                if (psiphon == AetherPsiphon.REVERSE) add("PSIPHON")
                add(tunnel)
                if (psiphon == AetherPsiphon.CHAIN) add("PSIPHON")
                if (tor == AetherTor.CHAIN) add("TOR")
            }
        }
        val head = path.joinToString(" > ")
        val exit = profile.aetherExitNode?.trim().orEmpty()
        return if (exit.isEmpty()) head else "$head → $exit"
    }
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

internal suspend fun PagerState.navigateToPageOptimized(targetPage: Int) {
    if (pageCount <= 0) return
    val target = targetPage.coerceIn(0, pageCount - 1)
    if (target == settledPage) return

    // The rail pill carries the motion; the pager swaps instantly so content
    // never scrolls through intermediate pages.
    scrollToPage(target)
}
